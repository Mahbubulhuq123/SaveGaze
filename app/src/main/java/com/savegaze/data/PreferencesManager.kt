package com.savegaze.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.savegaze.guard.ContentGuardRules
import com.savegaze.ml.GenderFilter
import com.savegaze.service.DrmAppPolicy
import com.savegaze.service.IntimateActionMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

enum class BlurStyle(val displayName: String, val description: String) {
    SOLID_BLACK("Solid Black (HaramBlur)", "100% pitch black privacy blocks. Completely opaque, zero see-through."),
    DARK_FROSTED("Dark Frosted Glass", "Sophisticated deep frosted-glass privacy blocks with subtle accent rims."),
    LIGHT_CENSOR("Light Censor Card", "Clean light-colored censor cards.")
}

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "savegaze_settings")

class PreferencesManager(private val context: Context) {

    private object Keys {
        val GENDER_FILTER = stringPreferencesKey("gender_filter")
        val SENSITIVITY = floatPreferencesKey("sensitivity")
        val BLUR_INTENSITY = intPreferencesKey("blur_intensity")
        val IS_ENABLED = booleanPreferencesKey("is_enabled")
        val SCAN_INTERVAL = longPreferencesKey("scan_interval")
        val BLUR_STYLE = stringPreferencesKey("blur_style")
        val BLUR_OPACITY = intPreferencesKey("blur_opacity")
        val BLUR_FACES = booleanPreferencesKey("blur_faces")
        val BLUR_FULL_BODY = booleanPreferencesKey("blur_full_body")
        val DISMISSED_UPDATE_VERSION = stringPreferencesKey("dismissed_update_version")
        val REMIND_ME_LATER_TIMESTAMP = longPreferencesKey("remind_me_later_timestamp")
        val AUTO_CHECK_UPDATES = booleanPreferencesKey("auto_check_updates")

        // ContentGuard Unified Protection Keys
        val WEBSITE_BLOCKER_ENABLED = booleanPreferencesKey("cg_website_blocker_enabled")
        val KEYWORD_BLOCKER_ENABLED = booleanPreferencesKey("cg_keyword_blocker_enabled")
        val APP_BLOCKER_ENABLED = booleanPreferencesKey("cg_app_blocker_enabled")
        val TAMPER_GUARD_ENABLED = booleanPreferencesKey("cg_tamper_guard_enabled")
        val CROSS_BLOCK_LOCKOUT_ENABLED = booleanPreferencesKey("cg_cross_block_lockout_enabled")
        val BLOCK_SOCIAL_DISTRACTIONS = booleanPreferencesKey("cg_block_social_distractions")
        val MASTER_PASSWORD_HASH = stringPreferencesKey("cg_master_password_hash")
        val CUSTOM_KEYWORDS = stringSetPreferencesKey("cg_custom_keywords")
        val CUSTOM_WEBSITES = stringSetPreferencesKey("cg_custom_websites")
        val BLOCKED_APPS = stringSetPreferencesKey("cg_blocked_apps")
        val BLOCK_TIMESTAMPS_CSV = stringPreferencesKey("cg_block_timestamps_csv")
        val LOCKOUT_UNTIL_MS = longPreferencesKey("cg_lockout_until_ms")
        val TAMPER_UNLOCK_UNTIL_MS = longPreferencesKey("cg_tamper_unlock_until_ms")
        val TOTAL_BLOCKED_COUNT = intPreferencesKey("cg_total_blocked_count")

        // Intimate Video & Audio Guard Keys
        val INTIMATE_GUARD_ENABLED = booleanPreferencesKey("intimate_guard_enabled")
        val INTIMATE_AUDIO_ENABLED = booleanPreferencesKey("intimate_audio_enabled")
        val AUTO_MOVIE_AUDIO_CAST_ENABLED = booleanPreferencesKey("auto_movie_audio_cast_enabled")
        val INTIMATE_ACTION_MODE = stringPreferencesKey("intimate_action_mode")
        val DRM_APP_POLICY = stringPreferencesKey("drm_app_policy")
    }

    // ── Flow-based reactive getters ─────────────────────────────────────

    val genderFilterFlow: Flow<GenderFilter> = context.dataStore.data.map { prefs ->
        val raw = prefs[Keys.GENDER_FILTER] ?: GenderFilter.BOTH.name
        try {
            GenderFilter.valueOf(raw)
        } catch (_: IllegalArgumentException) {
            GenderFilter.BOTH
        }
    }

    val sensitivityFlow: Flow<Float> = context.dataStore.data.map { prefs ->
        val raw = prefs[Keys.SENSITIVITY] ?: 0.25f
        if (raw > 0.45f) 0.25f else raw.coerceIn(0.15f, 0.45f)
    }

    val blurIntensityFlow: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[Keys.BLUR_INTENSITY] ?: 25
    }

    val isEnabledFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.IS_ENABLED] ?: true
    }

    val scanIntervalFlow: Flow<Long> = context.dataStore.data.map { prefs ->
        prefs[Keys.SCAN_INTERVAL] ?: 250L
    }

    val blurStyleFlow: Flow<BlurStyle> = context.dataStore.data.map { prefs ->
        val raw = prefs[Keys.BLUR_STYLE] ?: BlurStyle.SOLID_BLACK.name
        try {
            BlurStyle.valueOf(raw)
        } catch (_: IllegalArgumentException) {
            BlurStyle.SOLID_BLACK
        }
    }

    val blurOpacityFlow: Flow<Int> = context.dataStore.data.map { prefs ->
        (prefs[Keys.BLUR_OPACITY] ?: 100).coerceIn(50, 100)
    }

    val blurFacesFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.BLUR_FACES] ?: false
    }

    val blurFullBodyFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.BLUR_FULL_BODY] ?: true
    }

    val dismissedUpdateVersionFlow: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[Keys.DISMISSED_UPDATE_VERSION]
    }

    val remindMeLaterTimestampFlow: Flow<Long> = context.dataStore.data.map { prefs ->
        prefs[Keys.REMIND_ME_LATER_TIMESTAMP] ?: 0L
    }

    val autoCheckUpdatesFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.AUTO_CHECK_UPDATES] ?: true
    }

    val websiteBlockerEnabledFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.WEBSITE_BLOCKER_ENABLED] ?: true
    }

    val keywordBlockerEnabledFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.KEYWORD_BLOCKER_ENABLED] ?: true
    }

    val appBlockerEnabledFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.APP_BLOCKER_ENABLED] ?: true
    }

    val tamperGuardEnabledFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.TAMPER_GUARD_ENABLED] ?: false
    }

    val crossBlockLockoutEnabledFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.CROSS_BLOCK_LOCKOUT_ENABLED] ?: true
    }

    val blockSocialDistractionsFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.BLOCK_SOCIAL_DISTRACTIONS] ?: false
    }

    val masterPasswordHashFlow: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[Keys.MASTER_PASSWORD_HASH]
    }

    val customKeywordsFlow: Flow<Set<String>> = context.dataStore.data.map { prefs ->
        prefs[Keys.CUSTOM_KEYWORDS] ?: emptySet()
    }

    val customWebsitesFlow: Flow<Set<String>> = context.dataStore.data.map { prefs ->
        prefs[Keys.CUSTOM_WEBSITES] ?: emptySet()
    }

    val blockedAppsFlow: Flow<Set<String>> = context.dataStore.data.map { prefs ->
        prefs[Keys.BLOCKED_APPS] ?: emptySet()
    }

    val lockoutUntilMsFlow: Flow<Long> = context.dataStore.data.map { prefs ->
        prefs[Keys.LOCKOUT_UNTIL_MS] ?: 0L
    }

    val tamperUnlockUntilMsFlow: Flow<Long> = context.dataStore.data.map { prefs ->
        prefs[Keys.TAMPER_UNLOCK_UNTIL_MS] ?: 0L
    }

    val totalBlockedCountFlow: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[Keys.TOTAL_BLOCKED_COUNT] ?: 0
    }

    val intimateGuardEnabledFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.INTIMATE_GUARD_ENABLED] ?: true
    }

    val intimateAudioEnabledFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.INTIMATE_AUDIO_ENABLED] ?: false
    }

    val autoMovieAudioCastEnabledFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.AUTO_MOVIE_AUDIO_CAST_ENABLED] ?: true
    }

    val intimateActionModeFlow: Flow<IntimateActionMode> = context.dataStore.data.map { prefs ->
        val raw = prefs[Keys.INTIMATE_ACTION_MODE] ?: IntimateActionMode.SMART_CONTEXT.name
        try {
            IntimateActionMode.valueOf(raw)
        } catch (_: IllegalArgumentException) {
            IntimateActionMode.SMART_CONTEXT
        }
    }

    val drmAppPolicyFlow: Flow<DrmAppPolicy> = context.dataStore.data.map { prefs ->
        val raw = prefs[Keys.DRM_APP_POLICY] ?: DrmAppPolicy.WARN_ONLY.name
        try {
            DrmAppPolicy.valueOf(raw)
        } catch (_: IllegalArgumentException) {
            DrmAppPolicy.WARN_ONLY
        }
    }

    val recentBlockCountFlow: Flow<Int> = context.dataStore.data.map { prefs ->
        val now = System.currentTimeMillis()
        parseTimestamps(prefs[Keys.BLOCK_TIMESTAMPS_CSV]).count {
            it >= now - ContentGuardRules.CROSS_BLOCK_WINDOW_MS
        }
    }

    // ── Suspend getters ─────────────────────────────────────────────────

    suspend fun getGenderFilter(): GenderFilter = genderFilterFlow.first()
    suspend fun getSensitivity(): Float = sensitivityFlow.first()
    suspend fun getBlurIntensity(): Int = blurIntensityFlow.first()
    suspend fun getIsEnabled(): Boolean = isEnabledFlow.first()
    suspend fun isEnabled(): Boolean = getIsEnabled()
    suspend fun getScanInterval(): Long = scanIntervalFlow.first()
    suspend fun getBlurStyle(): BlurStyle = blurStyleFlow.first()
    suspend fun getBlurOpacity(): Int = blurOpacityFlow.first()
    suspend fun getBlurFaces(): Boolean = blurFacesFlow.first()
    suspend fun getBlurFullBody(): Boolean = blurFullBodyFlow.first()
    suspend fun getDismissedUpdateVersion(): String? = dismissedUpdateVersionFlow.first()
    suspend fun getRemindMeLaterTimestamp(): Long = remindMeLaterTimestampFlow.first()
    suspend fun getAutoCheckUpdates(): Boolean = autoCheckUpdatesFlow.first()

    suspend fun getWebsiteBlockerEnabled(): Boolean = websiteBlockerEnabledFlow.first()
    suspend fun getKeywordBlockerEnabled(): Boolean = keywordBlockerEnabledFlow.first()
    suspend fun getAppBlockerEnabled(): Boolean = appBlockerEnabledFlow.first()
    suspend fun getTamperGuardEnabled(): Boolean = tamperGuardEnabledFlow.first()
    suspend fun getCrossBlockLockoutEnabled(): Boolean = crossBlockLockoutEnabledFlow.first()
    suspend fun getBlockSocialDistractions(): Boolean = blockSocialDistractionsFlow.first()
    suspend fun getMasterPasswordHash(): String? = masterPasswordHashFlow.first()
    suspend fun getCustomKeywords(): Set<String> = customKeywordsFlow.first()
    suspend fun getCustomWebsites(): Set<String> = customWebsitesFlow.first()
    suspend fun getBlockedApps(): Set<String> = blockedAppsFlow.first()
    suspend fun getLockoutUntilMs(): Long = lockoutUntilMsFlow.first()
    suspend fun getTamperUnlockUntilMs(): Long = tamperUnlockUntilMsFlow.first()
    suspend fun getTotalBlockedCount(): Int = totalBlockedCountFlow.first()
    suspend fun getIntimateGuardEnabled(): Boolean = intimateGuardEnabledFlow.first()
    suspend fun getIntimateAudioEnabled(): Boolean = intimateAudioEnabledFlow.first()
    suspend fun getAutoMovieAudioCastEnabled(): Boolean = autoMovieAudioCastEnabledFlow.first()
    suspend fun getIntimateActionMode(): IntimateActionMode = intimateActionModeFlow.first()
    suspend fun getDrmAppPolicy(): DrmAppPolicy = drmAppPolicyFlow.first()

    // ── Suspend setters ─────────────────────────────────────────────────

    suspend fun setGenderFilter(filter: GenderFilter) {
        context.dataStore.edit { it[Keys.GENDER_FILTER] = filter.name }
    }

    suspend fun setSensitivity(value: Float) {
        val clamped = if (value > 0.45f) 0.25f else value.coerceIn(0.15f, 0.45f)
        context.dataStore.edit { it[Keys.SENSITIVITY] = clamped }
    }

    suspend fun setBlurIntensity(value: Int) {
        context.dataStore.edit { it[Keys.BLUR_INTENSITY] = value }
    }

    suspend fun setIsEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.IS_ENABLED] = enabled }
    }

    suspend fun setScanInterval(intervalMs: Long) {
        context.dataStore.edit { it[Keys.SCAN_INTERVAL] = intervalMs }
    }

    suspend fun setBlurStyle(style: BlurStyle) {
        context.dataStore.edit { it[Keys.BLUR_STYLE] = style.name }
    }

    suspend fun setBlurOpacity(opacity: Int) {
        context.dataStore.edit { it[Keys.BLUR_OPACITY] = opacity.coerceIn(50, 100) }
    }

    suspend fun setBlurFaces(enabled: Boolean) {
        context.dataStore.edit { it[Keys.BLUR_FACES] = enabled }
    }

    suspend fun setBlurFullBody(enabled: Boolean) {
        context.dataStore.edit { it[Keys.BLUR_FULL_BODY] = enabled }
    }

    suspend fun setDismissedUpdateVersion(version: String) {
        context.dataStore.edit { it[Keys.DISMISSED_UPDATE_VERSION] = version }
    }

    suspend fun clearDismissedUpdateVersion() {
        context.dataStore.edit { it.remove(Keys.DISMISSED_UPDATE_VERSION) }
    }

    suspend fun setRemindMeLaterTimestamp(timestampMs: Long) {
        context.dataStore.edit { it[Keys.REMIND_ME_LATER_TIMESTAMP] = timestampMs }
    }

    suspend fun clearRemindMeLaterTimestamp() {
        context.dataStore.edit { it.remove(Keys.REMIND_ME_LATER_TIMESTAMP) }
    }

    suspend fun setAutoCheckUpdates(enabled: Boolean) {
        context.dataStore.edit { it[Keys.AUTO_CHECK_UPDATES] = enabled }
    }

    // ── ContentGuard Protection Setters ─────────────────────────────────

    suspend fun setWebsiteBlockerEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.WEBSITE_BLOCKER_ENABLED] = enabled }
    }

    suspend fun setKeywordBlockerEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.KEYWORD_BLOCKER_ENABLED] = enabled }
    }

    suspend fun setAppBlockerEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.APP_BLOCKER_ENABLED] = enabled }
    }

    suspend fun setTamperGuardEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.TAMPER_GUARD_ENABLED] = enabled }
    }

    suspend fun setCrossBlockLockoutEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.CROSS_BLOCK_LOCKOUT_ENABLED] = enabled }
    }

    suspend fun setBlockSocialDistractions(enabled: Boolean) {
        context.dataStore.edit { it[Keys.BLOCK_SOCIAL_DISTRACTIONS] = enabled }
    }

    suspend fun setIntimateGuardEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.INTIMATE_GUARD_ENABLED] = enabled }
    }

    suspend fun setIntimateAudioEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.INTIMATE_AUDIO_ENABLED] = enabled }
    }

    suspend fun setAutoMovieAudioCastEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.AUTO_MOVIE_AUDIO_CAST_ENABLED] = enabled }
    }

    suspend fun setIntimateActionMode(mode: IntimateActionMode) {
        context.dataStore.edit { it[Keys.INTIMATE_ACTION_MODE] = mode.name }
    }

    suspend fun setDrmAppPolicy(policy: DrmAppPolicy) {
        context.dataStore.edit { it[Keys.DRM_APP_POLICY] = policy.name }
    }

    suspend fun setMasterPassword(plainPassword: String) {
        val hash = if (plainPassword.isEmpty()) "" else ContentGuardRules.hashPassword(plainPassword)
        context.dataStore.edit { prefs ->
            if (hash.isEmpty()) prefs.remove(Keys.MASTER_PASSWORD_HASH)
            else prefs[Keys.MASTER_PASSWORD_HASH] = hash
        }
    }

    suspend fun addCustomKeyword(keyword: String) {
        val clean = keyword.trim().lowercase()
        if (clean.isEmpty()) return
        context.dataStore.edit { prefs ->
            val current = prefs[Keys.CUSTOM_KEYWORDS] ?: emptySet()
            prefs[Keys.CUSTOM_KEYWORDS] = current + clean
        }
    }

    suspend fun removeCustomKeyword(keyword: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[Keys.CUSTOM_KEYWORDS] ?: emptySet()
            prefs[Keys.CUSTOM_KEYWORDS] = current - keyword
        }
    }

    suspend fun addCustomWebsite(domain: String) {
        val clean = ContentGuardRules.normalizeHost(domain)
        if (clean.isEmpty()) return
        context.dataStore.edit { prefs ->
            val current = prefs[Keys.CUSTOM_WEBSITES] ?: emptySet()
            prefs[Keys.CUSTOM_WEBSITES] = current + clean
        }
    }

    suspend fun removeCustomWebsite(domain: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[Keys.CUSTOM_WEBSITES] ?: emptySet()
            prefs[Keys.CUSTOM_WEBSITES] = current - domain
        }
    }

    suspend fun addBlockedApp(packageName: String) {
        val clean = packageName.trim()
        if (clean.isEmpty()) return
        context.dataStore.edit { prefs ->
            val current = prefs[Keys.BLOCKED_APPS] ?: emptySet()
            prefs[Keys.BLOCKED_APPS] = current + clean
        }
    }

    suspend fun removeBlockedApp(packageName: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[Keys.BLOCKED_APPS] ?: emptySet()
            prefs[Keys.BLOCKED_APPS] = current - packageName
        }
    }

    suspend fun recordCrossBlockAndMaybeLockout(nowMs: Long = System.currentTimeMillis()): ContentGuardRules.CrossBlockUpdate {
        var result = ContentGuardRules.CrossBlockUpdate(emptyList(), 0L, false)
        context.dataStore.edit { prefs ->
            val lockoutEnabled = prefs[Keys.CROSS_BLOCK_LOCKOUT_ENABLED] ?: true
            val current = parseTimestamps(prefs[Keys.BLOCK_TIMESTAMPS_CSV])
            result = ContentGuardRules.recordCrossBlockEvent(current, nowMs, lockoutEnabled)
            prefs[Keys.BLOCK_TIMESTAMPS_CSV] = result.timestamps.joinToString(",")
            prefs[Keys.TOTAL_BLOCKED_COUNT] = (prefs[Keys.TOTAL_BLOCKED_COUNT] ?: 0) + 1
            if (result.triggeredLockout) {
                prefs[Keys.LOCKOUT_UNTIL_MS] = result.lockoutUntilMs
            }
        }
        return result
    }

    suspend fun clearLockout() {
        context.dataStore.edit { prefs ->
            prefs[Keys.LOCKOUT_UNTIL_MS] = 0L
            prefs[Keys.BLOCK_TIMESTAMPS_CSV] = ""
        }
    }

    suspend fun grantTemporaryTamperUnlock(durationMs: Long = 60_000L) {
        val until = System.currentTimeMillis() + durationMs
        context.dataStore.edit { it[Keys.TAMPER_UNLOCK_UNTIL_MS] = until }
    }

    private fun parseTimestamps(csv: String?): List<Long> {
        if (csv.isNullOrBlank()) return emptyList()
        return csv.split(',').mapNotNull { it.trim().toLongOrNull() }
    }
}
