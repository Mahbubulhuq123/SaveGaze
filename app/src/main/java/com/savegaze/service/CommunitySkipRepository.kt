package com.savegaze.service

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

sealed class SkipLookupState {
    object Idle : SkipLookupState()
    data class Loading(val normalizedTitle: String) : SkipLookupState()
    data class Found(
        val cacheKey: String,
        val sourceLabel: String,
        val displayTitle: String,
        val imdbId: String?,
        val segments: List<SkipSegment>
    ) : SkipLookupState()
    data class NotFound(val normalizedTitle: String, val cachedAtMs: Long) : SkipLookupState()
}

/**
 * Resolves scene skip timestamps with priority:
 * 1. User's local custom / MCF timestamps (offline, instant)
 * 2. CleanStream public community API (`cleanstream.elfhosted.com`) via catalog + Cinemeta IMDb resolution
 */
class CommunitySkipRepository {

    companion object {
        private const val TAG = "CommunitySkipRepo"
        private const val CLEANSTREAM_CATALOG_SEARCH = "https://cleanstream.elfhosted.com/catalog/movie/cleanstream-all/search="
        private const val CLEANSTREAM_SKIPS_URL = "https://cleanstream.elfhosted.com/api/skips/"
        private const val CINEMETA_SEARCH_URL = "https://v3-cinemeta.strem.io/catalog/movie/top/search="
        private const val NEGATIVE_TTL_MS = 20 * 60_000L
        private const val TIMEOUT_MS = 4_500
    }

    private val remoteCache = ConcurrentHashMap<String, SkipLookupState>()

    @Volatile
    private var customEntries: List<CustomSkipEntry> = emptyList()

    fun updateCustomEntries(entries: List<CustomSkipEntry>) {
        customEntries = entries
    }

    fun getCustomEntries(): List<CustomSkipEntry> = customEntries

    /**
     * Non-blocking synchronous lookup for fast frame-loop evaluation.
     * Launches an async background lookup on miss without blocking the scanning thread.
     */
    fun resolveNonBlocking(
        rawTitle: String,
        rawSubtitle: String = "",
        scope: CoroutineScope
    ): SkipLookupState {
        val combinedRaw = if (rawSubtitle.isNotBlank() && !rawTitle.contains(rawSubtitle, ignoreCase = true)) {
            "$rawTitle $rawSubtitle"
        } else {
            rawTitle
        }
        val norm = CommunitySkipRules.normalizeTitle(combinedRaw)
        if (norm.baseTitle.length < 2) return SkipLookupState.Idle

        // 1. Check user custom / local MCF entries first (exact episode key, then base title key)
        val exactKey = CommunitySkipRules.normalizeKey(norm.baseTitle, norm.episodeTag)
        val baseKey = CommunitySkipRules.normalizeKey(norm.baseTitle, null)
        val customMatch = customEntries.firstOrNull { it.key == exactKey }
            ?: customEntries.firstOrNull { it.key == baseKey }
        if (customMatch != null && customMatch.segments.isNotEmpty()) {
            return SkipLookupState.Found(
                cacheKey = customMatch.key,
                sourceLabel = "Custom",
                displayTitle = customMatch.displayTitle,
                imdbId = null,
                segments = customMatch.segments
            )
        }

        // 2. Check cached CleanStream lookup
        val cacheKey = "${baseKey}:${norm.year ?: 0}"
        val now = System.currentTimeMillis()
        val existing = remoteCache[cacheKey]
        when (existing) {
            is SkipLookupState.Found -> return existing
            is SkipLookupState.Loading -> return existing
            is SkipLookupState.NotFound -> {
                if (now - existing.cachedAtMs < NEGATIVE_TTL_MS) return existing
            }
            else -> Unit
        }

        remoteCache[cacheKey] = SkipLookupState.Loading(norm.baseTitle)
        scope.launch(Dispatchers.IO) {
            fetchCleanStreamSegments(cacheKey, norm)
        }
        return SkipLookupState.Loading(norm.baseTitle)
    }

    private fun fetchCleanStreamSegments(cacheKey: String, norm: NormalizedMediaTitle) {
        try {
            val encoded = URLEncoder.encode(norm.baseTitle, "UTF-8").replace("+", "%20")

            // Try CleanStream's own catalog search first (1 round-trip to identify movies in DB)
            val cleanJson = httpGet("${CLEANSTREAM_CATALOG_SEARCH}$encoded.json")
            var imdbId = cleanJson?.let {
                CommunitySkipRules.matchCatalogCandidate(
                    norm.baseTitle,
                    norm.year,
                    CommunitySkipRules.parseCatalogJson(it)
                )
            }

            // Secondary IMDb lookup via Cinemeta when alternate titles differ slightly
            if (imdbId == null) {
                val cinJson = httpGet("${CINEMETA_SEARCH_URL}$encoded.json")
                imdbId = cinJson?.let {
                    CommunitySkipRules.matchCatalogCandidate(
                        norm.baseTitle,
                        norm.year,
                        CommunitySkipRules.parseCatalogJson(it)
                    )
                }
            }

            if (imdbId == null) {
                remoteCache[cacheKey] = SkipLookupState.NotFound(norm.baseTitle, System.currentTimeMillis())
                return
            }

            val skipsUrl = "$CLEANSTREAM_SKIPS_URL$imdbId?nudity=low&sex=low&violence=off&language=off&drugs=off&fear=off"
            val skipsJson = httpGet(skipsUrl)
            val segments = skipsJson?.let { CommunitySkipRules.parseCleanStreamSkipsJson(it) }.orEmpty()
            if (segments.isEmpty()) {
                Log.i(TAG, "CleanStream had 0 intimate segments for '${norm.baseTitle}' ($imdbId)")
                remoteCache[cacheKey] = SkipLookupState.NotFound(norm.baseTitle, System.currentTimeMillis())
            } else {
                Log.i(TAG, "CleanStream loaded ${segments.size} intimate segments for '${norm.baseTitle}' ($imdbId)")
                remoteCache[cacheKey] = SkipLookupState.Found(
                    cacheKey = cacheKey,
                    sourceLabel = "CleanStream",
                    displayTitle = norm.baseTitle,
                    imdbId = imdbId,
                    segments = segments
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "CleanStream lookup failed for ${norm.baseTitle}", e)
            remoteCache[cacheKey] = SkipLookupState.NotFound(norm.baseTitle, System.currentTimeMillis())
        }
    }

    private fun httpGet(urlStr: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "SaveGaze-HybridSkip/1.0")
            }
            if (conn.responseCode in 200..299) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else null
        } catch (_: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }
}
