package com.gios.lightcontrol.keys

/**
 * What the daltonizer read-back found, named — and deliberately free of Android imports, so the
 * three cases can be tested on the JVM. [ColorMode.verify] is the only caller.
 */
object ColorOutcome {

    /**
     * Name the read-back 900 ms after a write.
     *
     * `LOST` used to mean nothing more than "the pair does not match", and switching apps within
     * a second of each other is enough to produce that from two writes that both worked: app A's
     * rule is written, app B comes forward and states its own, and A's read-back lands afterwards
     * and reports A's values as overwritten. Two of the six lines in the report that prompted
     * this were exactly that, and since the issue title is counted off these outcomes, an
     * ordinary walk through three apps filed itself as a fault.
     *
     * So a mismatch that is precisely what the front app is asking for *now* is `superseded` —
     * this write was correct and is simply no longer the question. Anything else stays `LOST`,
     * which is the case the log exists for: values nobody in this app ever asked for.
     *
     * @param want what this write stated.
     * @param got what the settings read back as.
     * @param wantedNow what the front app's rule asks for as of the most recent apply, or null
     *   if nothing has been applied — in which case there is nothing that could have superseded.
     */
    fun of(want: Pair<Int, Int>, got: Pair<Int, Int>, wantedNow: Pair<Int, Int>?): String = when {
        got == want -> "ok"
        wantedNow != null && wantedNow != want && got == wantedNow -> "superseded"
        else -> "LOST"
    }
}
