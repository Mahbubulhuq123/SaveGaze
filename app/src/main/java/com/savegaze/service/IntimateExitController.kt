package com.savegaze.service

import com.savegaze.guard.ContentGuardLists

enum class MediaAppContext {
    REELS_SHORT,
    MOVIE_SERIES_PLAYER,
    BROWSER_OR_GENERAL
}

enum class IntimateActionMode(val displayName: String, val description: String) {
    SMART_CONTEXT(
        "Smart Auto-Skip / Next Reel / Back",
        "Next Reel on Reels/Shorts, skip scene forward on Movies/Series, and Back on browsers"
    ),
    BACK_ONLY(
        "Always Exit Video (Back)",
        "Blur screen, pause playback, and press Back immediately in every app"
    )
}

enum class DrmAppPolicy(val displayName: String, val description: String) {
    WARN_ONLY(
        "Show Warning Notice",
        "Warn once per session that DRM apps (Netflix, Prime, etc.) block screen & audio scanning"
    ),
    AUTO_BLOCK(
        "Auto-Block DRM Apps",
        "Prevent opening DRM streaming apps where SaveGaze cannot scan intimate scenes"
    ),
    OFF(
        "No Notice",
        "Allow DRM apps silently without warning or blocking"
    )
}

enum class ExitActionType {
    NEXT_REEL_SWIPE,
    SKIP_SCENE_FORWARD,
    PAUSE_AND_BACK
}

/**
 * Pure classification & action-selection engine for context-aware intimate scene response:
 * - Reels / Shorts apps -> swipe up to next reel
 * - Movie / Series players (Stremio, Nuvio, YouTube watch, VLC, browser fullscreen video) -> seek forward
 * - Browsers / social feeds / max retries -> pause media and navigate Back
 */
object IntimateExitRules {

    private val TIKTOK_PACKAGES = setOf(
        "com.zhiliaoapp.musically",
        "com.ss.android.ugc.trill",
        "com.zhiliaoapp.musically.go"
    )

    private val INSTAGRAM_PACKAGES = setOf(
        "com.instagram.android",
        "com.instagram.lite"
    )

    private val FACEBOOK_PACKAGES = setOf(
        "com.facebook.katana",
        "com.facebook.lite"
    )

    private val YOUTUBE_PACKAGES = setOf(
        "com.google.android.youtube",
        "app.revanced.android.youtube",
        "com.vanced.android.youtube"
    )

    private val DEDICATED_VIDEO_PLAYERS = setOf(
        "com.stremio.one",
        "org.stremio.app",
        "org.videolan.vlc",
        "com.mxtech.videoplayer.ad",
        "com.mxtech.videoplayer.pro",
        "com.plexapp.android",
        "org.xbmc.kodi",
        "com.brouken.player",
        "is.xyz.mpv"
    )

    private val SOCIAL_EXCLUDED_AUDIO_CAST = TIKTOK_PACKAGES + INSTAGRAM_PACKAGES + FACEBOOK_PACKAGES + setOf(
        "com.reddit.frontpage",
        "com.twitter.android",
        "com.snapchat.android",
        "org.telegram.messenger"
    )

    private val YT_LONG_PLAYER_HINTS = listOf(
        "watch_player", "watch_while", "player_view", "player_overlays",
        "player_control", "time_bar", "exo_progress", "fast_forward", "fullscreen_button"
    )

    private val REEL_VIEW_HINTS = listOf(
        "reel", "shorts", "clips_", "fb_shorts", "short_video"
    )

    private val PLAYER_VIEW_HINTS = listOf(
        "player_view", "watch_player", "exo_", "seekbar", "time_bar", "media_controller",
        "video_player", "play_pause", "fast_forward"
    )

    fun classifyContext(
        packageName: String,
        treeHints: Set<String>,
        hasSeekBarOrPlayerControls: Boolean,
        isLandscape: Boolean
    ): MediaAppContext {
        val pkg = packageName.lowercase()
        val loweredHints = treeHints.map { it.lowercase() }

        if (pkg in TIKTOK_PACKAGES) {
            return MediaAppContext.REELS_SHORT
        }

        if (pkg in YOUTUBE_PACKAGES) {
            if (loweredHints.any { h -> REEL_VIEW_HINTS.any { h.contains(it) } }) {
                return MediaAppContext.REELS_SHORT
            }
            if (hasSeekBarOrPlayerControls || isLandscape ||
                loweredHints.any { h -> PLAYER_VIEW_HINTS.any { h.contains(it) } }
            ) {
                return MediaAppContext.MOVIE_SERIES_PLAYER
            }
            return MediaAppContext.BROWSER_OR_GENERAL
        }

        if (pkg in INSTAGRAM_PACKAGES || pkg in FACEBOOK_PACKAGES) {
            val looksLikeReel = loweredHints.any { h -> REEL_VIEW_HINTS.any { h.contains(it) } }
            return if (looksLikeReel) MediaAppContext.REELS_SHORT else MediaAppContext.BROWSER_OR_GENERAL
        }

        if (pkg in DEDICATED_VIDEO_PLAYERS || pkg.contains("stremio") || pkg.contains("nuvio")) {
            return MediaAppContext.MOVIE_SERIES_PLAYER
        }

        if (pkg in ContentGuardLists.BROWSER_PACKAGES) {
            val playingVideo = hasSeekBarOrPlayerControls || isLandscape ||
                loweredHints.any { h -> PLAYER_VIEW_HINTS.any { h.contains(it) } }
            return if (playingVideo) MediaAppContext.MOVIE_SERIES_PLAYER else MediaAppContext.BROWSER_OR_GENERAL
        }

        if (hasSeekBarOrPlayerControls || loweredHints.any { h -> PLAYER_VIEW_HINTS.any { h.contains(it) } }) {
            return MediaAppContext.MOVIE_SERIES_PLAYER
        }

        return MediaAppContext.BROWSER_OR_GENERAL
    }

    fun chooseAction(
        context: MediaAppContext,
        mode: IntimateActionMode,
        consecutiveSkipsOnSameVideo: Int
    ): ExitActionType {
        if (mode == IntimateActionMode.BACK_ONLY) {
            return ExitActionType.PAUSE_AND_BACK
        }
        return when (context) {
            MediaAppContext.REELS_SHORT -> {
                if (consecutiveSkipsOnSameVideo < 3) ExitActionType.NEXT_REEL_SWIPE
                else ExitActionType.PAUSE_AND_BACK
            }
            MediaAppContext.MOVIE_SERIES_PLAYER -> {
                if (consecutiveSkipsOnSameVideo < 2) ExitActionType.SKIP_SCENE_FORWARD
                else ExitActionType.PAUSE_AND_BACK
            }
            MediaAppContext.BROWSER_OR_GENERAL -> ExitActionType.PAUSE_AND_BACK
        }
    }

    /**
     * Returns true when Screen Cast Audio AI should automatically engage for long-form content:
     * - Stremio, Nuvio, VLC, MX Player, Kodi, Plex, MPV -> true immediately
     * - YouTube -> true ONLY on non-Shorts long video watch player (never Home/Search/Shorts)
     * - Browsers (Chrome, etc.) -> true ONLY when fullscreen/player controls or landscape media playback is active
     * - Social Media apps (Facebook, Instagram, TikTok, etc.) -> ALWAYS false
     */
    fun shouldAutoCastAudio(
        packageName: String,
        treeHints: Set<String>,
        hasSeekBarOrPlayerControls: Boolean,
        isLandscape: Boolean,
        isMediaPlaying: Boolean
    ): Boolean {
        val pkg = packageName.lowercase()
        if (pkg.isBlank() || pkg in SOCIAL_EXCLUDED_AUDIO_CAST) return false

        if (pkg in DEDICATED_VIDEO_PLAYERS ||
            pkg.contains("stremio") ||
            pkg.contains("nuvio") ||
            pkg.contains("mxtech.videoplayer")
        ) {
            return true
        }

        val loweredHints = treeHints.map { it.lowercase() }

        if (pkg in YOUTUBE_PACKAGES) {
            val isShorts = loweredHints.any { h -> REEL_VIEW_HINTS.any { h.contains(it) } }
            if (isShorts) return false
            val hasWatchPlayer = loweredHints.any { h -> YT_LONG_PLAYER_HINTS.any { h.contains(it) } }
            return hasWatchPlayer || hasSeekBarOrPlayerControls || (isLandscape && isMediaPlaying)
        }

        if (pkg in ContentGuardLists.BROWSER_PACKAGES) {
            val hasPlayerNode = hasSeekBarOrPlayerControls ||
                loweredHints.any { h -> PLAYER_VIEW_HINTS.any { h.contains(it) } }
            return hasPlayerNode || (isLandscape && isMediaPlaying)
        }

        return false
    }
}
