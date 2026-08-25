package com.gios.lightcontrol.ui

import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * The switcher's controls, applied to this app's own screens: the wheel moves a highlight from
 * row to row and a wheel click opens the highlighted one.
 *
 * ### Why the app and not the phone
 *
 * The interesting version of this is system-wide — the service walking the accessibility node
 * tree of whatever app is in front and clicking the focused node. That version needs
 * `canRetrieveWindowContent="true"` on `ControlService`, which today is false and is the reason
 * this app can honestly say it never reads what is on your screen. Turning that on is a real
 * change in what the app is, and it is worth proving the *interaction* is good before spending
 * it. So this is the same idea inside our own settings, where the rows are ours and no new
 * capability is involved.
 *
 * ### How a row becomes selectable
 *
 * By registering itself, rather than by anything walking a layout. Any row with something to do
 * calls [WheelCursor.item] and reports where it ended up on screen; the cursor sorts what it
 * holds by vertical position, which is the reading order for every screen in this app and needs
 * no list index, no key scheme and no cooperation between a screen and its rows. Rows that come
 * and go — the ones that only appear when a binding is set to Resume, say — appear and disappear
 * from the order by existing, which is exactly right and is free.
 *
 * ### Two rules learned from the switcher
 *
 *  - **Nothing is highlighted until the wheel is turned.** A screen that opens with a selection
 *    is a screen where a click does something you did not ask for; the first notch is what says
 *    you are driving.
 *  - **The list does not wrap.** The switcher wraps because eight rows on one screen have no
 *    top or bottom worth feeling. A settings screen has both, and wrapping from the last row to
 *    the first mid-scroll reads as the screen jumping rather than as the selection moving.
 */
class WheelCursor {

    private class Item(val top: Float, val bottom: Float, val activate: () -> Unit)

    private val items = LinkedHashMap<Any, Item>()

    /** The selected row's key, or null when nothing is selected yet. */
    var selected by mutableStateOf<Any?>(null)
        private set

    /**
     * Whether the wheel drives rows at all. Off returns every screen to plain scrolling.
     *
     * State rather than a preference read, because the switch for it lives on one of the screens
     * this changes the behaviour of, and a setting you have to leave the screen to see the effect
     * of is a setting nobody believes worked.
     */
    var enabled by mutableStateOf(true)

    /**
     * Whether the wheel is driving rows right now, as opposed to scrolling the page.
     *
     * Two modes, and **touching the screen is what leaves this one**. A highlight is a claim
     * about where your attention is, and the moment a finger lands on the glass that claim is
     * wrong: the row under the highlight is not the row under the thumb, and a click aimed at
     * one would open the other. So a touch drops the selection and hands the wheel back to
     * scrolling, which is what a phone with a working touchscreen should do anyway.
     *
     * A wheel click is the way back in — it highlights the first row on screen rather than
     * opening anything, because there is nothing selected for it to open. That makes the click
     * mean one consistent thing: *take the wheel seriously*, first by selecting, then by opening.
     */
    var selecting by mutableStateOf(true)
        private set

    /** Set by [WheelScroll] for whichever list is on screen. */
    private var state: ScrollableState? = null
    private var scope: CoroutineScope? = null
    private var viewTop = 0f

    /**
     * The bottom of the window, asked for rather than stored.
     *
     * A View's height is not Compose state, so a value captured at composition is whatever it was
     * before the first layout — usually zero, which makes every row look as though it is below
     * the fold. Reading it at the moment it is needed is both simpler and always right.
     */
    private var viewBottomOf: () -> Float = { 0f }

    /** True when there is anything on this screen the wheel could select. */
    val hasItems: Boolean get() = items.isNotEmpty()

    fun attach(state: ScrollableState, scope: CoroutineScope, top: Float, bottom: () -> Float) {
        this.state = state
        this.scope = scope
        viewTop = top
        viewBottomOf = bottom
    }

    fun register(key: Any, top: Float, bottom: Float, activate: () -> Unit) {
        val item = Item(top, bottom, activate)
        items[key] = item
        // Checked on every layout pass, not only when the wheel moves. This is what keeps the
        // highlight on screen: a row reports where it *actually* is after each scroll frame, so
        // any error left over from an animation that was still running when the next notch
        // arrived is corrected on the next pass instead of being carried. The first version
        // measured once, at the notch, and a fast turn scrolled the selection clean off the end.
        if (key === selected) ensureVisible(item)
    }

    fun unregister(key: Any) {
        items.remove(key)
        if (selected == key) selected = null
    }

    /**
     * Everything, top to bottom. Sorted on each use rather than kept sorted: rows move when the
     * list scrolls, so any order stored at registration time is stale by the next notch.
     */
    private fun ordered(): List<Map.Entry<Any, Item>> = items.entries.sortedBy { it.value.top }

    /**
     * Wipe the selection. Called when the screen changes — the rows under it are gone.
     *
     * [selecting] deliberately survives. Which input you are using is a fact about you, not about
     * the screen you happen to be on: somebody navigating by thumb does not want the wheel to
     * start selecting again every time a new screen opens.
     */
    fun reset() {
        items.clear()
        selected = null
    }

    /** A finger landed. Drop the highlight and give the wheel back to the page. */
    fun touched() {
        if (!selecting && selected == null) return
        selecting = false
        selected = null
    }

    /**
     * The wheel click: select, or open what is selected.
     *
     * False means it did neither and the key belongs to whoever wanted it next.
     */
    fun click(): Boolean {
        if (!enabled) return false
        if (!selecting || selected == null) {
            if (!hasItems) return false
            selecting = true
            // Down the list, which from nothing means the first row on screen. See [move].
            move(1)
            return true
        }
        return activate()
    }

    /**
     * Move by [delta] rows.
     *
     * With nothing selected this selects the first row in the direction of travel *that is on
     * screen*, not the first row of the list. Half a page down a long screen, a first notch that
     * highlighted something above the fold — and then scrolled up to show you — would be the
     * screen answering a question nobody asked.
     */
    fun move(delta: Int) {
        val rows = ordered()
        if (rows.isEmpty()) return
        val current = rows.indexOfFirst { it.key == selected }
        val next = when {
            current >= 0 -> (current + delta).coerceIn(0, rows.size - 1)
            delta > 0 -> rows.indexOfFirst { it.value.bottom > viewTop }.coerceAtLeast(0)
            else -> rows.indexOfLast { it.value.top < viewBottomOf() }.coerceAtLeast(0)
        }
        selected = rows[next].key
        ensureVisible(rows[next].value)
    }

    /** Open the highlighted row. False when nothing is highlighted, so the key can go elsewhere. */
    fun activate(): Boolean {
        val item = items[selected] ?: return false
        item.activate()
        return true
    }

    /**
     * Keep the selection on screen, with a row's worth of air beyond it.
     *
     * The air is what makes this feel like a list moving under a fixed cursor rather than like a
     * cursor sliding to the edge and dragging the page: you can always see where the next press
     * is going.
     */
    private fun ensureVisible(item: Item) {
        val scroller = state ?: return
        val runner = scope ?: return
        val viewBottom = viewBottomOf()
        if (viewBottom <= viewTop) return
        // A row of air past the selection, so you can see where the next press is going — unless
        // the row is so tall that asking for air on both sides is unsatisfiable, which is how a
        // self-correcting scroller turns into one that oscillates forever.
        val height = (item.bottom - item.top).coerceAtLeast(1f)
        val air = if (height * 3f < viewBottom - viewTop) height else 0f
        val by = when {
            item.bottom + air > viewBottom -> item.bottom + air - viewBottom
            item.top - air < viewTop -> item.top - air - viewTop
            else -> return
        }
        // Under a couple of pixels is not worth a frame, and it is the difference that rounding
        // alone can produce — which would be a scroll on every layout pass, forever.
        if (abs(by) < MIN_SCROLL_PX) return
        // One scroll at a time. A second animation started over a live one fights it, and the
        // loser is whichever set of coordinates was read first.
        if (job?.isActive == true) return
        job = runner.launch { runCatching { scroller.animateScrollBy(by) } }
    }

    private var job: Job? = null
}

/** The cursor for the screen on show, or null when the feature is off. */
val LocalCursor = staticCompositionLocalOf<WheelCursor?> { null }

/** What a row needs to be one of the wheel's stops: whether it is the one, and where it is. */
class CursorStop(val selected: Boolean, val modifier: Modifier)

/**
 * Make this row one of the wheel's stops.
 *
 * The key is a plain object remembered by the call site, so two rows with the same words are
 * still two rows and a row that scrolls off and back is still itself. The position is reported on
 * every layout pass, because the coordinates are what the ordering is built from and they change
 * whenever the list moves.
 *
 * A row with no [onActivate] is not a stop. There is nothing for a click to do on it, and a
 * highlight that lands somewhere a press does nothing is worse than one row of extra travel.
 */
@Composable
fun cursorStop(onActivate: (() -> Unit)?): CursorStop {
    val cursor = LocalCursor.current
    if (cursor == null || !cursor.enabled || onActivate == null) return CursorStop(false, Modifier)
    val key = remember { Any() }
    DisposableEffect(cursor, key) {
        onDispose { cursor.unregister(key) }
    }
    // Through the latest lambda, never the one that happened to be current when the row was last
    // laid out. A toggle's onClick closes over the value it is flipping, and a row that changes
    // its words without changing its size does not re-lay-out — so a captured lambda would go on
    // flipping from a value that is one press old, which reads as a switch that will not stick.
    val latest = rememberUpdatedState(onActivate)
    // Position and size, not `boundsInWindow()`. That answer is clipped to the parent, so a row
    // scrolled under the top bar reports a sliver at the clip edge — which corrupts both the
    // ordering and the arithmetic that decides whether the selection is still on screen.
    val modifier = Modifier.onGloballyPositioned { coords ->
        val top = coords.positionInWindow().y
        cursor.register(key, top, top + coords.size.height) { latest.value.invoke() }
    }
    return CursorStop(cursor.selected === key, modifier)
}

/** Below this, a correction is rounding rather than a scroll. See [WheelCursor.ensureVisible]. */
private const val MIN_SCROLL_PX = 2f
