package com.gios.lightcontrol.ui

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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.gios.lightcontrol.Action
import com.gios.lightcontrol.Button
import com.gios.lightcontrol.EdgeLength
import com.gios.lightcontrol.EdgeSide
import com.gios.lightcontrol.Gesture
import com.gios.lightcontrol.Prefs
import com.gios.lightcontrol.switcher.HomeApp
import com.gios.lightcontrol.switcher.appName

/**
 * Everything about the recent-apps list, on one screen.
 *
 * These settings used to live under **Buttons → Home button**, because for a long time the double
 * press of home was the only way to open the switcher and settings belong where the thing is
 * reachable from. That stopped being true in v3.86: the switcher is an ordinary [Action.Switcher]
 * binding now, available on all five buttons and both edges. So the rows were sitting under one
 * particular gesture on one particular button, which is not where anybody looks for "how tall is
 * the list" or "which app is Home" — and they vanished entirely if you moved the binding somewhere
 * else, which reads as settings that have been taken away.
 *
 * The bindings themselves are deliberately **not** here. A screen that could set both what a button
 * does and what this feature does would be a second binding editor, out of step with the first the
 * moment either changed. [openedBy] names the gestures instead, and Buttons and Edge gestures stay
 * the one place a binding is chosen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwitcherScreen(onHomeApp: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }

    var stepMs by remember { mutableLongStateOf(prefs.switcherStepMs) }
    var homeRow by remember { mutableStateOf(prefs.switcherHomeRow) }
    // Both read on every composition rather than remembered: they are set on other screens, and
    // this one is recomposed on the way back from them. A remembered answer would be the one from
    // before the change, which on these two rows is the whole point -- they exist to say where Home
    // goes and how the list is opened.
    val homeGoesTo = runCatching {
        HomeApp.label(prefs, context.packageManager) { appName(context, it) }
    }.getOrDefault("the system's home")
    val opened = openedBy(prefs)

    val scroll = rememberScrollState()
    WheelScroll(scroll)

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                colors = barColors(),
                title = {
                    Column {
                        Text("App switcher", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "the apps you have been in",
                            style = MaterialTheme.typography.labelSmall,
                            color = com.gios.lightcontrol.ui.theme.Dim,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(scroll)) {
            SectionLabel("OPENING IT")
            // Read-only, and it names the gestures rather than offering to change them. A list
            // with nothing on it is the one state worth saying out loud: every setting below is
            // about a window that, right now, nothing can open.
            MenuRow(
                label = if (opened.isEmpty()) "Nothing opens it" else "Opened by",
                detail = if (opened.isEmpty()) "NONE" else null,
                sub = if (opened.isEmpty()) {
                    "bind App switcher to a button under Buttons, or to an edge under Edge " +
                        "gestures. Out of the box it is a double press of home."
                } else {
                    opened.joinToString(" · ")
                },
                dim = opened.isEmpty(),
            )
            Rule()

            SectionLabel("THE LIST")
            MenuRow(
                label = "Scroll speed",
                detail = switcherSpeed(stepMs),
                sub = "at most one row every $stepMs ms. The wheel sends a notch every 35–60 ms, " +
                    "so without a floor one flick runs the whole list two or three times over.",
                onClick = {
                    stepMs = when (stepMs) {
                        60L -> 120L
                        120L -> 200L
                        200L -> 320L
                        else -> 60L
                    }
                    prefs.switcherStepMs = stepMs
                },
            )
            MenuRow(
                label = "Show Home",
                detail = if (homeRow) "ON" else "OFF",
                sub = if (homeRow) {
                    "a Home row at the bottom, always, with a drawn house — and the app it opens " +
                        "is left out of the recents above it. Every other row is somewhere you " +
                        "were; this is how you leave."
                } else {
                    "off, so there is no Home row and your launcher is listed by its own name " +
                        "and icon like any other app. This screen only — nothing else renames it."
                },
                onClick = {
                    homeRow = !homeRow
                    prefs.switcherHomeRow = homeRow
                },
            )
            // Only when there is a row to point somewhere. A destination for a row that is
            // switched off is a setting with nothing behind it.
            if (homeRow) {
                MenuRow(
                    label = "Home app",
                    detail = homeGoesTo.uppercase(),
                    sub = "what that row opens. Pick it — this used to be worked out from the " +
                        "phone and got it wrong twice, because LightOS holds the home role here " +
                        "whether you use it or not.",
                    onClick = onHomeApp,
                )
            }
            Rule()

            SectionLabel("WHILE IT IS UP")
            // Said rather than settable. None of this is a preference and all of it is a thing
            // people ask about, which is the definition of a line of text rather than a switch.
            MenuRow(
                label = "The gestures",
                sub = "the wheel moves the selection and a click opens it · holding the click, " +
                    "or a row, opens that app's page in Settings, where Force stop is · home " +
                    "closes it · so does the camera button, and six seconds of nothing.",
                dim = true,
            )
            MenuRow(
                label = "Why it is not the system's",
                sub = "LightOS ships no recents screen. Asking for one reports success and draws " +
                    "nothing, which is why this list is drawn here instead — SYSTEM SWITCHER at " +
                    "the bottom of it asks anyway, and says so when nothing comes up.",
                dim = true,
            )
            Rule()
        }
    }
}

/**
 * Every gesture bound to [Action.Switcher], named the way its own screen names it.
 *
 * An edge that is switched off is left out even when something is bound to it: the binding is
 * stored, but nothing on that edge can happen, and a list of ways to open this window must not
 * include one that cannot.
 */
private fun openedBy(prefs: Prefs): List<String> {
    val out = mutableListOf<String>()
    runCatching {
        Button.entries.forEach { button ->
            Gesture.entries
                .filter { prefs.bindable(button, it) && prefs.action(button, it) == Action.Switcher }
                .forEach { out += "${button.label}, ${it.label.lowercase()}" }
        }
        EdgeSide.entries.forEach { side ->
            val live = if (side == EdgeSide.Left) prefs.leftEdgeOn else prefs.rightEdgeOn
            if (!live) return@forEach
            EdgeLength.entries
                .filter { prefs.edgeAction(side, it) == Action.Switcher }
                .forEach { out += "${side.label}, ${it.label.lowercase()}" }
        }
    }
    return out
}

private fun switcherSpeed(stepMs: Long): String = when {
    stepMs <= 80L -> "FAST"
    stepMs <= 150L -> "NORMAL"
    stepMs <= 260L -> "SLOW"
    else -> "SLOWEST"
}
