package com.savegaze

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Security
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
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.savegaze.data.PreferencesManager
import com.savegaze.guard.SaveGazeVpnService
import com.savegaze.service.SaveGazeAccessibilityService
import com.savegaze.ui.screens.GuardScreen
import com.savegaze.ui.screens.HomeScreen
import com.savegaze.ui.screens.SettingsScreen
import com.savegaze.ui.screens.SettingsSection
import com.savegaze.ui.theme.SaveGazeTheme
import com.savegaze.update.AppUpdateManager
import com.savegaze.update.UpdateDialog
import com.savegaze.update.UpdateStatus

class MainActivity : ComponentActivity() {

    private var hasProjectionPermission by mutableStateOf(false)
    private var hasAudioPermission by mutableStateOf(false)

    private val audioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasAudioPermission = isGranted
        if (isGranted) {
            SaveGazeAccessibilityService.refreshAudioCapture()
            if (!hasProjectionPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                requestScreenCapturePermission()
            }
        }
    }

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            SaveGazeAccessibilityService.setMediaProjectionResult(result.resultCode, result.data!!)
            hasProjectionPermission = true
        }
    }

    fun requestIntimateAudioPermissions() {
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        hasAudioPermission = granted
        if (!granted) {
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else if (!hasProjectionPermission) {
            requestScreenCapturePermission()
        } else {
            SaveGazeAccessibilityService.refreshAudioCapture()
        }
    }

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            SaveGazeVpnService.start(applicationContext)
        }
    }

    fun requestScreenCapturePermission() {
        val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
        mpManager?.createScreenCaptureIntent()?.let { intent ->
            mediaProjectionLauncher.launch(intent)
        }
    }

    fun requestStartVpnBlocker() {
        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) {
            vpnPermissionLauncher.launch(prepareIntent)
        } else {
            SaveGazeVpnService.start(applicationContext)
        }
    }

    fun stopOptionalAudioCast() {
        SaveGazeAccessibilityService.stopScreenCastIfOptional()
        hasProjectionPermission = SaveGazeAccessibilityService.hasMediaProjection()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            enableEdgeToEdge()
        } catch (_: Throwable) {
        }

        hasProjectionPermission = SaveGazeAccessibilityService.hasMediaProjection()
        hasAudioPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        val preferencesManager = PreferencesManager(applicationContext)
        val updateManager = AppUpdateManager.getInstance(applicationContext)

        setContent {
            SaveGazeTheme {
                SaveGazeApp(
                    preferencesManager = preferencesManager,
                    updateManager = updateManager,
                    hasMediaProjection = hasProjectionPermission,
                    hasAudioPermission = hasAudioPermission,
                    onRequestMediaProjection = { requestScreenCapturePermission() },
                    onRequestAudioPermissions = { requestIntimateAudioPermissions() },
                    onStopAudioCast = { stopOptionalAudioCast() },
                    onRequestVpnStart = { requestStartVpnBlocker() },
                    onStopVpn = { SaveGazeVpnService.stop(applicationContext) }
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
    hasAudioPermission: Boolean,
    onRequestMediaProjection: () -> Unit,
    onRequestAudioPermissions: () -> Unit,
    onStopAudioCast: () -> Unit,
    onRequestVpnStart: () -> Unit,
    onStopVpn: () -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    var pendingSettingsSection by remember { mutableStateOf<SettingsSection?>(null) }

    val updateStatus by updateManager.status.collectAsState()
    val currentVersion = remember { updateManager.getCurrentVersionName() }
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(Unit) {
        updateManager.checkForUpdates(manual = false)
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val current = updateManager.status.value
                if (current is UpdateStatus.PermissionRequired && updateManager.canInstallUnknownApps()) {
                    updateManager.installApk(current.apkFile)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

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
            NavigationItem(label = "Guard", icon = Icons.Default.Security),
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
                        icon = { Icon(imageVector = item.icon, contentDescription = item.label) },
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
                    selectedTab = 2
                },
                onOpenGuard = { selectedTab = 1 },
                onRequestVpnStart = onRequestVpnStart,
                hasMediaProjection = hasMediaProjection,
                hasAudioPermission = hasAudioPermission,
                onRequestMediaProjection = onRequestMediaProjection,
                onRequestAudioPermissions = onRequestAudioPermissions,
                onStopAudioCast = onStopAudioCast,
                modifier = Modifier.padding(innerPadding)
            )
            1 -> GuardScreen(
                preferencesManager = preferencesManager,
                onRequestVpnStart = onRequestVpnStart,
                onStopVpn = onStopVpn,
                modifier = Modifier.padding(innerPadding)
            )
            2 -> SettingsScreen(
                preferencesManager = preferencesManager,
                updateManager = updateManager,
                sectionToReveal = pendingSettingsSection,
                onSectionRevealed = { pendingSettingsSection = null },
                hasAudioPermission = hasAudioPermission,
                hasMediaProjection = hasMediaProjection,
                onRequestAudioPermissions = onRequestAudioPermissions,
                modifier = Modifier.padding(innerPadding)
            )
        }
    }
}
