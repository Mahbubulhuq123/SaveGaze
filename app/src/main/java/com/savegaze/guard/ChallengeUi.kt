package com.savegaze.guard

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay

/**
 * BlockP-grade protection challenge modal:
 * 1. Verifies Master Password (when configured).
 * 2. Requires typing a random 40-second Canvas-rendered commitment sentence character-for-character
 *    with copy/paste blocked.
 */
@Composable
fun BlockPChallengeDialog(
    actionTitle: String,
    storedPasswordHash: String?,
    requireSentenceChallenge: Boolean = true,
    onSuccess: () -> Unit,
    onDismiss: () -> Unit
) {
    val hasPassword = !storedPasswordHash.isNullOrBlank()
    var step by remember { mutableIntStateOf(if (hasPassword) 0 else 1) }
    var passwordInput by remember { mutableStateOf("") }
    var passwordError by remember { mutableStateOf<String?>(null) }

    val targetSentence = remember { ContentGuardLists.COMMITMENT_SENTENCES.random() }
    var typedSentence by remember { mutableStateOf("") }
    var pasteNotice by remember { mutableStateOf<String?>(null) }
    var remainingSeconds by remember { mutableIntStateOf(ContentGuardRules.CHALLENGE_SECONDS) }
    val clipboard = LocalClipboardManager.current

    if (step == 1 && requireSentenceChallenge) {
        LaunchedEffect(step) {
            // Clear clipboard to discourage pasting the commitment sentence
            try {
                clipboard.setText(AnnotatedString(""))
            } catch (_: Exception) {
            }
            while (remainingSeconds > 0) {
                delay(1_000L)
                remainingSeconds -= 1
            }
            onDismiss()
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = false)
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = Color(0xFF0F172A),
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Text(
                    text = if (step == 0) "Master Password Required" else "40s Commitment Challenge",
                    color = Color(0xFFF43F5E),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = if (step == 0) {
                        "Enter your Master Password to authorize: $actionTitle"
                    } else {
                        "Type the entire sentence shown in the image box below before 40s expires to authorize: $actionTitle. Copy/paste is blocked."
                    },
                    color = Color(0xFFCBD5E1),
                    style = MaterialTheme.typography.bodySmall
                )

                Spacer(modifier = Modifier.height(14.dp))

                if (step == 0) {
                    OutlinedTextField(
                        value = passwordInput,
                        onValueChange = {
                            passwordInput = it
                            passwordError = null
                        },
                        label = { Text("Master Password") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (passwordError != null) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = passwordError!!,
                            color = Color(0xFFF43F5E),
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = onDismiss) {
                            Text("Cancel", color = Color(0xFF94A3B8))
                        }
                        Button(
                            onClick = {
                                if (ContentGuardRules.verifyPassword(passwordInput, storedPasswordHash)) {
                                    if (requireSentenceChallenge) {
                                        step = 1
                                    } else {
                                        onSuccess()
                                    }
                                } else {
                                    passwordError = "Incorrect password."
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE11D48))
                        ) {
                            Text(if (requireSentenceChallenge) "Next" else "Unlock")
                        }
                    }
                } else {
                    // Countdown header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Time Remaining: 0:%02d".format(remainingSeconds),
                            color = if (remainingSeconds <= 10) Color(0xFFEF4444) else Color(0xFFFACC15),
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { remainingSeconds / ContentGuardRules.CHALLENGE_SECONDS.toFloat() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp),
                        color = if (remainingSeconds <= 10) Color(0xFFEF4444) else Color(0xFFFACC15)
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Non-selectable Canvas sentence box (blocks text selection & accessibility scraping)
                    CanvasSentenceBox(sentence = targetSentence)

                    Spacer(modifier = Modifier.height(10.dp))

                    val matchPrefixLen = run {
                        var m = 0
                        val n = minOf(typedSentence.length, targetSentence.length)
                        while (m < n && typedSentence[m] == targetSentence[m]) m++
                        m
                    }
                    val perfectMatch = typedSentence == targetSentence

                    OutlinedTextField(
                        value = typedSentence,
                        onValueChange = { incoming ->
                            // Reject bulk paste while allowing mobile keyboard single-word autocomplete
                            val delta = incoming.length - typedSentence.length
                            if (delta > 15) {
                                pasteNotice = "Paste is strictly blocked! Type manually."
                            } else {
                                pasteNotice = null
                                typedSentence = incoming
                            }
                        },
                        label = { Text("Type exact sentence above") },
                        minLines = 2,
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = when {
                            pasteNotice != null -> pasteNotice!!
                            perfectMatch -> "Sentence matched perfectly!"
                            else -> "$matchPrefixLen / ${targetSentence.length} characters matched"
                        },
                        color = when {
                            pasteNotice != null -> Color(0xFFF43F5E)
                            perfectMatch -> Color(0xFF4ADE80)
                            matchPrefixLen == typedSentence.length -> Color(0xFF94A3B8)
                            else -> Color(0xFFF43F5E)
                        },
                        style = MaterialTheme.typography.labelMedium
                    )

                    Spacer(modifier = Modifier.height(14.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = onDismiss) {
                            Text("Cancel", color = Color(0xFF94A3B8))
                        }
                        Button(
                            onClick = { if (perfectMatch) onSuccess() },
                            enabled = perfectMatch && remainingSeconds > 0,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE11D48))
                        ) {
                            Text("Verify & Proceed")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CanvasSentenceBox(sentence: String) {
    val textMeasurer = rememberTextMeasurer()
    val style = remember {
        TextStyle(
            color = Color(0xFFF8FAFC),
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            lineHeight = 19.sp
        )
    }

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(112.dp)
            .background(Color(0xFF1E293B), RoundedCornerShape(12.dp))
            .border(1.dp, Color(0xFF334155), RoundedCornerShape(12.dp))
            .clearAndSetSemantics {}
    ) {
        val gridColor = Color(0xFF283246)
        val step = 22.dp.toPx()
        var x = 0f
        while (x < size.width) {
            drawLine(gridColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.5f)
            x += step
        }
        var y = 0f
        while (y < size.height) {
            drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.5f)
            y += step
        }

        val pad = 12.dp.toPx()
        val maxW = (size.width - pad * 2).toInt().coerceAtLeast(100)
        val layout = textMeasurer.measure(
            text = AnnotatedString(sentence),
            style = style,
            constraints = Constraints(maxWidth = maxW)
        )
        val topY = ((size.height - layout.size.height) / 2f).coerceAtLeast(pad / 2f)
        drawText(layout, topLeft = Offset(pad, topY))
    }
}

object DeviceAdminHelper {
    fun isActive(context: Context): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager ?: return false
        return dpm.isAdminActive(SaveGazeDeviceAdminReceiver.componentName(context))
    }

    fun requestEnable(context: Context) {
        if (isActive(context)) return
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, SaveGazeDeviceAdminReceiver.componentName(context))
            putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Prevents bypassing SaveGaze protection by uninstalling the app."
            )
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun disable(context: Context) {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager ?: return
        val comp = SaveGazeDeviceAdminReceiver.componentName(context)
        if (dpm.isAdminActive(comp)) {
            dpm.removeActiveAdmin(comp)
        }
    }
}

