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
 * The back gesture: whether it is on, how wide the strip is, and which apps are left out.
 *
 * The guide says what it costs rather than what it does, because what it does is obvious and what
 * it costs is not: this is the only feature in the app that takes a *touch*, and somebody who
 * turns it on and later finds an app whose left edge no longer works needs to be able to connect
 * the two.
 */
@Composable
fun BackSwipeScreen(onPerApp: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    val canOverlay = Grants.canDrawOverlays(context)

    var on by remember { mutableStateOf(prefs.backSwipe) }
    var width by remember { mutableIntStateOf(prefs.backSwipeWidthDp) }
    var trigger by remember { mutableIntStateOf(prefs.backSwipeTriggerDp) }
    var hud by remember { mutableStateOf(prefs.backSwipeIndicator) }
    val excluded = prefs.backSwipeOffApps().size

    SectionScaffold(
        title = "Swipe back",
        onBack = onBack,
        guide = "LightOS has no back button, and its gesture-navigation setting only reaches " +
            "Light's own tools. So an app you sideloaded has no way back at all: one that pushes " +
            "a screen and forgot to draw its own arrow is a dead end until you press home. This " +
            "puts a thin strip down the left edge of the screen. Drag it to the right and let " +
            "go, and the app goes back.",
    ) {
        MenuRow(
            label = "Swipe back",
            detail = if (!canOverlay) "NO GRANT" else if (on) "ON" else "OFF",
            dim = !canOverlay,
            sub = when {
                !canOverlay ->
                    "the strip is a window, so it needs the overlay grant. Set it on the ADB " +
                        "screen, or by hand: adb shell appops set com.gios.lightcontrol " +
                        "SYSTEM_ALERT_WINDOW allow"
                on ->
                    "on. Touches that start within $width dp of the left edge come here instead " +
                        "of going to the app. Everything else on the screen is untouched."
                else ->
                    "off. Turn it on and a strip appears down the left edge in every app except " +
                        "Light's own."
            },
            onClick = {
                on = !on
                prefs.backSwipe = on
                // Acted on now rather than at the next app switch, so the gesture can be tried
                // on this very screen. See [OwnWindow.settingChanged].
                OwnWindow.settingChanged()
            },
        )
        Rule()

        SectionLabel("THE GESTURE")
        MenuRow(
            label = "Strip width",
            detail = "$width dp",
            sub = "the whole cost of the feature is this number — how much of the left edge " +
                "belongs to the strip instead of to the app",
            onClick = {
                width = when (width) {
                    10 -> 14
                    14 -> 20
                    20 -> 28
                    else -> 10
                }
                prefs.backSwipeWidthDp = width
                OwnWindow.settingChanged()
            },
        )
        MenuRow(
            label = "How far to drag",
            detail = "$trigger dp",
            sub = "past this the gesture is armed and letting go goes back. Come back under it " +
                "and it disarms, so a stroke can always be changed your mind about.",
            onClick = {
                trigger = when (trigger) {
                    32 -> 48
                    48 -> 64
                    64 -> 88
                    else -> 32
                }
                prefs.backSwipeTriggerDp = trigger
                OwnWindow.settingChanged()
            },
        )
        MenuRow(
            label = "Show the indicator",
            detail = if (hud) "ON" else "OFF",
            sub = if (hud) {
                "a small box at your thumb: an outline while the drag is short, white and " +
                    "labelled BACK once letting go would go back"
            } else {
                "hidden. The gesture still works, with nothing on screen to say whether it is " +
                    "armed yet."
            },
            onClick = {
                hud = !hud
                prefs.backSwipeIndicator = hud
                OwnWindow.settingChanged()
            },
        )
        Rule()

        SectionLabel("WHERE")
        MenuRow(
            label = "Apps left out",
            detail = if (excluded == 0) "NONE" else "$excluded",
            sub = "the apps whose left edge is a control of their own",
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
            sub = "or the app switcher. Both are full-screen windows above the strip, and their " +
                "own swipes are what the left edge is for while they are up.",
            dim = true,
        )
        Rule()

        SectionLabel("WHAT IT CANNOT DO")
        MenuRow(
            label = "A swallowed touch cannot be given back",
            sub = "there is no way to watch a touch without receiving it. Gesture detection " +
                "through the accessibility API needs touch exploration switched on, which " +
                "changes how the whole phone is driven. So a stroke that starts on the strip is " +
                "the strip's, even when it turns out to be a scroll.",
            dim = true,
        )
        MenuRow(
            label = "Going back is a request",
            sub = "the phone is asked to go back; what the app does with that is the app's. On " +
                "its first screen many apps accept it and do nothing, which from out here is " +
                "indistinguishable from working.",
            dim = true,
        )
        MenuRow(
            label = "It can never cost a key",
            sub = "the strip is never focusable, so the wheel and the buttons are untouched by " +
                "it. That is the rule the rest of this app is built on.",
            dim = true,
        )
    }
}
