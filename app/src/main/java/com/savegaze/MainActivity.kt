package com.savegaze

import android.app.Activity
import android.content.Context
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.savegaze.data.PreferencesManager
import com.savegaze.service.SaveGazeAccessibilityService
import com.savegaze.ui.screens.HomeScreen
import com.savegaze.ui.screens.SettingsScreen
import com.savegaze.ui.screens.SettingsSection
import com.savegaze.ui.theme.SaveGazeTheme
import com.savegaze.update.AppUpdateManager
import com.savegaze.update.UpdateDialog
import com.savegaze.update.UpdateStatus

class MainActivity : ComponentActivity() {

    private var hasProjectionPermission by mutableStateOf(false)

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            android.util.Log.w("MainActivity", "MediaProjection permission GRANTED! Sending to service...")
            SaveGazeAccessibilityService.setMediaProjectionResult(result.resultCode, result.data!!)
            hasProjectionPermission = true
        } else {
            android.util.Log.w("MainActivity", "MediaProjection permission NOT granted: resultCode=${result.resultCode}")
        }
    }

    fun requestScreenCapturePermission() {
        android.util.Log.w("MainActivity", "Requesting MediaProjection screen capture intent...")
        val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
        mpManager?.createScreenCaptureIntent()?.let { intent ->
            mediaProjectionLauncher.launch(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            enableEdgeToEdge()
        } catch (e: Throwable) {
            android.util.Log.w("MainActivity", "EdgeToEdge warning: ${e.message}")
        }

        try {
            hasProjectionPermission = SaveGazeAccessibilityService.hasMediaProjection()
        } catch (e: Throwable) {
            android.util.Log.e("MainActivity", "Failed to check media projection state", e)
        }

        val preferencesManager = PreferencesManager(applicationContext)
        val updateManager = AppUpdateManager.getInstance(applicationContext)

        setContent {
            SaveGazeTheme {
                SaveGazeApp(
                    preferencesManager = preferencesManager,
                    updateManager = updateManager,
                    hasMediaProjection = hasProjectionPermission,
                    onRequestMediaProjection = { requestScreenCapturePermission() }
                )
            }
        }
    }
}

private data class NavigationItem(
    val label: String,
    val icon: ImageVector
)

@Composable
private fun SaveGazeApp(
    preferencesManager: PreferencesManager,
    updateManager: AppUpdateManager,
    hasMediaProjection: Boolean,
    onRequestMediaProjection: () -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    var pendingSettingsSection by remember { mutableStateOf<SettingsSection?>(null) }

    val updateStatus by updateManager.status.collectAsState()
    val currentVersion = remember { updateManager.getCurrentVersionName() }
    val lifecycleOwner = LocalLifecycleOwner.current

    // Check for updates automatically in background on launch
    LaunchedEffect(Unit) {
        updateManager.checkForUpdates(manual = false)
    }

    // Auto-resume installation if user returns from granting unknown sources permission
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val current = updateManager.status.value
                if (current is UpdateStatus.PermissionRequired) {
                    if (updateManager.canInstallUnknownApps()) {
                        updateManager.installApk(current.apkFile)
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Modal In-App Update Dialog
    UpdateDialog(
        status = updateStatus,
        currentVersion = currentVersion,
        onStartDownload = { info -> updateManager.startDownload(info) },
        onCancelDownload = { updateManager.cancelDownload() },
        onInstallApk = { file -> updateManager.installApk(file) },
        onOpenSettings = { updateManager.openInstallPermissionSettings() },
        onDismiss = { updateManager.dismissUpdate() },
        onDismissWithDontShowAgain = { versionName -> updateManager.dismissUpdateWithDontShowAgain(versionName) },
        onRemindLater = { delayMillis -> updateManager.remindMeLater(delayMillis) }
    )

    val navigationItems = remember {
        listOf(
            NavigationItem(label = "Home", icon = Icons.Default.Home),
            NavigationItem(label = "Settings", icon = Icons.Default.Settings)
        )
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface
            ) {
                navigationItems.forEachIndexed { index, item ->
                    NavigationBarItem(
                        selected = selectedTab == index,
                        onClick = {
                            pendingSettingsSection = null
                            selectedTab = index
                        },
                        icon = {
                            Icon(
                                imageVector = item.icon,
                                contentDescription = item.label
                            )
                        },
                        label = { Text(text = item.label) }
                    )
                }
            }
        }
    ) { innerPadding ->
        when (selectedTab) {
            0 -> HomeScreen(
                preferencesManager = preferencesManager,
                updateManager = updateManager,
                onOpenSettings = { section ->
                    pendingSettingsSection = section
                    selectedTab = 1
                },
                hasMediaProjection = hasMediaProjection,
                onRequestMediaProjection = onRequestMediaProjection,
                modifier = Modifier.padding(innerPadding)
            )
            1 -> SettingsScreen(
                preferencesManager = preferencesManager,
                updateManager = updateManager,
                sectionToReveal = pendingSettingsSection,
                onSectionRevealed = { pendingSettingsSection = null },
                modifier = Modifier.padding(innerPadding)
            )
        }
    }
}
