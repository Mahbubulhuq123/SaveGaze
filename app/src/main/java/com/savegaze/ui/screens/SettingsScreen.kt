package com.savegaze.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.savegaze.data.BlurStyle
import com.savegaze.data.PreferencesManager
import com.savegaze.guard.BlockPChallengeDialog
import com.savegaze.guard.DeviceAdminHelper
import com.savegaze.ml.GenderFilter
import com.savegaze.service.DrmAppPolicy
import com.savegaze.service.IntimateActionMode
import com.savegaze.update.AppUpdateManager
import com.savegaze.update.UpdateStatus
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Anchor targets used by Home shortcuts to deep-link into the single settings surface. */
enum class SettingsSection(val title: String) {
    GENDER("Gender Filter"),
    FACE("Face Blurring"),
    INTIMATE_GUARD("Intimate Video & Audio Guard"),
    FULL_BODY("Full Body Blurring"),
    UNINSTALL_PROTECTION("Uninstall Protection"),
    SENSITIVITY("Detection Sensitivity"),
    INTENSITY("Blur Intensity"),
    STYLE("Blur Style & Variation"),
    OPACITY("Blur Opacity / Transparency"),
    UPDATES("App Updates")
}

@Composable
fun SettingsScreen(
    preferencesManager: PreferencesManager,
    updateManager: AppUpdateManager,
    sectionToReveal: SettingsSection? = null,
    onSectionRevealed: () -> Unit = {},
    hasAudioPermission: Boolean = true,
    hasMediaProjection: Boolean = true,
    onRequestAudioPermissions: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    val sectionOffsets = remember { mutableStateMapOf<SettingsSection, Int>() }
    val revealPaddingPx = with(LocalDensity.current) { 12.dp.roundToPx() }
    val targetOffset = sectionToReveal?.let { sectionOffsets[it] }

    val updateStatus by updateManager.status.collectAsState()

    LaunchedEffect(updateStatus) {
        if (updateStatus is UpdateStatus.UpToDate) {
            val ver = updateManager.getCurrentVersionName()
            android.widget.Toast.makeText(context, "SaveGaze is up to date (v$ver)", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(sectionToReveal, targetOffset) {
        val section = sectionToReveal ?: return@LaunchedEffect
        val offset = sectionOffsets[section] ?: return@LaunchedEffect
        scrollState.animateScrollTo((offset - revealPaddingPx).coerceAtLeast(0))
        onSectionRevealed()
    }

    val selectedGender by preferencesManager.genderFilterFlow.collectAsState(initial = GenderFilter.BOTH)
    val autoCheckUpdates by preferencesManager.autoCheckUpdatesFlow.collectAsState(initial = true)
    val remindTimestamp by preferencesManager.remindMeLaterTimestampFlow.collectAsState(initial = 0L)
    val dismissedVersion by preferencesManager.dismissedUpdateVersionFlow.collectAsState(initial = null)
    val sensitivityPreference by preferencesManager.sensitivityFlow.collectAsState(initial = 0.25f)
    var sensitivity by remember { mutableFloatStateOf(sensitivityPreference) }
    val blurIntensityPreference by preferencesManager.blurIntensityFlow.collectAsState(initial = 25)
    var blurIntensity by remember { mutableIntStateOf(blurIntensityPreference) }
    val blurStyle by preferencesManager.blurStyleFlow.collectAsState(initial = BlurStyle.SOLID_BLACK)
    val blurOpacity by preferencesManager.blurOpacityFlow.collectAsState(initial = 100)
    var sliderOpacity by remember { mutableFloatStateOf(blurOpacity.toFloat()) }
    val blurFaces by preferencesManager.blurFacesFlow.collectAsState(initial = false)
    val intimateGuardEnabled by preferencesManager.intimateGuardEnabledFlow.collectAsState(initial = true)
    val intimateAudioEnabled by preferencesManager.intimateAudioEnabledFlow.collectAsState(initial = true)
    val intimateActionMode by preferencesManager.intimateActionModeFlow.collectAsState(initial = IntimateActionMode.SMART_CONTEXT)
    val drmAppPolicy by preferencesManager.drmAppPolicyFlow.collectAsState(initial = DrmAppPolicy.WARN_ONLY)
    val tamperEnabled by preferencesManager.tamperGuardEnabledFlow.collectAsState(initial = false)
    val masterHash by preferencesManager.masterPasswordHashFlow.collectAsState(initial = null)
    var pendingChallengeTitle by remember { mutableStateOf<String?>(null) }
    var pendingChallengeAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var howItWorksExpanded by remember { mutableStateOf(false) }

    pendingChallengeTitle?.let { title ->
        BlockPChallengeDialog(
            actionTitle = title,
            storedPasswordHash = masterHash,
            onSuccess = {
                pendingChallengeTitle = null
                val act = pendingChallengeAction
                pendingChallengeAction = null
                act?.invoke()
            },
            onDismiss = {
                pendingChallengeTitle = null
                pendingChallengeAction = null
            }
        )
    }

    LaunchedEffect(blurOpacity) {
        sliderOpacity = blurOpacity.toFloat()
    }

    LaunchedEffect(sensitivityPreference) {
        sensitivity = sensitivityPreference
    }

    LaunchedEffect(blurIntensityPreference) {
        blurIntensity = blurIntensityPreference
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        Text(
            text = "Settings",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.height(20.dp))

        // Gender Filter Section
        ElevatedCard(
            modifier = Modifier
                .fillMaxWidth()
                .registerSettingsSection(SettingsSection.GENDER, sectionOffsets),
            shape = RoundedCornerShape(16.dp),
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Text(
                    text = "Gender Filter",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "Select which detected genders to blur",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(12.dp))

                GenderFilterOption(
                    label = "Male Only",
                    description = "Blur full male body immediately on semi-nude content (plus face if Face Blurring is ON)",
                    selected = selectedGender == GenderFilter.MALE_ONLY,
                    onClick = {
                        coroutineScope.launch { preferencesManager.setGenderFilter(GenderFilter.MALE_ONLY) }
                    }
                )

                GenderFilterOption(
                    label = "Female Only",
                    description = "Blur full female body immediately on semi-nude content (plus face if Face Blurring is ON)",
                    selected = selectedGender == GenderFilter.FEMALE_ONLY,
                    onClick = {
                        coroutineScope.launch { preferencesManager.setGenderFilter(GenderFilter.FEMALE_ONLY) }
                    }
                )

                GenderFilterOption(
                    label = "Both",
                    description = "Blur full body immediately on semi-nude content for both genders",
                    selected = selectedGender == GenderFilter.BOTH,
                    onClick = {
                        coroutineScope.launch { preferencesManager.setGenderFilter(GenderFilter.BOTH) }
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Face Blurring Section
        ElevatedCard(
            modifier = Modifier
                .fillMaxWidth()
                .registerSettingsSection(SettingsSection.FACE, sectionOffsets),
            shape = RoundedCornerShape(16.dp),
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = blurFaces,
                        onValueChange = { checked ->
                            if (!checked) {
                                pendingChallengeTitle = "Disable Face Blurring"
                                pendingChallengeAction = {
                                    coroutineScope.launch { preferencesManager.setBlurFaces(false) }
                                }
                            } else {
                                coroutineScope.launch { preferencesManager.setBlurFaces(true) }
                            }
                        },
                        role = Role.Switch
                    )
                    .padding(20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Face Blurring",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (blurFaces)
                            "Thoroughly blurs faces of the selected target gender, plus whole body on semi-nude detection."
                        else
                            "Faces are preserved; whole body blurs automatically when semi-nude content is detected.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
                Switch(
                    checked = blurFaces,
                    onCheckedChange = null
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Intimate Video, Audio & DRM Guard Section
        ElevatedCard(
            modifier = Modifier
                .fillMaxWidth()
                .registerSettingsSection(SettingsSection.INTIMATE_GUARD, sectionOffsets),
            shape = RoundedCornerShape(16.dp),
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Text(
                    text = "Intimate Video & Audio Guard",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Detects intimate scenes (kissing, bed scenes, explicit visuals, or moaning audio), blurs first, then skips or exits.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(14.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = intimateGuardEnabled,
                            onValueChange = { wantOn ->
                                if (!wantOn) {
                                    pendingChallengeTitle = "Disable Intimate Video Guard"
                                    pendingChallengeAction = {
                                        coroutineScope.launch { preferencesManager.setIntimateGuardEnabled(false) }
                                    }
                                } else {
                                    coroutineScope.launch { preferencesManager.setIntimateGuardEnabled(true) }
                                }
                            },
                            role = Role.Switch
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Auto-Action on Intimate Scenes",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "Blurs screen immediately first, then skips scene / next reel / goes back",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Switch(checked = intimateGuardEnabled, onCheckedChange = null)
                }

                Divider(modifier = Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = intimateAudioEnabled,
                            onValueChange = { wantOn ->
                                if (!wantOn) {
                                    pendingChallengeTitle = "Disable Intimate Audio Detection"
                                    pendingChallengeAction = {
                                        coroutineScope.launch { preferencesManager.setIntimateAudioEnabled(false) }
                                    }
                                } else {
                                    coroutineScope.launch { preferencesManager.setIntimateAudioEnabled(true) }
                                    if (!hasAudioPermission || !hasMediaProjection) {
                                        onRequestAudioPermissions()
                                    }
                                }
                            },
                            role = Role.Switch
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Playback Audio Detection (Moaning)",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "Uses internal playback audio (no mic) via YAMNet AI; needs Audio + Capture permission",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Switch(checked = intimateAudioEnabled, onCheckedChange = null)
                }

                if (intimateGuardEnabled && intimateAudioEnabled && (!hasAudioPermission || !hasMediaProjection)) {
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedButton(onClick = onRequestAudioPermissions) {
                        Text("Grant Audio + Screen Permission")
                    }
                }

                Divider(modifier = Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)

                Text(
                    text = "When Intimate Scene Triggers",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(6.dp))
                IntimateActionMode.entries.forEach { mode ->
                    BlurStyleOption(
                        label = mode.displayName,
                        description = mode.description,
                        selected = intimateActionMode == mode,
                        onClick = {
                            coroutineScope.launch { preferencesManager.setIntimateActionMode(mode) }
                        }
                    )
                }

                Divider(modifier = Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)

                Text(
                    text = "DRM Apps (Netflix, Prime Video, Hotstar)",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(6.dp))
                DrmAppPolicy.entries.forEach { policy ->
                    BlurStyleOption(
                        label = policy.displayName,
                        description = policy.description,
                        selected = drmAppPolicy == policy,
                        onClick = {
                            if (drmAppPolicy == DrmAppPolicy.AUTO_BLOCK && policy != DrmAppPolicy.AUTO_BLOCK) {
                                pendingChallengeTitle = "Relax DRM App Policy"
                                pendingChallengeAction = {
                                    coroutineScope.launch { preferencesManager.setDrmAppPolicy(policy) }
                                }
                            } else {
                                coroutineScope.launch { preferencesManager.setDrmAppPolicy(policy) }
                            }
                        }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Whole-Body Semi-Nude Mode Info Section
        ElevatedCard(
            modifier = Modifier
                .fillMaxWidth()
                .registerSettingsSection(SettingsSection.FULL_BODY, sectionOffsets),
            shape = RoundedCornerShape(16.dp),
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Text(
                    text = "Whole-Body Semi-Nude Blurring",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Active automatically: instead of small fragmented body-part patches, SaveGaze blurs the entire target-gender body as soon as any semi-nude or nude cue is detected.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Uninstall Protection / Prevention Section (Off on fresh install; stays ON after updates once enabled)
        ElevatedCard(
            modifier = Modifier
                .fillMaxWidth()
                .registerSettingsSection(SettingsSection.UNINSTALL_PROTECTION, sectionOffsets),
            shape = RoundedCornerShape(16.dp),
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = tamperEnabled,
                        onValueChange = { wantOn ->
                            if (wantOn) {
                                coroutineScope.launch {
                                    preferencesManager.setTamperGuardEnabled(true)
                                    DeviceAdminHelper.requestEnable(context)
                                }
                            } else {
                                pendingChallengeTitle = "Disable Uninstall Protection"
                                pendingChallengeAction = {
                                    coroutineScope.launch {
                                        preferencesManager.grantTemporaryTamperUnlock(90_000L)
                                        preferencesManager.setTamperGuardEnabled(false)
                                        DeviceAdminHelper.disable(context)
                                    }
                                }
                            }
                        },
                        role = Role.Switch
                    )
                    .padding(20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Uninstall Protection",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (tamperEnabled)
                            "Active: blocks uninstalling SaveGaze, Force Stop, and disabling Accessibility/Admin. Persists across app updates."
                        else
                            "Off by default after fresh install. Turn ON to block casual uninstallation & tampering.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
                Switch(
                    checked = tamperEnabled,
                    onCheckedChange = null
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Detection Sensitivity Section
        ElevatedCard(
            modifier = Modifier
                .fillMaxWidth()
                .registerSettingsSection(SettingsSection.SENSITIVITY, sectionOffsets),
            shape = RoundedCornerShape(16.dp),
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Text(
                    text = "Detection Sensitivity",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "Confidence threshold for content detection",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Threshold: ${String.format("%.2f", sensitivity)}",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary
                )

                Slider(
                    value = sensitivity,
                    onValueChange = { sensitivity = it },
                    onValueChangeFinished = {
                        if (sensitivity > sensitivityPreference && !masterHash.isNullOrBlank()) {
                            val target = sensitivity
                            sensitivity = sensitivityPreference
                            pendingChallengeTitle = "Loosen Detection Sensitivity"
                            pendingChallengeAction = {
                                sensitivity = target
                                coroutineScope.launch { preferencesManager.setSensitivity(target) }
                            }
                        } else {
                            coroutineScope.launch { preferencesManager.setSensitivity(sensitivity) }
                        }
                    },
                    valueRange = 0.15f..0.45f,
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.primary
                    )
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "0.15 — Maximum Protection",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.End
                    ) {
                        Text(
                            text = "0.45 — Stricter",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Lower values detect subtle semi-nude content and swimwear. " +
                            "Higher values are stricter and only blur clear intimate content.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Blur Intensity Section
        ElevatedCard(
            modifier = Modifier
                .fillMaxWidth()
                .registerSettingsSection(SettingsSection.INTENSITY, sectionOffsets),
            shape = RoundedCornerShape(16.dp),
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Text(
                    text = "Blur Intensity",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "Adjust the strength of the blur overlay",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Radius: $blurIntensity px",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary
                )

                Slider(
                    value = blurIntensity.toFloat(),
                    onValueChange = { blurIntensity = it.roundToInt() },
                    onValueChangeFinished = {
                        coroutineScope.launch { preferencesManager.setBlurIntensity(blurIntensity) }
                    },
                    valueRange = 10f..40f,
                    steps = 29,
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.primary
                    )
                )

                Row(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Subtle",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = "Heavy",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Blur Style / Variation Section
        ElevatedCard(
            modifier = Modifier
                .fillMaxWidth()
                .registerSettingsSection(SettingsSection.STYLE, sectionOffsets),
            shape = RoundedCornerShape(16.dp),
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Text(
                    text = "Blur Style & Variation",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "Select visual style for the privacy blocks",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(12.dp))

                BlurStyleOption(
                    label = "Solid Black (HaramBlur style)",
                    description = "100% pitch black privacy blocks. Completely opaque, zero see-through.",
                    selected = blurStyle == BlurStyle.SOLID_BLACK,
                    onClick = {
                        coroutineScope.launch { preferencesManager.setBlurStyle(BlurStyle.SOLID_BLACK) }
                    }
                )

                BlurStyleOption(
                    label = "Dark Frosted Glass",
                    description = "Modern dark frosted-glass cards with subtle translucent rims.",
                    selected = blurStyle == BlurStyle.DARK_FROSTED,
                    onClick = {
                        coroutineScope.launch { preferencesManager.setBlurStyle(BlurStyle.DARK_FROSTED) }
                    }
                )

                BlurStyleOption(
                    label = "Light Censor Card",
                    description = "Clean, minimal light-colored censor cards.",
                    selected = blurStyle == BlurStyle.LIGHT_CENSOR,
                    onClick = {
                        coroutineScope.launch { preferencesManager.setBlurStyle(BlurStyle.LIGHT_CENSOR) }
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Blur Opacity & Transparency Section
        ElevatedCard(
            modifier = Modifier
                .fillMaxWidth()
                .registerSettingsSection(SettingsSection.OPACITY, sectionOffsets),
            shape = RoundedCornerShape(16.dp),
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                val displayOpacity = sliderOpacity.roundToInt()

                Text(
                    text = "Blur Opacity / Transparency",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "Adjust opacity to eliminate any see-through skin exposure",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "$displayOpacity% — ${if (displayOpacity == 100) "Completely Solid (Zero Transparency)" else "Translucent"}",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary
                )

                Slider(
                    value = sliderOpacity,
                    onValueChange = { sliderOpacity = it },
                    onValueChangeFinished = {
                        coroutineScope.launch { preferencesManager.setBlurOpacity(sliderOpacity.roundToInt()) }
                    },
                    valueRange = 50f..100f,
                    steps = 9,
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.primary
                    )
                )

                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OpacityPresetChip(
                        label = "100% (Solid)",
                        selected = displayOpacity == 100,
                        onClick = {
                            sliderOpacity = 100f
                            coroutineScope.launch { preferencesManager.setBlurOpacity(100) }
                        }
                    )
                    OpacityPresetChip(
                        label = "90%",
                        selected = displayOpacity == 90,
                        onClick = {
                            sliderOpacity = 90f
                            coroutineScope.launch { preferencesManager.setBlurOpacity(90) }
                        }
                    )
                    OpacityPresetChip(
                        label = "75%",
                        selected = displayOpacity == 75,
                        onClick = {
                            sliderOpacity = 75f
                            coroutineScope.launch { preferencesManager.setBlurOpacity(75) }
                        }
                    )
                    OpacityPresetChip(
                        label = "60%",
                        selected = displayOpacity == 60,
                        onClick = {
                            sliderOpacity = 60f
                            coroutineScope.launch { preferencesManager.setBlurOpacity(60) }
                        }
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "100% opacity ensures full protection like HaramBlur, making it impossible to see through skin or swimwear.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ── App Updates Section ─────────────────────────────────────
        ElevatedCard(
            modifier = Modifier
                .fillMaxWidth()
                .registerSettingsSection(SettingsSection.UPDATES, sectionOffsets),
            shape = RoundedCornerShape(16.dp),
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.SystemUpdate,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Check for Updates",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        val vName = updateManager.getCurrentVersionName()
                        val vCode = updateManager.getCurrentVersionCode()
                        Text(
                            text = "Installed: v$vName (Build $vCode)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Dynamic Status & Result Display
                when (val status = updateStatus) {
                    is UpdateStatus.Checking -> {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = "Checking for new versions online…",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }

                    is UpdateStatus.UpdateAvailable -> {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.SystemUpdate,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "Version v${status.info.versionName} is Available!",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = status.info.releaseNotes.take(120).ifBlank { "Performance updates and fixes available." },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                Button(
                                    onClick = { updateManager.startDownload(status.info) },
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text("Update Now (v${status.info.versionName})")
                                }
                            }
                        }
                    }

                    is UpdateStatus.UpToDate -> {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = "SaveGaze is up to date",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }

                    is UpdateStatus.Error -> {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Info,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = status.message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }
                    }

                    else -> {
                        val now = System.currentTimeMillis()
                        if (remindTimestamp > now) {
                            val remainingHours = ((remindTimestamp - now) / (3600 * 1000L)).coerceAtLeast(1L)
                            Text(
                                text = "⏱️ Reminder active: update prompt snoozed for ~$remainingHours h. You can still update anytime below.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else if (!dismissedVersion.isNullOrBlank()) {
                            Text(
                                text = "ℹ️ Prompt for v$dismissedVersion was muted with 'Don't show again'. Check manually below anytime.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            Text(
                                text = "Tap 'Check for Updates' to verify if a newer version of SaveGaze or updated AI models are available.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Prominent Action Button
                Button(
                    onClick = { updateManager.checkForUpdates(manual = true) },
                    enabled = updateStatus !is UpdateStatus.Checking && updateStatus !is UpdateStatus.Downloading,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (updateStatus is UpdateStatus.Checking) "Checking..." else "Check for Updates"
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))
                Divider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(modifier = Modifier.height(12.dp))

                // Auto-check toggle
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = autoCheckUpdates,
                            onValueChange = { enabled ->
                                coroutineScope.launch { preferencesManager.setAutoCheckUpdates(enabled) }
                            },
                            role = Role.Switch
                        )
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Auto-Check on App Launch",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "Automatically check for newer releases when opening SaveGaze",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = autoCheckUpdates,
                        onCheckedChange = { enabled ->
                            coroutineScope.launch { preferencesManager.setAutoCheckUpdates(enabled) }
                        }
                    )
                }

                // If user had set a reminder or dismissed a version, allow resetting
                if (remindTimestamp > 0L || !dismissedVersion.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(
                        onClick = {
                            updateManager.resetUpdatePreferences()
                            android.widget.Toast.makeText(context, "Update reminders and exclusions reset", android.widget.Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text("Reset Muted Updates & Reminders")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // About Section
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Text(
                    text = "About",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(12.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "SaveGaze",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium
                        )
                        val vName = updateManager.getCurrentVersionName()
                        val vCode = updateManager.getCurrentVersionCode()
                        Text(
                            text = "Version $vName (Build $vCode)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Divider(color = MaterialTheme.colorScheme.outlineVariant)

                Spacer(modifier = Modifier.height(12.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "Privacy",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "All processing happens on-device. No data is collected, stored, or transmitted.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // How It Works Section
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            role = Role.Button,
                            onClickLabel = if (howItWorksExpanded) "Collapse How It Works" else "Expand How It Works"
                        ) { howItWorksExpanded = !howItWorksExpanded }
                        .padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Build,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "How It Works",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        imageVector = if (howItWorksExpanded)
                            Icons.Default.KeyboardArrowUp
                        else
                            Icons.Default.KeyboardArrowDown,
                        contentDescription = if (howItWorksExpanded) "Collapse" else "Expand",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                AnimatedVisibility(
                    visible = howItWorksExpanded,
                    enter = expandVertically(),
                    exit = shrinkVertically()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 20.dp, end = 20.dp, bottom = 20.dp)
                    ) {
                        Divider(color = MaterialTheme.colorScheme.outlineVariant)
                        Spacer(modifier = Modifier.height(12.dp))

                        HowItWorksStep(
                            number = "1",
                            title = "Screen Capture",
                            description = "SaveGaze uses Android's accessibility service to capture screen content in real time."
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        HowItWorksStep(
                            number = "2",
                            title = "Hybrid AI Gender & Semi-Nude Detection",
                            description = "Dual on-device ONNX models (PordaV4x3 person/gender + NudeNet semi-nude) classify target genders and semi-nude cues with instant action."
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        HowItWorksStep(
                            number = "3",
                            title = "Whole-Body Blur & ContentGuard Shield",
                            description = "Semi-nude detections blur the whole body (plus faces when enabled), while the built-in DNS VPN, keyword/app blockers, 5-in-10m lockout, and 40s challenge prevent bypassing."
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        HowItWorksStep(
                            number = "4",
                            title = "Privacy First",
                            description = "Everything runs locally on your device. No images or data ever leave your phone."
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun GenderFilterOption(
    label: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.RadioButton
            )
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = null,
            colors = RadioButtonDefaults.colors(
                selectedColor = MaterialTheme.colorScheme.primary
            )
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun BlurStyleOption(
    label: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.RadioButton
            )
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = null,
            colors = RadioButtonDefaults.colors(
                selectedColor = MaterialTheme.colorScheme.primary
            )
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun OpacityPresetChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(
            1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
        ),
        modifier = Modifier
            .defaultMinSize(minHeight = 44.dp, minWidth = 44.dp)
            .clickable(role = Role.Button, onClick = onClick)
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun HowItWorksStep(
    number: String,
    title: String,
    description: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = number,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun Modifier.registerSettingsSection(
    section: SettingsSection,
    offsets: MutableMap<SettingsSection, Int>
): Modifier = onGloballyPositioned { coordinates ->
    offsets[section] = coordinates.positionInParent().y.roundToInt()
}
