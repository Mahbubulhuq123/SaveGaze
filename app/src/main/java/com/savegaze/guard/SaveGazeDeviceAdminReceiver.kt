package com.savegaze.guard

import android.app.admin.DeviceAdminReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent

/**
 * Device Administrator receiver used to prevent casual uninstallation of SaveGaze
 * until the user passes the Master Password + 40-second Commitment Challenge.
 */
class SaveGazeDeviceAdminReceiver : DeviceAdminReceiver() {
    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        return "SaveGaze protection is active. Deactivating Device Admin will allow uninstalling content protection."
    }

    companion object {
        fun componentName(context: Context): ComponentName =
            ComponentName(context, SaveGazeDeviceAdminReceiver::class.java)
    }
}
