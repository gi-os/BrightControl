package com.gios.lightcontrol.report

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Something the app tried, and could not do. */
data class Failure(
    /** In the app's own words, lower case, completing "<app> could not …". */
    val what: String,
    /** Whatever detail there was — an exception, an HTTP code, the reason a parser gave. */
    val detail: String?,
)

/**
 * When the app knows it has failed, it says so and offers to report it.
 *
 * Waiting for a shake means only the failures a person is annoyed enough to report get reported,
 * which is a biased sample of exactly the wrong kind: the quiet ones that leave a screen looking
 * ordinary never arrive at all. This app is mostly a reader of other people's score feeds, so
 * the quiet failure — a provider that started returning an empty array — is the common one.
 *
 * **The nagging is the thing to get right.** A feed that cannot be reached fails on every
 * refresh, and an app that asks to report it twelve times before lunch gets its reporting turned
 * off. So the same failure asks once an hour at most.
 */
object Trouble {

    /** Long enough that a failing hourly poll asks once, not once an hour. */
    private const val QUIET_MS = 60L * 60L * 1_000L

    /**
     * Between any two reports at all, regardless of what they are about.
     *
     * A minute. Long enough that one batch of failures produces one report, short enough that a
     * genuinely different problem an hour later still gets through — and the batches themselves now
     * summarise rather than reporting per command, so this is a floor rather than the mechanism.
     */
    private const val MIN_GAP_MS = 60L * 1_000L

    private var lastSent = 0L
    private val lastAsked = mutableMapOf<String, Long>()
    private val _latest = MutableStateFlow<Failure?>(null)

    /** Set when there is something worth asking about. Cleared by whoever asks. */
    val latest: StateFlow<Failure?> = _latest

    /**
     * Note a failure. Cheap and safe to call from anywhere, including a catch block that is
     * already handling something worse.
     */
    @Synchronized
    fun record(what: String, detail: String? = null) {
        val now = SystemClock.elapsedRealtime()
        // **A floor between any two, whatever they say.**
        //
        // The per-message hour was the only limit, and it was enough while these were offered — a
        // sheet appears once and a second failure finds it already up. Sent automatically it is not
        // nearly enough: the message names the command, so nine grants failing in one batch are nine
        // *different* messages, each one its own first offence. Thirty issues arrived in a few
        // seconds this way, and thirty reports of one problem is worse than one, because now
        // somebody has to read thirty to find out it was one.
        if (now - lastSent < MIN_GAP_MS) return
        val previous = lastAsked[what]
        if (previous != null && now - previous < QUIET_MS) return
        lastAsked[what] = now
        lastSent = now
        // Not overwritten: the first failure of a cascade is the one that explains the rest.
        if (_latest.value == null) _latest.value = Failure(what, detail)
    }

    fun record(what: String, error: Throwable) =
        record(what, "${error::class.java.simpleName}: ${error.message}")

    fun clear() {
        _latest.value = null
    }
}
