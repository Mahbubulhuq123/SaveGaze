package com.savegaze.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.savegaze.MainActivity
import com.savegaze.R
import com.savegaze.util.Constants

/**
 * Dedicated Android 14+ (API 34–37) foreground service holding `foregroundServiceType="mediaProjection"`
 * so the OS never revokes MediaProjection while streaming or watching long videos.
 */
class ScreenCastForegroundService : Service() {

    companion object {
        private const val TAG = "ScreenCastFgs"
        private const val FGS_NOTIFICATION_ID = Constants.NOTIFICATION_ID + 1

        @Volatile
        var isForegroundActive: Boolean = false
            private set

        @Volatile
        private var onReadyCallback: (() -> Unit)? = null

        fun start(context: Context, onReady: (() -> Unit)? = null) {
            onReadyCallback = onReady
            val intent = Intent(context, ScreenCastForegroundService::class.java)
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start ScreenCastForegroundService", e)
                onReadyCallback = null
                onReady?.invoke()
            }
        }

        fun stop(context: Context) {
            onReadyCallback = null
            isForegroundActive = false
            try {
                context.stopService(Intent(context, ScreenCastForegroundService::class.java))
            } catch (_: Exception) {
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureNotificationChannel()
        val notification = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    FGS_NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            } else {
                startForeground(FGS_NOTIFICATION_ID, notification)
            }
            isForegroundActive = true
        } catch (e: Exception) {
            Log.e(TAG, "startForeground(mediaProjection) failed", e)
        }
        onReadyCallback?.let { cb ->
            onReadyCallback = null
            cb.invoke()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        isForegroundActive = false
        super.onDestroy()
    }

    private fun ensureNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(Constants.NOTIFICATION_CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                Constants.NOTIFICATION_CHANNEL_ID,
                getString(R.string.service_notification_channel),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.accessibility_service_description)
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, Constants.NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Screen Cast Audio Guard active for video")
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }
}
