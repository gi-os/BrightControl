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
import androidx.compose.runtime.remember
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
fun ButtonsScreen(onPick: (Button, Gesture) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }

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
                Gesture.entries.forEach { gesture ->
                    val action = prefs.action(button, gesture)
                    MenuRow(
                        label = gesture.label,
                        detail = shortLabel(action),
                        sub = longLabel(context.packageManager, action),
                        onClick = { onPick(button, gesture) },
                    )
                }
                if (button == Button.WheelClick) {
                    MenuRow(
                        label = "Double tap",
                        detail = "SWITCH TURN",
                        sub = "flips turning between brightness and scrolling. A tap waits " +
                            "for its partner, so a double tap never also fires the tap binding.",
                        dim = true,
                    )
                }
                if (button == Button.Home) {
                    MenuRow(
                        label = "Both unbound",
                        detail = "PASS",
                        sub = "LightOS keeps the home button until you bind a gesture. Bind one " +
                            "and the service takes the key for both, because it must swallow " +
                            "the press to time the hold — so give the other gesture Home or " +
                            "LightOS home to keep a way back.",
                        dim = true,
                    )
                }
                if (button == Button.Camera) {
                    MenuRow(
                        label = "Inside a camera",
                        detail = "PASS",
                        sub = "both stages go to any app registered as a camera, whatever is " +
                            "bound here. Its shutter needs them, and opening a camera from " +
                            "inside one does nothing.",
                        dim = true,
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
    is Action.Launch -> "APP"
}

fun longLabel(pm: PackageManager, action: Action): String? = when (action) {
    Action.PassThrough -> "the app in front gets the key"
    Action.None -> "swallowed, does nothing"
    Action.Torch -> "flashlight on or off"
    Action.OpenCamera -> "opens the Light camera"
    Action.DefaultHome -> "whichever launcher is default"
    Action.LightOsHome -> "Light's dashboard, by name"
    is Action.Launch -> appLabel(pm, action.pkg)
}

/** An app's own name, falling back to the package when it has been uninstalled. */
fun appLabel(pm: PackageManager, pkg: String): String = runCatching {
    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
}.getOrDefault(pkg)
