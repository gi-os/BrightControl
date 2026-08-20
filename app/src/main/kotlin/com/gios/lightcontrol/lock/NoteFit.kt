package com.gios.lightcontrol.lock

import kotlin.math.abs

/**
 * The two sums behind the lock face's notification column, kept apart from the view that uses
 * them.
 *
 * No Android imports, on purpose: this is the part of [NoteScroll] that can be wrong in a way you
 * would only find out about at half past midnight with a locked phone in your hand — a row cut in
 * half, or a drag that settles a few pixels off — and it is arithmetic, so it is testable on the
 * JVM. Same reason `Policy.builtInRuleFor` is a pure function.
 */
object NoteFit {

    /**
     * How tall the column may be so that it ends where a notification ends.
     *
     * [rows] are the measured heights of the notifications, top to bottom, and [available] is the
     * space the layout had left over. Answers the tallest run of whole rows that fits inside it,
     * or `0` when not even the first one does — which the caller reads as "show what you can",
     * because a clipped notification still beats a blank gap.
     */
    fun visibleHeight(rows: List<Int>, available: Int): Int {
        var edge = 0
        var fit = 0
        for (row in rows) {
            edge += row
            if (edge > available) break
            fit = edge
        }
        return fit
    }

    /**
     * Where a released drag should come to rest.
     *
     * [tops] are the offsets of each row inside the column, [maxScroll] is as far as it can go,
     * and [scrollY] is where the finger left it. The nearest row boundary wins, with the very
     * bottom of the list as a candidate of its own so the last notification can sit flush instead
     * of being held a few pixels short by the row above it.
     *
     * Ties go to the smaller offset — the reading that shows more of what is above rather than
     * less.
     */
    fun snapTarget(tops: List<Int>, maxScroll: Int, scrollY: Int): Int {
        if (maxScroll <= 0) return 0
        val candidates = tops.filter { it in 0..maxScroll } + maxScroll
        var best = candidates.first()
        var gap = abs(best - scrollY)
        for (candidate in candidates) {
            val distance = abs(candidate - scrollY)
            if (distance < gap) {
                gap = distance
                best = candidate
            }
        }
        return best
    }
}
