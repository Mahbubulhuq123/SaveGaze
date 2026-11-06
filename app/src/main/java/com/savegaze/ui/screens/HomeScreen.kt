package com.savegaze.ui.screens

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.savegaze.data.BlurStyle
import com.savegaze.data.PreferencesManager
import com.savegaze.ml.GenderFilter
import com.savegaze.service.DetectorStatus
import com.savegaze.service.SaveGazeAccessibilityService
import com.savegaze.update.AppUpdateManager
import com.savegaze.update.UpdateStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

private fun isAccessibilityServiceEnabled(context: Context): Boolean {
    val serviceName = ComponentName(context, SaveGazeAccessibilityService::class.java)
    val enabledServices = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false
    return enabledServices.contains(serviceName.flattenToString())
}

private enum class ProtectionState {
    CHECKING,
    OFF,
    PAUSED,
    STARTING,
    ERROR,
    ACTIVE
}

private enum class HealthLevel {
    OK,
    WARN,
    ERROR,
    NEUTRAL
}

private fun formatFrameAge(lastFrameAtMillis: Long, nowMillis: Long): String {
    if (lastFrameAtMillis <= 0L) return "Waiting for first scan"
    val ageMillis = (nowMillis - lastFrameAtMillis).coerceAtLeast(0L)
    return when {
        ageMillis < 2_000L -> "Just now"
        ageMillis < 60_000L -> "${ageMillis / 1000}s ago"
        else -> "${ageMillis / 60_000}m ago"
    }
}

private fun frameHealthLevel(lastFrameAtMillis: Long, nowMillis: Long): HealthLevel {
    if (lastFrameAtMillis <= 0L) return HealthLevel.NEUTRAL
    val ageMillis = (nowMillis - lastFrameAtMillis).coerceAtLeast(0L)
    return if (ageMillis > 5_000L) HealthLevel.WARN else HealthLevel.OK
}

@Composable
fun HomeScreen(
    preferencesManager: PreferencesManager,
    updateManager: AppUpdateManager,
    onOpenSettings: (SettingsSection) -> Unit = {},
    hasMediaProjection: Boolean = true,
    onRequestMediaProjection: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()

    val updateStatus by updateManager.status.collectAsState()

    // Null until the first check completes, so we never flash "OFF" on launch.
    var isServiceEnabled by remember { mutableStateOf<Boolean?>(null) }
    val masterEnabled by preferencesManager.isEnabledFlow.collectAsState(initial = null)
    val health by SaveGazeAccessibilityService.healthFlow.collectAsState()

    val selectedGender by preferencesManager.genderFilterFlow.collectAsState(initial = GenderFilter.BOTH)
    val sensitivity by preferencesManager.sensitivityFlow.collectAsState(initial = 0.25f)
    val blurStyle by preferencesManager.blurStyleFlow.collectAsState(initial = BlurStyle.SOLID_BLACK)
    val blurOpacity by preferencesManager.blurOpacityFlow.collectAsState(initial = 100)

    // Re-check on every resume so returning from system Accessibility settings updates the card.
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isServiceEnabled = isAccessibilityServiceEnabled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        isServiceEnabled = isAccessibilityServiceEnabled(context)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val systemEnabled = isServiceEnabled == true
    val protectionState = when {
        isServiceEnabled == null -> ProtectionState.CHECKING
        !systemEnabled -> ProtectionState.OFF
        masterEnabled == null -> ProtectionState.CHECKING
        masterEnabled == false -> ProtectionState.PAUSED
        health.detectorStatus == DetectorStatus.FAILED -> ProtectionState.ERROR
        !health.overlayActive && health.lastError != null -> ProtectionState.ERROR
        !health.serviceRunning ||
            health.detectorStatus == DetectorStatus.LOADING ||
            !health.overlayActive -> ProtectionState.STARTING
        else -> ProtectionState.ACTIVE
    }

    val statusAccent by animateColorAsState(
        targetValue = when (protectionState) {
            ProtectionState.ACTIVE -> Color(0xFF2E7D32)
            ProtectionState.PAUSED, ProtectionState.STARTING -> Color(0xFF8A6100)
            ProtectionState.ERROR -> MaterialTheme.colorScheme.error
            ProtectionState.OFF, ProtectionState.CHECKING -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = tween(durationMillis = 500),
        label = "statusAccent"
    )

    val statusContainer by animateColorAsState(
        targetValue = when (protectionState) {
            ProtectionState.ACTIVE -> Color(0xFF2E7D32)
            ProtectionState.PAUSED, ProtectionState.STARTING -> Color(0xFF8A6100)
            ProtectionState.ERROR -> MaterialTheme.colorScheme.error
            ProtectionState.OFF -> Color(0xFF616161)
            ProtectionState.CHECKING -> MaterialTheme.colorScheme.surfaceVariant
        },
        animationSpec = tween(durationMillis = 500),
        label = "statusContainer"
    )

    val statusOnContainer = when (protectionState) {
        ProtectionState.CHECKING -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> Color.White
    }

    val glowAlpha by animateFloatAsState(
        targetValue = if (protectionState == ProtectionState.ACTIVE) 0.3f else 0.0f,
        animationSpec = tween(durationMillis = 500),
        label = "glowAlpha"
    )

    var nowMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(systemEnabled, masterEnabled) {
        if (systemEnabled && masterEnabled == true) {
            while (true) {
                nowMillis = System.currentTimeMillis()
                delay(1_000L)
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(8.dp))

        // App Header
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                imageVector = Icons.Default.Shield,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(36.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = "SaveGaze",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Real-time screen content protection",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(24.dp))

        // In-app Update Available Banner
        val currentUpdate = updateStatus
        if (currentUpdate is UpdateStatus.UpdateAvailable) {
            val info = currentUpdate.info
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .clickable {
                        updateManager.checkForUpdates(manual = true)
                    },
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.SystemUpdate,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Update Available: v${info.versionName}",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            text = "Tap to download and install automatically",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = { updateManager.startDownload(info) },
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Update")
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        // Service Status Card
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 4.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                statusAccent.copy(alpha = 0.08f),
                                MaterialTheme.colorScheme.surface
                            )
                        )
                    )
                    .padding(28.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Glowing status indicator
                    Box(contentAlignment = Alignment.Center) {
                        // Glow layer
                        if (protectionState == ProtectionState.ACTIVE) {
                            Box(
                                modifier = Modifier
                                    .size(100.dp)
                                    .blur(24.dp)
                                    .clip(CircleShape)
                                    .background(statusAccent.copy(alpha = glowAlpha))
                            )
                        }
                        // Main indicator
                        Box(
                            modifier = Modifier
                                .size(80.dp)
                                .shadow(
                                    elevation = if (protectionState == ProtectionState.ACTIVE) 12.dp else 4.dp,
                                    shape = CircleShape
                                )
                                .clip(CircleShape)
                                .background(statusContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            when (protectionState) {
                                ProtectionState.ACTIVE -> Text(
                                    text = "ON",
                                    style = MaterialTheme.typography.headlineMedium,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = statusOnContainer
                                )
                                ProtectionState.OFF -> Text(
                                    text = "OFF",
                                    style = MaterialTheme.typography.headlineMedium,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = statusOnContainer
                                )
                                ProtectionState.PAUSED -> Icon(
                                    imageVector = Icons.Default.Pause,
                                    contentDescription = null,
                                    tint = statusOnContainer,
                                    modifier = Modifier.size(36.dp)
                                )
                                ProtectionState.ERROR -> Icon(
                                    imageVector = Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = statusOnContainer,
                                    modifier = Modifier.size(36.dp)
                                )
                                ProtectionState.STARTING,
                                ProtectionState.CHECKING -> CircularProgressIndicator(
                                    modifier = Modifier.size(28.dp),
                                    color = statusOnContainer,
                                    strokeWidth = 3.dp
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = when (protectionState) {
                            ProtectionState.CHECKING -> "Checking Protection…"
                            ProtectionState.OFF -> "Protection Off"
                            ProtectionState.PAUSED -> "Protection Paused"
                            ProtectionState.STARTING -> "Starting Protection…"
                            ProtectionState.ERROR -> "Protection Needs Attention"
                            ProtectionState.ACTIVE -> "Protection Active"
                        },
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = statusAccent,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = when (protectionState) {
                            ProtectionState.CHECKING -> "Verifying accessibility access"
                            ProtectionState.OFF -> "Enable the accessibility service to start protection"
                            ProtectionState.PAUSED -> "Scanning is paused. Turn protection back on to resume."
                            ProtectionState.STARTING -> "Loading the on-device detector and screen overlay"
                            ProtectionState.ERROR -> health.lastError
                                ?: "Protection could not start. Try reopening the app."
                            ProtectionState.ACTIVE -> "SaveGaze is monitoring and protecting your screen"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )

                    // Master pause switch — the single control for starting/stopping protection.
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 20.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Protection",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = when {
                                    !systemEnabled -> "Accessibility service is off"
                                    masterEnabled == true -> "Scanning and blurring are enabled"
                                    else -> "Scanning and blurring are paused"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = systemEnabled && masterEnabled == true,
                            onCheckedChange = { enabled ->
                                coroutineScope.launch {
                                    preferencesManager.setIsEnabled(enabled)
                                }
                            },
                            enabled = systemEnabled && protectionState != ProtectionState.CHECKING
                        )
                    }

                    Divider(
                        modifier = Modifier.padding(top = 16.dp, bottom = 12.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )

                    // Target Gender quick selector
                    Column(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Blur Target Gender",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = when (selectedGender) {
                                    GenderFilter.FEMALE_ONLY -> "Female only"
                                    GenderFilter.MALE_ONLY -> "Male only"
                                    GenderFilter.BOTH -> "Both genders"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Medium
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            GenderQuickChip(
                                label = "Female",
                                selected = selectedGender == GenderFilter.FEMALE_ONLY,
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    coroutineScope.launch {
                                        preferencesManager.setGenderFilter(GenderFilter.FEMALE_ONLY)
                                    }
                                }
                            )
                            GenderQuickChip(
                                label = "Male",
                                selected = selectedGender == GenderFilter.MALE_ONLY,
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    coroutineScope.launch {
                                        preferencesManager.setGenderFilter(GenderFilter.MALE_ONLY)
                                    }
                                }
                            )
                            GenderQuickChip(
                                label = "Both",
                                selected = selectedGender == GenderFilter.BOTH,
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    coroutineScope.launch {
                                        preferencesManager.setGenderFilter(GenderFilter.BOTH)
                                    }
                                }
                            )
                        }
                    }

                    if (protectionState == ProtectionState.OFF) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = {
                                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Open Accessibility Settings")
                        }
                    }

                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                        Spacer(modifier = Modifier.height(12.dp))
                        if (!hasMediaProjection) {
                            Button(
                                onClick = onRequestMediaProjection,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.secondary
                                ),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Shield,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Grant Screen Capture (Android 10)")
                            }
                        } else {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Shield,
                                    contentDescription = null,
                                    tint = Color(0xFF2E7D32),
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Screen Capture Active",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = Color(0xFF2E7D32)
                                )
                            }
                        }
                    }
                }
            }
        }

        // Real health signals: "Active" must mean the pipeline is actually working.
        if (systemEnabled && masterEnabled == true) {
            Spacer(modifier = Modifier.height(16.dp))

            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp)
                ) {
                    Text(
                        text = "Protection Health",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    HealthRow(
                        label = "AI detector",
                        value = when (health.detectorStatus) {
                            DetectorStatus.LOADING -> "Loading…"
                            DetectorStatus.READY -> "Ready"
                            DetectorStatus.FAILED -> "Failed"
                        },
                        level = when (health.detectorStatus) {
                            DetectorStatus.LOADING -> HealthLevel.NEUTRAL
                            DetectorStatus.READY -> HealthLevel.OK
                            DetectorStatus.FAILED -> HealthLevel.ERROR
                        }
                    )

                    HealthRow(
                        label = "Screen overlay",
                        value = if (health.overlayActive) "Running" else "Not running",
                        level = when {
                            health.overlayActive -> HealthLevel.OK
                            health.serviceRunning -> HealthLevel.ERROR
                            else -> HealthLevel.NEUTRAL
                        }
                    )

                    HealthRow(
                        label = "Last frame scanned",
                        value = formatFrameAge(health.lastFrameAtMillis, nowMillis),
                        level = frameHealthLevel(health.lastFrameAtMillis, nowMillis)
                    )

                    health.lastError?.let { error ->
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Last error: $error",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Settings shortcuts — all controls live in exactly one place (Settings).
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Text(
                    text = "Protection Settings",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "Adjust how detection and shielding behave",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(8.dp))

                SettingsShortcutRow(
                    icon = Icons.Default.People,
                    title = "Gender Filter",
                    value = when (selectedGender) {
                        GenderFilter.MALE_ONLY -> "Male only"
                        GenderFilter.FEMALE_ONLY -> "Female only"
                        GenderFilter.BOTH -> "Both genders"
                    },
                    onClick = { onOpenSettings(SettingsSection.GENDER) }
                )

                Divider(color = MaterialTheme.colorScheme.outlineVariant)

                SettingsShortcutRow(
                    icon = Icons.Default.Tune,
                    title = "Detection Sensitivity",
                    value = String.format(Locale.getDefault(), "Threshold %.2f", sensitivity),
                    onClick = { onOpenSettings(SettingsSection.SENSITIVITY) }
                )

                Divider(color = MaterialTheme.colorScheme.outlineVariant)

                SettingsShortcutRow(
                    icon = Icons.Default.Palette,
                    title = "Blur Style & Opacity",
                    value = "${blurStyle.displayName.substringBefore(" (")} · $blurOpacity%",
                    onClick = { onOpenSettings(SettingsSection.STYLE) }
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun HealthRow(
    label: String,
    value: String,
    level: HealthLevel
) {
    val indicatorColor = when (level) {
        HealthLevel.OK -> Color(0xFF2E7D32)
        HealthLevel.WARN -> Color(0xFF8A6100)
        HealthLevel.ERROR -> MaterialTheme.colorScheme.error
        HealthLevel.NEUTRAL -> MaterialTheme.colorScheme.outline
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(indicatorColor)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = indicatorColor
        )
    }
}

@Composable
private fun SettingsShortcutRow(
    icon: ImageVector,
    title: String,
    value: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Icon(
            imageVector = Icons.Default.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun GenderQuickChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .defaultMinSize(minHeight = 44.dp)
            .clip(RoundedCornerShape(12.dp))
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.RadioButton
            ),
        shape = RoundedCornerShape(12.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        border = BorderStroke(
            width = if (selected) 1.5.dp else 1.dp,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp, horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            if (selected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}
