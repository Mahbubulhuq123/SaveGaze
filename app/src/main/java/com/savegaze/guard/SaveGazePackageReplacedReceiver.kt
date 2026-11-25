package com.savegaze.guard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import com.savegaze.data.PreferencesManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Restores active ContentGuard protection services automatically after an in-place app update.
 */
class SaveGazePackageReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val prefs = PreferencesManager(context.applicationContext)
                if (prefs.getWebsiteBlockerEnabled() && VpnService.prepare(context) == null) {
                    runCatching { SaveGazeVpnService.start(context.applicationContext) }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
