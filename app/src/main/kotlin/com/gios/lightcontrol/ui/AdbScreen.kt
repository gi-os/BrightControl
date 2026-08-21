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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import kotlinx.coroutines.withContext

/**
 * The phone granting itself everything, over its own wireless debugging — so a reinstall no
 * longer means finding a computer to re-enable the service and re-grant the appops.
 *
 * The reliable path is the manual one: pair once with the code the phone shows, then connect on
 * the port the Wireless debugging screen shows. Auto-find is a convenience that leans on mDNS,
 * which only works on Wi-Fi and only while the daemon is advertising — so it is offered second,
 * not first.
 *
 * Every network call blocks and runs on [Dispatchers.IO]; after each one the real connection
 * state is read back so the grant steps stay locked until a connection actually exists.
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
    var connected by remember { mutableStateOf(false) }
    var log by remember { mutableStateOf(listOf<String>()) }
    fun say(line: String) { log = (log + line).takeLast(80) }

    // Every action funnels through here: refuse to overlap, flip busy, run off the main thread,
    // turn any thrown transport error into a log line rather than a crash, and read the real
    // connection state back so the UI can gate on it.
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
        // imePadding keeps the last fields above the keyboard on the phones that run edge-to-edge;
        // adjustResize in the manifest handles the rest. Either way the fields scroll into reach.
        Column(Modifier.padding(pad).fillMaxSize().imePadding().verticalScroll(scroll)) {
            MenuRow(
                label = if (connected) "Connected" else "Not connected",
                detail = if (connected) "OK" else "—",
                sub = if (connected) {
                    "the phone is talking to its own daemon; grants below are unlocked"
                } else {
                    "pair and connect first — the grant buttons stay locked until then"
                },
                dim = !connected,
            )
            Rule()

            GuideText(
                "One-time setup, on the phone: Settings → System → Developer options → Wireless " +
                    "debugging → turn on. Tap \"Pair device with pairing code\" for the pairing " +
                    "port and code; the main Wireless debugging screen shows the connect port " +
                    "(the number after the colon). Keep Wi-Fi on.",
            )
            Rule()

            SectionLabel("1 · PAIR (ONCE PER INSTALL)")
            AdbField("Pairing port", pairPort, KeyboardType.Number) { pairPort = it }
            AdbField("Pairing code", pairCode, KeyboardType.Number) { pairCode = it }
            BigButton(
                label = if (busy) "…" else "PAIR",
                enabled = !busy && pairPort.isNotBlank() && pairCode.isNotBlank(),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            ) {
                val port = pairPort.trim().toIntOrNull()
                if (port == null) { say("pairing port is not a number"); return@BigButton }
                run("pair on $port") {
                    val ok = AdbManager.getInstance(context).pair("127.0.0.1", port, pairCode.trim())
                    if (ok) "paired — now connect below" else "pairing rejected — re-check the code and port"
                }
            }
            Rule()

            SectionLabel("2 · CONNECT")
            AdbField("Connect port", connectPort, KeyboardType.Number) { connectPort = it }
            BigButton(
                label = if (busy) "…" else "CONNECT",
                filled = true,
                enabled = !busy && connectPort.isNotBlank(),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            ) {
                val port = connectPort.trim().toIntOrNull()
                if (port == null) { say("connect port is not a number"); return@BigButton }
                prefs.adbPort = connectPort.trim()
                run("connect $port") {
                    // A stale half-open manager fails every retry; start clean each connect.
                    AdbManager.reset()
                    val ok = AdbManager.getInstance(context).connect("127.0.0.1", port)
                    if (ok) "connected" else "connect refused — is the connect port right, and did pairing succeed?"
                }
            }
            MenuRow(
                label = "Auto-find (best effort)",
                detail = if (busy) "…" else "TRY",
                sub = "discovers the port over mDNS. Wi-Fi only, and only while the Wireless " +
                    "debugging screen is open. If it finds nothing, type the port above.",
                onClick = {
                    if (!busy) run("auto-find") {
                        AdbManager.reset()
                        val ok = AdbManager.getInstance(context).autoConnect(context, 15_000L)
                        if (ok) "connected (auto-discovered)" else "nothing found — enter the connect port by hand"
                    }
                },
            )
            Rule()

            SectionLabel("3 · GRANT EVERYTHING")
            if (!connected) {
                MenuRow(
                    label = "Connect first",
                    sub = "these run against the connected daemon",
                    dim = true,
                )
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

            SectionLabel("ADVANCED — RUN A COMMAND")
            GuideText(
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
