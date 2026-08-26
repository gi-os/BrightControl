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
import com.gios.lightcontrol.adb.AdbWifi
import com.gios.lightcontrol.adb.GrantCheckRunner
import com.gios.lightcontrol.adb.GrantRun
import com.gios.lightcontrol.adb.Outcome
import com.gios.lightcontrol.adb.SelfGrant
import com.gios.lightcontrol.adb.StepResult
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
fun AdbScreen(
    onBack: () -> Unit,
    /**
     * Called with a request that was set aside because there was no connection, the moment there
     * is one. See [Prefs.holdGrantRequest] — the screen that promised "this request will still be
     * here" is the reason this parameter exists.
     */
    onCarriedRequest: (pkg: String, lines: List<String>, heldMinutes: Long) -> Unit = { _, _, _ -> },
) {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    val scope = rememberCoroutineScope()

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

    // **Ask the phone whether it is connected, rather than waiting to be told.**
    //
    // `connected` was only ever written inside [run], so a screen nobody had pressed anything on
    // sat there saying "Not connected" with a live socket underneath it — and every effect keyed on
    // that flag, including the hand-back below, never ran. Asked on arrival, and again the moment
    // the automatic pairing reports Done, which is the other time it changes without a press.
    LaunchedEffect(Unit) {
        // A phase left over from an attempt that died with its process disables the PAIR button
        // forever. Nothing that was genuinely running survives to here.
        AdbPairSession.releaseStalePhase()
        val live = withContext(Dispatchers.IO) { AdbManager.ensureAlive(context) }
        connected = live
    }
    LaunchedEffect(AdbPairSession.phase) {
        if (AdbPairSession.phase == AdbPairSession.Phase.Done) {
            connected = withContext(Dispatchers.IO) { AdbManager.ensureAlive(context) }
        }
    }

    // The request that was set aside, if there is one. Read on every recomposition rather than
    // remembered: it is written by another screen and cleared by this one.
    val heldPkg = prefs.pendingGrantPkg
    val heldLines = prefs.pendingGrantLines
    val hasHeld = heldPkg.isNotBlank() && heldLines.isNotEmpty()
    val heldLabel = remember(heldPkg) {
        runCatching {
            context.packageManager.getApplicationLabel(
                context.packageManager.getApplicationInfo(heldPkg, 0),
            ).toString()
        }.getOrDefault(heldPkg)
    }
    fun openApp(pkg: String) {
        runCatching {
            val launch = context.packageManager.getLaunchIntentForPackage(pkg)
                ?: return@runCatching
            context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
    fun goToHeld() {
        val minutes = (System.currentTimeMillis() - prefs.pendingGrantAt) / 60_000L
        prefs.clearGrantRequest()
        onCarriedRequest(heldPkg, heldLines, minutes)
    }

    // **Hand a carried request back the moment there is a connection to run it with.**
    //
    // The request screen says "this request will still be here" and then sends people here, where
    // until now the request was gone and the only button was GRANT ALL — this app setting *itself*
    // up, which is not what anybody was asked to approve. So it is kept in [Prefs] across the trip
    // through Settings (which this process does not reliably survive) and handed straight back.
    //
    // Watched rather than read once, because on the automatic route the connection comes up while
    // this screen is already open — the pairing finishes, `connected` flips, and the right screen
    // to be looking at is no longer this one.
    //
    // Six hours is a cap on resurrection, not an expiry: a request older than that is somebody
    // else's abandoned session, and being yanked to a screen you have forgotten asking for is
    // worse than losing it. Anything inside it is offered, however old, with its age said out
    // loud — an address in it may have moved on, and that is for the person to judge.
    //
    // Keyed on the *pairing* finishing rather than on `connected`, which is a flag that also flips
    // when somebody wanders in with a working connection already. Being moved to another screen on
    // arrival is a screen fighting you; being moved there the instant the setup you just did
    // succeeds is the whole point of doing it. Either way the row below is always there.
    var jumped by remember { mutableStateOf(false) }
    LaunchedEffect(AdbPairSession.phase, connected) {
        if (jumped || !connected) return@LaunchedEffect
        if (AdbPairSession.phase != AdbPairSession.Phase.Done) return@LaunchedEffect
        if (!hasHeld) return@LaunchedEffect
        // Six hours is a cap on resurrection, not an expiry. Anything inside it is offered however
        // old, with its age said out loud on the screen that shows the commands.
        if (System.currentTimeMillis() - prefs.pendingGrantAt > 6 * 60 * 60 * 1000L) {
            prefs.clearGrantRequest()
            return@LaunchedEffect
        }
        jumped = true
        goToHeld()
    }

    // The screen stays on for the whole of setup, not just while a command is in flight: the
    // pairing dialog this reads its code from belongs to Settings, and Settings pausing is what
    // tears a pairing session down — so a screen that sleeps while somebody is finding Wireless
    // debugging has already lost. Lifted when the screen goes.
    KeepAwake(
        busy ||
            AdbPairSession.phase in setOf(
                AdbPairSession.Phase.Waiting,
                AdbPairSession.Phase.Pairing,
                AdbPairSession.Phase.Granting,
            ),
    )

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
                sub = when {
                    connected -> "the phone is talking to its own daemon — grants below are unlocked"
                    AdbWifi.on(context) == false ->
                        "wireless debugging is off, so there is nothing to connect to — the button " +
                            "below switches it on"
                    else -> "do the steps below once; the grant buttons stay locked until connected"
                },
                dim = !connected,
            )
            Rule()

            // **Why you are here, first.** Somebody who arrived from another app's request came
            // to run *that*, and until now the screen answered with its own four-step walkthrough
            // and a GRANT ALL button that sets up this app. The request is offered before the
            // setup, whether or not there is a connection yet — knowing it survived is worth as
            // much as being able to run it.
            if (hasHeld) {
                SectionLabel("$heldLabel IS WAITING")
                MenuRow(
                    label = "Its request",
                    detail = "${heldLines.size} LINE${if (heldLines.size == 1) "" else "S"}",
                    sub = heldLines.joinToString("  ·  ").take(120),
                    onClick = { goToHeld() },
                )
                Rule()
                BigButton(
                    label = "GO TO $heldLabel'S REQUEST".uppercase(),
                    filled = connected,
                    enabled = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                ) { goToHeld() }
                if (!connected) {
                    Guide(
                        "Pair below first — the request screen will not run anything without a " +
                            "connection, and it says so when you get there.",
                    )
                }
                Rule()
            }

            // The full walkthrough, on the page, because this is a strange thing to ask a phone to
            // do and every step has a way to go wrong.
            SectionLabel("HOW THIS WORKS")
            Guide(
                "This connects the app to the phone's own Android debugging service and runs the " +
                    "setup commands for you — no computer. You do it once per install.",
            )

            // **The cause, before the steps.** Wireless debugging goes off on its own — a reboot
            // clears it — and when it does, every screen here reports the consequence instead:
            // "the connection is gone". The state is readable without any permission, so there is
            // no excuse for not saying it, and the switch is writable with a grant this app
            // already has.
            val wifiOn = AdbWifi.on(context)
            val devOn = AdbWifi.developerOptionsOn(context)
            if (wifiOn != true) {
                SectionLabel("WIRELESS DEBUGGING IS " + if (wifiOn == null) "UNKNOWN" else "OFF")
                Guide(
                    if (devOn == false) {
                        "Developer options are off, and wireless debugging lives inside them. Turn " +
                            "those on first — Settings → About phone → tap Build number seven times."
                    } else {
                        "Nothing here can work while the phone's debugging service is not " +
                            "listening. The pairing this app already has is kept — there is simply " +
                            "nothing to connect to. This app can switch it back on itself."
                    },
                )
                BigButton(
                    label = "TURN WIRELESS DEBUGGING ON",
                    filled = true,
                    enabled = !busy && devOn != false,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                ) {
                    run("turn wireless debugging on") {
                        val result = AdbWifi.turnOn(context)
                        if (!result.ok) return@run result.said
                        // Straight on to a connection: the daemon is listening and the pairing is
                        // on disk, so there is nothing left for anybody to do by hand.
                        if (AdbManager.ensureAlive(context)) {
                            "wireless debugging is on, and connected"
                        } else {
                            "wireless debugging is on, but nothing answered yet — pair below"
                        }
                    }
                }
                Rule()
            }

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

            SectionLabel("STEP 3 — BRIGHTCONTROL'S OWN GRANTS")
            Guide(
                "These are the permissions *this* app needs — the key service, the colour writes, " +
                    "the caller name. If you came here from another app's request, that request is " +
                    "waiting and comes back on its own the moment the connection is up; this " +
                    "button is not it.",
            )
            Step(
                if (readerOn) "4" else "5",
                "Tap GRANT ALL, then reopen the app so the new permissions are picked up.",
            )
            if (!connected) {
                MenuRow(
                    label = "Not showing as connected",
                    sub = "GRANT ALL will try to reconnect on its own — the pairing is kept, and " +
                        "only the port goes stale. Tap it and see before setting anything up again",
                    dim = true,
                )
            }
            BigButton(
                label = "GRANT ALL",
                filled = true,
                // Deliberately not gated on `connected`. That flag reads false after any trip
                // through Settings, and disabling the button on it meant the one action that
                // would have fixed the connection was the one the screen refused to offer.
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            ) {
                run("grant all") {
                    // A stop from an earlier run would otherwise cancel this one on its first
                    // command, before anybody had pressed anything.
                    AdbManager.clearAbort()
                    // Reconnect in front of the batch. The listener does not survive leaving
                    // the Wireless-debugging screen, so by the time anyone presses this the
                    // connection from step 3 is usually gone — and the port that replaced it is
                    // discoverable, so there is nothing here to ask the user.
                    // Reconnect if it takes one, but do not refuse to run on the answer. The
                    // probe inside this sends a command of its own, and the first command on a
                    // freshly connected socket is the one that dies — so a working connection
                    // regularly answered "no" here and GRANT ALL reported that nothing ran, on a
                    // phone where NFC ON worked a second later. Each grant below reconnects and
                    // retries by itself, and what the phone says about each one afterwards is the
                    // better answer.
                    val reachable = AdbManager.ensureAlive(context)
                    val adb = AdbManager.getInstance(context)
                    // Each grant is read back off the phone rather than judged by what the
                    // command printed. `shell:` carries no exit status, so a command that failed
                    // quietly used to be reported as ok, and a run where the socket died on the
                    // first line still ended with the word "done" on the last.
                    // **Reported as it goes, and stopped when there is nothing to talk to.**
                    //
                    // Nine grants, and a dead socket costs each one a twelve-second reconnect and
                    // three probes before it fails. Run as a `map` with the result printed at the
                    // end, that is over two minutes of greyed-out buttons and an empty log — which
                    // reads exactly like a button that did nothing, and was reported as one.
                    //
                    // So each line is printed the moment it is known, and the batch gives up the
                    // first time a grant fails *with the connection down*. One dead socket cannot
                    // usefully be reconnected nine times: if the reconnect inside the first failure
                    // could not get one, the eight after it will not either.
                    val results = mutableListOf<StepResult>()
                    for ((index, step) in SelfGrant.steps.withIndex()) {
                        val r = GrantCheckRunner.runAndVerify(
                            context = context,
                            adb = adb,
                            label = step.label,
                            command = step.command,
                            check = step.check,
                        )
                        results += r
                        val state = when (r.outcome) {
                            Outcome.Held -> "OK"
                            Outcome.Failed -> "FAILED"
                            Outcome.Unknown -> "UNKNOWN"
                        }
                        withContext(Dispatchers.Main) {
                            say("${index + 1}/${SelfGrant.steps.size}  ${r.label} — $state")
                        }
                        if (GrantRun.stopRequested || AdbManager.stopping) {
                            withContext(Dispatchers.Main) { say("stopped") }
                            break
                        }
                        val socketGone = r.outcome == Outcome.Failed &&
                            !runCatching { AdbManager.getInstance(context).connected() }
                                .getOrDefault(false)
                        if (socketGone) {
                            withContext(Dispatchers.Main) {
                                say("stopped — the connection is down, so the rest would fail too")
                            }
                            break
                        }
                    }
                    val lines = StringBuilder()
                    if (!reachable) {
                        lines.append(
                            "the connection did not answer a probe first — ran anyway\n",
                        )
                    }
                    results.forEach { r ->
                        val state = when (r.outcome) {
                            Outcome.Held -> "OK"
                            Outcome.Failed -> "FAILED"
                            Outcome.Unknown -> "UNKNOWN"
                        }
                        lines.append(r.label).append(" — ").append(state)
                            .append(" · ").append(r.detail).append('\n')
                    }
                    val failed = results.count { it.outcome == Outcome.Failed }
                    val unknown = results.count { it.outcome == Outcome.Unknown }
                    val ranShort = results.size < SelfGrant.steps.size
                    if (ranShort) {
                        lines.append(
                            "${SelfGrant.steps.size - results.size} were not attempted\n",
                        )
                    }
                    lines.append(
                        when {
                            failed > 0 && !adb.connected() ->
                                "$failed did not run — the connection dropped. The debugging " +
                                    "port changes every time wireless debugging is switched off " +
                                    "and on. Set it up again and run this once more."
                            failed > 0 ->
                                "$failed ran and did not take. The reason is on each line above."
                            unknown > 0 ->
                                "$unknown could not be confirmed either way."
                            else ->
                                "all granted and read back — reopen the app so they are picked up"
                        },
                    )
                    lines.toString().trim()
                }
            }
            Rule()

            if (busy) {
                // The same escape the request screen has. Closing the socket is what ends a
                // command that is blocked in a read; a flag on its own would only be a promise.
                BigButton(
                    label = if (GrantRun.stopRequested) "STOPPING…" else "STOP",
                    filled = false,
                    enabled = !GrantRun.stopRequested,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                ) {
                    GrantRun.stop()
                    say("stopped — the connection was closed; the buttons are yours again")
                    // Released here rather than when the work finishes noticing. A batch with no
                    // connection spends twelve seconds a step inside a lookup that cannot be
                    // interrupted, and waiting for it is what made STOP feel like nothing.
                    busy = false
                }
                Rule()
            }

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
                        AdbManager.runVia(context, "svc nfc enable")
                        "NFC enabled"
                    }
                }
                BigButton(
                    label = "NFC OFF",
                    enabled = !busy && connected,
                    modifier = Modifier.weight(1f).padding(start = 4.dp),
                ) {
                    run("nfc disable") {
                        AdbManager.runVia(context, "svc nfc disable")
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
                    val out = AdbManager.runVia(
                        context,
                        "sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh",
                    )
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
                run(c) { AdbManager.runVia(context, c).ifBlank { "(no output)" } }
            }
            Rule()

            if (hasHeld) {
                // A trip that ends on this screen is a trip that has not ended. The app that sent
                // you here is the one thing certainly worth a tap from the bottom of the page.
                SectionLabel("BACK TO WHERE YOU CAME FROM")
                BigButton(
                    label = "OPEN $heldLabel".uppercase(),
                    filled = false,
                    enabled = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                ) { openApp(heldPkg) }
                Rule()
            }

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
internal fun AdbField(
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
