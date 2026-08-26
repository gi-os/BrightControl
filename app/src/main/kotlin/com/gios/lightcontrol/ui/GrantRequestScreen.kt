package com.gios.lightcontrol.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.unit.dp
import com.gios.lightcontrol.adb.AdbManager
import com.gios.lightcontrol.adb.GrantCheckRunner
import com.gios.lightcontrol.adb.GrantRun
import com.gios.lightcontrol.adb.GrantRequest
import com.gios.lightcontrol.adb.Outcome
import androidx.compose.ui.unit.sp
import com.gios.lightcontrol.ui.theme.Dim
import com.gios.lightcontrol.adb.StepResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Another app's setup, shown before any of it runs.
 *
 * An app in BrightMarket says in its README which ADB grants it needs. BrightMarket sends that
 * list here rather than making the user find a computer. This screen is the consent step, and it
 * is the reason the feature is defensible: the request has already been parsed and rebuilt by
 * [GrantRequest] — nothing arbitrary can reach the shell — but the user still sees the exact
 * commands, named app first, and nothing runs until they say so.
 *
 * A refused request is shown as a refusal, with the line that caused it. That is deliberately
 * loud: a request naming a package other than the sender is the shape an attack takes, and it
 * should look like something went wrong rather than quietly running the safe half.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GrantRequestScreen(
    appLabel: String,
    pkg: String,
    lines: List<String>,
    onBack: () -> Unit,
    onAdb: () -> Unit,
    /**
     * How long this request sat waiting for a connection, if it came back from ADB setup rather
     * than straight from the app. Said out loud rather than used to hide anything: a Bluetooth
     * address in a request goes stale in about fifteen minutes, and only the person holding the
     * ring can judge whether it has.
     */
    heldMinutes: Long? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { com.gios.lightcontrol.Prefs(context) }

    // The APK path is looked up here rather than accepted in the request: "confirm pairing" runs
    // the requesting app's own installed code as the shell, and *own* is a fact only the package
    // manager can establish. A package that is not installed resolves to null, and the request is
    // refused with that as the reason.
    val parsed = remember(pkg, lines) {
        GrantRequest.parse(pkg, lines) { named ->
            runCatching {
                context.packageManager.getApplicationInfo(named, 0).sourceDir
            }.getOrNull()
        }
    }
    var connected by remember { mutableStateOf(AdbManager.getInstance(context).connected()) }
    var busy by remember { mutableStateOf(false) }
    /** The last press could not reach the daemon. Not the same thing as never having had one. */
    var dropped by remember { mutableStateOf(false) }

    // Read from the run itself rather than kept here: a run takes up to three quarters of a minute
    // and outlives this composition, and local state rebuilt is local state gone — which is why a
    // run in progress came back looking like a run that never happened. A stale run belonging to
    // another app's request is dropped rather than shown against this one. See [GrantRun].
    LaunchedEffect(pkg) { GrantRun.clearIfNot(pkg) }
    val saying = GrantRun.saying
    val at = GrantRun.step
    val busyRun = GrantRun.phase == GrantRun.Phase.Running
    val results = GrantRun.results
    val ran = GrantRun.phase == GrantRun.Phase.Done
    /** Either this composition started something, or a run outlived the composition that did. */
    val working = busy || busyRun

    // A second hand, so the screen counts rather than asserts that something is happening. Ticks
    // only while something is running, and stops on its own when the run ends.
    var tick by remember { mutableStateOf(0L) }
    LaunchedEffect(working) {
        while (working) {
            tick = GrantRun.elapsedSeconds
            kotlinx.coroutines.delay(1_000)
        }
    }

    // Success is every step read back and confirmed. Nothing weaker: reaching the end of the list
    // is what the old DONE meant, and a run where the socket died on the first command reached
    // the end of the list too.
    val allHeld = ran && results.isNotEmpty() && results.all { it.outcome == Outcome.Held }

    // Try to be connected before the user reads the screen, rather than showing them the last
    // socket's obituary. `connected` is initialised from the flag, which says "no" after any trip
    // through Settings — and answering that with GO TO ADB SETUP sends the user to redo a setup
    // that is already done. The pairing is kept; only the port is stale, and that is discoverable.
    LaunchedEffect(Unit) {
        if (!connected) {
            connected = withContext(Dispatchers.IO) { AdbManager.ensureAlive(context) }
        }
        // Set aside as soon as it is known that it cannot run yet, rather than when somebody taps
        // GO TO ADB SETUP. People leave with Home, or the phone takes them somewhere, and a
        // request lost that way is one the app that sent it has to be asked for all over again —
        // which for a Bluetooth address means a rescan, because it has rotated by then.
        if (!connected && parsed is GrantRequest.Parsed.Ok) {
            prefs.holdGrantRequest(pkg, lines)
        }
    }

    // A pairing confirmation waits three quarters of a minute for the platform to raise its
    // request, and whether that request becomes a dialog or a notification is decided by whether
    // the phone is interactive. A screen going dark mid-run does not just hide the answer, it
    // changes it.
    KeepAwake(working)

    val scroll = rememberScrollState()
    WheelScroll(scroll)

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                colors = barColors(),
                title = { Text("Set up $appLabel", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(scroll)) {
            when (parsed) {
                is GrantRequest.Parsed.Refused -> {
                    SectionLabel("REFUSED")
                    GuideText(
                        "This request was turned down and nothing has been run. An app may ask to " +
                            "set up itself and nothing else.",
                    )
                    if (parsed.line.isNotBlank()) {
                        SectionLabel("THE LINE")
                        GuideText(parsed.line)
                    }
                    SectionLabel("WHY")
                    GuideText(parsed.why)
                }

                is GrantRequest.Parsed.Ok -> {
                    // **Status, before anything else, always.** The complaint that produced this
                    // was "I have no clue its actual status", and it was fair: the screen showed a
                    // button and nothing else, so a run that had not started, one in flight, and
                    // one that had finished badly all looked the same. Three facts, none of them
                    // inferred: whether there is a shell, whether the daemon is even listening, and
                    // what the last or current run is doing.
                    SectionLabel("STATUS")
                    MenuRow(
                        label = "Shell",
                        detail = if (connected) "READY" else "NO",
                        sub = if (connected) {
                            "connected to the phone's debugging service"
                        } else {
                            "not connected — nothing can run until it is"
                        },
                        dim = !connected,
                    )
                    Rule()
                    val wifi = com.gios.lightcontrol.adb.AdbWifi.on(context)
                    MenuRow(
                        label = "Wireless debugging",
                        detail = when (wifi) {
                            true -> "ON"
                            false -> "OFF"
                            null -> "?"
                        },
                        sub = when (wifi) {
                            true -> "the daemon is listening"
                            false -> "the daemon is not listening, which is why nothing runs"
                            null -> "the phone will not say"
                        },
                        dim = wifi != true,
                    )
                    Rule()
                    MenuRow(
                        label = "This request",
                        detail = when {
                            working -> "${tick}s"
                            allHeld -> "DONE"
                            ran -> "FAILED"
                            else -> "READY"
                        },
                        sub = when {
                            working && GrantRun.steps > 1 ->
                                "running step $at of ${GrantRun.steps}"
                            working -> "running — the transcript below is live"
                            allHeld -> "every line ran and read back as done"
                            ran -> "it ran and did not take — see what it said below"
                            else -> "nothing has been run yet"
                        },
                        dim = !working && !ran,
                    )
                    Rule()

                    if (heldMinutes != null && heldMinutes >= 10) {
                        SectionLabel("THIS WAITED $heldMinutes MINUTES")
                        GuideText(
                            "It was set aside while you set up ADB, and it is being offered as it " +
                                "arrived. If there is a Bluetooth address in it, the device may " +
                                "have changed address by now — a ring rotates its own every " +
                                "quarter of an hour. Run it, and if it finds nothing, ask the app " +
                                "again for a fresh one.",
                        )
                    }
                    // A repair step is the one shape that touches a package other than the
                    // requester, so it gets said out loud instead of hiding inside a list the
                    // paragraph above has just promised names one app.
                    val foreign = parsed.steps.filter { it.foreign }
                    SectionLabel("WHAT $appLabel IS ASKING FOR")
                    GuideText(
                        if (foreign.isEmpty()) {
                            "$pkg needs these to work. They are the same lines its README would " +
                                "have you run from a computer. Each one names $pkg and nothing " +
                                "else — that is checked here, not taken on trust."
                        } else {
                            "$pkg needs these to work. They are the same lines its README would " +
                                "have you run from a computer, and every one of them is rebuilt " +
                                "here rather than run as sent."
                        },
                    )
                    if (foreign.isNotEmpty()) {
                        SectionLabel("THIS ONE TOUCHES ANOTHER APP")
                        GuideText(
                            "Resetting a system app clears that app's own settings, and it is the " +
                                "fix for one that crashes every time you open it — Bluetooth " +
                                "pairing, most often. It does not touch your files, your " +
                                "accounts, or any app you installed, and only the phone's own " +
                                "system apps can be named this way.",
                        )
                    }
                    parsed.steps.forEach { step ->
                        MenuRow(
                            label = step.label,
                            detail = "",
                            sub = step.command.take(160),
                        )
                        Rule()
                    }

                    if (!connected) {
                        SectionLabel("NOT CONNECTED YET")
                        GuideText(
                            "BrightControl talks to the phone's own debugging service to do this, " +
                                "and it is not connected right now. Set that up once, then come " +
                                "back — this request will still be here. Pair once and it is " +
                                "kept: the port is found again on its own every time after " +
                                "that, so there is no need to stay on the ADB screen or to " +
                                "read a port off Settings.",
                        )
                        BigButton(
                            label = "GO TO ADB SETUP",
                            filled = true,
                            enabled = true,
                            modifier = Modifier.fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                            onClick = {
                                // Set the request aside before leaving. Setup is a trip through
                                // Settings and this process does not reliably survive it — the
                                // pairing helper is an accessibility service, and coming back from
                                // that screen is exactly where everything in memory has gone. The
                                // ADB screen hands it back as soon as it has a connection.
                                prefs.holdGrantRequest(pkg, lines)
                                onAdb()
                            },
                        )
                    } else {
                        // The commonest cause, and the only one with a fix on this screen. Read
                        // needs no permission; the write needs a grant this app gave itself.
                        if (com.gios.lightcontrol.adb.AdbWifi.on(context) == false) {
                            SectionLabel("WIRELESS DEBUGGING IS OFF")
                            GuideText(
                                "That is why nothing can run: the phone's debugging service is not " +
                                    "listening, so there is nothing for this app to talk to. The " +
                                    "pairing it already has is kept.",
                            )
                            BigButton(
                                label = "TURN IT ON AND RUN",
                                filled = true,
                                enabled = !working,
                                modifier = Modifier.fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 6.dp),
                            ) {
                                busy = true
                                scope.launch {
                                    val said = withContext(Dispatchers.IO) {
                                        val turned = com.gios.lightcontrol.adb.AdbWifi.turnOn(context)
                                        if (turned.ok) AdbManager.ensureAlive(context) else false
                                    }
                                    connected = said
                                    dropped = !said
                                    busy = false
                                }
                            }
                        }
                        if (dropped) {
                            SectionLabel("NOTHING GOT THROUGH")
                            GuideText(
                                "The phone's debugging service drops its listener when you leave " +
                                    "the Wireless-debugging screen, and the first command on a " +
                                    "freshly made connection is the one most likely to die. Both " +
                                    "fix themselves on a second attempt: the pairing is kept and " +
                                    "the port is found again on its own. Press TRY AGAIN. If it " +
                                    "says this twice, wireless debugging has been switched off.",
                            )
                        }
                        // **A way out, always.** A command can be waiting three quarters of a
                        // minute on a phone somebody has changed their mind about, and a screen
                        // whose only affordance is waiting is a screen that gets force-quit —
                        // which loses the transcript, the run, and any idea of what happened.
                        if (working) {
                            BigButton(
                                label = "STOP",
                                filled = false,
                                enabled = !GrantRun.stopRequested,
                                modifier = Modifier.fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 6.dp),
                            ) { GrantRun.stop() }
                        }
                        BigButton(
                            label = when {
                                // Seconds, because a wait that counts is a wait somebody can
                                // believe. Steps too, when there is more than one.
                                working && GrantRun.steps > 1 ->
                                    "RUNNING $at/${GrantRun.steps} · ${tick}s"
                                working -> "RUNNING… ${tick}s"
                                allHeld -> "DONE"
                                dropped || ran -> "TRY AGAIN"
                                else -> "RUN THESE ${parsed.steps.size}"
                            },
                            filled = true,
                            // Still tappable after a failed run. The old screen disabled itself
                            // on DONE, so the one thing to do about a failure was the one thing
                            // the screen would not let you do.
                            enabled = !working && !allHeld,
                            modifier = Modifier.fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                        ) {
                            busy = true
                            GrantRun.start(pkg, parsed.steps.size)
                            scope.launch {
                                // Reconnect rather than probe. The daemon's listener does not
                                // survive leaving the Wireless-debugging screen, so a connection
                                // made during setup is normally dead by the time the user has
                                // walked back to the app that needed it — which is every time
                                // this button is pressed. The port is discoverable and the
                                // pairing is kept, so this is a second of work, not a question.
                                val live = withContext(Dispatchers.IO) {
                                    AdbManager.ensureAlive(context)
                                }
                                // **A "no" from the probe does not stop the run.**
                                //
                                // This used to return here, reporting that nothing ran — and it
                                // was wrong often enough to be worth not asking. The probe sends
                                // its own command down a socket that may have been connected
                                // milliseconds ago, and the first command on a new socket is the
                                // one that dies; NFC worked on the same connection in the same
                                // minute because it asks nothing and simply sends. Each step
                                // reconnects and retries on its own anyway.
                                //
                                // So the answer is kept as a warning and the steps are run. What
                                // the phone says about each grant afterwards is a better answer
                                // than what a probe said about the socket beforehand.
                                dropped = !live
                                // One at a time, and each one narrating. A pairing confirmation
                                // waits three quarters of a minute for the platform to raise its
                                // request; run as a `map` with the results shown at the end, that
                                // is a screen saying RUNNING… over a command that is talking the
                                // whole time and nobody listening.
                                val collected = mutableListOf<StepResult>()
                                withContext(Dispatchers.IO) {
                                    val adb = AdbManager.getInstance(context)
                                    parsed.steps.forEachIndexed { index, step ->
                                        // Asked to stop: do not start another command. The one
                                        // already running was ended by the socket closing.
                                        if (GrantRun.stopRequested || AdbManager.stopping) {
                                            return@forEachIndexed
                                        }
                                        withContext(Dispatchers.Main) {
                                            GrantRun.at(index + 1, step.label)
                                        }
                                        collected += GrantCheckRunner.runAndVerify(
                                            context = context,
                                            adb = adb,
                                            label = step.label,
                                            command = step.command,
                                            check = step.check,
                                            timeoutMs = step.timeoutMs,
                                        ) { line ->
                                            // Called from the stream reader's thread. [GrantRun.say]
                                            // is synchronised for exactly this.
                                            GrantRun.say(line)
                                        }
                                    }
                                }
                                val out = collected.toList()
                                GrantRun.finished(out)
                                // Held only if nothing got through at all. One step failing is a
                                // grant that did not take; every step failing on a socket error is
                                // a connection, and those read differently on the page.
                                dropped = out.isNotEmpty() && out.all { it.outcome == Outcome.Failed }
                                connected = runCatching {
                                    AdbManager.getInstance(context).connected()
                                }.getOrDefault(false)
                                busy = false
                                // `ran` comes from the run's phase now, which [GrantRun.finished]
                                // has already set.
                            }
                        }
                    }

                    // **What it is actually doing.** The helper that answers a pairing request
                    // prints `createBond true`, then `state BONDING`, then either
                    // `setPairingConfirmation true` and `RESULT bonded` or the reason it gave up.
                    // Reading that as it happens is the difference between waiting and watching —
                    // and it is the only place the ring's own answer ever appears.
                    if (saying.isNotEmpty()) {
                        SectionLabel(
                            if (working) "WHAT IT IS SAYING · ${tick}s" else "WHAT IT SAID",
                        )
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            saying.forEach { line ->
                                Text(
                                    line,
                                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                                    color = if (line.startsWith("·")) Color.White else Dim,
                                )
                            }
                        }
                        Rule()
                    }

                    if (results.isNotEmpty()) {
                        val failed = results.count { it.outcome == Outcome.Failed }
                        val unknown = results.count { it.outcome == Outcome.Unknown }
                        SectionLabel(
                            when {
                                failed > 0 -> "DID NOT WORK"
                                unknown > 0 -> "PARTLY CONFIRMED"
                                else -> "RESULT"
                            },
                        )
                        // Every line says which of the three things it is, so a run can be read
                        // at a glance instead of inferred from whether a command printed
                        // anything. The state came from the phone, not from the output.
                        results.forEach { r ->
                            MenuRow(
                                label = r.label,
                                detail = when (r.outcome) {
                                    Outcome.Held -> "OK"
                                    Outcome.Failed -> "FAILED"
                                    Outcome.Unknown -> "UNKNOWN"
                                },
                                sub = r.detail,
                            )
                            Rule()
                        }
                        if (failed > 0 && !connected) {
                            // One dead socket produces a whole list of failures, and reading that
                            // list step by step is how an evening goes missing. Say it once.
                            GuideText(
                                "The connection dropped mid-run and could not be picked back up. " +
                                    "That normally means wireless debugging is switched off, or " +
                                    "the phone has forgotten the pairing — both are set up in " +
                                    "one pass on the ADB screen.",
                            )
                            BigButton(
                                label = "GO TO ADB SETUP",
                                filled = true,
                                enabled = true,
                                modifier = Modifier.fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 6.dp),
                                onClick = {
                                    // Held here as well as on the not-connected route above: a run
                                    // that died mid-way is the case where coming back to the same
                                    // list matters most.
                                    prefs.holdGrantRequest(pkg, lines)
                                    onAdb()
                                },
                            )
                        } else if (failed > 0) {
                            GuideText(
                                "The connection is still up, so these ran and did not take. The " +
                                    "reason is on each line above.",
                            )
                        } else if (unknown > 0) {
                            GuideText(
                                "Nothing on this phone records whether the unknown ones worked, so " +
                                    "they are not being called done. Open $appLabel and see.",
                            )
                        } else {
                            GuideText(
                                "All of them read back as granted. Some are only picked up when " +
                                    "the app starts, so if $appLabel still says something is " +
                                    "missing, close and reopen it.",
                            )
                        }
                        // **A way back to what you were doing.** Everything above happens because
                        // another app asked for it, and the run finishing is the moment to return
                        // to that app rather than to this one's home screen. Shown after a result
                        // of any kind: a failure is also a thing to go and look at over there.
                        SectionLabel("NEXT")
                        BigButton(
                            label = "OPEN $appLabel".uppercase(),
                            filled = allHeld,
                            enabled = true,
                            modifier = Modifier.fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                        ) {
                            runCatching {
                                val launch = context.packageManager.getLaunchIntentForPackage(pkg)
                                    ?: return@runCatching
                                context.startActivity(
                                    launch.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * How many lines of a command's own output to keep on screen.
 *
 * A pairing confirmation prints a handful; a command that goes wrong in a loop could print
 * thousands. The last of them is always the answer, so the tail is the part worth keeping.
 */
private const val MAX_LIVE_LINES = 40
