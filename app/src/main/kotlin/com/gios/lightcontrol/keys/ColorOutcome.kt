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

    /**
     * The whole log line, built in one place so the log's shape can be tested on the JVM.
     *
     * The package is written **in full**. It used to be cut to its last segment, which reads
     * nicely for the apps you set the rules on — `lightchat`, `lightcamera` — and hides the one
     * line that matters. A rule is lost to whatever wrote after it, so the interesting package
     * in a report is nearly always one nobody chose: a system window that raised a window-state
     * event, stated the baseline over the front app's colour, and appeared in the log under a
     * bare word like `edgegestures` that cannot be looked up, granted a rule, or even searched
     * for. A package id you can spell is the difference between a log that names the culprit and
     * one that only proves there was one.
     *
     * The outcome stays last: the Color screen's headline counts the lines by their ending.
     */
    fun line(
        at: String,
        pkg: String,
        rule: String,
        want: Pair<Int, Int>,
        got: Pair<Int, Int>,
        wantedNow: Pair<Int, Int>?,
    ): String =
        "$at $pkg ${rule.uppercase()} " +
            "want ${want.first}/${want.second} got ${got.first}/${got.second} " +
            of(want, got, wantedNow)
}
