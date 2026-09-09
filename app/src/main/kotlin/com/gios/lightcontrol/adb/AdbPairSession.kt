package com.gios.lightcontrol.adb

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.concurrent.Executors

/**
 * The state behind one-tap pairing, shared by the ADB screen and [AdbPairReader].
 *
 * ## Why a reader service and not a text box
 *
 * Every route that asks the user to carry the six digits somewhere is closed, and it is worth
 * writing down why so nobody reopens them:
 *
 * - **Type it in this app.** `WirelessDebuggingFragment.onPause()` calls `removeDialog(...)`, and
 *   `AdbWirelessDialog.onStop()` calls `dismiss()` and then `onDismiss()`, which calls
 *   `mAdbManager.disablePairing()`. The session dies when Settings *pauses*, not when it is
 *   covered — so Home kills the pairing exactly as dead as Back does. Earlier builds of this
 *   screen told users to leave the dialog with Home. That advice was wrong, and it is why
 *   pairing here so often failed with a code that looked correct.
 * - **Type it into a notification.** Shizuku's trick, and it needs a notification shade with
 *   direct reply. LightOS has no shade to type into.
 * - **Ask the system.** Settings does not read its own dialog: it calls
 *   `IAdbManager.enablePairingByPairingCode()` and gets the digits back in a broadcast. That
 *   path needs `MANAGE_DEBUGGING`, which is `signature|privileged` with no `development` flag,
 *   so `pm grant` cannot hand it over even after a successful pairing.
 * - **A floating panel.** An overlay is the only window that does not pause Settings, which is
 *   why it nearly worked — but it has to survive this process being backgrounded, and on this
 *   phone it usually does not.
 *
 * What is left needs no window and no typing at all: read the digits off the dialog while
 * Settings is still the foreground app. The code is a plain `TextView` in
 * `adb_wireless_dialog.xml` and the dialog sets no `FLAG_SECURE`, so an accessibility service
 * can see it.
 *
 * ## What this object guarantees
 *
 * The reader is inert unless [arm] has been called, and it disarms itself the moment it finds a
 * code or [WINDOW_MS] passes. Nothing is read outside that window, and the service is declared
 * with `packageNames="com.android.settings"` so it cannot see any other app even when armed.
 */
object AdbPairSession {

    enum class Phase { Idle, Waiting, Pairing, Granting, Done, Failed }

    /** How long the reader stays armed after the user asks to pair. */
    const val WINDOW_MS = 90_000L

    var phase by mutableStateOf(Phase.Idle)
        private set

    /** One line for the user, always the truth about what just happened. */
    var message by mutableStateOf("")
        private set

    /** Grant results, filled in after a successful connect. */
    var grants by mutableStateOf(listOf<String>())
        private set

    /**
     * What the reader actually saw on the last screen it could not make sense of.
     *
     * Light ships its own build of Settings, and the one thing that cannot be checked from a
     * desk is whether that build still renders the code as readable text. When the read fails
     * this holds the text it did find, so the screen can show it and the answer takes one run
     * instead of a guessing match. Memory only, cleared on the next attempt.
     */
    var unreadable by mutableStateOf<String?>(null)
        private set

    @Volatile
    var armed = false
        private set

    /**
     * When the pairing dialog was first seen looking unreadable, or 0 while it is not.
     *
     * The complaint used to be filed on the first sweep that found the dialog without a code, and
     * the reader sweeps twice a second. light-reports#300, #285 and #284 are all that first sweep:
     * a dialog Settings had not filled in yet, complained about, and then almost certainly paired
     * with a moment later. See [AdbPairCode.looksUnpopulated] for how the two are told apart, and
     * [UNREADABLE_GRACE_MS] for the wait that covers the cases the shape test cannot.
     */
    private var unreadableSince = 0L

    /**
     * Automatic recoveries from a key the daemon will not trust, this attempt.
     *
     * Reset only by a pairing the user started, never by the one [startOver] starts, so a phone
     * that refuses every key it is given cannot loop through Settings forever.
     */
    private var staleKeyRecoveries = 0

    /**
     * The first line of every window seen while armed, in order, deduplicated.
     *
     * ### Why silence had to become data
     *
     * The reader used to report every screen it thought was the dialog and could not read, which
     * produced two reports against the *Wireless debugging list* — a screen that has never had a
     * code on it. Excluding the list stopped the false reports and took the signal with it: a
     * ninety-second window that ends with nothing said is now indistinguishable from one where the
     * dialog never opened, one where it opened in a window nobody looked at, and one where the user
     * simply never got there.
     *
     * A list of what was actually seen tells those apart in one line, and costs a string per
     * window.
     */
    private val seen = LinkedHashSet<String>()

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var expire: Runnable? = null

    /** True once the user has enabled the reader in Settings → Accessibility. */
    fun readerEnabled(context: Context): Boolean =
        runCatching {
            val flat = android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ).orEmpty()
            flat.split(':').any { it.equals(READER_COMPONENT, ignoreCase = true) }
        }.getOrDefault(false)

    /**
     * Arm the reader for one [WINDOW_MS] window.
     *
     * @param fresh a person asked for this. [startOver] arms with `fresh = false`, so its automatic
     * second go does not hand itself a new budget of automatic second goes.
     */
    fun arm(fresh: Boolean = true) {
        cancelExpiry()
        if (fresh) staleKeyRecoveries = 0
        unreadableSince = 0L
        grants = emptyList()
        unreadable = null
        synchronized(seen) { seen.clear() }
        phase = Phase.Waiting
        message = "waiting for the pairing dialog — open Wireless debugging → Pair device with pairing code"
        armed = true
        expire = Runnable {
            if (armed) {
                armed = false
                phase = Phase.Failed
                val windows = synchronized(seen) { seen.toList() }
                message = "timed out after ${WINDOW_MS / 1000}s without seeing a pairing code"
                // Reported, not just shown: a window that ends in silence is the case that needs
                // explaining most, and the list of screens it did see is the whole explanation.
                com.gios.lightcontrol.report.Trouble.record(
                    "find the pairing dialog in ${WINDOW_MS / 1000} seconds",
                    if (windows.isEmpty()) {
                        "no Settings window was read at all — the pairing helper may not be running"
                    } else {
                        "windows seen while waiting:\n" + windows.joinToString("\n")
                    },
                )
            }
        }.also { main.postDelayed(it, WINDOW_MS) }
    }

    fun cancel() {
        cancelExpiry()
        armed = false
        phase = Phase.Idle
        message = ""
    }

    /**
     * Let go of a phase that has outlived its work.
     *
     * [Phase.Pairing] and [Phase.Granting] both disable the PAIR button, on the reasoning that two
     * pairing attempts at once are worse than one. Fair — but nothing here had a way to *leave*
     * those phases if the work behind them died with the process, and a phase nobody can leave is a
     * button nobody can press. Called when the screen is opened: by then anything that was really
     * running has either finished or gone with the process that was running it.
     */
    fun releaseStalePhase() {
        if (phase == Phase.Pairing || phase == Phase.Granting) {
            phase = Phase.Failed
            message = "the last attempt did not finish — start it again"
        }
    }

    private fun cancelExpiry() {
        expire?.let { main.removeCallbacks(it) }
        expire = null
    }

    /**
     * Called by the reader for every Settings screen it sees while armed. Returns true once a
     * code has been taken, after which the reader is disarmed and this does nothing.
     */
    fun offerScreen(context: Context, text: String): Boolean {
        if (!armed) return false
        // **Take the connect port while it is on screen.** mDNS is how the daemon is normally
        // found, and light-reports#122 is mDNS finding nothing seconds after a pairing the daemon
        // accepted. The Wireless debugging screen prints the port in the same window this is
        // reading for a code, so there is no reason to depend on discovery at all. Only from the
        // list — the dialog's port is the pairing port and it dies with the box.
        AdbPairCode.connectAddress(text)?.let { (host, port) ->
            runCatching {
                val prefs = com.gios.lightcontrol.Prefs(context)
                if (prefs.adbPort != port.toString()) {
                    prefs.notePairStep("read the connect port off the screen: $port")
                }
                prefs.adbHost = host
                prefs.adbPort = port.toString()
            }
        }
        // Cheap and bounded: the heading of each distinct window, which is enough to say whether
        // the dialog was ever among them.
        text.lineSequence().firstOrNull { it.isNotBlank() }?.let { heading ->
            synchronized(seen) {
                if (seen.size < MAX_SEEN) seen += heading.trim().take(60)
            }
        }
        val code = AdbPairCode.extract(text) ?: run {
            // Only worth reporting if this really looks like the pairing dialog; the user walks
            // through several Settings screens on the way there.
            if (AdbPairCode.looksLikePairingDialog(text)) {
                // **A dialog with nothing in it yet is not a dialog that cannot be read.** Settings
                // gets the digits back in a broadcast, so for a moment the box is up with no code
                // and no address on it, and a reader sweeping twice a second looks straight into
                // that moment. Say nothing and look again — the next sweep is 500 ms away.
                if (AdbPairCode.looksUnpopulated(text)) return false
                // Populated, and still no code. That is a real read failure — but only once it has
                // stayed that way. The shape test above catches the ordinary race; this catches the
                // one where the address lands before the digits do.
                val firstSeen = unreadableSince
                val now = android.os.SystemClock.elapsedRealtime()
                if (firstSeen == 0L) {
                    unreadableSince = now
                    return false
                }
                if (now - firstSeen < UNREADABLE_GRACE_MS) return false
                main.post { unreadable = text.take(600) }
                // **And file it.** light-reports#61 is this failure, reported by hand — "pairing
                // box present but numbers within not detected" — with no trace of the text that
                // was actually on screen, because nothing carried it into the report. The one
                // thing needed to fix a reader that cannot read is what it read. Through [Trouble],
                // so it raises the report chip and lands in the issue verbatim, throttled to once
                // an hour so walking past the dialog twice does not ask twice.
                com.gios.lightcontrol.report.Trouble.record(
                    "read the pairing code off the dialog",
                    text.take(600),
                )
            }
            return false
        }

        armed = false
        unreadableSince = 0L
        cancelExpiry()
        val app = context.applicationContext
        main.post {
            unreadable = null
            phase = Phase.Pairing
            message = "found the code — pairing"
        }
        worker.execute { pairAndGrant(app, code) }
        return true
    }

    private fun pairAndGrant(context: Context, code: String) {
        val prefs = com.gios.lightcontrol.Prefs(context)
        val adb = AdbManager.getInstance(context)
        // Every step, on disk. See [com.gios.lightcontrol.Prefs.pairTrail] — this whole sequence
        // runs while the app is in the background, so anything held in memory is a diagnosis
        // nobody will ever read.
        prefs.notePairStep("code read, pairing")

        val paired = runCatching { adb.pairViaMdns(context, code, 60_000L) }.getOrDefault(false)
        prefs.notePairStep(if (paired) "pairing accepted" else "pairing REFUSED")
        if (!paired) {
            main.post {
                phase = Phase.Failed
                message = "read the code but pairing failed — the dialog may have closed. " +
                    "Reopen it and try again; the code is fresh each time."
            }
            com.gios.lightcontrol.report.Trouble.record(
                "pair with the code it read off the dialog",
                "the daemon refused the pairing. The code is fresh each time the box opens, so a " +
                    "refusal usually means the box had closed — but a stale key on this phone can " +
                    "also be refused, which FORGET THE PAIRING clears.",
            )
            return
        }

        var connected = runCatching { adb.connectAuto(context, 15_000L) }.getOrDefault(false)
        if (!connected) {
            // The port the reader took off the screen, which needs no discovery to be true.
            val port = prefs.adbPort.toIntOrNull()
            if (port != null) {
                prefs.notePairStep("mDNS found nothing — trying the port from the screen: $port")
                connected = runCatching { adb.connectPort(context, port) }.getOrDefault(false)
            }
        }
        prefs.notePairStep(if (connected) "connected" else "connect FAILED after pairing")
        if (!connected) {
            main.post {
                phase = Phase.Failed
                message = "paired, but could not connect — use CONNECT below"
            }
            com.gios.lightcontrol.report.Trouble.record(
                "connect after a pairing the daemon accepted",
                "the pairing was accepted and mDNS then found nothing to connect to. Wireless " +
                    "debugging may have been switched off by the trip through Settings.",
            )
            return
        }

        // **Proven, not assumed — and patiently.**
        //
        // A connection that is up and refuses every command is the state reported all evening as
        // "Stream closed", and at this point it is indistinguishable from success unless something
        // asks. So something asks. But asking *once* was wrong, and wrong in the most damaging
        // direction: the first command on a freshly connected socket is the one that dies — that is
        // why [AdbManager.ensureAlive] probes three times — so a good pairing was being declared
        // untrusted, the grants abandoned, and the user told to throw away a pairing that worked.
        // light-reports#116 and #119 are that false verdict.
        //
        // Several tries, then a reconnect and one more. Only after all of that is a refusal a fact
        // about the key rather than about the moment.
        val usable = proven(context, adb, prefs)
        prefs.notePairStep(if (usable) "shell answers" else "shell REFUSED a command")
        if (!usable) {
            // **Do the thing the message used to ask for.**
            //
            // By here the verdict is not a guess: [proven] asked four times a fifth of a second
            // apart and then reconnected and asked again, so the key really is one the daemon will
            // not trust. A key in that state is worth nothing — keeping it costs the user the one
            // press that throws it away, and six reports (light-reports#307, #301, #267, #250,
            // #249, #237) are that press not being made, all of them carrying this same sentence
            // ending in "This is the state FORGET THE PAIRING exists for."
            //
            // So forget it here and put them back at the pairing box. A new pairing needs a fresh
            // six digits and only Settings can produce those, so this cannot finish the job on its
            // own — what it can do is remove the dead key and reopen the one screen that matters,
            // which is what START OVER AND PAIR does when pressed by hand.
            //
            // Once. [staleKeyRecoveries] survives the [arm] inside [startOver], so a phone that
            // refuses every key it is handed says so instead of walking through Settings forever.
            if (staleKeyRecoveries < STALE_KEY_RECOVERIES) {
                staleKeyRecoveries++
                prefs.notePairStep("key not trusted — forgetting it and starting over")
                com.gios.lightcontrol.report.Trouble.record(
                    "run anything after pairing and connecting",
                    "the daemon accepted both the pairing and the connection and then refused a " +
                        "shell stream, which means the key it accepted is not one it trusts. The " +
                        "app has thrown that key away and reopened the pairing box by itself; if " +
                        "this report is the only sign of it, the second attempt worked.",
                )
                startOver(context)
                return
            }
            main.post {
                phase = Phase.Failed
                message = "paired and connected twice, and the phone will not run anything either " +
                    "time — it is refusing every key it is given. Turn wireless debugging off and " +
                    "on in Settings, then pair again."
            }
            com.gios.lightcontrol.report.Trouble.record(
                "run anything after pairing twice",
                "the daemon accepted the pairing and the connection both times and refused a shell " +
                    "stream both times, with a fresh key the second time. So this is not a stale " +
                    "key on this phone — the daemon is refusing keys it has just accepted.",
            )
            return
        }

        main.post {
            phase = Phase.Granting
            message = "connected — applying grants"
        }

        // Published one at a time. Nine grants with a reconnect apiece is minutes of a screen
        // that says "GRANTING…" and shows nothing, which is indistinguishable from being stuck —
        // and this phase disables the PAIR button, so being stuck here is being stuck.
        val done = mutableListOf<String>()
        for (step in SelfGrant.steps) {
            // Through [AdbManager.runVia]: the connection made a second ago is the one most likely
            // to have been reported up before the daemon settled, and `Stream closed` on the first
            // grant of the batch used to be reported as the grant failing.
            val out = AdbManager.runVia(context, step.command)
            val ok = out.isBlank() || out.contains("done") || out.contains("already")
            done += "${if (ok) "OK" else "??"}  ${step.label}" +
                if (out.isBlank()) "" else " — ${out.take(80)}"
            val snapshot = done.toList()
            main.post { grants = snapshot }
            // Nothing to reconnect to: the reconnect inside runVia already tried, and eight more
            // attempts would cost a minute each to learn the same thing.
            if (!ok && out.contains("connection is gone")) {
                main.post {
                    phase = Phase.Failed
                    message = "paired and connected, but the connection dropped part-way through " +
                        "the grants. Tap GRANT ALL below — the pairing is kept."
                }
                return
            }
        }

        prefs.notePairStep("granted ${done.count { it.startsWith("OK") }} of ${SelfGrant.steps.size}")
        main.post {
            phase = Phase.Done
            message = "paired, connected, and granted. You can turn the pairing reader back off."
        }
    }

    /** Enough windows to tell what happened, few enough that a report stays readable. */
    private const val MAX_SEEN = 12

    /**
     * Throw the pairing away and put the user back at the pairing box, without a press.
     *
     * The same chain START OVER AND PAIR runs: forget the key, make sure wireless debugging is on,
     * arm the reader, open Developer options. Armed with `fresh = false`, so the recovery budget
     * this was called from is not handed back.
     *
     * Deliberately not a call into the ADB screen's copy of this chain. That one is inside a button
     * and also builds the text that button prints; sharing it would mean the screen having to exist
     * for the recovery to work, and by here the user may well have put the phone down.
     */
    private fun startOver(context: Context) {
        val prefs = com.gios.lightcontrol.Prefs(context)
        runCatching { AdbManager.forgetPairing(context) }
        if (runCatching { AdbWifi.on(context) }.getOrNull() != true) {
            runCatching { AdbWifi.turnOn(context) }
        }
        main.post {
            prefs.clearPairTrail()
            prefs.notePairStep("armed again after throwing away a key the daemon would not trust")
            arm(fresh = false)
            message = "that key was refused — throwing it away and asking for a new code. " +
                "Open the pairing box again."
            runCatching {
                context.startActivity(
                    android.content.Intent(
                        android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS,
                    ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
    }

    /**
     * How long the pairing dialog may sit there populated and unreadable before it is reported.
     *
     * [AdbPairCode.looksUnpopulated] already catches the ordinary race, where neither the code nor
     * the address has arrived. This covers the narrower one where the address lands first: three
     * seconds is six sweeps, far longer than a broadcast takes, and short enough that a genuinely
     * unreadable dialog is still reported while the user is standing in front of it.
     */
    private const val UNREADABLE_GRACE_MS = 3_000L

    /** Automatic goes at a key the daemon will not trust, before the user is told. */
    private const val STALE_KEY_RECOVERIES = 1

    /**
     * Whether the connection will actually carry a command, asked until it is fair to stop asking.
     *
     * Four attempts a fifth of a second apart, then one reconnect and a last ask. Cheap on the path
     * that works — the first attempt almost always answers — and it removes the settling race
     * rather than reporting it as a broken key.
     */
    private fun proven(
        context: Context,
        adb: AdbManager,
        prefs: com.gios.lightcontrol.Prefs,
    ): Boolean {
        repeat(PROVE_TRIES) { attempt ->
            if (runCatching { adb.alive() }.getOrDefault(false)) {
                if (attempt > 0) prefs.notePairStep("shell answered on try ${attempt + 1}")
                return true
            }
            runCatching { Thread.sleep(PROVE_GAP_MS) }
        }
        prefs.notePairStep("shell silent after $PROVE_TRIES tries — reconnecting once")
        return runCatching { AdbManager.ensureAlive(context) }.getOrDefault(false)
    }

    /** How many times to ask a new connection whether it works before believing it does not. */
    private const val PROVE_TRIES = 4

    /** Between asks. Long enough for a daemon to finish settling, short enough not to be felt. */
    private const val PROVE_GAP_MS = 250L

    const val READER_COMPONENT = "com.gios.lightcontrol/com.gios.lightcontrol.adb.AdbPairReader"
}
