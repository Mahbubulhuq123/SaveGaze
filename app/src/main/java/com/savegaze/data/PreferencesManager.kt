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
import androidx.datastore.preferences.preferencesDataStore
import com.savegaze.ml.GenderFilter
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
        prefs[Keys.SCAN_INTERVAL] ?: 500L
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
        prefs[Keys.BLUR_FULL_BODY] ?: false
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

    // ── Suspend setters ─────────────────────────────────────────────────

    suspend fun setGenderFilter(filter: GenderFilter) {
        context.dataStore.edit { prefs ->
            prefs[Keys.GENDER_FILTER] = filter.name
        }
    }

    suspend fun setSensitivity(value: Float) {
        val clamped = if (value > 0.45f) 0.25f else value.coerceIn(0.15f, 0.45f)
        context.dataStore.edit { prefs ->
            prefs[Keys.SENSITIVITY] = clamped
        }
    }

    suspend fun setBlurIntensity(value: Int) {
        context.dataStore.edit { prefs ->
            prefs[Keys.BLUR_INTENSITY] = value
        }
    }

    suspend fun setIsEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.IS_ENABLED] = enabled
        }
    }

    suspend fun setScanInterval(intervalMs: Long) {
        context.dataStore.edit { prefs ->
            prefs[Keys.SCAN_INTERVAL] = intervalMs
        }
    }

    suspend fun setBlurStyle(style: BlurStyle) {
        context.dataStore.edit { prefs ->
            prefs[Keys.BLUR_STYLE] = style.name
        }
    }

    suspend fun setBlurOpacity(opacity: Int) {
        val clamped = opacity.coerceIn(50, 100)
        context.dataStore.edit { prefs ->
            prefs[Keys.BLUR_OPACITY] = clamped
        }
    }

    suspend fun setBlurFaces(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.BLUR_FACES] = enabled
        }
    }

    suspend fun setBlurFullBody(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.BLUR_FULL_BODY] = enabled
        }
    }
}
