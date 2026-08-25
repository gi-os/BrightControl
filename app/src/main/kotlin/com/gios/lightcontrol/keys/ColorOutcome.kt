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

    /**
     * How many times another app repainted the screen out from under a colour rule.
     *
     * ### Why the per-line outcome cannot see this
     *
     * [of] compares `got` against `want` — this app's read-back against the value this app had
     * just written — so a write that landed reads `ok` no matter what happens to the screen a
     * second later. That is why light-reports#37, #38, #44 and #45 all arrived headlined "N held,
     * 0 overwritten" over a log whose every other line is somebody else stating the baseline. The
     * counts were true and the report was useless.
     *
     * ### What a repaint looks like
     *
     * A `COLOR` line for one package, then a `DEFAULT` or `MONO` line for a *different* package
     * within [REPAINT_WINDOW_S] seconds. On this phone the baseline is monochrome, so that
     * second line is the screen going grey under an app that asked for colour and is still in
     * front — the fault, spelled out, in two lines that individually both say `ok`.
     *
     * One repaint is counted per colour write. A window state that raises three baseline writes
     * in a row is one thing going wrong, not three.
     *
     * @param log newest first, the way [com.gios.lightcontrol.Prefs.colorLog] stores it.
     */
    fun repaints(log: List<String>): Int {
        var count = 0
        var colour: Line? = null
        // Oldest first, because "landed after" is the whole question.
        for (line in log.mapNotNull { parse(it) }.reversed()) {
            if (line.rule == "COLOR") {
                colour = line
                continue
            }
            if (line.rule != "DEFAULT" && line.rule != "MONO") continue
            val since = colour ?: continue
            if (line.pkg == since.pkg) continue
            // A negative gap is the log crossing midnight, which is not a measurement. Left
            // uncounted rather than guessed at: the log is twelve lines and it will say so again.
            val gap = line.at - since.at
            if (gap < 0 || gap > REPAINT_WINDOW_S) continue
            count++
            colour = null
        }
        return count
    }

    /** How long after a colour write a baseline write is still that colour being undone. */
    const val REPAINT_WINDOW_S = 3

    private class Line(val at: Int, val pkg: String, val rule: String)

    /**
     * The three fields [repaints] needs back out of a line, or null if this is not one.
     *
     * Parsing the app's own format rather than storing structured records: the log is a ring of
     * strings in SharedPreferences that a phone in somebody's pocket is already carrying, and a
     * schema change would blank it for everyone mid-investigation.
     */
    private fun parse(line: String): Line? {
        val parts = line.split(' ')
        if (parts.size < 3) return null
        val hms = parts[0].split(':')
        if (hms.size != 3) return null
        val h = hms[0].toIntOrNull() ?: return null
        val m = hms[1].toIntOrNull() ?: return null
        val sec = hms[2].toIntOrNull() ?: return null
        if (parts[1].isBlank() || parts[2].isBlank()) return null
        return Line(h * 3600 + m * 60 + sec, parts[1], parts[2])
    }
}
