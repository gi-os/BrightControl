package com.gios.lightcontrol.switcher

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.gios.lightcontrol.lock.LightType

/**
 * The app switcher LightOS does not have, drawn by the service that owns the home button.
 *
 * ### Why a window rather than an activity
 *
 * The same reason the lock face is one (see `lock/LockOverlay`), plus one of its own. A
 * `TYPE_ACCESSIBILITY_OVERLAY` sits at layer 31, above anything an app or LightOS can put on
 * screen, so this appears over whatever you were looking at without starting anything, without
 * the background-activity-start appop, and with no task of its own to get stuck in. It also
 * cannot disturb the list it is showing: an activity would push itself onto the recents order it
 * exists to display, so the switcher would always be the last thing you used.
 *
 * `performGlobalAction(GLOBAL_ACTION_RECENTS)` is deliberately not what this does. That asks
 * SystemUI for a recents screen and this phone ships no such screen: the call returns true for
 * "injected" and nothing appears, which is the worst answer available — a gesture that reports
 * success and does nothing.
 *
 * ### What it takes
 *
 * Keys, while it is up — the wheel moves the selection, a wheel click opens what is under it,
 * home puts it away. Those are read by the service (`ControlService.onSwitcherKey`) and not
 * here: this window is `FLAG_NOT_FOCUSABLE`, so it never holds key focus and can never be the
 * reason a key stops arriving somewhere. Touches it does take: a row opens that app, anywhere
 * else closes it.
 *
 * It also closes itself after [IDLE_MS] with nothing pressed. A full-screen window that can
 * outlive the user's interest in it is one you cannot get out of, and this one is bound to the
 * home button, which is the key that must never be the way a phone gets stuck.
 */
class SwitcherOverlay(private val context: Context) {

    /** One recent app: the package, and the name a person recognizes it by. */
    data class Entry(val pkg: String, val label: String)

    private val handler = Handler(Looper.getMainLooper())
    private val type = LightType(context)

    private var root: FrameLayout? = null
    private var content: View? = null
    private var column: LinearLayout? = null
    private var rows: List<TextView> = emptyList()
    private var entries: List<Entry> = emptyList()
    private var index = 0

    /** Told which package was chosen. The service does the launching — it owns the throttle. */
    var onPick: ((String) -> Unit)? = null

    val showing: Boolean get() = root != null

    /** The package under the selection, or null when nothing is up. */
    val selected: String? get() = entries.getOrNull(index)?.pkg

    private val idle = Runnable { hide() }

    /**
     * Put it up. False means nothing was added, and the caller must not swallow keys for it.
     *
     * An empty list is a refusal, not an error: it is the ordinary state of a phone that has
     * been used for one thing since it booted.
     */
    fun show(list: List<Entry>): Boolean {
        if (list.isEmpty()) return false
        entries = list
        index = 0
        if (root != null) {
            column?.let { runCatching { fill(it) } }
            runCatching { enter() }
            arm()
            return true
        }
        val wm = context.getSystemService(WindowManager::class.java) ?: return false
        val view = build() ?: return false
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // Not focusable, for the same reason the lock face is not: key focus belongs to
            // whatever was already holding it. The service reads our keys off the filter instead.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }
        val added = runCatching { wm.addView(view, params); true }.getOrDefault(false)
        if (!added) return false
        root = view
        runCatching { enter() }
        arm()
        return true
    }

    /** Move the selection by [delta], wrapping — the wheel has no ends. */
    fun move(delta: Int) {
        if (entries.isEmpty()) return
        index = ((index + delta) % entries.size + entries.size) % entries.size
        paint()
        arm()
    }

    /** Take the selection, and get out of the way. */
    fun choose() {
        val pkg = selected
        hide()
        if (pkg != null) onPick?.invoke(pkg)
    }

    /**
     * Take it down.
     *
     * The reference is dropped only after the removal succeeds — a window this app has forgotten
     * about but the system still has is a black screen with nothing left to close it.
     */
    fun hide(): Boolean {
        handler.removeCallbacks(idle)
        val view = root ?: return true
        val wm = context.getSystemService(WindowManager::class.java) ?: return false
        val gone = runCatching { wm.removeView(view); true }
            .getOrElse { runCatching { wm.removeViewImmediate(view); true }.getOrDefault(false) }
        if (!gone) return false
        root = null
        content = null
        column = null
        rows = emptyList()
        entries = emptyList()
        index = 0
        return true
    }

    /**
     * The entrance: black, and the list rising into it.
     *
     * v3.16 filled the background with an animated Bayer dither instead — grey cells on black,
     * sweeping down the screen while the grain grew. It read as noise on the device rather than
     * as texture, so it is gone and the ground is plain black again. What is left is the list's
     * own arrival: a little under a grid unit of travel, enough to read as movement and not
     * enough to be a slide.
     */
    private fun enter() {
        val view = content ?: return
        view.animate().cancel()
        view.alpha = 0f
        view.translationY = type.gridPx(1.5f).toFloat()
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay(CONTENT_DELAY_MS)
            .setDuration(CONTENT_MS)
            .start()
    }

    private fun arm() {
        handler.removeCallbacks(idle)
        handler.postDelayed(idle, IDLE_MS)
    }

    // ------------------------------------------------------------------------ the view

    private fun build(): FrameLayout? = runCatching {
        val frame = FrameLayout(context).apply {
            // Opaque from the first frame. Whatever is behind this window is an app the list is
            // there to replace, and a translucent switcher is two screens at once.
            background = ColorDrawable(Color.BLACK)
            // Anywhere that isn't a row means "no thanks".
            isClickable = true
            setOnClickListener { hide() }
        }
        val scroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        }
        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            // The SDK's grid: one unit in from the sides, three of top bar. The same figures as
            // the lock face, so the two screens this app draws look like one phone.
            setPadding(type.gridPx(1f), type.gridPx(3f), type.gridPx(1f), type.gridPx(3f))
        }
        scroll.addView(
            col,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        frame.addView(scroll)
        content = scroll
        column = col
        fill(col)
        frame
    }.getOrNull()

    private fun fill(col: LinearLayout) {
        col.removeAllViews()
        col.addView(
            TextView(context).apply {
                text = "RECENT"
                setTextColor(DIM)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, type.superfine * SCALE)
                typeface = type.medium
                letterSpacing = type.buttonTracking
                setPadding(0, 0, 0, type.gridPx(1f))
            },
        )
        rows = entries.map { entry ->
            TextView(context).apply {
                text = entry.label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, type.copy * SCALE)
                typeface = type.regular
                setPadding(0, type.gridPx(0.75f), 0, type.gridPx(0.75f))
                isClickable = true
                setOnClickListener {
                    val pkg = entry.pkg
                    hide()
                    onPick?.invoke(pkg)
                }
                col.addView(this)
            }
        }
        col.addView(
            TextView(context).apply {
                text = "Wheel to move · click to open · home to close"
                setTextColor(DIM)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, type.superfine * SCALE)
                typeface = type.regular
                setPadding(0, type.gridPx(2f), 0, 0)
            },
        )
        paint()
    }

    /** The selection is drawn as brightness, not as a box — this phone has no color to spend. */
    private fun paint() {
        rows.forEachIndexed { i, row ->
            row.setTextColor(if (i == index) Color.WHITE else DIM)
            row.typeface = if (i == index) type.medium else type.regular
        }
        val row = rows.getOrNull(index) ?: return
        val scroll = row.parent?.parent as? ScrollView ?: return
        row.post { runCatching { scroll.smoothScrollTo(0, row.top - row.height) } }
    }

    private companion object {
        /** LightOS's own secondary grey. The same value as the app's Compose `Dim`. */
        val DIM: Int = Color.rgb(0x9A, 0x9A, 0x9A)

        /** How long it waits with nothing pressed before closing itself. */
        const val IDLE_MS = 6_000L

        /**
         * Every size on this screen, over the SDK's own scale.
         *
         * The one deliberate departure from `LightType` in this app. The switcher is read at
         * arm's length in the second between deciding to leave an app and leaving it, which is
         * not the reading distance the SDK's body scale is set for, and a list of eight rows has
         * the room to spend.
         */
        const val SCALE = 1.15f

        /** How far behind the entrance the list comes in, and how long it takes. */
        const val CONTENT_DELAY_MS = 90L
        const val CONTENT_MS = 220L
    }
}
