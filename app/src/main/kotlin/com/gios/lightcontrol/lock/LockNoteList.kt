package com.gios.lightcontrol.lock

import android.content.Context
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

/**
 * The shade on the lock face: clamped to the room it has, and scrollable through the rest.
 *
 * A `LinearLayout` was drawing four notifications into whatever vertical space was left after the
 * clock, and when four did not fit it drew them anyway: the column is `MATCH_PARENT` inside a
 * window that is the whole panel, so the overflow ran off the bottom edge and the face showed two
 * notifications and the top half of a third.
 *
 * So the list clamps. The parent measures this with `AT_MOST` of exactly the space left over,
 * which is the one number that says how much fits, and the rows are laid out inside it whether or
 * not they all fit — everything past the fold is clipped rather than spilled.
 *
 * **And now it scrolls.** A vertical drag on the list moves [offset]; the gesture is recognised in
 * [LockOverlay]'s frame, which owns every touch on this window, and handed here as pixels. It was
 * once true that nothing here could scroll, on the argument that every drag already meant
 * something else. It stopped being true when the only other vertical gesture — swipe up for the
 * keypad — was replaced by the Home button. A drag up the face now has nothing else to mean, and a
 * notification you cannot reach is a notification you cannot read.
 *
 * The `+N MORE` line is [more], owned here so its count can follow the fold. It is child 0 and is
 * pinned at the foot rather than scrolled, because a marker of what is below has to stay where the
 * bottom is; rows are children 1..n, in the order they were added.
 */
class LockNoteList(context: Context, private val more: TextView) : ViewGroup(context) {

    /** How far down the rows have been pushed, in pixels. Never past [range]. */
    private var offset = 0

    /** Height of every row together, whether or not it fits. */
    private var content = 0

    /** The room the rows are drawn in, which is what is left after the footer takes its line. */
    private var viewport = 0

    /** Notifications wholly below the fold, plus [extra] never handed over. */
    private var hidden = 0

    /** True when the rows are taller than the room, which is the only time the footer is kept. */
    private var overflowing = false

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
    fun visibleRows(): List<View> = (0 until rows()).map { row(it) }.filter { it.bottom > 0 && it.top < viewport }

    /** Everything but [more], which outlives every fill. */
    fun clearRows() {
        if (childCount > 1) removeViews(1, childCount - 1)
    }

    /** How far the rows can be pushed. Zero when they all fit, which is the ordinary evening. */
    private fun range(): Int = (content - viewport).coerceAtLeast(0)

    /** Whether a drag on this list would move anything. Read by the frame before it takes one. */
    fun scrollable(): Boolean = range() > 0

    /**
     * Push the rows by [dy] pixels and say whether anything moved.
     *
     * Clamped at both ends and never sprung: a lock screen that bounces is a lock screen that
     * looks like it is loading. A new notification arriving re-clamps on the next measure, so a
     * list scrolled to the bottom does not end up scrolled past it when a row is swiped away.
     */
    fun scrollNotes(dy: Float): Boolean {
        val want = (offset + dy).toInt().coerceIn(0, range())
        if (want == offset) return false
        offset = want
        requestLayout()
        invalidate()
        return true
    }

    /** Back to the top, for a face being shown again. */
    fun resetScroll() {
        if (offset == 0) return
        offset = 0
        requestLayout()
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

        content = (0 until rows()).sumOf { row(it).measuredHeight }
        // The footer is reserved on a single question — do the rows overflow — and never on where
        // the list happens to be scrolled to. Reserving it by what is currently below the fold
        // would change the viewport as you scrolled, which changes what is below the fold.
        overflowing = content > budget || extra > 0
        viewport = (budget - if (overflowing) more.measuredHeight else 0).coerceAtLeast(0)
        offset = offset.coerceIn(0, range())

        setMeasuredDimension(
            width,
            paddingTop + paddingBottom + minOf(content, viewport) +
                if (overflowing) more.measuredHeight else 0,
        )
        count()
    }

    /**
     * The number on the `+N MORE` line, written on the next frame rather than in this one.
     *
     * `setText` calls `requestLayout`, and calling that from inside a measure pass is the classic
     * way to get a layout that never settles. Posting it cannot loop: the line is one line tall
     * whatever number is on it, and the space it takes is already decided by [overflowing], which
     * no number here can change.
     */
    private fun count() {
        var below = extra
        var y = 0
        for (i in 0 until rows()) {
            val height = row(i).measuredHeight
            if (y - offset >= viewport) below++
            y += height
        }
        hidden = below
        val want = if (hidden > 0) "+$hidden MORE" else ""
        if (more.text?.toString() == want) return
        post { more.text = want }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val x = paddingLeft
        var y = paddingTop - offset
        for (i in 0 until rows()) {
            val child = row(i)
            child.layout(x, y, x + child.measuredWidth, y + child.measuredHeight)
            y += child.measuredHeight
        }
        if (overflowing) {
            val top = paddingTop + viewport
            more.layout(x, top, x + more.measuredWidth, top + more.measuredHeight)
        }
    }

    /**
     * Rows are clipped to the list rather than skipped, which is what makes the scroll possible:
     * a row half over the fold is half drawn, and the half of it below is what the drag is for.
     * The footer is furniture and is only drawn when it was given room.
     */
    override fun drawChild(canvas: Canvas, child: View, drawingTime: Long): Boolean {
        if (child === more && !overflowing) return false
        return super.drawChild(canvas, child, drawingTime)
    }
}
