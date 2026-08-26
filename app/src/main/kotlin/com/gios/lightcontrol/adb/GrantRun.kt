package com.gios.lightcontrol.adb

import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * The state of the run happening on the request screen, held outside the composition.
 *
 * ## Why this is not local state
 *
 * It was, and it kept vanishing. A run on this screen can take three quarters of a minute — a
 * pairing confirmation waits for the platform to raise its request — and in that time somebody
 * puts the phone down, gets taken to another app by a notification, or comes back through the
 * launcher. Every one of those rebuilds the composition, and local state rebuilt is local state
 * gone: the screen came back saying nothing had ever happened, in the middle of something
 * happening. "It doesn't show" and "it isn't working" are indistinguishable from there.
 *
 * So the run lives here, the same way [AdbPairSession] does, and the screen is a window onto it
 * rather than its owner. Coming back mid-run shows the run; coming back after it shows what it
 * said.
 *
 * ## What it keeps
 *
 * The transcript, which is the only place a command's own words are ever visible; which step of
 * how many; when it started, so the screen can count seconds rather than assert that something is
 * happening; and the results, read back off the phone. Nothing here is a claim — every line came
 * from the shell or from the package manager.
 */
object GrantRun {

    enum class Phase { Idle, Running, Done }

    var phase by mutableStateOf(Phase.Idle)
        private set

    /** Which app's request this is, so a stale run cannot be shown against a new request. */
    var pkg by mutableStateOf("")
        private set

    var step by mutableStateOf(0)
        private set

    var steps by mutableStateOf(0)
        private set

    /** What the running commands have said, oldest first. */
    var saying by mutableStateOf(listOf<String>())
        private set

    var results by mutableStateOf(listOf<StepResult>())
        private set

    /** Elapsed clock, monotonic so a time-zone change mid-run cannot make it negative. */
    var startedAt by mutableStateOf(0L)
        private set

    val elapsedSeconds: Long
        get() = if (phase == Phase.Running && startedAt > 0) {
            (SystemClock.elapsedRealtime() - startedAt) / 1_000
        } else {
            0
        }

    /**
     * Set when somebody asks a run to stop, cleared when the next one starts.
     *
     * ### Why stopping needs the socket, not a flag
     *
     * A command in flight is blocked in a read that ignores interruption — that is the whole reason
     * commands are given a deadline and run on a thread the app walks away from. A flag cannot end
     * it. Closing the socket underneath it can, and does: the read returns, the thread finishes, and
     * the step reports what happened. So [stop] does both — the flag tells the loop not to start the
     * next step, and the reset ends the one already going.
     *
     * The flag is read by the batch on the ADB screen as well, which is not a [GrantRun] but wants
     * exactly the same "stop asking and let me try again" behaviour.
     */
    var stopRequested by mutableStateOf(false)
        private set

    /**
     * Let go now, and let the abandoned work die in its own time.
     *
     * ### Why stopping cannot mean waiting
     *
     * The point of STOP is to get the buttons back. Waiting for the work to *notice* does not do
     * that: a command is blocked in a read with a deadline of up to forty-five seconds, and a
     * lookup already inside mDNS discovery cannot be interrupted at all. Every version of this that
     * marked the run "stopping" and waited for the loop to agree left the screen exactly as stuck as
     * before, which is what got reported three times.
     *
     * So the run is declared over here, immediately. The socket is closed, nothing further starts,
     * and whatever is still unwinding in the background is *abandoned* rather than awaited — its
     * results are dropped on arrival by the generation check in [finished], so a stopped run can
     * never overwrite the one somebody starts a second later.
     */
    fun stop() {
        stopRequested = true
        // Over, as far as the screen is concerned. This is the line that gives the buttons back.
        phase = Phase.Done
        generation++
        say("· stopped — the connection was closed; anything still running has been abandoned")
        // Not `reset()`: that closes the socket and leaves [AdbManager.runVia] free to treat the
        // closure as an ordinary dead socket, reconnect, and run the command again — which is
        // precisely what made the first STOP button appear to do nothing. `abort` closes the socket
        // *and* says why, so the retry stands down.
        runCatching { AdbManager.abort() }
    }

    /**
     * Which run this is. Bumped by [start] and by [stop], so results arriving from a run nobody is
     * waiting for any more can be told apart from the current one's and dropped.
     */
    private var generation = 0

    fun start(pkg: String, steps: Int) {
        generation++
        stopRequested = false
        // A stop from a previous run must not cancel this one before it starts.
        runCatching { AdbManager.clearAbort() }
        this.pkg = pkg
        this.steps = steps
        step = 0
        saying = emptyList()
        results = emptyList()
        startedAt = SystemClock.elapsedRealtime()
        phase = Phase.Running
    }

    fun at(step: Int, label: String) {
        this.step = step
        // The step's own name in front of its output, so a transcript of several commands can be
        // read as several commands rather than one long stream.
        say("· $label")
    }

    @Synchronized
    fun say(line: String) {
        // The tail is the part that matters: the last thing a command says is normally the answer,
        // and a command that goes wrong in a loop can say a great deal.
        saying = (saying + line).takeLast(KEEP_LINES)
    }

    /**
     * The results of a run, taken only if it is still the run in progress.
     *
     * [generation] is the whole point: a stopped run's steps keep unwinding for as long as their
     * deadlines take, and there is nothing to stop them arriving here a minute later, on top of a
     * run somebody has since started. Dropping them is the only correct thing to do with them —
     * they describe a question nobody is asking any more.
     */
    fun finished(generation: Int, results: List<StepResult>) {
        if (generation != this.generation) return
        this.results = results
        phase = Phase.Done
        stopRequested = false
    }

    /** The generation a run should carry back to [finished]. Read once, as the run starts. */
    val current: Int
        get() = generation

    /** Forget a run belonging to a different request, so nothing stale is shown as current. */
    fun clearIfNot(pkg: String) {
        if (this.pkg.isNotEmpty() && this.pkg != pkg) {
            phase = Phase.Idle
            saying = emptyList()
            results = emptyList()
            step = 0
            steps = 0
            this.pkg = ""
        }
    }

    private const val KEEP_LINES = 60
}
