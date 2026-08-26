package com.gios.lightcontrol.lock

import android.content.Context
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

/**
 * The shade on the lock face, clamped to the room it actually has.
 *
 * A `LinearLayout` was drawing four notifications into whatever vertical space was left after the
 * clock, and when four did not fit it drew them anyway: the column is `MATCH_PARENT` inside a
 * window that is the whole panel, so the overflow ran off the bottom edge and the face showed two
 * notifications and the top half of a third. Nothing was scrollable — this window is
 * `FLAG_NOT_FOCUSABLE` and every drag on it already means something else (up for the keypad, right
 * to dismiss) — so a half-row was simply a row you could not read and could not reach.
 *
 * So the list clamps instead. The parent measures this with `AT_MOST` of exactly the space left
 * over, which is the one number that says how many rows fit, and rows past that point are measured
 * and then neither laid out nor drawn. What is missing is said out loud on the `+N MORE` line
 * rather than implied by a row that runs off the screen, and swiping the top ones away brings the
 * rest up — which is the only way through the stack this face offers.
 *
 * The `+N MORE` line is [more], owned here so its count can follow the clamp. It is child 0 and is
 * drawn at the foot; rows are children 1..n, in the order they were added.
 */
class LockNoteList(context: Context, private val more: TextView) : ViewGroup(context) {

    /** Rows on screen this pass. Everything after them is measured and then left alone. */
    private var shown = 0

    /** Notifications not on screen: dropped for space here, plus [extra] never handed over. */
    private var hidden = 0

    /** Height of the rows that fit, carried between [fitIn] and [onMeasure]. */
    private var used = 0

    /** Notes the caller had but did not add, because `MAX_NOTES` cut them off first. */
    var extra = 0
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
        }

    init {
        addView(more)
    }

    /** Rows only — the `+N MORE` line is furniture and is never one of them. */
    private fun rows(): Int = childCount - 1

    private fun row(i: Int): View = getChildAt(i + 1)

    /** The rows a finger can actually land on. Used by the swipe; see [LockOverlay]. */
    fun visibleRows(): List<View> = (0 until shown.coerceAtMost(rows())).map { row(it) }

    /** Everything but [more], which outlives every fill. */
    fun clearRows() {
        if (childCount > 1) removeViews(1, childCount - 1)
        shown = 0
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val inner = (width - paddingLeft - paddingRight).coerceAtLeast(0)
        val across = MeasureSpec.makeMeasureSpec(inner, MeasureSpec.EXACTLY)
        val free = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        val mode = MeasureSpec.getMode(heightMeasureSpec)
        val budget = if (mode == MeasureSpec.UNSPECIFIED) {
            Int.MAX_VALUE
        } else {
            (MeasureSpec.getSize(heightMeasureSpec) - paddingTop - paddingBottom).coerceAtLeast(0)
        }

        for (i in 0 until childCount) getChildAt(i).measure(across, free)

        // Two passes, because the `+N MORE` line only takes a line when something is missing, and
        // whether anything is missing is what the first pass is working out. A single pass that
        // always reserved it would throw away a row on the common case of everything fitting.
        var fit = fitIn(budget)
        if (fit < rows() || extra > 0) fit = fitIn(budget - more.measuredHeight)

        shown = fit
        hidden = (rows() - fit) + extra
        setMeasuredDimension(
            width,
            paddingTop + paddingBottom + used + if (hidden > 0) more.measuredHeight else 0,
        )
        count()
    }

    /** How many rows fit in [budget], and how tall they came to. */
    private fun fitIn(budget: Int): Int {
        var total = 0
        var count = 0
        for (i in 0 until rows()) {
            val height = row(i).measuredHeight
            if (total + height > budget) break
            total += height
            count++
        }
        used = total
        return count
    }

    /**
     * The number on the `+N MORE` line, written on the next frame rather than in this one.
     *
     * `setText` calls `requestLayout`, and calling that from inside a measure pass is the classic
     * way to get a layout that never settles. Posting it cannot loop: the line is one line tall
     * whatever number is on it, so the text that lands next frame changes nothing this pass
     * measured.
     */
    private fun count() {
        val want = if (hidden > 0) "+$hidden MORE" else ""
        if (more.text?.toString() == want) return
        post { more.text = want }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val x = paddingLeft
        var y = paddingTop
        for (i in 0 until shown) {
            val child = row(i)
            child.layout(x, y, x + child.measuredWidth, y + child.measuredHeight)
            y += child.measuredHeight
        }
        if (hidden > 0) more.layout(x, y, x + more.measuredWidth, y + more.measuredHeight)
    }

    /**
     * A row that did not fit is still a child, and a `ViewGroup` paints every child it has —
     * including one it never laid out, at whatever bounds it held last time. Skipping it here is
     * what makes the clamp visible rather than theoretical.
     */
    override fun drawChild(canvas: Canvas, child: View, drawingTime: Long): Boolean {
        if (child === more) {
            if (hidden <= 0) return false
        } else if (indexOfChild(child) > shown) {
            return false
        }
        return super.drawChild(canvas, child, drawingTime)
    }
}
