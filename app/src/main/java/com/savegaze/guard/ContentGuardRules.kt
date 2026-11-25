package com.savegaze.guard

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Pure Kotlin decision & matching engine for ContentGuard features:
 * - Domain blocking (exact + subdomain match, `www.` normalized)
 * - Whole-word keyword matching (strict word boundaries matching desktop extension)
 * - Cross-block tracking (5 blocks in 10 minutes -> 5-minute lockout)
 * - Salted SHA-256 master password hashing & Android Settings tamper detection
 */
object ContentGuardRules {

    const val CROSS_BLOCK_LIMIT = 5
    const val CROSS_BLOCK_WINDOW_MS = 10 * 60 * 1000L
    const val LOCKOUT_DURATION_MS = 5 * 60 * 1000L
    const val CHALLENGE_SECONDS = 40
    private const val PASSWORD_SALT = "contentguard_salt_2024:"

    data class BlockDecision(
        val blocked: Boolean,
        val reason: String = "",
        val matchedItem: String = ""
    )

    data class CrossBlockUpdate(
        val timestamps: List<Long>,
        val lockoutUntilMs: Long,
        val triggeredLockout: Boolean
    )

    fun normalizeHost(rawHostOrUrl: String): String {
        var s = rawHostOrUrl.trim().lowercase()
        s = s.removePrefix("https://").removePrefix("http://")
        s = s.substringBefore('/').substringBefore('?').substringBefore('#')
        s = s.substringBefore(':')
        return s.removePrefix("www.").trimEnd('.')
    }

    fun buildActiveDomains(
        customWebsites: Set<String>,
        blockSocialDistractions: Boolean = false
    ): Set<String> {
        val out = HashSet<String>(ContentGuardLists.DEFAULT_ADULT_DOMAINS.size + customWebsites.size + 16)
        out.addAll(ContentGuardLists.DEFAULT_ADULT_DOMAINS)
        out.addAll(ContentGuardLists.GAMBLING_DOMAINS)
        if (blockSocialDistractions) {
            out.addAll(ContentGuardLists.OPTIONAL_SOCIAL_DOMAINS)
        }
        for (site in customWebsites) {
            val clean = normalizeHost(site)
            if (clean.isNotEmpty()) out.add(clean)
        }
        return out
    }

    fun isDomainBlocked(hostOrUrl: String, blockedDomains: Set<String>): String? {
        val host = normalizeHost(hostOrUrl)
        if (host.isEmpty() || !host.contains('.')) return null
        for (domain in blockedDomains) {
            val cleanDomain = normalizeHost(domain)
            if (cleanDomain.isEmpty()) continue
            if (host == cleanDomain || host.endsWith(".$cleanDomain")) {
                return cleanDomain
            }
        }
        return null
    }

    fun safeUrlDecode(raw: String): String {
        return try {
            URLDecoder.decode(raw, StandardCharsets.UTF_8.name())
        } catch (_: Exception) {
            raw
        }
    }

    fun matchesWholeWordKeyword(text: String, keyword: String): Boolean {
        val kw = keyword.trim().lowercase()
        if (kw.isEmpty()) return false
        val escaped = Regex.escape(kw)
        val pattern = Regex("(?<![a-zA-Z0-9])$escaped(?![a-zA-Z0-9])", RegexOption.IGNORE_CASE)
        return pattern.containsMatchIn(text)
    }

    /**
     * Checks a URL string for blocked domains or any single blocked keyword (default or custom).
     */
    fun checkUrl(
        rawUrl: String,
        blockedDomains: Set<String>,
        customKeywords: Set<String>,
        keywordBlockerEnabled: Boolean = true
    ): BlockDecision {
        if (rawUrl.isBlank()) return BlockDecision(false)
        val decoded = safeUrlDecode(rawUrl).lowercase()

        val matchedDomain = isDomainBlocked(decoded, blockedDomains)
        if (matchedDomain != null) {
            return BlockDecision(true, "Blocked Website", matchedDomain)
        }

        if (keywordBlockerEnabled) {
            for (kw in customKeywords) {
                if (matchesWholeWordKeyword(decoded, kw)) {
                    return BlockDecision(true, "Custom Blocked Keyword", kw)
                }
            }
            for (kw in ContentGuardLists.DEFAULT_KEYWORDS) {
                if (matchesWholeWordKeyword(decoded, kw)) {
                    return BlockDecision(true, "Blocked Adult Keyword in URL", kw)
                }
            }
        }

        return BlockDecision(false)
    }

    /**
     * Checks visible screen/page text using desktop ContentGuard rules:
     * - Custom keyword -> instant block (1 hit)
     * - Default adult keywords -> blocks when >= [threshold] distinct keywords match (default 3)
     */
    fun checkVisibleScreenText(
        screenText: String,
        customKeywords: Set<String>,
        blockedDomains: Set<String>,
        threshold: Int = 3
    ): BlockDecision {
        if (screenText.isBlank()) return BlockDecision(false)
        val clipped = if (screenText.length > 50_000) screenText.substring(0, 50_000) else screenText

        for (kw in customKeywords) {
            if (matchesWholeWordKeyword(clipped, kw)) {
                return BlockDecision(true, "Custom Keyword Detected", kw)
            }
        }

        // Check explicit domain names appearing in address/title text
        for (domain in blockedDomains) {
            if (domain.length >= 5 && matchesWholeWordKeyword(clipped, domain)) {
                return BlockDecision(true, "Blocked Domain on Screen", domain)
            }
        }

        var hitCount = 0
        var lastHit = ""
        for (kw in ContentGuardLists.DEFAULT_KEYWORDS) {
            if (matchesWholeWordKeyword(clipped, kw)) {
                hitCount++
                lastHit = kw
                if (hitCount >= threshold) {
                    return BlockDecision(true, "Adult Keywords on Screen ($hitCount matches)", lastHit)
                }
            }
        }
        return BlockDecision(false)
    }

    fun recordCrossBlockEvent(
        existingTimestamps: List<Long>,
        nowMs: Long,
        lockoutEnabled: Boolean = true
    ): CrossBlockUpdate {
        val windowStart = nowMs - CROSS_BLOCK_WINDOW_MS
        val recent = existingTimestamps.filter { it >= windowStart }.toMutableList()
        recent.add(nowMs)
        return if (lockoutEnabled && recent.size >= CROSS_BLOCK_LIMIT) {
            CrossBlockUpdate(
                timestamps = emptyList(),
                lockoutUntilMs = nowMs + LOCKOUT_DURATION_MS,
                triggeredLockout = true
            )
        } else {
            CrossBlockUpdate(
                timestamps = recent,
                lockoutUntilMs = 0L,
                triggeredLockout = false
            )
        }
    }

    fun hashPassword(password: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest((PASSWORD_SALT + password).toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun verifyPassword(inputPassword: String, storedHash: String?): Boolean {
        if (storedHash.isNullOrBlank()) return true
        return hashPassword(inputPassword) == storedHash
    }

    /**
     * Detects when the user opens Android Settings screens that can disable SaveGaze protection
     * (Accessibility service, Device admin deactivation, VPN disconnect, App Info / Uninstall / Force Stop).
     */
    fun isTamperSettingsScreen(packageName: String, visibleText: String): Boolean {
        if (packageName !in ContentGuardLists.SETTINGS_PACKAGES) return false
        val lower = visibleText.lowercase()
        val mentionsApp = lower.contains("savegaze")
        if (!mentionsApp) return false
        val dangerSignals = listOf(
            "uninstall", "force stop", "deactivate", "device admin", "turn off",
            "accessibility", "vpn", "disconnect", "remove", "clear data", "clear storage"
        )
        // Allow genuine in-app APK update prompts from PackageInstaller without blocking
        val looksLikeUpdateInstall = (lower.contains("update") || lower.contains("install an update")) &&
            dangerSignals.none { lower.contains(it) }
        if (looksLikeUpdateInstall) return false
        return dangerSignals.any { lower.contains(it) }
    }
}
