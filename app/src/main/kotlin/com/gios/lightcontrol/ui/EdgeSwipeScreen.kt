package com.gios.lightcontrol.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import com.gios.lightcontrol.EdgeLength
import com.gios.lightcontrol.EdgeSide
import com.gios.lightcontrol.Prefs
import com.gios.lightcontrol.keys.Grants
import com.gios.lightcontrol.keys.OwnWindow

/**
 * The edge gestures: which edges are live, what each one's two swipes do, and the distances.
 *
 * Laid out like the Buttons screen, because it is the same thing: a set of gestures, each with a
 * short and a long version, each bound to an [Action] chosen from the same picker. The guide says
 * what they cost rather than what they do, because what they do is obvious and what they cost is
 * not — these are the only features in the app that take a *touch*, and somebody who turns one on
 * and later finds an app whose edge no longer works needs to be able to connect the two.
 */
@Composable
fun EdgeSwipeScreen(
    onPerApp: () -> Unit,
    onPick: (EdgeSide, EdgeLength) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    val canOverlay = Grants.canDrawOverlays(context)
    val screenWidthDp = LocalConfiguration.current.screenWidthDp

    var left by remember { mutableStateOf(prefs.leftEdgeOn) }
    var right by remember { mutableStateOf(prefs.rightEdgeOn) }
    var width by remember { mutableIntStateOf(prefs.edgeWidthDp) }
    var trigger by remember { mutableIntStateOf(prefs.edgeTriggerDp) }
    var long by remember { mutableIntStateOf(prefs.edgeLongDp) }
    var hud by remember { mutableStateOf(prefs.edgeIndicator) }
    val excluded = prefs.edgeSwipeOffApps().size
    val either = left || right

    SectionScaffold(
        title = "Edge gestures",
        onBack = onBack,
        guide = "LightOS has no navigation bar, and its gesture-navigation setting only reaches " +
            "Light's own tools. So an app you sideloaded has no way back and no way to the recents " +
            "list. These put a thin strip down an edge of the screen. Each edge does one thing on " +
            "a short swipe inwards and another on a long one, and both are bound like a button.",
    ) {
        if (!canOverlay) {
            GrantRow(
                label = "Draw over other apps",
                ok = false,
                fix = "adb shell appops set com.gios.lightcontrol SYSTEM_ALERT_WINDOW allow",
                sub = "a strip is a window, so nothing here works until this is granted. Set it " +
                    "on the ADB screen, or with this line",
            )
            Rule()
        }

        for (side in EdgeSide.entries) {
            val on = if (side == EdgeSide.Left) left else right
            SectionLabel(side.label.uppercase())
            MenuRow(
                label = side.label,
                detail = if (!canOverlay) "NO GRANT" else if (on) "ON" else "OFF",
                dim = !canOverlay,
                sub = when {
                    !canOverlay -> "needs the overlay grant above"
                    on && side == EdgeSide.Left ->
                        "on, as it ships. Touches that start within $width dp of the left edge " +
                            "come here instead of going to the app. Everything else on the screen " +
                            "is untouched, and one tap here turns it off."
                    on ->
                        "on. Touches that start within $width dp of the right edge come here " +
                            "instead of going to the app."
                    side == EdgeSide.Left ->
                        "off. This is the edge that goes back, and this phone has no other way " +
                            "to — turn it back on unless an app you use needs its left edge."
                    else ->
                        "off, as it ships. The app switcher is already a double press of home; " +
                            "turn this on to reach it by thumb as well."
                },
                onClick = {
                    val next = !on
                    if (side == EdgeSide.Left) {
                        left = next
                        prefs.leftEdgeOn = next
                    } else {
                        right = next
                        prefs.rightEdgeOn = next
                    }
                    // Acted on now rather than at the next app switch, so a gesture can be tried
                    // on this very screen. See [OwnWindow.settingChanged].
                    OwnWindow.settingChanged()
                },
            )
            if (on) {
                for (length in EdgeLength.entries) {
                    val action = prefs.edgeAction(side, length)
                    MenuRow(
                        label = length.label,
                        detail = shortLabel(action),
                        sub = when {
                            length == EdgeLength.Long && !action.acts ->
                                "nothing. This edge has one gesture, and the indicator measures " +
                                    "the short one."
                            length == EdgeLength.Long ->
                                longLabel(context.packageManager, action).orEmpty() +
                                    " — carry the drag past $long dp"
                            else ->
                                longLabel(context.packageManager, action).orEmpty() +
                                    " — a drag of $trigger dp"
                        },
                        onClick = { onPick(side, length) },
                    )
                }
            }
        }
        Rule()

        SectionLabel("THE DISTANCES")
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
            label = "Short swipe",
            detail = "$trigger dp",
            sub = "past this the short binding is armed and letting go performs it. Come back " +
                "under it and it disarms, so a stroke can always be changed your mind about.",
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
            label = "Long swipe",
            detail = if (long == 0) "OFF" else "$long dp",
            sub = if (long == 0) {
                "off. Both edges have one gesture each, whatever the long bindings say."
            } else {
                "carry the drag this far and the long binding replaces the short one. The screen " +
                    "is $screenWidthDp dp across, and a threshold past four fifths of it is " +
                    "pulled back so the gesture stays completable."
            },
            onClick = {
                long = when (long) {
                    0 -> 110
                    110 -> 150
                    150 -> 200
                    200 -> 260
                    else -> 0
                }
                prefs.edgeLongDp = long
                OwnWindow.settingChanged()
            },
        )
        MenuRow(
            label = "Show the indicator",
            detail = if (hud) "ON" else "OFF",
            sub = if (hud) {
                "a box at your thumb: an outline while the drag is short, then the name of " +
                    "whichever binding a release would perform. A tick marks where the long " +
                    "swipe takes over."
            } else {
                "hidden. The gestures still work, with nothing on screen to say which one is " +
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
        MenuRow(
            label = "A long swipe costs no more than a short one",
            sub = "the strip is the same width either way. Only how far the finger travels " +
                "afterwards differs, and by then the touch is already ours.",
            dim = true,
        )
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
