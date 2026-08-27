package com.gios.lightcontrol.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.gios.lightcontrol.Button
import com.gios.lightcontrol.Gesture
import com.gios.lightcontrol.Prefs
import com.gios.lightcontrol.TurnAction
import com.gios.lightcontrol.keys.Brightness
import com.gios.lightcontrol.keys.Grants
import com.gios.lightcontrol.report.CrashLog

/** What a bare turn does, per-app overrides, and swipe distance. */
@Composable
fun WheelScreen(onPerApp: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }

    var turn by remember { mutableStateOf(prefs.unknownAppTurn) }
    val cursor = LocalCursor.current
    var cursorOn by remember { mutableStateOf(prefs.wheelCursor) }
    var swipeDp by remember { mutableIntStateOf(prefs.swipeDp) }
    var lightOs by remember { mutableStateOf(prefs.lightOsScreens) }
    var lightOsBright by remember { mutableStateOf(prefs.lightOsBrightness) }

    SectionScaffold(
        title = "The wheel",
        onBack = onBack,
        guide = "A turn does one of a few things: change brightness, scroll by faking a finger " +
            "drag, or pass straight to apps that understand the wheel. This sets the default; " +
            "any single app can be overridden below, and Light's own tools are never touched.",
    ) {
        SectionLabel("A TURN MEANS")
        MenuRow(
            label = "Default",
            detail = turn.label,
            sub = when (turn) {
                TurnAction.Brightness -> "everywhere, including apps that scroll themselves"
                TurnAction.Swipe -> "a synthetic finger-drag, so lists scroll"
                TurnAction.PassThrough -> "apps that understand the wheel scroll; others do nothing"
                TurnAction.Consume -> "swallowed, so nothing acts on the notch"
            },
            onClick = {
                turn = when (turn) {
                    TurnAction.Brightness -> TurnAction.Swipe
                    TurnAction.Swipe -> TurnAction.PassThrough
                    TurnAction.PassThrough -> TurnAction.Brightness
                    TurnAction.Consume -> TurnAction.Brightness
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
        MenuRow(
            label = "Wheel selects rows",
            detail = if (cursorOn) "ON" else "OFF",
            sub = "in this app's own screens: a turn moves the highlight, a click opens it. " +
                "Touching the screen drops the highlight and gives the wheel back to scrolling; " +
                "a click picks it up again. Off means scrolling only.",
            onClick = {
                cursorOn = !cursorOn
                prefs.wheelCursor = cursorOn
                cursor?.enabled = cursorOn
            },
        )
        MenuRow(
            label = "Double tap to switch",
            detail = shortLabel(prefs.action(Button.WheelClick, Gesture.DoubleTap)),
            sub = "flips turning between brightness and scrolling, and says which. It is an " +
                "ordinary binding now — change it, or point it somewhere else, under Buttons.",
            dim = true,
        )
        Rule()

        SectionLabel("LIGHTOS SCREENS")
        MenuRow(
            label = "Buttons on LightOS screens",
            detail = if (lightOs) "ON" else "OFF",
            sub = "the lock screen and dashboard, which are one activity. Buttons only — turning " +
                "stays LightOS's, which is what it already does well.",
            onClick = {
                lightOs = !lightOs
                prefs.lightOsScreens = lightOs
            },
        )
        MenuRow(
            label = "LightOS brightness",
            detail = if (lightOsBright) "ON" else "BLOCKED",
            sub = if (lightOsBright) {
                "a turn on those screens dims the screen, because LightOS gets the notch. Tap to " +
                    "stop it getting them."
            } else {
                "turns are swallowed on those screens, so the brightness stays where you put it."
            },
            onClick = {
                lightOsBright = !lightOsBright
                prefs.lightOsBrightness = lightOsBright
            },
        )
    }
}

/** Brightness range and the on-screen level. */
@Composable
fun BrightnessScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    val brightness = remember { Brightness(context) }
    val canOverlay = Grants.canDrawOverlays(context)

    var steps by remember { mutableIntStateOf(prefs.brightnessSteps) }
    var readout by remember { mutableStateOf(prefs.showReadout) }

    SectionScaffold(
        title = "Brightness",
        onBack = onBack,
        guide = "Turning the wheel changes screen brightness in most apps. This is how big each " +
            "step is, and whether the level shows on screen as you turn.",
    ) {
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
            sub = if (canOverlay) "a bar at the bottom as you turn" else "needs the overlay grant",
            dim = !canOverlay,
            onClick = {
                readout = !readout
                prefs.showReadout = readout
            },
        )
        MenuRow(
            label = "Current level",
            detail = "${brightness.percent() ?: 0}% of ${brightness.max()}",
            sub = "worked out from the platform's own two copies of the value",
            dim = true,
        )
    }
}

/** The volume readout LightOS has no screen for. */
@Composable
fun VolumeScreen(onWifiRinger: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    val canOverlay = Grants.canDrawOverlays(context)

    var volumeHud by remember { mutableStateOf(prefs.showVolume) }
    var volumePin by remember { mutableStateOf(prefs.volumePin) }
    var callBoost by remember { mutableStateOf(prefs.callBoost) }

    SectionScaffold(
        title = "Volume",
        onBack = onBack,
        guide = "LightOS shows nothing when you change the volume — the level moves silently. " +
            "This draws a strip at the top of the screen so you can see it, and optionally lets " +
            "you pick which stream the keys move. Both are off until you ask: the strip appears " +
            "over whatever you are looking at.",
    ) {
        MenuRow(
            label = "Show the level",
            detail = if (volumeHud) "ON" else "OFF",
            sub = if (canOverlay) "a strip at the top when a volume key is pressed" else "needs the overlay grant",
            dim = !canOverlay,
            onClick = {
                volumeHud = !volumeHud
                prefs.showVolume = volumeHud
            },
        )
        MenuRow(
            label = "Tap to pick a stream",
            detail = if (volumePin) "ON" else "OFF",
            sub = "tap the strip for a list of every volume this phone has — media, ring, " +
                "notifications, alarm — and the keys move the one you choose. The only setting " +
                "here that lets a volume key be consumed, which is why it is off by default.",
            subMaxLines = 4,
            onClick = {
                volumePin = !volumePin
                prefs.volumePin = volumePin
            },
        )
        MenuRow(
            label = "Loud speakerphone",
            detail = if (callBoost) "ON" else "OFF",
            sub = if (callBoost) {
                "a call on the phone's own speaker starts at maximum. Turn it down mid-call and " +
                    "it stays down; the earpiece level is never touched."
            } else {
                "off. The call speaker stays wherever it was last left, which on LightOS is a " +
                    "number nothing shows you."
            },
            onClick = {
                callBoost = !callBoost
                prefs.callBoost = callBoost
            },
        )
        MenuRow(
            label = "Maximum is the ceiling",
            sub = "call audio does not pass through this phone's app mixer, so no app can add a " +
                "decibel past the top of the scale",
            dim = true,
        )
        MenuRow(
            label = "Not over LightOS",
            sub = "its own screens have their own volume control — except during a call, where " +
                "the dialer has none and the strip is the only feedback there is",
            dim = true,
        )
        Rule()

        SectionLabel("THE RINGER")
        MenuRow(
            label = "Ringer by Wi-Fi",
            detail = if (prefs.wifiRingerOn) "ON" else "\u203a",
            sub = "silent on some networks, loud on others — the office and the flat are " +
                "different places and this phone knows which one it is on",
            onClick = onWifiRinger,
        )
    }
}

/** Faults, the last crash, and the key log — the screen for a morning something is wrong. */
@Composable
fun DiagnosticsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }

    var fault by remember { mutableStateOf(prefs.fault()) }
    var crash by remember { mutableStateOf(CrashLog.read(context)) }
    var homeFault by remember { mutableStateOf(prefs.homeFault()) }
    var lockFault by remember { mutableStateOf(prefs.lockFault()) }
    var logKeys by remember { mutableStateOf(prefs.logKeys) }
    var standDown by remember { mutableStateOf(prefs.standDownOnMash) }
    var keyLog by remember { mutableStateOf(prefs.keyLog()) }
    var autoSend by remember { mutableStateOf(prefs.autoSendFailures) }

    SectionScaffold(
        title = "Diagnostics",
        onBack = onBack,
        guide = "A key filter can't explain itself any other way: there is no computer attached " +
            "when a button misbehaves, and the event is over before you can look. Everything the " +
            "app noticed about itself is here.",
    ) {
        SectionLabel("WHEN SOMETHING GOES WRONG")
        MenuRow(
            label = "Send failures without asking",
            detail = if (autoSend) "ON" else "OFF",
            sub = if (autoSend) {
                "a failure the app noticed itself is filed on light-reports as it happens, with " +
                    "what it tried and what came back. Nothing is asked, because there is nothing " +
                    "to ask: the app has already written the description. Shaking still opens the " +
                    "sheet, since that one needs your words."
            } else {
                "off: a failure offers to be reported instead. The offer appears while you are in " +
                    "the middle of the thing that failed, and one tap outside it throws away the " +
                    "only account of what went wrong anybody will ever have."
            },
            onClick = {
                autoSend = !autoSend
                prefs.autoSendFailures = autoSend
            },
        )
        Rule()
        MenuRow(
            label = "Allowed to stand itself down",
            detail = if (standDown) "ON" else "OFF",
            sub = if (standDown) {
                "four presses of one binding in four seconds, or three throws in a minute, and " +
                    "the filter goes quiet until you open this app. That takes the wheel and " +
                    "every button with it — including on a phone that was working fine and just " +
                    "had its flashlight pressed four times."
            } else {
                "off: a run of presses is logged and obeyed, and a run of faults clears itself " +
                    "after a quiet minute. The buttons keep working. The master switch above is " +
                    "still yours either way."
            },
            onClick = {
                standDown = !standDown
                prefs.standDownOnMash = standDown
            },
        )
        Rule()

        fault?.let { text ->
            SectionLabel("FAULT")
            MenuRow(
                label = if (prefs.faultDormant()) "Key service went quiet" else "Last fault",
                detail = "CLEAR",
                sub = "$text — opening this app already re-armed it. Tap to forget.",
                onClick = {
                    prefs.clearFault()
                    fault = null
                },
            )
        }
        homeFault?.let { reason ->
            SectionLabel("HOME BUTTON")
            MenuRow(
                label = "Takeover disarmed itself",
                detail = "RETRY",
                sub = "$reason. Tap to arm it again.",
                onClick = {
                    prefs.armHome()
                    homeFault = null
                },
            )
        }
        lockFault?.let { reason ->
            SectionLabel("LOCK FACE")
            MenuRow(
                label = "Lock face switched itself off",
                detail = "RE-ARM",
                sub = "$reason. Tap to turn it back on.",
                onClick = {
                    prefs.armLock()
                    lockFault = null
                },
            )
        }
        crash?.let { text ->
            SectionLabel("LAST CRASH")
            var full by remember { mutableStateOf(false) }
            MenuRow(
                label = text.lineSequence().firstOrNull().orEmpty(),
                detail = if (full) "×" else "OPEN",
                sub = if (full) text else "tap to read the stack",
                onClick = { full = !full },
            )
            if (full) {
                MenuRow(
                    label = "Forget it",
                    detail = "CLEAR",
                    onClick = {
                        CrashLog.clear(context)
                        crash = null
                    },
                )
            }
        }

        SectionLabel("KEY LOG")
        MenuRow(
            label = "Keep a log",
            detail = if (logKeys) "ON" else "OFF",
            sub = "the last dozen decisions: what arrived, what was in front, what was done",
            onClick = {
                logKeys = !logKeys
                prefs.logKeys = logKeys
                if (!logKeys) {
                    prefs.clearLog()
                    keyLog = emptyList()
                }
            },
        )
        if (keyLog.isNotEmpty()) {
            keyLog.forEach { line -> MenuRow(label = line, dim = true) }
            MenuRow(
                label = "Clear",
                detail = "×",
                onClick = {
                    prefs.clearLog()
                    keyLog = emptyList()
                },
            )
        } else if (logKeys) {
            MenuRow(
                label = "Nothing yet",
                sub = "press a button, then come back — reopening the screen re-reads it",
                dim = true,
            )
        }
    }
}
