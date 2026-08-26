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

    fun start(pkg: String, steps: Int) {
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

    fun finished(results: List<StepResult>) {
        this.results = results
        phase = Phase.Done
    }

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
