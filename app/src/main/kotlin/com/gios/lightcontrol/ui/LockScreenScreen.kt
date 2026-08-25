package com.gios.lightcontrol.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.gios.lightcontrol.Prefs
import com.gios.lightcontrol.lock.LockBackground
import com.gios.lightcontrol.lock.LockNotes

/**
 * The Light-style lock face and everything it draws. Extracted whole from the old single
 * settings scroll — the behavior is unchanged, it just has its own screen and a guide now.
 */
@Composable
fun LockScreenScreen(
    onBackground: () -> Unit,
    onResumeApps: () -> Unit,
    onResumeFallback: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }

    var lockScreen by remember { mutableStateOf(prefs.lockScreen) }
    var lockFault by remember { mutableStateOf(prefs.lockFault()) }
    var lockPrompt by remember { mutableStateOf(prefs.lockPrompt) }
    var lockNotes by remember { mutableStateOf(prefs.lockNotes) }
    var lockMedia by remember { mutableStateOf(prefs.lockMedia) }
    var lockCalls by remember { mutableStateOf(prefs.lockCalls) }
    var lockHold by remember { mutableStateOf(prefs.lockHoldToEnter) }
    val notesGranted = LockNotes.granted(context)
    val hasBackground = LockBackground.has(context)
    val phoneState = context.checkSelfPermission(
        android.Manifest.permission.READ_PHONE_STATE,
    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    val answerCalls = context.checkSelfPermission(
        android.Manifest.permission.ANSWER_PHONE_CALLS,
    ) == android.content.pm.PackageManager.PERMISSION_GRANTED

    SectionScaffold(
        title = "Lock screen",
        onBack = onBack,
        guide = "A Light-style face painted over the real lock screen. The stock lock screen is " +
            "still underneath and still what unlocks the phone — your thumb on the power button " +
            "works exactly as before. This app never becomes what unlocks the phone; keep your " +
            "PIN or pattern set.",
    ) {
        MenuRow(
            label = "Light lock face",
            detail = if (lockScreen) "ON" else "OFF",
            sub = if (lockScreen) {
                "on. Tap the face to reach the keypad. Unlocking lands wherever Resume would."
            } else {
                "off. Needs a screen lock set. Turn on for a clock, notifications and your own " +
                    "picture over the stock lock screen."
            },
            onClick = {
                lockScreen = !lockScreen
                if (lockScreen) prefs.armLock() else prefs.lockScreen = false
                lockFault = prefs.lockFault()
            },
        )
        lockFault?.let { reason ->
            MenuRow(
                label = "Lock face switched itself off",
                detail = "RE-ARM",
                sub = "$reason. Tap to turn it back on.",
                dim = true,
                onClick = {
                    prefs.armLock()
                    lockScreen = true
                    lockFault = null
                },
            )
        }
        if (lockScreen) {
            MenuRow(
                label = "Background",
                detail = if (hasBackground) "SET" else "NONE",
                sub = if (hasBackground) {
                    "tap to re-edit — the photo, how it meets the panel, and the filter stack"
                } else {
                    "a photo, dithered or faded to taste. The same editor as BrightChat's chat " +
                        "wallpapers, and the same filters."
                },
                onClick = onBackground,
            )
            MenuRow(
                label = "Prompt",
                detail = if (lockPrompt) "ON" else "OFF",
                sub = if (lockPrompt) {
                    "\"press the power button\" and \"or tap for the keypad\", under the clock"
                } else {
                    "hidden. The clock, the notifications and nothing else."
                },
                onClick = {
                    lockPrompt = !lockPrompt
                    prefs.lockPrompt = lockPrompt
                },
            )
            MenuRow(
                label = "Hold to enter",
                detail = if (lockHold) "ON" else "OFF",
                sub = if (lockHold) {
                    "unlocking holds the face open so you can read it. Press and hold anywhere " +
                        "for a second to go in; swipe up for the keypad."
                } else {
                    "unlocking opens your app straight away, the instant the sensor reads you."
                },
                onClick = {
                    lockHold = !lockHold
                    prefs.lockHoldToEnter = lockHold
                },
            )
            val chosen = prefs.resumeApps().size
            MenuRow(
                label = "Unlocks into",
                detail = if (chosen == 0) "NONE" else "$chosen",
                sub = if (chosen == 0) {
                    "nothing chosen, so every unlock goes to the destination below"
                } else {
                    "sleep in one of these and unlocking brings it back, once"
                },
                onClick = onResumeApps,
            )
            MenuRow(
                label = "Otherwise open",
                detail = shortLabel(prefs.resumeFallback),
                sub = (longLabel(context.packageManager, prefs.resumeFallback) ?: "home") +
                    " — where an unlock goes when there is nothing to bring back. Point it at " +
                    "Luma to land there instead of LightOS.",
                onClick = onResumeFallback,
            )
            GrantRow(
                label = "Signal bars",
                ok = phoneState,
                fix = "adb shell pm grant com.gios.lightcontrol android.permission.READ_PHONE_STATE",
                sub = "cellular strength. Without this the bars are drawn empty rather than at zero",
            )
            MenuRow(
                label = "Notifications",
                detail = when {
                    !lockNotes -> "OFF"
                    notesGranted -> "ON"
                    else -> "NO GRANT"
                },
                dim = lockNotes && !notesGranted,
                sub = if (lockNotes && !notesGranted) {
                    "adb shell cmd notification allow_listener " +
                        "com.gios.lightcontrol/.lock.LockNotifications"
                } else {
                    "the shade, on the lock face. Nothing is stored and nothing leaves the phone."
                },
                onClick = {
                    lockNotes = !lockNotes
                    prefs.lockNotes = lockNotes
                },
            )
            MenuRow(
                label = "Calls",
                detail = when {
                    !lockCalls -> "STANDS ASIDE"
                    notesGranted -> "ON"
                    else -> "NO GRANT"
                },
                dim = lockCalls && !notesGranted,
                sub = when {
                    lockCalls && !notesGranted ->
                        "same grant as Notifications: adb shell cmd notification allow_listener " +
                            "com.gios.lightcontrol/.lock.LockNotifications"
                    lockCalls ->
                        "a ringing call gets a card here, with answer and decline. Answer it and " +
                            "the face steps aside for LightOS's own in-call screen."
                    else ->
                        "off. The face hides for the whole call instead, so the stock " +
                            "incoming-call screen is what you see."
                },
                onClick = {
                    lockCalls = !lockCalls
                    prefs.lockCalls = lockCalls
                },
            )
            GrantRow(
                label = "Answer without the shade buttons",
                ok = answerCalls,
                fix = "adb shell pm grant com.gios.lightcontrol " +
                    "android.permission.ANSWER_PHONE_CALLS",
                sub = "only the fallback. The card presses the dialer's own buttons first, and " +
                    "those need nothing granted",
            )
            MenuRow(
                label = "Now playing",
                detail = when {
                    !lockMedia -> "OFF"
                    notesGranted -> "ON"
                    else -> "NO GRANT"
                },
                dim = lockMedia && !notesGranted,
                sub = if (lockMedia && !notesGranted) {
                    "same grant as Notifications: adb shell cmd notification allow_listener " +
                        "com.gios.lightcontrol/.lock.LockNotifications"
                } else {
                    "cover, track and skip controls on the face, for whatever is playing. " +
                        "LightOS draws these for its own player only."
                },
                onClick = {
                    lockMedia = !lockMedia
                    prefs.lockMedia = lockMedia
                },
            )
        }
    }
}
