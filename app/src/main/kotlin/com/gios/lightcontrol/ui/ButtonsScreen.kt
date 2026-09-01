package com.gios.lightcontrol.ui

import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.gios.lightcontrol.Action
import com.gios.lightcontrol.Button
import com.gios.lightcontrol.Gesture
import com.gios.lightcontrol.Prefs
import com.gios.lightcontrol.ui.theme.Dim

/**
 * Every button, with its tap and its hold.
 *
 * Tap and hold are listed as separate rows rather than hidden behind one "configure" screen,
 * because the pairing is the whole feature — the point is seeing at a glance that tapping the
 * wheel is the flashlight while holding it opens something else.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ButtonsScreen(
    onPick: (Button, Gesture) -> Unit,
    onResumeApps: () -> Unit,
    onSwitcher: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }

    var homeArmed by remember { mutableStateOf(prefs.homeTakeover) }
    // Resume's app list belongs wherever that action is reachable from — and that is a binding
    // rather than a switch of its own. Read on every composition rather than remembered, because
    // the thing it depends on is changed on another screen: this one is recomposed on the way back
    // from the picker, and a remembered answer would be the one from before the change.
    //
    // The switcher's own settings are no longer here at all. They were, back when a double press
    // of home was the only way to open that window; it is an ordinary binding on all five buttons
    // and both edges now, so they have a screen of their own and this is a door to it.
    val switcherBound =
        Button.entries.any { b -> Gesture.entries.any { prefs.action(b, it) == Action.Switcher } }
    val resumeBound =
        Button.entries.any { b -> Gesture.entries.any { prefs.action(b, it) == Action.Resume } }
    var cameraLightOs by remember { mutableStateOf(prefs.cameraOnLightOs) }

    val scroll = rememberScrollState()
    WheelScroll(scroll)

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                colors = barColors(),
                title = { Text("Buttons", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(scroll)) {
            Button.entries.forEach { button ->
                SectionLabel(button.label.uppercase())
                val double = prefs.action(button, Gesture.DoubleTap)
                Gesture.entries.filter { prefs.bindable(button, it) }.forEach { gesture ->
                    val action = prefs.action(button, gesture)
                    MenuRow(
                        label = gesture.label,
                        detail = shortLabel(action),
                        sub = longLabel(context.packageManager, action),
                        onClick = { onPick(button, gesture) },
                    )
                }
                // Said rather than silently absent: two rows that used to be here are gone, and a
                // setting that vanishes without explanation is its own small bug report.
                if (!prefs.bindable(button, Gesture.Hold)) {
                    MenuRow(
                        label = "Hold and double tap",
                        detail = "\u2014",
                        sub = "not offered on the volume keys. Timing either one means keeping the " +
                            "press until it is over, and on these keys that is the volume not " +
                            "changing while the phone waits to find out what you meant.",
                        dim = true,
                    )
                }
                // Only asked where the answer matters. A button with no double tap bound never
                // waits for one, so the row would be a setting with nothing behind it.
                if (double.acts) {
                    var waits by remember(button) { mutableStateOf(prefs.tapWaitsForDouble(button)) }
                    MenuRow(
                        label = "Tap waits for the second",
                        detail = if (waits) "WAITS" else "FIRES BOTH",
                        sub = if (waits) {
                            "the tap is held back a moment, so a double tap never also fires it " +
                                "on the way past. Costs a third of a second on every press."
                        } else {
                            "the tap fires the instant you let go, and a second press adds the " +
                                "double on top of it. Fast, at the price of doing both."
                        },
                        onClick = {
                            waits = !waits
                            prefs.setTapWaitsForDouble(button, waits)
                        },
                    )
                }
                if (button == Button.Home) {
                    // A door, not the settings themselves. It only appears once something is bound
                    // to the switcher, which is the same rule the rows it replaced followed — and
                    // the screen behind it is on the main menu either way, so a binding moved to
                    // another button never takes a setting away with it.
                    if (switcherBound) {
                        MenuRow(
                            label = "App switcher",
                            detail = "\u203a",
                            sub = "the recent-apps list: how fast it scrolls, and the Home row " +
                                "pinned to the bottom of it",
                            onClick = onSwitcher,
                        )
                    }
                    MenuRow(
                        label = "Timing the hold takes the key",
                        detail = if (homeArmed) "ON" else "OFF",
                        sub = if (homeArmed) {
                            "the press is swallowed and replayed, so both gestures work. Never " +
                                "on the lock screen, with the screen off, or on LightOS's own " +
                                "screens — there the whole press goes through untouched."
                        } else {
                            "off, so nothing is consumed: LightOS sees every press and the hold " +
                                "binding doesn't apply. Tap to arm it."
                        },
                        onClick = {
                            homeArmed = !homeArmed
                            if (homeArmed) prefs.armHome() else prefs.homeTakeover = false
                        },
                    )
                    if (resumeBound) {
                        val chosen = prefs.resumeApps().size
                        MenuRow(
                            label = "Resume apps",
                            detail = if (chosen == 0) "NONE" else "$chosen",
                            sub = if (chosen == 0) {
                                "nothing chosen yet, so anything bound to Resume just goes home"
                            } else {
                                "sleep in one of these and Resume brings it back, once"
                            },
                            onClick = onResumeApps,
                        )
                    }
                }
                if (button == Button.Camera) {
                    MenuRow(
                        label = "On LightOS screens",
                        detail = if (cameraLightOs) "ON" else "OFF",
                        sub = if (cameraLightOs) {
                            "if you point a gesture above at an app, it fires on Light's home " +
                                "and lock screens too — which is where this button is actually " +
                                "pressed. Left on CAMERA, LightOS keeps the key and opens its " +
                                "own camera, because ours would have to ask which one you meant."
                        } else {
                            "off, so LightOS answers the camera button on its own screens and " +
                                "whatever you bind here only applies inside other apps."
                        },
                        onClick = {
                            cameraLightOs = !cameraLightOs
                            prefs.cameraOnLightOs = cameraLightOs
                        },
                    )
                    MenuRow(
                        label = "Inside a camera",
                        detail = "PASS",
                        sub = "both stages go to any app registered as a camera, whatever is " +
                            "bound here. Its shutter needs them, and opening a camera from " +
                            "inside one does nothing.",
                        dim = true,
                    )
                }
                // Same door the camera button gets, for the same reason, and it only appears
                // when the hold actually opens the switcher — without that binding the row
                // would be a setting with nothing behind it.
                if (button == Button.WheelClick &&
                    prefs.action(button, Gesture.Hold) is Action.Switcher
                ) {
                    var switcherLightOs by remember(button) {
                        mutableStateOf(prefs.switcherOnLightOs)
                    }
                    MenuRow(
                        label = "On LightOS screens",
                        detail = if (switcherLightOs) "ON" else "OFF",
                        sub = if (switcherLightOs) {
                            "the held wheel opens the switcher on Light's home and lock " +
                                "screens too — where you actually stand between apps. The " +
                                "click is claimed there to time the hold, so a short press " +
                                "fires the tap binding instead of reaching LightOS."
                        } else {
                            "off, so LightOS keeps its wheel click and the switcher hold " +
                                "only works inside other apps."
                        },
                        onClick = {
                            switcherLightOs = !switcherLightOs
                            prefs.switcherOnLightOs = switcherLightOs
                        },
                    )
                }
                Rule()
            }
            Gap(16)
            Text(
                "Volume keys pass through by default — they already work, and binding them " +
                    "takes a function away to add one.",
                style = MaterialTheme.typography.bodyMedium,
                color = Dim,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Gap(28)
        }
    }
}

fun shortLabel(action: Action): String = when (action) {
    Action.PassThrough -> "PASS"
    Action.None -> "NONE"
    Action.Torch -> "TORCH"
    Action.OpenCamera -> "CAMERA"
    Action.DefaultHome -> "HOME"
    Action.LightOsHome -> "LIGHTOS"
    Action.Resume -> "RESUME"
    Action.Back -> "BACK"
    Action.Switcher -> "APPS"
    Action.OpenSettings -> "SETTINGS"
    Action.Shade -> "SHADE"
    Action.QuickSettings -> "QUICK"
    Action.Screenshot -> "SHOT"
    Action.LockNow -> "LOCK"
    Action.PowerMenu -> "POWER"
    Action.ColorFlip -> "COLOUR"
    Action.SwitchTurn -> "TURN"
    Action.ShowLock -> "FACE"
    Action.Keyboard -> "KEYS"
    Action.Hotspot -> "HOTSPOT"
    Action.VolumeUp -> "VOL +"
    Action.VolumeDown -> "VOL -"
    Action.BrightnessUp -> "BRIGHT +"
    Action.BrightnessDown -> "BRIGHT -"
    is Action.Launch -> "APP"
}

fun longLabel(pm: PackageManager, action: Action): String? = when (action) {
    Action.PassThrough -> "the app in front gets the key"
    Action.None -> "swallowed, does nothing"
    Action.Torch -> "flashlight on or off"
    Action.OpenCamera -> "opens the Light camera"
    Action.DefaultHome -> "whichever launcher is default"
    Action.LightOsHome -> "Light's dashboard, by name"
    Action.Resume -> "back to a chosen app if the screen went off in it, else where you set"
    Action.Back -> "goes back — the one thing this phone has no button for"
    Action.Switcher -> "the list of apps you have been in"
    Action.OpenSettings -> "the system settings, which LightOS has no way into"
    Action.Shade -> "pulls the notification shade down"
    Action.QuickSettings -> "the quick settings panel"
    Action.Screenshot -> "takes a screenshot"
    Action.LockNow -> "locks the phone, as the power button would"
    Action.PowerMenu -> "the power menu"
    Action.ColorFlip -> "flips the app in front between colour and mono, and remembers it"
    Action.SwitchTurn -> "flips turning the wheel between brightness and scrolling"
    Action.ShowLock -> "puts the lock face up over whatever is on screen"
    Action.Keyboard -> "pops the keyboard-replace band up over a LightOS app, or away"
    Action.Hotspot -> "raises or drops the hotspot, with the name already saved"
    Action.VolumeUp -> "one step up, on whatever the volume keys would move"
    Action.VolumeDown -> "one step down, on whatever the volume keys would move"
    Action.BrightnessUp -> "one notch brighter"
    Action.BrightnessDown -> "one notch dimmer"
    is Action.Launch -> appLabel(pm, action.pkg)
}

/** An app's own name, falling back to the package when it has been uninstalled. */
fun appLabel(pm: PackageManager, pkg: String): String = runCatching {
    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
}.getOrDefault(pkg)

