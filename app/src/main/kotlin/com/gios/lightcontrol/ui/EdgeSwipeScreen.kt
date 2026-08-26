package com.gios.lightcontrol.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.gios.lightcontrol.Prefs
import com.gios.lightcontrol.keys.Grants
import com.gios.lightcontrol.keys.OwnWindow

/**
 * The two edge gestures: which are on, how wide the strips are, and which apps are left out.
 *
 * The guide says what they cost rather than what they do, because what they do is obvious and what
 * they cost is not: these are the only features in the app that take a *touch*, and somebody who
 * turns one on and later finds an app whose edge no longer works needs to be able to connect the
 * two.
 */
@Composable
fun EdgeSwipeScreen(onPerApp: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    val canOverlay = Grants.canDrawOverlays(context)

    var back by remember { mutableStateOf(prefs.backSwipe) }
    var apps by remember { mutableStateOf(prefs.switcherSwipe) }
    var width by remember { mutableIntStateOf(prefs.edgeWidthDp) }
    var trigger by remember { mutableIntStateOf(prefs.edgeTriggerDp) }
    var hud by remember { mutableStateOf(prefs.edgeIndicator) }
    val excluded = prefs.edgeSwipeOffApps().size
    val either = back || apps

    SectionScaffold(
        title = "Edge gestures",
        onBack = onBack,
        guide = "LightOS has no navigation bar, and its gesture-navigation setting only reaches " +
            "Light's own tools. So an app you sideloaded has no way back and no way to the recents " +
            "list. These put a thin strip down an edge of the screen. Drag it inwards and let go.",
    ) {
        MenuRow(
            label = "Left edge · go back",
            detail = if (!canOverlay) "NO GRANT" else if (back) "ON" else "OFF",
            dim = !canOverlay,
            sub = when {
                !canOverlay -> "the strip is a window, so it needs the overlay grant below"
                back ->
                    "on. Drag right from the left edge and let go. Touches that start within " +
                        "$width dp of that edge come here instead of going to the app."
                else -> "off. Turn it on for a back gesture in every app except Light's own."
            },
            onClick = {
                back = !back
                prefs.backSwipe = back
                // Acted on now rather than at the next app switch, so the gesture can be tried on
                // this very screen. See [OwnWindow.settingChanged].
                OwnWindow.settingChanged()
            },
        )
        MenuRow(
            label = "Right edge · app switcher",
            detail = if (!canOverlay) "NO GRANT" else if (apps) "ON" else "OFF",
            dim = !canOverlay,
            sub = when {
                !canOverlay -> "same grant as the left edge"
                apps ->
                    "on. Drag left from the right edge and let go. The same list the home button " +
                        "opens on a double press, without needing a button."
                else ->
                    "off. Turn it on to reach the recent-apps list by thumb — including on " +
                        "LightOS's own screens, where the home button is LightOS's."
            },
            onClick = {
                apps = !apps
                prefs.switcherSwipe = apps
                OwnWindow.settingChanged()
            },
        )
        if (!canOverlay) {
            GrantRow(
                label = "Draw over other apps",
                ok = false,
                fix = "adb shell appops set com.gios.lightcontrol SYSTEM_ALERT_WINDOW allow",
                sub = "a strip is a window. Set it on the ADB screen, or with this line",
            )
        }
        Rule()

        SectionLabel("THE GESTURE")
        MenuRow(
            label = "Strip width",
            detail = "$width dp",
            sub = "the whole cost of these gestures is this number — how much of an edge belongs " +
                "to the strip instead of to the app. One number for both edges.",
            onClick = {
                width = when (width) {
                    10 -> 14
                    14 -> 20
                    20 -> 28
                    else -> 10
                }
                prefs.edgeWidthDp = width
                OwnWindow.settingChanged()
            },
        )
        MenuRow(
            label = "How far to drag",
            detail = "$trigger dp",
            sub = "past this the gesture is armed and letting go acts. Come back under it and it " +
                "disarms, so a stroke can always be changed your mind about.",
            onClick = {
                trigger = when (trigger) {
                    32 -> 48
                    48 -> 64
                    64 -> 88
                    else -> 32
                }
                prefs.edgeTriggerDp = trigger
                OwnWindow.settingChanged()
            },
        )
        MenuRow(
            label = "Show the indicator",
            detail = if (hud) "ON" else "OFF",
            sub = if (hud) {
                "a small box at your thumb: an outline while the drag is short, white and " +
                    "labelled BACK or APPS once letting go would act"
            } else {
                "hidden. The gestures still work, with nothing on screen to say whether one is " +
                    "armed yet."
            },
            onClick = {
                hud = !hud
                prefs.edgeIndicator = hud
                OwnWindow.settingChanged()
            },
        )
        Rule()

        SectionLabel("WHERE")
        MenuRow(
            label = "Apps left out",
            detail = if (excluded == 0) "NONE" else "$excluded",
            sub = "the apps whose edges are controls of their own. One list, both edges.",
            onClick = onPerApp,
        )
        MenuRow(
            label = "Never over Light's own tools",
            sub = "LightOS, its keyboard and its launcher have their own gesture navigation. A " +
                "second strip over the top would be the weaker of two gestures on one edge.",
            dim = true,
        )
        MenuRow(
            label = "Never over the lock face",
            sub = "or the app switcher. Both are full-screen windows above a strip, and their own " +
                "swipes are what the edges are for while they are up.",
            dim = true,
        )
        Rule()

        SectionLabel("WHAT THEY CANNOT DO")
        MenuRow(
            label = "A swallowed touch cannot be given back",
            sub = "there is no way to watch a touch without receiving it. Gesture detection " +
                "through the accessibility API needs touch exploration switched on, which " +
                "changes how the whole phone is driven. So a stroke that starts on a strip is " +
                "the strip's, even when it turns out to be a scroll.",
            dim = true,
        )
        if (back) {
            MenuRow(
                label = "Going back is a request",
                sub = "the phone is asked to go back; what the app does with that is the app's. " +
                    "On its first screen many apps accept it and do nothing, which from out here " +
                    "is indistinguishable from working.",
                dim = true,
            )
        }
        if (either) {
            MenuRow(
                label = "It can never cost a key",
                sub = "a strip is never focusable, so the wheel and the buttons are untouched by " +
                    "it. That is the rule the rest of this app is built on.",
                dim = true,
            )
        }
    }
}
