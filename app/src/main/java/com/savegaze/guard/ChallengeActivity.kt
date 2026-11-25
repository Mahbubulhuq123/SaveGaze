package com.savegaze.guard

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.savegaze.data.PreferencesManager
import com.savegaze.service.SaveGazeAccessibilityService
import com.savegaze.ui.theme.SaveGazeTheme
import kotlinx.coroutines.launch

/**
 * Modal challenge activity launched when the user clicks "Unlock Early" from the 5-minute
 * Auto-Lockout screen or attempts to authorize temporary Settings / Device Admin access.
 */
class ChallengeActivity : ComponentActivity() {

    companion object {
        const val EXTRA_MODE = "challenge_mode"
        const val EXTRA_TITLE = "challenge_title"
        const val MODE_UNLOCK_LOCKOUT = "unlock_lockout"
        const val MODE_TAMPER_UNLOCK = "tamper_unlock"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_UNLOCK_LOCKOUT
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Authorize Protection Change"
        val prefs = PreferencesManager(applicationContext)

        setContent {
            SaveGazeTheme {
                val scope = rememberCoroutineScope()
                var storedHash by remember { mutableStateOf<String?>(null) }
                var loaded by remember { mutableStateOf(false) }

                LaunchedEffect(Unit) {
                    storedHash = prefs.getMasterPasswordHash()
                    loaded = true
                }

                if (loaded) {
                    BlockPChallengeDialog(
                        actionTitle = title,
                        storedPasswordHash = storedHash,
                        requireSentenceChallenge = mode != MODE_UNLOCK_LOCKOUT,
                        onSuccess = {
                            scope.launch {
                                if (mode == MODE_UNLOCK_LOCKOUT) {
                                    prefs.clearLockout()
                                    SaveGazeAccessibilityService.clearLockoutUi()
                                } else {
                                    prefs.grantTemporaryTamperUnlock(60_000L)
                                }
                                finish()
                            }
                        },
                        onDismiss = { finish() }
                    )
                }
            }
        }
    }
}
