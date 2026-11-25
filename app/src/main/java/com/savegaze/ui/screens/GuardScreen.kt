package com.savegaze.ui.screens

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.savegaze.data.PreferencesManager
import com.savegaze.guard.BlockPChallengeDialog
import com.savegaze.guard.ContentGuardLists
import com.savegaze.guard.ContentGuardRules
import com.savegaze.guard.DeviceAdminHelper
import com.savegaze.guard.SaveGazeVpnService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class InstalledAppItem(
    val label: String,
    val packageName: String
)

@Composable
fun GuardScreen(
    preferencesManager: PreferencesManager,
    onRequestVpnStart: () -> Unit,
    onStopVpn: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    val websiteEnabled by preferencesManager.websiteBlockerEnabledFlow.collectAsState(initial = true)
    val keywordEnabled by preferencesManager.keywordBlockerEnabledFlow.collectAsState(initial = true)
    val appEnabled by preferencesManager.appBlockerEnabledFlow.collectAsState(initial = true)
    val tamperEnabled by preferencesManager.tamperGuardEnabledFlow.collectAsState(initial = false)
    val lockoutEnabled by preferencesManager.crossBlockLockoutEnabledFlow.collectAsState(initial = true)
    val socialBlocked by preferencesManager.blockSocialDistractionsFlow.collectAsState(initial = false)
    val customKeywords by preferencesManager.customKeywordsFlow.collectAsState(initial = emptySet())
    val customWebsites by preferencesManager.customWebsitesFlow.collectAsState(initial = emptySet())
    val blockedApps by preferencesManager.blockedAppsFlow.collectAsState(initial = emptySet())
    val masterHash by preferencesManager.masterPasswordHashFlow.collectAsState(initial = null)
    val recentBlocks by preferencesManager.recentBlockCountFlow.collectAsState(initial = 0)
    val totalBlocks by preferencesManager.totalBlockedCountFlow.collectAsState(initial = 0)
    val vpnRunning by SaveGazeVpnService.isRunningFlow.collectAsState()

    var adminActive by remember { mutableStateOf(DeviceAdminHelper.isActive(context)) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                adminActive = DeviceAdminHelper.isActive(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Pending challenge action
    var challengeTitle by remember { mutableStateOf<String?>(null) }
    var challengeAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    fun requireChallenge(title: String, action: () -> Unit) {
        challengeTitle = title
        challengeAction = action
    }

    challengeTitle?.let { title ->
        BlockPChallengeDialog(
            actionTitle = title,
            storedPasswordHash = masterHash,
            onSuccess = {
                challengeTitle = null
                val act = challengeAction
                challengeAction = null
                act?.invoke()
            },
            onDismiss = {
                challengeTitle = null
                challengeAction = null
            }
        )
    }

    var newKeyword by remember { mutableStateOf("") }
    var newWebsite by remember { mutableStateOf("") }
    var showAppPicker by remember { mutableStateOf(false) }
    var newPassword by remember { mutableStateOf("") }

    if (showAppPicker) {
        AppPickerDialog(
            currentlyBlocked = blockedApps,
            onToggleApp = { pkg, currentlyAdded ->
                if (currentlyAdded) {
                    showAppPicker = false
                    requireChallenge("Unblock App ($pkg)") {
                        scope.launch { preferencesManager.removeBlockedApp(pkg) }
                    }
                } else {
                    scope.launch { preferencesManager.addBlockedApp(pkg) }
                }
            },
            onDismiss = { showAppPicker = false }
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        Text(
            text = "ContentGuard Shield",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "DNS firewall, keyword & app blocker, 5-in-10m auto-lockout, and tamper defense",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Summary Stats & VPN Status
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (vpnRunning) "DNS Website Firewall Active" else "DNS Website Firewall Off",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = if (vpnRunning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                        )
                        Text(
                            text = "Recent blocks: $recentBlocks / ${ContentGuardRules.CROSS_BLOCK_LIMIT} (10m) · Total blocked: $totalBlocks",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Button(
                        onClick = {
                            if (vpnRunning) {
                                requireChallenge("Stop DNS Firewall") { onStopVpn() }
                            } else {
                                onRequestVpnStart()
                            }
                        },
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.VpnKey, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (vpnRunning) "Stop" else "Start")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Core Guard Toggles
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Text("Protection Modules", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(8.dp))

                GuardToggleRow(
                    title = "Website & Domain Blocker",
                    subtitle = "Blocks ${ContentGuardLists.DEFAULT_ADULT_DOMAINS.size}+ adult domains & custom sites via DNS + URL bar",
                    checked = websiteEnabled,
                    onCheckedChange = { wantOn ->
                        if (!wantOn) {
                            requireChallenge("Disable Website Blocker") {
                                scope.launch { preferencesManager.setWebsiteBlockerEnabled(false) }
                            }
                        } else {
                            scope.launch { preferencesManager.setWebsiteBlockerEnabled(true) }
                        }
                    }
                )
                Divider(color = MaterialTheme.colorScheme.outlineVariant)

                GuardToggleRow(
                    title = "Keyword Blocker (URL & Screen)",
                    subtitle = "Blocks ${ContentGuardLists.DEFAULT_KEYWORDS.size}+ adult keywords & custom words in URLs/pages",
                    checked = keywordEnabled,
                    onCheckedChange = { wantOn ->
                        if (!wantOn) {
                            requireChallenge("Disable Keyword Blocker") {
                                scope.launch { preferencesManager.setKeywordBlockerEnabled(false) }
                            }
                        } else {
                            scope.launch { preferencesManager.setKeywordBlockerEnabled(true) }
                        }
                    }
                )
                Divider(color = MaterialTheme.colorScheme.outlineVariant)

                GuardToggleRow(
                    title = "App Blocker",
                    subtitle = "Blocks selected distracting apps instantly on launch (${blockedApps.size} selected)",
                    checked = appEnabled,
                    onCheckedChange = { wantOn ->
                        if (!wantOn) {
                            requireChallenge("Disable App Blocker") {
                                scope.launch { preferencesManager.setAppBlockerEnabled(false) }
                            }
                        } else {
                            scope.launch { preferencesManager.setAppBlockerEnabled(true) }
                        }
                    }
                )
                Divider(color = MaterialTheme.colorScheme.outlineVariant)

                GuardToggleRow(
                    title = "5-in-10m Cross-Block Auto-Lockout",
                    subtitle = "Locks screen for 5 minutes after 5 blocked attempts within 10 minutes",
                    checked = lockoutEnabled,
                    onCheckedChange = { wantOn ->
                        if (!wantOn) {
                            requireChallenge("Disable Auto-Lockout") {
                                scope.launch { preferencesManager.setCrossBlockLockoutEnabled(false) }
                            }
                        } else {
                            scope.launch { preferencesManager.setCrossBlockLockoutEnabled(true) }
                        }
                    }
                )
                Divider(color = MaterialTheme.colorScheme.outlineVariant)

                GuardToggleRow(
                    title = "Block Social Media Sites",
                    subtitle = "Also block Instagram, TikTok, Facebook, Reddit, X, Telegram domains",
                    checked = socialBlocked,
                    onCheckedChange = { wantOn ->
                        if (!wantOn) {
                            requireChallenge("Unblock Social Sites") {
                                scope.launch { preferencesManager.setBlockSocialDistractions(false) }
                            }
                        } else {
                            scope.launch { preferencesManager.setBlockSocialDistractions(true) }
                        }
                    }
                )
                Divider(color = MaterialTheme.colorScheme.outlineVariant)

                GuardToggleRow(
                    title = "Uninstall & Settings Tamper Shield",
                    subtitle = "Off on fresh install. When ON, blocks uninstall, Force Stop & disabling Accessibility/Admin (persists across updates)",
                    checked = tamperEnabled,
                    onCheckedChange = { wantOn ->
                        if (!wantOn) {
                            requireChallenge("Disable Uninstall & Tamper Shield") {
                                scope.launch {
                                    preferencesManager.grantTemporaryTamperUnlock(90_000L)
                                    preferencesManager.setTamperGuardEnabled(false)
                                    DeviceAdminHelper.disable(context)
                                    adminActive = DeviceAdminHelper.isActive(context)
                                }
                            }
                        } else {
                            scope.launch {
                                preferencesManager.setTamperGuardEnabled(true)
                                DeviceAdminHelper.requestEnable(context)
                            }
                        }
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Device Admin & Settings Access
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.AdminPanelSettings, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Uninstall Defense & Master Password", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = if (adminActive) "Device Admin is ACTIVE (uninstallation blocked until deactivated)." else "Activate Device Admin so SaveGaze cannot be casually uninstalled.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!adminActive) {
                        Button(
                            onClick = { DeviceAdminHelper.requestEnable(context) }
                        ) {
                            Icon(Icons.Default.Shield, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Enable Admin Lock")
                        }
                    } else {
                        OutlinedButton(
                            onClick = {
                                requireChallenge("Deactivate Device Admin") {
                                    scope.launch {
                                        preferencesManager.grantTemporaryTamperUnlock(90_000L)
                                        preferencesManager.setTamperGuardEnabled(false)
                                        DeviceAdminHelper.disable(context)
                                        adminActive = DeviceAdminHelper.isActive(context)
                                        Toast.makeText(context, "Device Admin removed & Settings unlocked for 90s", Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                        ) {
                            Text("Deactivate Admin")
                        }
                    }

                    OutlinedButton(
                        onClick = {
                            requireChallenge("Unlock Android Settings (60s)") {
                                scope.launch {
                                    preferencesManager.grantTemporaryTamperUnlock(60_000L)
                                    Toast.makeText(context, "Settings unlocked for 60 seconds", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    ) {
                        Text("Unlock Settings")
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))
                Divider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = if (masterHash.isNullOrBlank()) "Set Master Password (Optional)" else "Master Password is Set",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newPassword,
                        onValueChange = { newPassword = it },
                        label = { Text("New Password") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        enabled = newPassword.isNotBlank(),
                        onClick = {
                            val pw = newPassword.trim()
                            if (masterHash.isNullOrBlank()) {
                                scope.launch {
                                    preferencesManager.setMasterPassword(pw)
                                    newPassword = ""
                                    Toast.makeText(context, "Master Password saved", Toast.LENGTH_SHORT).show()
                                }
                            } else {
                                requireChallenge("Change Master Password") {
                                    scope.launch {
                                        preferencesManager.setMasterPassword(pw)
                                        newPassword = ""
                                        Toast.makeText(context, "Master Password updated", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        }
                    ) {
                        Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Save")
                    }
                }
                if (!masterHash.isNullOrBlank()) {
                    TextButton(
                        onClick = {
                            requireChallenge("Remove Master Password") {
                                scope.launch {
                                    preferencesManager.setMasterPassword("")
                                    Toast.makeText(context, "Master Password cleared", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    ) {
                        Text("Remove Master Password")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Custom Keywords
        CustomListCard(
            title = "Custom Blocked Keywords (${customKeywords.size})",
            placeholder = "e.g. adult keyword",
            inputValue = newKeyword,
            onInputChange = { newKeyword = it },
            items = customKeywords.sorted(),
            onAdd = {
                val clean = newKeyword.trim()
                if (clean.isNotEmpty()) {
                    scope.launch { preferencesManager.addCustomKeyword(clean) }
                    newKeyword = ""
                }
            },
            onRemove = { item ->
                requireChallenge("Remove Keyword ($item)") {
                    scope.launch { preferencesManager.removeCustomKeyword(item) }
                }
            }
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Custom Websites
        CustomListCard(
            title = "Custom Blocked Websites (${customWebsites.size})",
            placeholder = "e.g. example.com",
            inputValue = newWebsite,
            onInputChange = { newWebsite = it },
            items = customWebsites.sorted(),
            onAdd = {
                val clean = newWebsite.trim()
                if (clean.isNotEmpty()) {
                    scope.launch { preferencesManager.addCustomWebsite(clean) }
                    newWebsite = ""
                }
            },
            onRemove = { item ->
                requireChallenge("Unblock Website ($item)") {
                    scope.launch { preferencesManager.removeCustomWebsite(item) }
                }
            }
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Blocked Apps Card
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Blocked Apps (${blockedApps.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Button(onClick = { showAppPicker = true }) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Pick Apps")
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                if (blockedApps.isEmpty()) {
                    Text("No apps blocked yet. Tap 'Pick Apps' to block any installed app.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    @OptIn(ExperimentalLayoutApi::class)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        blockedApps.sorted().forEach { pkg ->
                            AssistChip(
                                onClick = {
                                    requireChallenge("Unblock App ($pkg)") {
                                        scope.launch { preferencesManager.removeBlockedApp(pkg) }
                                    }
                                },
                                label = { Text(pkg) },
                                trailingIcon = { Icon(Icons.Default.Close, contentDescription = "Remove", modifier = Modifier.size(14.dp)) }
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun GuardToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun CustomListCard(
    title: String,
    placeholder: String,
    inputValue: String,
    onInputChange: (String) -> Unit,
    items: List<String>,
    onAdd: () -> Unit,
    onRemove: (String) -> Unit
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = inputValue,
                    onValueChange = onInputChange,
                    label = { Text(placeholder) },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Button(onClick = onAdd, enabled = inputValue.isNotBlank()) {
                    Text("Add")
                }
            }
            if (items.isNotEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items.forEach { item ->
                        AssistChip(
                            onClick = { onRemove(item) },
                            label = { Text(item) },
                            trailingIcon = { Icon(Icons.Default.Close, contentDescription = "Remove", modifier = Modifier.size(14.dp)) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AppPickerDialog(
    currentlyBlocked: Set<String>,
    onToggleApp: (String, Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var apps by remember { mutableStateOf<List<InstalledAppItem>>(emptyList()) }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            pm.getInstalledApplications(0)
                .filter { it.packageName != context.packageName && (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 || it.packageName in ContentGuardLists.BROWSER_PACKAGES }
                .map {
                    InstalledAppItem(
                        label = pm.getApplicationLabel(it).toString(),
                        packageName = it.packageName
                    )
                }
                .distinctBy { it.packageName }
                .sortedBy { it.label.lowercase() }
        }
    }

    val filtered = remember(apps, query) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) apps
        else apps.filter { it.label.lowercase().contains(q) || it.packageName.lowercase().contains(q) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Apps to Block") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search installed apps") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                    items(filtered, key = { it.packageName }) { app ->
                        val isBlocked = app.packageName in currentlyBlocked
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(app.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                Text(app.packageName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(
                                checked = isBlocked,
                                onCheckedChange = { onToggleApp(app.packageName, isBlocked) }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        }
    )
}
