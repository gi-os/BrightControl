package com.gios.lightcontrol.lock

import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView

/**
 * The notification column on the lock face: as many whole notifications as the screen has room
 * for, and a drag to reach the rest.
 *
 * Two problems, and they are the same problem. The list used to be a plain `LinearLayout` at the
 * bottom of a weighted column, which means the space it got was whatever was left over and the
 * views it held were taller than that — so the third notification was drawn with its bottom half
 * missing, every time. Raising or lowering `MAX_NOTES` cannot fix that, because the number that
 * fits depends on how many lines each of them happens to have.
 *
 * So the count is not decided in advance at all. This measures the rows it was given, keeps the
 * ones that fit **whole**, and scrolls to the ones that do not.
 *
 * ### Why the touch handling is the careful part
 *
 * The face is dismissed by swiping up — that is how the keypad is reached, and it is the one
 * gesture on this screen that must never break. A `ScrollView` swallows touches whether or not it
 * has anything to scroll, so dropping one into that column would have made a band across the
 * middle of the lock screen where the swipe simply stopped working.
 *
 * Hence [onTouchEvent] returning `false` outright when there is nothing to scroll: the event is
 * never claimed, the parent's listener sees the gesture exactly as it did before, and the notes
 * only take over the drag in the one case where taking it over is the point. Nothing here asks
 * for the bouncer, dismisses the keyguard, or starts anything — a drag in this column moves this
 * column and does nothing else, which is the whole request.
 */
class NoteScroll(context: Context) : ScrollView(context) {

    /** Where the rows go. Owned here so nothing outside has to know this is a scroller. */
    val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    init {
        isVerticalScrollBarEnabled = false
        // No glow and no rubber band. Both read as a surface being pulled, and this one is a list
        // of four lines of text on a lock screen.
        overScrollMode = View.OVER_SCROLL_NEVER
        isFillViewport = false
        addView(
            list,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
    }

    /** Back to the newest, for when the contents have changed under it. */
    fun toTop() = scrollTo(0, 0)

    private val scrollable: Boolean
        get() = canScrollVertically(1) || canScrollVertically(-1)

    /**
     * Shrink to the last row boundary that fits.
     *
     * `super` has already clamped us to the space the column had left, which is the height that
     * cuts a row in half. This walks the rows it measured and gives back the tallest height that
     * ends where a row ends, so the bottom edge of this view is always the bottom edge of a
     * notification.
     *
     * The child keeps its full height — that is what there is to scroll — so this makes the
     * viewport shorter, never the content.
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val available = measuredHeight
        if (list.measuredHeight <= available) return

        val fit = NoteFit.visibleHeight(rows().map { it.measuredHeight }, available)
        // Nothing whole fits — one very tall notification in a very short gap. A part of it beats
        // an empty screen, so the clamped height stands.
        if (fit > 0) setMeasuredDimension(measuredWidth, fit)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean =
        scrollable && super.onInterceptTouchEvent(ev)

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        // Not claimed unless there is something to scroll. See the class comment: this is what
        // keeps swipe-up-for-the-keypad working over the notifications.
        if (!scrollable) return false
        val handled = super.onTouchEvent(ev)
        if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
            snap()
        }
        return handled
    }

    /**
     * No fling.
     *
     * A list this short does not need momentum, and momentum is what would leave it resting
     * mid-notification — the exact thing this class exists to prevent. The finger moves it and
     * letting go settles it.
     */
    override fun fling(velocityY: Int) = Unit

    /** Settle on a row boundary, or flush against the end of the list. */
    private fun snap() {
        val max = (list.height - height).coerceAtLeast(0)
        val target = NoteFit.snapTarget(rows().map { it.top }, max, scrollY)
        if (target != scrollY) smoothScrollTo(0, target)
    }

    /** The notifications, in order, skipping any the face has hidden. */
    private fun rows(): List<View> = (0 until list.childCount)
        .map { list.getChildAt(it) }
        .filter { it.visibility != View.GONE }
}
