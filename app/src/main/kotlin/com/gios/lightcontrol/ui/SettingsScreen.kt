package com.gios.lightcontrol.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.gios.lightcontrol.Button
import com.gios.lightcontrol.Gesture
import com.gios.lightcontrol.Prefs
import com.gios.lightcontrol.TurnAction
import com.gios.lightcontrol.keys.Brightness
import com.gios.lightcontrol.keys.Grants
import com.gios.lightcontrol.keys.LightKeys
import com.gios.lightcontrol.ui.theme.Dim

/**
 * One screen: what's granted, then what the controls do.
 *
 * The grant block is first because it is the only thing that can make the app do nothing at
 * all, and each missing grant shows the exact adb line rather than a vague "not enabled" —
 * LightOS has no Settings screens for any of these.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onButtons: () -> Unit, onPerApp: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    val brightness = remember { Brightness(context) }

    // Read on each composition; a grant only changes while the app is in the background, so
    // recomposing on resume is soon enough.
    val serviceOn = Grants.serviceEnabled(context)
    val canWrite = Grants.canWriteSettings(context)
    val canOverlay = Grants.canDrawOverlays(context)

    var turn by remember { mutableStateOf(prefs.unknownAppTurn) }
    var doubleTap by remember { mutableStateOf(prefs.doubleTapSwitchesTurn) }
    var lightOs by remember { mutableStateOf(prefs.lightOsScreens) }
    var steps by remember { mutableIntStateOf(prefs.brightnessSteps) }
    var swipeDp by remember { mutableIntStateOf(prefs.swipeDp) }
    var readout by remember { mutableStateOf(prefs.showReadout) }

    val scroll = rememberScrollState()
    WheelScroll(scroll)

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                colors = barColors(),
                title = { Text("Controls", style = MaterialTheme.typography.titleMedium) },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(scroll)) {

            SectionLabel("SETUP")
            GrantRow(
                label = "Key service",
                ok = serviceOn,
                fix = "adb shell settings put secure enabled_accessibility_services " +
                    "com.gios.lightcontrol/com.gios.lightcontrol.keys.ControlService",
            )
            GrantRow(
                label = "Brightness",
                ok = canWrite,
                fix = "adb shell appops set com.gios.lightcontrol WRITE_SETTINGS allow",
            )
            GrantRow(
                label = "Overlay",
                ok = canOverlay,
                sub = "the level readout, and opening an app from the service",
                fix = "adb shell appops set com.gios.lightcontrol SYSTEM_ALERT_WINDOW allow",
            )
            if (!LightKeys.wheelLabelsPresent()) {
                MenuRow(
                    label = "Wheel keycodes",
                    detail = "SCANCODE",
                    sub = "This build doesn't publish WHEEL_CW; falling back to raw " +
                        "scancodes, which still works.",
                    dim = true,
                )
            }
            Rule()

            SectionLabel("BUTTONS")
            MenuRow(
                label = "Tap and hold",
                detail = "${shortLabel(prefs.action(Button.WheelClick, Gesture.Tap))} · " +
                    shortLabel(prefs.action(Button.Camera, Gesture.Tap)),
                sub = "wheel click and camera button, tap and hold separately",
                onClick = onButtons,
            )
            MenuRow(
                label = "LightOS screens",
                detail = if (lightOs) "BUTTONS" else "OFF",
                sub = "the lock screen and the dashboard, which are one activity. Buttons only " +
                    "— turning stays LightOS's, which is what it already does well.",
                onClick = {
                    lightOs = !lightOs
                    prefs.lightOsScreens = lightOs
                },
            )
            MenuRow(
                label = "Double tap the wheel",
                detail = if (doubleTap) "SWITCH TURN" else "OFF",
                sub = "flips turning between brightness and scrolling, and says which. " +
                    "Replaces hold-and-turn, which needed two motions at once.",
                onClick = {
                    doubleTap = !doubleTap
                    prefs.doubleTapSwitchesTurn = doubleTap
                },
            )
            Rule()

            SectionLabel("TURNING THE WHEEL")
            MenuRow(
                label = "A turn means",
                detail = turn.label,
                sub = when (turn) {
                    TurnAction.Brightness -> "everywhere, including apps that scroll themselves"
                    TurnAction.Swipe -> "a synthetic finger-drag, so lists scroll"
                    TurnAction.PassThrough -> "apps that understand the wheel scroll; others do nothing"
                },
                onClick = {
                    turn = when (turn) {
                        TurnAction.Brightness -> TurnAction.Swipe
                        TurnAction.Swipe -> TurnAction.PassThrough
                        TurnAction.PassThrough -> TurnAction.Brightness
                    }
                    prefs.unknownAppTurn = turn
                },
            )
            MenuRow(
                label = "Per-app",
                detail = "${prefs.overrides().size}",
                sub = "override any single app. Light's own tools are never touched.",
                onClick = onPerApp,
            )
            MenuRow(
                label = "Swipe distance",
                detail = "$swipeDp dp",
                sub = "how far one notch drags, where turning scrolls by swipe",
                onClick = {
                    swipeDp = when (swipeDp) {
                        48 -> 64
                        64 -> 96
                        96 -> 128
                        else -> 48
                    }
                    prefs.swipeDp = swipeDp
                },
            )
            Rule()

            SectionLabel("BRIGHTNESS")
            MenuRow(
                label = "Notches end to end",
                detail = "$steps",
                sub = "how far one turn takes you",
                onClick = {
                    steps = when (steps) {
                        12 -> 24
                        24 -> 48
                        else -> 12
                    }
                    prefs.brightnessSteps = steps
                },
            )
            MenuRow(
                label = "Show the level",
                detail = if (readout) "ON" else "OFF",
                sub = if (canOverlay) null else "needs the overlay grant above",
                dim = !canOverlay,
                onClick = {
                    readout = !readout
                    prefs.showReadout = readout
                },
            )
            MenuRow(
                label = "Scale",
                detail = "${brightness.percent() ?: 0}% of ${brightness.max()}",
                sub = "worked out from the platform's own two copies of the value",
                dim = true,
            )
            Gap(28)
            Text(
                "The wheel and camera button are ordinary key events on this phone — Light " +
                    "patched Generic.kl to label them. Nothing here reads the screen; the " +
                    "service is told which app is in front and nothing else.",
                style = MaterialTheme.typography.bodyMedium,
                color = Dim,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Gap(28)
        }
    }
}

@Composable
private fun GrantRow(label: String, ok: Boolean, fix: String, sub: String? = null) {
    var showing by remember { mutableStateOf(false) }
    MenuRow(
        label = label,
        detail = if (ok) "ON" else "OFF",
        sub = when {
            showing -> fix
            ok -> sub
            else -> sub ?: "tap for the adb line"
        },
        dim = !ok,
        onClick = { showing = !showing },
    )
}

@Composable
fun SectionLabel(text: String) {
    Gap(18)
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = Dim,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
    Gap(6)
}
