package com.gios.lightcontrol.ui

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.gios.lightcontrol.Prefs
import com.gios.lightcontrol.keys.KeyboardService

/**
 * The keyboard-replace prototype's screen.
 *
 * This is the one feature in this app that deliberately reads which field is focused, so it is a
 * door of its own with its own switch, off by default, rather than a toggle buried in a section.
 * The two switches here are the same two modes the service implements: inject text into the
 * focused field (default), or simulate presses on the underlying LightOS keyboard (the fallback
 * for fields that refuse text actions).
 */
@Composable
fun KeyboardScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }

    var replace by remember { mutableStateOf(prefs.keyboardReplace) }
    var simulate by remember { mutableStateOf(prefs.keyboardReplaceSimulate) }

    SectionScaffold(
        title = "Keyboard replace",
        onBack = onBack,
        guide = "PROTOTYPE · a second keyboard over LightOS apps. Off until you switch it on — " +
            "it is the one feature here that reads which field is focused, and it only ever does " +
            "that inside LightOS's own apps.",
    ) {
        MenuRow(
            label = "Keyboard replace",
            detail = if (replace) "ON" else "OFF",
            sub = if (replace) {
                "inside a LightOS app, a tap on a text field brings up our keyboard band and " +
                    "types into that field instead of LightOS's own keys."
            } else {
                "off, so LightOS apps keep their built-in keyboard and nothing is read. " +
                    "Tap to arm the prototype."
            },
            onClick = {
                replace = !replace
                prefs.keyboardReplace = replace
            },
        )
        MenuRow(
            label = "Service",
            detail = if (keyboardServiceEnabled(context)) "ON" else "OFF",
            sub = if (keyboardServiceEnabled(context)) {
                "the keyboard-replace accessibility service is enabled, so the band can appear."
            } else {
                "the keyboard-replace accessibility service is off. Enable it in ADB & grants " +
                    "so the band can appear."
            },
            dim = true,
        )
        Rule()
        SectionLabel("FALLBACK")
        MenuRow(
            label = "Simulate key presses",
            detail = if (simulate) "ON" else "OFF",
            sub = if (simulate) {
                "keys are pressed on the LightOS keyboard underneath instead of text being " +
                    "written straight into the field. For fields that refuse text actions — " +
                    "password prompts, custom editors."
            } else {
                "off: text is written straight into the focused field. Tap to switch to " +
                    "simulating presses on the underlying keyboard."
            },
            onClick = {
                simulate = !simulate
                prefs.keyboardReplaceSimulate = simulate
            },
        )
        MenuRow(
            label = "Bind a key to summon it",
            detail = "›",
            sub = "if the band does not appear on its own, bind any button to Keyboard replace " +
                "and press it while inside a LightOS app to bring the band up.",
            dim = true,
        )
    }
}

/** Whether the keyboard-replace accessibility service is in the enabled list. */
private fun keyboardServiceEnabled(context: Context): Boolean {
    val component = ComponentName(context, KeyboardService::class.java)
    val flat = "${component.packageName}/${component.className}"
    val raw = runCatching {
        Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        )
    }.getOrNull().orEmpty()
    return raw.split(':').any { entry ->
        val parsed = runCatching { ComponentName.unflattenFromString(entry.trim()) }.getOrNull()
        parsed == component ||
            entry.trim() == flat ||
            entry.trim() == ".keys.KeyboardService"
    }
}
