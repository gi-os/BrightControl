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
        val previous = lastAsked[what]
        if (previous != null && now - previous < QUIET_MS) return
        lastAsked[what] = now
        // Not overwritten: the first failure of a cascade is the one that explains the rest.
        if (_latest.value == null) _latest.value = Failure(what, detail)
    }

    fun record(what: String, error: Throwable) =
        record(what, "${error::class.java.simpleName}: ${error.message}")

    fun clear() {
        _latest.value = null
    }
}
