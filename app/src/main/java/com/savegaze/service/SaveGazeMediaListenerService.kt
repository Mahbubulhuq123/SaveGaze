package com.savegaze.service

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.concurrent.ConcurrentHashMap

data class PlaybackSnapshot(
    val packageName: String,
    val title: String,
    val subtitle: String,
    val positionMs: Long,
    val durationMs: Long,
    val isPlaying: Boolean
)

/**
 * Passive NotificationListener + MediaSession bridge allowing SaveGaze to read the
 * playing title and exact playback position (and jump directly past marked scenes via seekTo)
 * across Nuvio, Stremio, VLC, MX Player, browsers, and other media players.
 */
class SaveGazeMediaListenerService : NotificationListenerService() {

    companion object {
        private val notificationTitlesByPkg = ConcurrentHashMap<String, String>()

        fun isNotificationAccessEnabled(context: Context): Boolean {
            val flat = ComponentName(context, SaveGazeMediaListenerService::class.java).flattenToString()
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            ) ?: return false
            return enabled.split(':').any { it.equals(flat, ignoreCase = true) }
        }

        fun getActivePlaybackSnapshot(context: Context, targetPackage: String): PlaybackSnapshot? {
            if (targetPackage.isBlank() || !isNotificationAccessEnabled(context)) return null
            val msm = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager ?: return null
            val component = ComponentName(context, SaveGazeMediaListenerService::class.java)
            val controllers = try {
                msm.getActiveSessions(component)
            } catch (_: SecurityException) {
                return null
            } catch (_: Exception) {
                return null
            }

            val controller = pickControllerForPackage(controllers, targetPackage)
            if (controller != null) {
                val meta = controller.metadata
                val title = meta?.getString(MediaMetadata.METADATA_KEY_TITLE)
                    ?: meta?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
                    ?: notificationTitlesByPkg[controller.packageName]
                    ?: notificationTitlesByPkg[targetPackage]
                    ?: ""
                val subtitle = meta?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
                    ?: meta?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                    ?: ""
                val durationMs = meta?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.coerceAtLeast(0L) ?: 0L
                val state = controller.playbackState
                val isPlaying = state?.state == PlaybackState.STATE_PLAYING
                val posMs = computeLivePositionMs(state, durationMs)
                if (title.isNotBlank() || posMs >= 0L) {
                    return PlaybackSnapshot(
                        packageName = controller.packageName,
                        title = title.trim(),
                        subtitle = subtitle.trim(),
                        positionMs = posMs,
                        durationMs = durationMs,
                        isPlaying = isPlaying
                    )
                }
            }

            val notifTitle = notificationTitlesByPkg[targetPackage]
            return if (!notifTitle.isNullOrBlank()) {
                PlaybackSnapshot(
                    packageName = targetPackage,
                    title = notifTitle.trim(),
                    subtitle = "",
                    positionMs = -1L,
                    durationMs = 0L,
                    isPlaying = true
                )
            } else null
        }

        fun seekActivePlaybackTo(context: Context, targetPackage: String, targetPositionMs: Long): Boolean {
            if (targetPackage.isBlank() || !isNotificationAccessEnabled(context)) return false
            val msm = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager ?: return false
            val component = ComponentName(context, SaveGazeMediaListenerService::class.java)
            val controllers = try {
                msm.getActiveSessions(component)
            } catch (_: Exception) {
                return false
            }
            val controller = pickControllerForPackage(controllers, targetPackage) ?: return false
            return try {
                controller.transportControls.seekTo(targetPositionMs.coerceAtLeast(0L))
                true
            } catch (_: Exception) {
                false
            }
        }

        private fun pickControllerForPackage(
            controllers: List<MediaController>,
            targetPackage: String
        ): MediaController? {
            val matching = controllers.filter { it.packageName.equals(targetPackage, ignoreCase = true) }
            if (matching.isNotEmpty()) {
                return matching.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
                    ?: matching.first()
            }
            return controllers.firstOrNull {
                it.playbackState?.state == PlaybackState.STATE_PLAYING &&
                    !it.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).isNullOrBlank()
            }
        }

        private fun computeLivePositionMs(state: PlaybackState?, durationMs: Long): Long {
            if (state == null || state.position == PlaybackState.PLAYBACK_POSITION_UNKNOWN || state.position < 0L) {
                return -1L
            }
            var pos = state.position
            if (state.state == PlaybackState.STATE_PLAYING && state.lastPositionUpdateTime > 0L) {
                val delta = (SystemClock.elapsedRealtime() - state.lastPositionUpdateTime).coerceIn(0L, 10_000L)
                val speed = if (state.playbackSpeed > 0f) state.playbackSpeed else 1f
                pos += (delta * speed).toLong()
            }
            return if (durationMs > 0L) pos.coerceIn(0L, durationMs) else pos.coerceAtLeast(0L)
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val pkg = sbn?.packageName ?: return
        val notification = sbn.notification ?: return
        val isMediaStyle = notification.category == Notification.CATEGORY_TRANSPORT ||
            notification.extras?.containsKey(Notification.EXTRA_MEDIA_SESSION) == true
        if (!isMediaStyle) return
        val title = notification.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim()
        if (!title.isNullOrBlank()) {
            notificationTitlesByPkg[pkg] = title
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        val pkg = sbn?.packageName ?: return
        val notif = sbn.notification ?: return
        val isMediaStyle = notif.category == Notification.CATEGORY_TRANSPORT ||
            notif.extras?.containsKey(Notification.EXTRA_MEDIA_SESSION) == true
        if (isMediaStyle) {
            notificationTitlesByPkg.remove(pkg)
        }
    }
}
