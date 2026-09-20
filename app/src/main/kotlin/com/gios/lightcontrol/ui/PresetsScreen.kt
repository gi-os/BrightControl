package com.gios.lightcontrol.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.gios.lightcontrol.Launcher
import com.gios.lightcontrol.Preset
import com.gios.lightcontrol.Presets

/**
 * A whole working setup, in one tap, with the list of what it will do shown first.
 *
 * The screen answers the question this app gets asked more than any other, which is not "what
 * does this setting do" but "what should I switch on". It is deliberately two steps: the preset
 * says what it is about to do, and only the second tap does it. Forty settings changing at once
 * with no warning is not a feature, it is the same surprise as the phone going strange, arriving
 * from the other direction.
 *
 * The launcher line at the top is read from the system, not asked. Which launcher is default
 * decides what the home button should be bound to, and the phone already knows the answer.
 */
@Composable
fun PresetsScreen(onBack: () -> Unit, onSetup: () -> Unit) {
    val context = LocalContext.current
    val launcher = remember { Launcher.detect(context) }
    val launcherName = remember { Launcher.homeLabel(context) }

    // Null means the list of presets. A preset means its page, which is where applying happens.
    var opened by remember { mutableStateOf<Preset?>(null) }
    var applied by remember { mutableStateOf<String?>(null) }

    val current = opened
    if (current == null) {
        SectionScaffold(
            title = "Presets",
            onBack = onBack,
            guide = "A preset is a whole configuration, not a single setting. Applying one clears " +
                "everything you have set and writes its own values, so the phone ends up exactly " +
                "where the preset says and not layered over whatever was there. Tap one to read " +
                "what it does before anything changes.",
        ) {
            MenuRow(
                label = "Your launcher",
                detail = if (launcher == Launcher.LightOs) "LIGHTOS" else "OTHER",
                sub = if (launcher == Launcher.LightOs) {
                    "LightOS is set as home, so the home button already goes where it should. " +
                        "Presets leave it alone."
                } else {
                    "$launcherName is set as home. LightOS's own dashboard is only reachable " +
                        "through a binding, so presets put one on the home button's hold."
                },
                dim = true,
            )
            Rule()

            SectionLabel("START FROM")
            Presets.all.forEach { preset ->
                MenuRow(
                    label = preset.label,
                    detail = "›",
                    sub = preset.sub,
                    onClick = { opened = preset },
                )
            }
            Rule()

            applied?.let {
                MenuRow(label = "Applied", detail = "✓", sub = it, dim = true)
                Rule()
            }

            SectionLabel("IF SOMETHING IS STILL WRONG")
            GuideText(
                "A preset only sets what this app stores. It cannot grant anything, so a setup " +
                    "that looks half-applied is usually a missing grant rather than a missing " +
                    "setting.",
            )
            MenuRow(
                label = "Setup & guide",
                detail = "›",
                sub = "which grants are in place, and how to give the rest",
                onClick = onSetup,
            )
        }
        return
    }

    SectionScaffold(
        title = current.label,
        onBack = { opened = null },
        guide = current.sub + ". Everything you have set is cleared first, so this is the whole " +
            "configuration and not an addition to it.",
    ) {
        SectionLabel("WHAT IT DOES")
        current.lines(launcher, launcherName).forEach { line ->
            MenuRow(label = line, dim = true)
        }
        Rule()

        SectionLabel("WHAT IT KEEPS")
        GuideText(
            "The adb pairing, your lock-screen photo, the hotspot's name and password, and the " +
                "fault and crash log. A reset is often what somebody tries before reporting a " +
                "problem, and a report with no log in it cannot be read.",
        )
        Rule()

        Gap(8)
        BigButton(
            label = "APPLY " + current.label,
            filled = true,
            modifier = Modifier.padding(horizontal = 16.dp),
        ) {
            val source = Presets.apply(context, current)
            applied = when (source) {
                Presets.Source.File -> "${current.label}, from a settings file shipped with the app"
                Presets.Source.BuiltIn -> current.label
            }
            opened = null
        }
        Gap(12)
        GuideText(
            "Applies at once. A screen you already have open may show its old numbers until you " +
                "reopen it.",
        )
    }
}
