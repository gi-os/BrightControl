package com.gios.lightcontrol.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gios.lightcontrol.Prefs
import com.gios.lightcontrol.adb.AdbManager
import com.gios.lightcontrol.adb.SelfGrant
import com.gios.lightcontrol.ui.theme.Dim
import com.gios.lightcontrol.ui.theme.Faint
import com.gios.lightcontrol.ui.theme.RuleGrey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.rememberCoroutineScope
import kotlinx.coroutines.withContext

/**
 * The phone granting itself everything, over its own wireless debugging — so a reinstall no
 * longer means finding a computer to re-enable the service and re-grant the appops.
 *
 * How it works, said plainly on screen because it is a strange thing to ask for: Android's
 * developer options can run an ADB daemon on the device itself. This app connects to that daemon
 * over loopback with a certificate it made once, and from there the grant commands are ordinary
 * shell lines. Pairing is a one-time handshake (the phone shows a six-digit code); after it, a
 * connect and a "grant everything" is the whole job.
 *
 * All the network calls block, so every one runs on [Dispatchers.IO] and only the log comes back
 * to the screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdbScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    val scope = rememberCoroutineScope()

    var pairPort by remember { mutableStateOf("") }
    var pairCode by remember { mutableStateOf("") }
    var connectPort by remember { mutableStateOf(prefs.adbPort) }
    var command by remember { mutableStateOf("") }

    var busy by remember { mutableStateOf(false) }
    var log by remember { mutableStateOf(listOf<String>()) }
    fun say(line: String) { log = (log + line).takeLast(60) }

    // Every action funnels through here: refuse to overlap, flip busy, run off the main thread,
    // and turn any thrown transport error into a log line rather than a crash.
    fun run(label: String, block: suspend () -> String) {
        if (busy) return
        busy = true
        say("> $label")
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { block() }.getOrElse { "error: ${it.message ?: it.javaClass.simpleName}" }
            }
            if (result.isNotBlank()) say(result)
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
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(scroll)) {
            GuideText(
                "One-time setup: on the phone, open Settings → Developer options → Wireless " +
                    "debugging, turn it on, then \"Pair device with pairing code\". Type that " +
                    "code and its port below and pair. After that, use the connect port shown on " +
                    "the Wireless debugging screen, connect, and grant everything.",
            )
            Rule()

            SectionLabel("1 · PAIR (ONCE)")
            AdbField("Pairing port", pairPort, KeyboardType.Number) { pairPort = it }
            AdbField("Pairing code", pairCode, KeyboardType.Number) { pairCode = it }
            BigButton(
                label = if (busy) "…" else "PAIR",
                filled = false,
                enabled = !busy && pairPort.isNotBlank() && pairCode.isNotBlank(),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            ) {
                val port = pairPort.trim().toIntOrNull()
                if (port == null) { say("pairing port is not a number"); return@BigButton }
                run("pair on $port") {
                    val ok = AdbManager.getInstance(context).pair(prefs.adbHost, port, pairCode.trim())
                    if (ok) "paired — now connect below" else "pairing failed"
                }
            }
            Rule()

            SectionLabel("2 · CONNECT")
            AdbField("Connect port", connectPort, KeyboardType.Number) { connectPort = it }
            BigButton(
                label = if (busy) "…" else "CONNECT",
                enabled = !busy && connectPort.isNotBlank(),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            ) {
                val port = connectPort.trim().toIntOrNull()
                if (port == null) { say("connect port is not a number"); return@BigButton }
                prefs.adbPort = connectPort.trim()
                run("connect $port") {
                    val ok = AdbManager.getInstance(context).connect(prefs.adbHost, port)
                    if (ok) "connected" else "connect failed — check the port and that pairing succeeded"
                }
            }
            BigButton(
                label = "AUTO-FIND & CONNECT",
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            ) {
                run("auto-connect") {
                    val ok = AdbManager.getInstance(context).autoConnect(context, 15_000L)
                    if (ok) "connected (auto-discovered)" else "no daemon found — enter the port by hand"
                }
            }
            Rule()

            SectionLabel("3 · GRANT EVERYTHING")
            BigButton(
                label = "GRANT ALL",
                filled = true,
                enabled = !busy,
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

            SectionLabel("ADVANCED — RUN A COMMAND")
            GuideText(
                "Runs against the phone's own shell (no \"adb shell\" prefix). Whatever the daemon " +
                    "can do, this can — so be as careful as you would be at a terminal.",
            )
            AdbField("shell command", command, KeyboardType.Text) { command = it }
            BigButton(
                label = "RUN",
                enabled = !busy && command.isNotBlank(),
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
            Gap(28)
        }
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
                .border(1.dp, RuleGrey)
                .background(Color.Black)
                .padding(horizontal = 12.dp, vertical = 12.dp),
        )
    }
}
