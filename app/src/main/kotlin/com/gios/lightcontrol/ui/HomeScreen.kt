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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.gios.lightcontrol.Prefs
import com.gios.lightcontrol.keys.Grants
import com.gios.lightcontrol.notify.AlertHandoff
import com.gios.lightcontrol.report.CrashLog

/**
 * The top of the app: a master switch, a one-line health line, and a door to each section.
 *
 * This replaced a single scroll that had grown to every setting the app has. The split is not
 * cosmetic — each section screen now has room to say what it does, which the one long list never
 * did, and the things you reach for in a hurry (the off switch, whether the grants are actually
 * in place) are at the top instead of buried.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onGuide: () -> Unit,
    onButtons: () -> Unit,
    onWheel: () -> Unit,
    onEdges: () -> Unit,
    onBrightness: () -> Unit,
    onColor: () -> Unit,
    onLock: () -> Unit,
    onNotifications: () -> Unit,
    onVolume: () -> Unit,
    onAdb: () -> Unit,
    onWifiLogin: () -> Unit,
    onHotspot: () -> Unit,
    onSetup: () -> Unit,
    onDiagnostics: () -> Unit,
) {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }

    val serviceOn = Grants.serviceEnabled(context)
    val granted = listOf(
        serviceOn,
        Grants.canWriteSettings(context),
        Grants.canDrawOverlays(context),
        Grants.canWriteSecureSettings(context),
    )
    val ok = granted.count { it }
    val total = granted.size

    var enabled by remember { mutableStateOf(prefs.enabled) }
    val fault = remember { prefs.fault() }
    val crash = remember { CrashLog.read(context) }
    val homeFault = remember { prefs.homeFault() }
    val lockFault = remember { prefs.lockFault() }
    val anyTrouble = fault != null || crash != null || homeFault != null || lockFault != null

    val scroll = rememberScrollState()
    WheelScroll(scroll)

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                colors = barColors(),
                title = { Text("BrightControl", style = MaterialTheme.typography.titleMedium) },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(scroll)) {

            // The way out, first and always. A key filter that has gone wrong is the one thing you
            // cannot fix from inside a menu you can no longer drive — so the off switch is never
            // more than one tap from the top.
            MenuRow(
                label = if (enabled) "Everything on" else "EVERYTHING OFF",
                detail = if (enabled) "ON" else "OFF",
                sub = if (enabled) {
                    "tap to stop the app touching any key at all"
                } else {
                    "no key is intercepted anywhere — every control behaves as if this app were " +
                        "not installed. Tap to switch it back on."
                },
                onClick = {
                    enabled = !enabled
                    prefs.enabled = enabled
                },
            )
            Rule()

            // The health line. Not the grant detail — that lives in Setup — just enough to know
            // whether to go look.
            MenuRow(
                label = "Setup & guide",
                detail = if (!serviceOn) "SERVICE OFF" else "$ok/$total",
                sub = if (!serviceOn) {
                    "the key service is off, so nothing works yet — tap to set it up"
                } else if (ok < total) {
                    "some grants are missing; tap to see which and fix them"
                } else {
                    "everything granted. Tap for the walkthrough and the adb lines."
                },
                dim = serviceOn && ok == total,
                onClick = onSetup,
            )
            if (anyTrouble) {
                MenuRow(
                    label = "Something needs a look",
                    detail = "!",
                    sub = "a fault, a crash or a disarmed feature — tap for diagnostics",
                    onClick = onDiagnostics,
                )
            }
            Rule()

            SectionLabel("CONTROLS")
            MenuRow(
                label = "Buttons",
                sub = "what the wheel click, the camera key and the home button do",
                detail = "›",
                onClick = onButtons,
            )
            MenuRow(
                label = "The wheel",
                sub = "what a turn means, per-app, and how far a notch scrolls",
                detail = "›",
                onClick = onWheel,
            )
            MenuRow(
                label = "Edge gestures",
                detail = when {
                    prefs.leftEdgeOn && prefs.rightEdgeOn -> "BOTH"
                    prefs.leftEdgeOn -> "LEFT"
                    prefs.rightEdgeOn -> "RIGHT"
                    else -> "›"
                },
                sub = "swipe in from an edge — short and long, bound like a button",
                onClick = onEdges,
            )
            MenuRow(
                label = "Brightness",
                sub = "range of a turn, and the on-screen level",
                detail = "›",
                onClick = onBrightness,
            )
            MenuRow(
                label = "Volume",
                sub = "the level readout LightOS has no screen for",
                detail = "›",
                onClick = onVolume,
            )
            Rule()

            SectionLabel("SCREEN")
            MenuRow(
                label = "Color",
                detail = if (prefs.colorAutoSwitch) "AUTO" else "›",
                sub = "force color or mono per app, on a phone that is mono everywhere",
                onClick = onColor,
            )
            MenuRow(
                label = "Lock screen",
                detail = if (prefs.lockScreen) "ON" else "›",
                sub = "a Light-style face over the stock lock screen",
                onClick = onLock,
            )
            Rule()

            SectionLabel("NOTIFICATIONS")
            MenuRow(
                label = "Banners",
                // Owned, not merely switched on. Saying ON here while the section behind it says
                // NO GRANT is two screens disagreeing about the same setting.
                detail = if (AlertHandoff.owned(context)) "ON" else "\u203a",
                sub = "a box over whatever the phone is showing, for any app -- and the one list " +
                    "of apps this phone never shows you",
                onClick = onNotifications,
            )
            Rule()

            SectionLabel("SYSTEM")
            MenuRow(
                label = "Wi-Fi login",
                sub = "IN DEVELOPMENT · sign in to hotel and café Wi-Fi that wants a webpage first",
                detail = "›",
                onClick = onWifiLogin,
            )
            MenuRow(
                label = "Hotspot",
                detail = "›",
                sub = "IN DEVELOPMENT · raise it when a paired iPad is near, over this app's own shell",
                onClick = onHotspot,
            )
            MenuRow(
                label = "ADB & grants",
                sub = "let the phone grant itself everything, over its own wireless debugging",
                detail = "›",
                onClick = onAdb,
            )
            MenuRow(
                label = "Diagnostics",
                sub = "faults, the last crash, and the key log",
                detail = "›",
                onClick = onDiagnostics,
            )
            Gap(20)
        }
    }
}
