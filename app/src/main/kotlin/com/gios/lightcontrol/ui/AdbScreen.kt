package com.gios.lightcontrol.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gios.lightcontrol.Prefs
import android.content.Intent
import android.provider.Settings
import com.gios.lightcontrol.adb.AdbManager
import com.gios.lightcontrol.adb.AdbPairSession
import com.gios.lightcontrol.adb.SelfGrant
import com.gios.lightcontrol.ui.theme.Dim
import com.gios.lightcontrol.ui.theme.Faint
import com.gios.lightcontrol.ui.theme.RuleGray
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The phone granting itself everything, over its own wireless debugging — so a reinstall no
 * longer means finding a computer.
 *
 * Pairing discovers the pairing port over mDNS, the way libadb's own reference app does, so the
 * port the dialog shows — which changes every time — never has to be typed.
 *
 * The code does not have to be typed either, and that is the whole design of this screen. An
 * earlier version told users to leave the pairing dialog with **Home, not Back**, on the theory
 * that Home kept the session alive. It does not. `AdbWirelessDialog.onStop()` calls `dismiss()`
 * and then `onDismiss()`, which calls `disablePairing()`, and `WirelessDebuggingFragment
 * .onPause()` removes the dialog for good measure. The session dies when Settings stops being
 * the foreground app, whichever button got it there, so no route that carries the six digits
 * back to this app can work.
 *
 * So [AdbPairSession] and [com.gios.lightcontrol.adb.AdbPairReader] read the digits off the
 * dialog while it is still on screen. The manual field below is kept only as a last resort.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdbScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    val scope = rememberCoroutineScope()

    var pairCode by remember { mutableStateOf("") }
    var connectPort by remember { mutableStateOf(prefs.adbPort) }
    var command by remember { mutableStateOf("") }

    var busy by remember { mutableStateOf(false) }
    var connected by remember { mutableStateOf(false) }
    var log by remember { mutableStateOf(listOf<String>()) }
    fun say(line: String) { log = (log + line).takeLast(80) }

    fun run(label: String, block: suspend () -> String) {
        if (busy) return
        busy = true
        say("> $label")
        scope.launch {
            val (msg, conn) = withContext(Dispatchers.IO) {
                val m = runCatching { block() }
                    .getOrElse { "error: ${it.message ?: it.javaClass.simpleName}" }
                val c = runCatching { AdbManager.getInstance(context).connected() }.getOrDefault(false)
                m to c
            }
            if (msg.isNotBlank()) say(msg)
            connected = conn
            busy = false
        }
    }

    val scroll = rememberScrollState()
    WheelScroll(scroll)

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                colors = barColors(),
                title = { Text("ADB & grants", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().imePadding().verticalScroll(scroll)) {

            MenuRow(
                label = if (connected) "Connected" else "Not connected",
                detail = if (connected) "OK" else "—",
                sub = if (connected) {
                    "the phone is talking to its own daemon — grants below are unlocked"
                } else {
                    "do the steps below once; the grant buttons stay locked until connected"
                },
                dim = !connected,
            )
            Rule()

            // The full walkthrough, on the page, because this is a strange thing to ask a phone to
            // do and every step has a way to go wrong.
            SectionLabel("HOW THIS WORKS")
            Guide(
                "This connects the app to the phone's own Android debugging service and runs the " +
                    "setup commands for you — no computer. You do it once per install.",
            )

            SectionLabel("STEP 1 — TURN ON WIRELESS DEBUGGING")
            Step("1", "Open the phone's Settings → About phone. Tap Build number seven times to " +
                "unlock Developer options, if you haven't already.")
            Step("2", "Settings → System → Developer options → Wireless debugging → turn it ON. " +
                "Keep Wi-Fi connected.")

            SectionLabel("STEP 2 — PAIR (ONCE)")
            Guide(
                "The phone's pairing box closes the moment you leave Settings — leaving with " +
                    "Home kills it exactly as dead as Back does. So nothing you carry back here " +
                    "by hand can work. Instead the app reads the six digits off the box itself, " +
                    "while it is still open. You type nothing.",
            )

            // Polled rather than read once, so coming back from the Accessibility screen with it
            // switched on updates this page without a manual refresh. Stops as soon as it is on.
            var readerOn by remember { mutableStateOf(AdbPairSession.readerEnabled(context)) }
            LaunchedEffect(readerOn) {
                while (!readerOn) {
                    delay(1_000)
                    readerOn = AdbPairSession.readerEnabled(context)
                }
            }

            if (!readerOn) {
                Step("3", "One-time: turn on \"BrightControl pairing helper\" in Settings → " +
                    "Accessibility. It can only ever see the Settings app, and only for ninety " +
                    "seconds after you tap the button below. Turn it off when setup is done.")
                BigButton(
                    label = "OPEN ACCESSIBILITY SETTINGS",
                    filled = false,
                    enabled = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                ) {
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                }
            }

            Step(if (readerOn) "3" else "4", "Tap below. Settings opens; go to Wireless debugging " +
                "→ \"Pair device with pairing code\" and just leave the box on screen. The app " +
                "takes it from there — pair, connect, and grants, all of it.")

            BigButton(
                label = when (AdbPairSession.phase) {
                    AdbPairSession.Phase.Waiting -> "WAITING FOR THE PAIRING BOX…"
                    AdbPairSession.Phase.Pairing -> "PAIRING…"
                    AdbPairSession.Phase.Granting -> "GRANTING…"
                    else -> "PAIR AUTOMATICALLY"
                },
                filled = true,
                enabled = readerOn && AdbPairSession.phase !in setOf(
                    AdbPairSession.Phase.Waiting,
                    AdbPairSession.Phase.Pairing,
                    AdbPairSession.Phase.Granting,
                ),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            ) {
                AdbPairSession.arm()
                say("armed — open the pairing box and leave it up")
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }

            if (AdbPairSession.message.isNotBlank()) {
                Guide(AdbPairSession.message)
            }
            AdbPairSession.grants.forEach { Guide(it) }

            // Light ships its own build of Settings, and whether that build still renders the
            // code as readable text is the one thing that cannot be checked from a desk. If the
            // read misses, show what it did see rather than leaving the user guessing.
            AdbPairSession.unreadable?.let { seen ->
                Guide(
                    "Found the pairing box but no six-digit code in it. This is what the screen " +
                        "read as — send this along and it can be fixed:",
                )
                Guide(seen)
            }

            if (AdbPairSession.phase == AdbPairSession.Phase.Done) {
                BigButton(
                    label = "TURN THE PAIRING HELPER OFF",
                    filled = false,
                    enabled = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                ) {
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                }
            }

            Rule()
            Guide("Or type the code yourself. This only works while the pairing box is still on " +
                "screen, so it means a second device or a very fast thumb — the automatic route " +
                "above exists precisely because this one mostly cannot be done:")

            AdbField("Six-digit pairing code", pairCode, KeyboardType.Number) { pairCode = it }
            BigButton(
                label = if (busy) "PAIRING… (up to 60s)" else "PAIR",
                filled = true,
                enabled = !busy && pairCode.trim().length == 6,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            ) {
                run("pair with code") {
                    val adb = AdbManager.getInstance(context)
                    val ok = adb.pairViaMdns(context, pairCode.trim(), 60_000L)
                    if (!ok) {
                        "no pairing service found, or the code was wrong. Make sure the pairing " +
                            "box is still open (left with Home, not Back), then try again — the " +
                            "code is fresh each time the box opens."
                    } else {
                        // Pairing does not connect on its own; go straight on to it.
                        val c = runCatching { adb.connectAuto(context, 15_000L) }.getOrDefault(false)
                        if (c) "paired and connected" else "paired — now tap CONNECT below"
                    }
                }
            }
            Rule()

            SectionLabel("STEP 3 — CONNECT")
            Step("6", "Tap CONNECT. It finds the running daemon over Wi-Fi. If it can't, read the " +
                "connect port from the top of the Wireless debugging screen (the number after the " +
                "colon), type it below, and connect.")
            BigButton(
                label = if (busy) "…" else "CONNECT",
                filled = true,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            ) {
                run("connect (auto)") {
                    AdbManager.reset()
                    val ok = AdbManager.getInstance(context).connectAuto(context, 15_000L)
                    if (ok) "connected" else "not found automatically — type the connect port below"
                }
            }
            AdbField("Connect port (fallback)", connectPort, KeyboardType.Number) { connectPort = it }
            BigButton(
                label = "CONNECT ON PORT",
                enabled = !busy && connectPort.isNotBlank(),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            ) {
                val port = connectPort.trim().toIntOrNull()
                if (port == null) { say("connect port is not a number"); return@BigButton }
                prefs.adbPort = connectPort.trim()
                run("connect $port") {
                    AdbManager.reset()
                    val ok = AdbManager.getInstance(context).connectPort(context, port)
                    if (ok) "connected" else "refused — check the port, and that pairing succeeded"
                }
            }
            Rule()

            SectionLabel("STEP 4 — GRANT EVERYTHING")
            Step("7", "With the status at the top reading Connected, tap GRANT ALL. It enables the " +
                "key service and every permission, then reopen the app so they're picked up.")
            if (!connected) {
                MenuRow(label = "Connect first", sub = "GRANT ALL unlocks once connected", dim = true)
            }
            BigButton(
                label = "GRANT ALL",
                filled = true,
                enabled = !busy && connected,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            ) {
                run("grant all") {
                    val adb = AdbManager.getInstance(context)
                    val lines = StringBuilder()
                    SelfGrant.steps.forEach { step ->
                        val out = runCatching { adb.runCommand(step.command) }
                            .getOrElse { "failed: ${it.message}" }
                        lines.append(step.label).append(" — ")
                            .append(if (out.isBlank()) "ok" else out).append('\n')
                    }
                    lines.append("done — reopen the app so the new grants are read")
                    lines.toString().trim()
                }
            }
            Rule()

            SectionLabel("NFC — FOR CHIP MODS")
            Guide(
                "Turns the phone's NFC radio on or off over adb — handy when working with NFC " +
                    "chip mods and implants that the normal toggle fights with. Runs " +
                    "\"svc nfc enable/disable\"; needs a connection.",
            )
            androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                BigButton(
                    label = "NFC ON",
                    enabled = !busy && connected,
                    modifier = Modifier.weight(1f).padding(end = 4.dp),
                ) {
                    run("nfc enable") {
                        AdbManager.getInstance(context).runCommand("svc nfc enable")
                        "NFC enabled"
                    }
                }
                BigButton(
                    label = "NFC OFF",
                    enabled = !busy && connected,
                    modifier = Modifier.weight(1f).padding(start = 4.dp),
                ) {
                    run("nfc disable") {
                        AdbManager.getInstance(context).runCommand("svc nfc disable")
                        "NFC disabled"
                    }
                }
            }
            Rule()

            SectionLabel("SHIZUKU")
            Guide(
                "Shizuku hands a shell UID to apps that need one — BrightHotspot uses it to raise " +
                    "the hotspot, which is a system-only call since Android 11. Its own way in is " +
                    "the wireless-debugging pairing flow, and Android tears that down on every " +
                    "reboot, so it is a dance you repeat rather than finish.\n\nThis app already " +
                    "has the shell. Same privilege, one tap, and Shizuku still asks you app by " +
                    "app in its own screen afterwards.",
            )
            BigButton(
                label = "START SHIZUKU",
                enabled = !busy && connected,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            ) {
                run("start shizuku") {
                    val out = AdbManager.getInstance(context)
                        .runCommand("sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh")
                    // Shizuku's script says nothing on success and a good deal on failure, and
                    // the commonest failure is the one worth naming: it is not installed, so the
                    // path does not exist.
                    when {
                        out.contains("No such file", ignoreCase = true) ->
                            "Shizuku is not installed — get it from shizuku.rikka.app first"
                        out.isBlank() -> "Started. Grant the app you want inside Shizuku."
                        else -> out.take(400)
                    }
                }
            }
            Rule()

            SectionLabel("ADVANCED — RUN A COMMAND")
            Guide(
                "Runs against the phone's own shell (no \"adb shell\" prefix). Whatever the daemon " +
                    "can do, this can — be as careful as you would be at a terminal.",
            )
            AdbField("shell command", command, KeyboardType.Text) { command = it }
            BigButton(
                label = "RUN",
                enabled = !busy && connected && command.isNotBlank(),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            ) {
                val c = command.trim()
                run(c) { AdbManager.getInstance(context).runCommand(c).ifBlank { "(no output)" } }
            }
            Rule()

            SectionLabel("LOG")
            if (log.isEmpty()) {
                MenuRow(label = "Nothing yet", dim = true)
            } else {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    log.forEach { line ->
                        Text(
                            line,
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                            color = if (line.startsWith(">")) Color.White else Dim,
                        )
                    }
                }
                MenuRow(label = "Clear log", detail = "×", onClick = { log = emptyList() })
            }
            Gap(48)
        }
    }
}

@Composable
private fun Guide(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = Dim,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/** A numbered step: the number in white, the instruction in dim, so the sequence reads at a glance. */
@Composable
private fun Step(number: String, text: String) {
    Row2(number, text)
}

@Composable
private fun Row2(number: String, text: String) {
    androidx.compose.foundation.layout.Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Text(
            "$number.",
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
            color = Color.White,
            modifier = Modifier.padding(end = 10.dp),
        )
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Dim)
    }
}

/** A bordered single-line input in the mono idiom — BasicTextField so no Material chrome leaks in. */
@Composable
private fun AdbField(
    label: String,
    value: String,
    keyboard: KeyboardType,
    onChange: (String) -> Unit,
) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Faint)
        Gap(4)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = Color.White),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(Color.White),
            keyboardOptions = KeyboardOptions(keyboardType = keyboard),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, RuleGray)
                .background(Color.Black)
                .padding(horizontal = 12.dp, vertical = 12.dp),
        )
    }
}
