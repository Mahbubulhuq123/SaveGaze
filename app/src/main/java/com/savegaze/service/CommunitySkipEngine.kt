package com.savegaze.service

import java.util.Locale
import kotlin.math.max
import kotlin.math.min

data class SkipSegment(
    val startMs: Long,
    val endMs: Long,
    val category: String = "nudity",
    val severity: String = "high"
)

data class CatalogCandidate(
    val imdbId: String,
    val title: String,
    val year: Int? = null
)

data class NormalizedMediaTitle(
    val baseTitle: String,
    val year: Int?,
    val episodeTag: String?
)

data class CustomSkipEntry(
    val key: String,
    val displayTitle: String,
    val segments: List<SkipSegment>
)

data class MatchedSkipWindow(
    val segment: SkipSegment,
    val targetEndMs: Long
)

/**
 * Pure Kotlin parser, title normalizer, timestamp matcher, and local JSON serializer
 * for CleanStream / MCF / human skip timestamp ranges. Zero Android or external JSON dependencies.
 */
object CommunitySkipRules {

    private val EPISODE_REGEX = Regex("""\b[sS](\d{1,2})[\s._-]*[eE](\d{1,3})\b|\b(\d{1,2})x(\d{1,3})\b""")
    private val WORD_EPISODE_REGEX = Regex("""\bseason\s*(\d{1,2})\s*episode\s*(\d{1,3})\b""", RegexOption.IGNORE_CASE)
    private val YEAR_REGEX = Regex("""\b(19\d{2}|20\d{2})\b""")
    private val FILE_EXT_REGEX = Regex("""\.(mkv|mp4|avi|mov|webm|m4v)$""", RegexOption.IGNORE_CASE)
    private val RELEASE_JUNK_REGEX = Regex(
        """\b(2160p|1080p|720p|480p|4k|hdr|dv|dolby|atmos|web[- ]?dl|webrip|bluray|blu-ray|bdrip|hdrip|remux|x264|x265|hevc|h\.?264|h\.?265|aac\d*(?:\.\d)?|ddp\d*(?:\.\d)?|dts|multi|dual)\b""",
        RegexOption.IGNORE_CASE
    )
    private val BRACKET_GROUP_REGEX = Regex("""\[[^\]]*]|\([^\)]*\)""")
    private val TIME_RANGE_REGEX = Regex(
        """(\d{1,2}:\d{2}(?::\d{2})?(?:\.\d{1,3})?)\s*(?:-|–|—|->|to)\s*(\d{1,2}:\d{2}(?::\d{2})?(?:\.\d{1,3})?)""",
        RegexOption.IGNORE_CASE
    )
    private val CLOCK_PAIR_REGEX = Regex("""\b(\d{1,2}:\d{2}(?::\d{2})?)\s*/\s*(\d{1,2}:\d{2}(?::\d{2})?)\b""")

    private val INTIMATE_CATEGORIES = setOf("nudity", "sex", "sexual", "intimate", "kissing")

    fun normalizeTitle(rawTitle: String): NormalizedMediaTitle {
        var s = rawTitle.trim()
        if (s.isEmpty()) return NormalizedMediaTitle("", null, null)

        s = FILE_EXT_REGEX.replace(s, "")

        // Extract season/episode marker (e.g. S01E06 -> S01E06)
        var episodeTag: String? = null
        val epMatch = EPISODE_REGEX.find(s)
        if (epMatch != null) {
            val sNum = (epMatch.groupValues[1].ifEmpty { epMatch.groupValues[3] }).toIntOrNull()
            val eNum = (epMatch.groupValues[2].ifEmpty { epMatch.groupValues[4] }).toIntOrNull()
            if (sNum != null && eNum != null) {
                episodeTag = String.format(Locale.US, "S%02dE%02d", sNum, eNum)
            }
            s = s.removeRange(epMatch.range)
        } else {
            val wMatch = WORD_EPISODE_REGEX.find(s)
            if (wMatch != null) {
                val sNum = wMatch.groupValues[1].toIntOrNull()
                val eNum = wMatch.groupValues[2].toIntOrNull()
                if (sNum != null && eNum != null) {
                    episodeTag = String.format(Locale.US, "S%02dE%02d", sNum, eNum)
                }
                s = s.removeRange(wMatch.range)
            }
        }

        // Extract a valid release year before stripping brackets/parens
        var extractedYear: Int? = null
        for (m in YEAR_REGEX.findAll(s)) {
            val y = m.groupValues[1].toIntOrNull()
            if (y != null && y in 1920..2035) {
                extractedYear = y
            }
        }

        // Strip bracketed tags like [Dual Audio] or (2013)
        s = BRACKET_GROUP_REGEX.replace(s, " ")
        s = s.replace('.', ' ').replace('_', ' ')

        // Strip release quality tags and everything after the first codec/quality marker if trailing junk follows
        val junkMatch = RELEASE_JUNK_REGEX.find(s)
        if (junkMatch != null && junkMatch.range.first > 1) {
            s = s.substring(0, junkMatch.range.first)
        } else {
            s = RELEASE_JUNK_REGEX.replace(s, " ")
        }

        // Remove extracted trailing year token from base title
        if (extractedYear != null) {
            s = Regex("""\b$extractedYear\b""").replace(s, " ")
        }

        val cleaned = s.replace(Regex("""[\s\-–—:|]+$"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
        return NormalizedMediaTitle(baseTitle = cleaned, year = extractedYear, episodeTag = episodeTag)
    }

    fun normalizeKey(title: String, episodeTag: String? = null): String {
        val normTitle = normalizeAlphaNum(title)
        return if (episodeTag.isNullOrBlank()) normTitle else "$normTitle|${episodeTag.uppercase(Locale.US)}"
    }

    private fun normalizeAlphaNum(text: String): String =
        text.lowercase(Locale.US).replace(Regex("""[^a-z0-9]+"""), " ").trim()

    fun matchCatalogCandidate(
        queryTitle: String,
        queryYear: Int?,
        candidates: List<CatalogCandidate>
    ): String? {
        if (queryTitle.isBlank() || candidates.isEmpty()) return null
        val target = normalizeAlphaNum(queryTitle)
        if (target.isBlank()) return null

        val scored = candidates.mapNotNull { cand ->
            val candNorm = normalizeAlphaNum(cand.title)
            if (candNorm.isBlank() || !cand.imdbId.startsWith("tt")) return@mapNotNull null
            val score = when {
                candNorm == target && queryYear != null && cand.year == queryYear -> 100
                candNorm == target -> 90
                (candNorm.startsWith(target) || target.startsWith(candNorm)) &&
                    queryYear != null && cand.year == queryYear -> 75
                else -> 0
            }
            if (score > 0) cand.imdbId to score else null
        }
        return scored.maxByOrNull { it.second }?.first
    }

    fun parseCleanStreamSkipsJson(jsonStr: String): List<SkipSegment> {
        val skipsBody = extractJsonArrayBody(jsonStr, "skips") ?: return emptyList()
        val items = splitTopLevelJsonObjects(skipsBody)
        val rawSegments = items.mapNotNull { obj ->
            val startMs = extractJsonLong(obj, "startMs") ?: return@mapNotNull null
            val endMs = extractJsonLong(obj, "endMs") ?: return@mapNotNull null
            val category = (extractJsonString(obj, "category") ?: "nudity").lowercase(Locale.US)
            val severity = (extractJsonString(obj, "severity") ?: "medium").lowercase(Locale.US)
            if (endMs <= startMs || startMs < 0L) return@mapNotNull null
            if (category !in INTIMATE_CATEGORIES) return@mapNotNull null
            SkipSegment(startMs = startMs, endMs = endMs, category = category, severity = severity)
        }
        return mergeNearbySegments(rawSegments)
    }

    fun parseCatalogJson(jsonStr: String): List<CatalogCandidate> {
        val metasBody = extractJsonArrayBody(jsonStr, "metas")
            ?: extractJsonArrayBody(jsonStr, "filters")
            ?: return emptyList()
        return splitTopLevelJsonObjects(metasBody).mapNotNull { obj ->
            val imdbId = extractJsonString(obj, "imdb_id")
                ?: extractJsonString(obj, "imdbId")
                ?: extractJsonString(obj, "id")
                ?: return@mapNotNull null
            val title = extractJsonString(obj, "name")
                ?: extractJsonString(obj, "title")
                ?: return@mapNotNull null
            val year = extractJsonLong(obj, "year")?.toInt()
                ?: extractJsonString(obj, "releaseInfo")?.let { YEAR_REGEX.find(it)?.groupValues?.get(1)?.toIntOrNull() }
            if (!imdbId.startsWith("tt") || title.isBlank()) return@mapNotNull null
            CatalogCandidate(imdbId = imdbId, title = title, year = year)
        }
    }

    fun parseCustomTimestampsInput(rawInput: String): List<SkipSegment> {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) return emptyList()

        // 1. CleanStream / MCF JSON format
        if (trimmed.contains("\"startMs\"") && trimmed.contains("\"endMs\"")) {
            val wrapped = if (trimmed.contains("\"skips\"")) trimmed else "{\"skips\":$trimmed}"
            val parsed = parseCleanStreamSkipsJson(wrapped)
            if (parsed.isNotEmpty()) return parsed
            // Also accept generic JSON segments where category was omitted
            val fallbackObjs = splitTopLevelJsonObjects(extractJsonArrayBody(wrapped, "skips").orEmpty())
            val generic = fallbackObjs.mapNotNull { obj ->
                val s = extractJsonLong(obj, "startMs") ?: return@mapNotNull null
                val e = extractJsonLong(obj, "endMs") ?: return@mapNotNull null
                val cat = (extractJsonString(obj, "category") ?: "nudity").lowercase(Locale.US)
                if (e > s && s >= 0L && cat in INTIMATE_CATEGORIES) SkipSegment(s, e, cat, "high") else null
            }
            if (generic.isNotEmpty()) return mergeNearbySegments(generic)
        }

        // 2. Human readable / MCF line time ranges (e.g. "35:30-37:10", "01:14:48 -> 01:15:13")
        val segments = mutableListOf<SkipSegment>()
        for (match in TIME_RANGE_REGEX.findAll(trimmed)) {
            val startMs = parseClockToMs(match.groupValues[1]) ?: continue
            val endMs = parseClockToMs(match.groupValues[2]) ?: continue
            if (endMs > startMs) {
                segments += SkipSegment(startMs = startMs, endMs = endMs, category = "custom", severity = "high")
            }
        }
        return mergeNearbySegments(segments)
    }

    fun mergeNearbySegments(segments: List<SkipSegment>, gapToleranceMs: Long = 1_500L): List<SkipSegment> {
        if (segments.size <= 1) return segments.sortedBy { it.startMs }
        val sorted = segments.sortedBy { it.startMs }
        val merged = mutableListOf<SkipSegment>()
        var current = sorted[0]
        for (i in 1 until sorted.size) {
            val next = sorted[i]
            if (next.startMs <= current.endMs + gapToleranceMs) {
                current = current.copy(endMs = max(current.endMs, next.endMs))
            } else {
                merged += current
                current = next
            }
        }
        merged += current
        return merged
    }

    fun findActiveSkipWindow(
        positionMs: Long,
        segments: List<SkipSegment>,
        lookaheadMs: Long = 500L,
        minRemainingMs: Long = 2_000L
    ): MatchedSkipWindow? {
        if (positionMs < 0L || segments.isEmpty()) return null
        for (seg in segments) {
            val triggerStart = (seg.startMs - lookaheadMs).coerceAtLeast(0L)
            val triggerEnd = seg.endMs - minRemainingMs
            if (triggerEnd > triggerStart && positionMs in triggerStart..triggerEnd) {
                return MatchedSkipWindow(segment = seg, targetEndMs = seg.endMs + 400L)
            }
        }
        return null
    }

    fun parseClockTimestampFromUi(screenText: String): Pair<Long, Long?>? {
        val m = CLOCK_PAIR_REGEX.find(screenText) ?: return null
        val cur = parseClockToMs(m.groupValues[1]) ?: return null
        val total = parseClockToMs(m.groupValues[2])
        return cur to total
    }

    fun parseClockToMs(raw: String): Long? {
        val clean = raw.trim()
        val parts = clean.split(':')
        if (parts.size !in 2..3) return null
        val secPart = parts.last().toDoubleOrNull() ?: return null
        val mins = parts[parts.size - 2].toLongOrNull() ?: return null
        val hrs = if (parts.size == 3) parts[0].toLongOrNull() ?: return null else 0L
        if (hrs < 0 || mins !in 0..59 || secPart < 0.0 || secPart >= 60.0) return null
        return ((hrs * 3600L + mins * 60L) * 1000L) + (secPart * 1000.0).toLong()
    }

    fun formatMs(ms: Long): String {
        val totalSec = (ms / 1000L).coerceAtLeast(0L)
        val hrs = totalSec / 3600L
        val mins = (totalSec % 3600L) / 60L
        val secs = totalSec % 60L
        return if (hrs > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hrs, mins, secs)
        } else {
            String.format(Locale.US, "%02d:%02d", mins, secs)
        }
    }

    fun serializeCustomStore(entries: List<CustomSkipEntry>): String {
        val sb = StringBuilder("[")
        entries.forEachIndexed { idx, entry ->
            if (idx > 0) sb.append(',')
            sb.append("{\"key\":\"").append(escapeJson(entry.key)).append('"')
            sb.append(",\"displayTitle\":\"").append(escapeJson(entry.displayTitle)).append('"')
            sb.append(",\"segments\":[")
            entry.segments.forEachIndexed { sIdx, seg ->
                if (sIdx > 0) sb.append(',')
                sb.append("{\"startMs\":").append(seg.startMs)
                    .append(",\"endMs\":").append(seg.endMs)
                    .append(",\"category\":\"").append(escapeJson(seg.category))
                    .append("\"}")
            }
            sb.append("]}")
        }
        sb.append(']')
        return sb.toString()
    }

    fun deserializeCustomStore(rawJson: String?): List<CustomSkipEntry> {
        if (rawJson.isNullOrBlank()) return emptyList()
        val trimmed = rawJson.trim()
        if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) return emptyList()
        val inner = trimmed.substring(1, trimmed.length - 1)
        return splitTopLevelJsonObjects(inner).mapNotNull { obj ->
            val key = extractJsonString(obj, "key") ?: return@mapNotNull null
            val display = extractJsonString(obj, "displayTitle") ?: key
            val segBody = extractJsonArrayBody(obj, "segments") ?: return@mapNotNull null
            val segs = splitTopLevelJsonObjects(segBody).mapNotNull { sObj ->
                val s = extractJsonLong(sObj, "startMs")
                val e = extractJsonLong(sObj, "endMs")
                val cat = extractJsonString(sObj, "category") ?: "custom"
                if (s != null && e != null && e > s && s >= 0L) SkipSegment(s, e, cat, "high") else null
            }
            if (segs.isEmpty()) null else CustomSkipEntry(key = key, displayTitle = display, segments = mergeNearbySegments(segs))
        }
    }

    // ── Lightweight Pure-Kotlin JSON Helpers (JVM-testable without org.json stubs) ──

    private fun escapeJson(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "")

    private fun findKeyColonIndex(json: String, key: String): Int {
        val pattern = Regex(""""${Regex.escape(key)}"\s*:""")
        return pattern.find(json)?.range?.last?.plus(1) ?: -1
    }

    internal fun extractJsonArrayBody(json: String, key: String): String? {
        var idx = findKeyColonIndex(json, key)
        if (idx < 0) return null
        while (idx < json.length && json[idx].isWhitespace()) idx++
        if (idx >= json.length || json[idx] != '[') return null
        var depth = 0
        var inStr = false
        var esc = false
        val start = idx + 1
        for (i in idx until json.length) {
            val c = json[i]
            if (esc) { esc = false; continue }
            if (c == '\\' && inStr) { esc = true; continue }
            if (c == '"') { inStr = !inStr; continue }
            if (!inStr) {
                if (c == '[') depth++
                else if (c == ']') {
                    depth--
                    if (depth == 0) return json.substring(start, i)
                }
            }
        }
        return null
    }

    internal fun splitTopLevelJsonObjects(body: String): List<String> {
        val out = mutableListOf<String>()
        var depth = 0
        var start = -1
        var inStr = false
        var esc = false
        for (i in body.indices) {
            val c = body[i]
            if (esc) { esc = false; continue }
            if (c == '\\' && inStr) { esc = true; continue }
            if (c == '"') { inStr = !inStr; continue }
            if (!inStr) {
                if (c == '{') {
                    if (depth == 0) start = i
                    depth++
                } else if (c == '}') {
                    depth--
                    if (depth == 0 && start >= 0) {
                        out += body.substring(start, i + 1)
                        start = -1
                    }
                }
            }
        }
        return out
    }

    private fun extractJsonString(json: String, key: String): String? {
        var idx = findKeyColonIndex(json, key)
        if (idx < 0) return null
        while (idx < json.length && json[idx].isWhitespace()) idx++
        if (idx >= json.length || json[idx] != '"') return null
        idx++
        val sb = StringBuilder()
        var esc = false
        while (idx < json.length) {
            val c = json[idx++]
            if (esc) {
                sb.append(when (c) { 'n' -> '\n'; 'r' -> '\r'; 't' -> '\t'; else -> c })
                esc = false
            } else if (c == '\\') {
                esc = true
            } else if (c == '"') {
                return sb.toString()
            } else {
                sb.append(c)
            }
        }
        return null
    }

    private fun extractJsonLong(json: String, key: String): Long? {
        var idx = findKeyColonIndex(json, key)
        if (idx < 0) return null
        while (idx < json.length && json[idx].isWhitespace()) idx++
        val start = idx
        while (idx < json.length && (json[idx].isDigit() || (idx == start && json[idx] == '-'))) idx++
        return if (idx > start) json.substring(start, idx).toLongOrNull() else null
    }
}
