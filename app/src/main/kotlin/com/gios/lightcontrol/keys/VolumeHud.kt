package com.gios.lightcontrol.keys

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * The volume, at the top of the screen, over whatever app is in front.
 *
 * LightOS ships no volume UI at all. The keys work — the system changes the volume, media
 * responds — but nothing on screen says so, so the only way to find a level is to keep pressing
 * until it is too loud and then come back down. On the ring and alarm streams, where there is no
 * sound to judge in the moment, there is no feedback whatsoever: a silent phone and a phone at one
 * notch look identical until something arrives.
 *
 * **It was only ever a readout, and from v3.94 it is a control.** "It reports; it never adjusts"
 * was the rule here for eleven releases, and it was the right rule while the only way to touch it
 * was a key: a key filter must never take a working control away to add one. A finger on a bar this
 * app drew takes nothing from anybody. So the bar is a slider — drag it and the volume goes there —
 * and the panel behind it is one slider per stream, which is the only way the ringer and alarm
 * levels can be reached on this phone at all.
 *
 * What is still true is the part that mattered: **no volume key is consumed** unless a stream has
 * been pinned deliberately, which is its own setting and off by default. See [VolumeWatcher].
 *
 * Top of the screen rather than the bottom, unlike the brightness readout: brightness is judged by
 * looking at the screen, so its readout stays clear of what you are reading, while volume is what
 * you glance up at with a thumb already on the key.
 *
 * Views, not Compose — a service has no lifecycle owner, and this is text and rectangles.
 */
class VolumeHud(private val context: Context) {

    /**
     * The strip's label was tapped: open the panel. The bar itself is a slider, so the label is
     * what is left to tap, and "which volume" is the right question for it to ask.
     */
    var onTap: (() -> Unit)? = null

    /** A row of the panel was chosen — let the hardware keys move that stream. */
    var onPick: ((Int) -> Unit)? = null

    /** A bar was dragged. The stream is an `AudioManager.STREAM_*`, the level an index on it. */
    var onSetLevel: ((Int, Int) -> Unit)? = null

    /**
     * The ringer-mode row was tapped. Returns the state to show afterwards, or null if the change
     * was refused — muting a phone needs Do Not Disturb access, and a row that silently did nothing
     * would be the third undiagnosable thing in this file.
     */
    var onCycleRinger: (() -> String?)? = null

    private val handler = Handler(Looper.getMainLooper())
    private val hide = Runnable { detach() }

    private var root: LinearLayout? = null
    private var title: TextView? = null
    private var bar: LevelBar? = null

    /**
     * A finger is on a bar. Nothing may take the window away while that is true.
     *
     * Held as a *time* rather than a boolean, and checked against the window, for the same reason
     * [picking] is derived: this suppresses everything arriving from outside, so a flag left set
     * with no finger and no window would be a HUD that had stopped appearing for good. A touch that
     * never delivers its release — the window taken away mid-gesture — is exactly how that happens.
     * Two seconds of silence ends it whatever the view thinks.
     */
    private var dragAt = 0L

    private val dragging: Boolean
        get() = root != null && dragAt != 0L &&
            android.os.SystemClock.uptimeMillis() - dragAt < DRAG_GRACE_MS

    /**
     * Whether the window currently holds the panel rather than the one-line strip.
     *
     * Derived from the window rather than kept as a plain flag, and deliberately. [VolumeWatcher]
     * drops a volume change while this is true, so a flag left true with no window on screen would
     * be a HUD that had stopped appearing at all, permanently, with nothing on the phone to say
     * why. Tying it to [root] makes that state unreachable.
     */
    val picking: Boolean get() = pickerUp && root != null

    private var pickerUp = false

    /** What the panel lists. */
    sealed interface Row {

        /** One stream, drawn as a slider. */
        data class Level(
            val stream: Int,
            val name: String,
            val level: Int,
            val max: Int,
            val note: String? = null,
            val current: Boolean = false,
        ) : Row

        /**
         * The ringer's mode, which is not a level and cannot be one.
         *
         * Normal, vibrate and silent are three states of one switch, and the bottom of the ring
         * slider is only the first of them. Dragging to zero gets you vibrate on this phone and
         * there is nothing further to drag — so getting from vibrate to silent had no gesture at
         * all, which is exactly the sort of thing LightOS leaves to an app to notice.
         */
        data class Mode(val name: String, val state: String, val hint: String) : Row
    }

    /**
     * Show [level] of [max] for the stream named [stream].
     *
     * [note] is for the states a level cannot express — the ringer on vibrate or silent, where the
     * number is not what the setting means.
     *
     * No percentage. A number that changes every press reads as the thing to watch, and it is the
     * wrong thing: what a glance at this strip is for is *roughly how loud*, which the bar already
     * says, and the label's job is which stream the keys are moving. The percentage was also a lie
     * about precision — a 7-step ringer cannot be at 43%.
     */
    fun show(
        stream: String,
        streamId: Int,
        level: Int,
        max: Int,
        note: String? = null,
        pinned: Boolean = false,
    ) {
        if (!allowed()) {
            VolumeSignals.note("no overlay permission — see Setup")
            return
        }
        // A HUD on a screen nobody is looking at is a window added and removed for nothing — and
        // volume can change while the phone is in a pocket, which is much of when it does.
        if (!screenOn()) {
            VolumeSignals.note("the screen was off")
            return
        }
        // A finger is on the panel. Nothing arriving from outside gets to replace it mid-drag.
        if (dragging) return
        // Coming back from the panel: the list's window is not this one.
        if (picking) detach()
        attach()
        if (root == null) {
            VolumeSignals.note("the window would not attach")
            return
        }
        val safeMax = max.coerceAtLeast(1)
        title?.text = stream +
            (note?.let { " · $it" } ?: if (level == 0) " · SILENT" else "") +
            if (pinned) " · PIN" else ""
        bar?.bind(streamId, level, safeMax)
        VolumeSignals.noteShown("$stream $level/$safeMax")
        handler.removeCallbacks(hide)
        // A pin is something you are in the middle of using, so it gets longer to be used in.
        handler.postDelayed(hide, if (pinned) PIN_DWELL_MS else DWELL_MS)
    }

    /**
     * Every volume at once, each one a slider.
     *
     * This replaced a tap that walked the streams one at a time. Cycling meant the only way to
     * reach the alarm was to tap past the ringer, inside a strip that disappears — four taps to
     * arrive somewhere, each one changing which stream the keys would move if you stopped. A panel
     * says what there is, says where each one sits, and lets you drag the one you meant.
     *
     * Same window and same rules: unfocusable, so no key can be swallowed, and
     * `FLAG_NOT_TOUCH_MODAL`, so every touch outside it goes to the app underneath.
     */
    fun showPicker(rows: List<Row>) {
        if (!allowed() || !screenOn() || rows.isEmpty()) return
        detach()
        val wm = context.getSystemService(WindowManager::class.java) ?: return
        val density = context.resources.displayMetrics.density
        val config = context.resources.configuration
        val unit = config.screenWidthDp / 27f * density
        val labelSp = 24.5f * config.screenHeightDp / 600f
        // `caption` on the SDK's scale, for the lines that are not a choice.
        val headSp = 19f * config.screenHeightDp / 600f

        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = ColorDrawable(Color.BLACK)
            setPadding(unit.toInt(), (unit * 0.38f).toInt(), unit.toInt(), (unit * 0.5f).toInt())
            // A tap on the padding between rows closes the panel rather than falling through to the
            // app. A list that sometimes swallows a tap and sometimes does not is worse than either.
            isClickable = true
            setOnClickListener { dismiss() }
        }
        box.addView(
            TextView(context).apply {
                text = "DRAG A BAR · TAP A NAME"
                setTextColor(Color.parseColor(DIM))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, headSp)
                letterSpacing = 0.15f
                isSingleLine = true
            },
        )
        for (row in rows) {
            when (row) {
                is Row.Level -> box.addView(levelRow(row, unit, density, labelSp))
                is Row.Mode -> box.addView(modeRow(row, unit, labelSp, headSp))
            }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP }

        runCatching { wm.addView(box, params) }
            .onSuccess {
                root = box
                title = null
                bar = null
                pickerUp = true
                keepOpen()
            }
    }

    private fun levelRow(row: Row.Level, unit: Float, density: Float, labelSp: Float): View {
        val line = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = (unit * 0.3f).toInt() }
        }
        val name = TextView(context).apply {
            text = row.name + (row.note?.let { " · $it" } ?: "") + if (row.current) " ·" else ""
            setTextColor(if (row.current) Color.WHITE else Color.parseColor(DIM))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, labelSp)
            letterSpacing = 0.15f
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
            // The name is the tap target, because the bar under it is a slider now. Tapping it
            // hands the hardware keys to this stream.
            isClickable = true
            setOnClickListener { runCatching { onPick?.invoke(row.stream) } }
        }
        line.addView(name)
        line.addView(
            LevelBar(context).apply {
                bind(row.stream, row.level, row.max.coerceAtLeast(1))
                onSet = { stream, level -> runCatching { onSetLevel?.invoke(stream, level) } }
                onGrab = { dragAt = android.os.SystemClock.uptimeMillis(); handler.removeCallbacks(hide) }
                onRelease = { dragAt = 0L; keepOpen() }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    (unit * TOUCH_UNITS).toInt().coerceAtLeast((28 * density).toInt()),
                ).apply { topMargin = (unit * 0.1f).toInt() }
            },
        )
        return line
    }

    private fun modeRow(row: Row.Mode, unit: Float, labelSp: Float, headSp: Float): View {
        val line = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = (unit * 0.3f).toInt() }
            isClickable = true
        }
        val state = TextView(context).apply {
            text = "${row.name} · ${row.state}"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, labelSp)
            letterSpacing = 0.15f
            isSingleLine = true
        }
        val hint = TextView(context).apply {
            text = row.hint
            setTextColor(Color.parseColor(DIM))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, headSp)
            letterSpacing = 0.15f
            isSingleLine = true
        }
        line.addView(state)
        line.addView(hint)
        line.setOnClickListener {
            keepOpen()
            val next = runCatching { onCycleRinger?.invoke() }.getOrNull()
            if (next == null) {
                hint.text = "needs DND access — see Volume in the app"
            } else {
                state.text = "${row.name} · $next"
                hint.text = row.hint
            }
        }
        return line
    }

    fun allowed(): Boolean =
        runCatching { Settings.canDrawOverlays(context) }.getOrDefault(false)

    private fun screenOn(): Boolean = runCatching {
        context.getSystemService(PowerManager::class.java)?.isInteractive ?: true
    }.getOrDefault(true)

    /** Push the dismissal back. Every touch on the panel buys more time on it. */
    private fun keepOpen() {
        handler.removeCallbacks(hide)
        handler.postDelayed(hide, PICKER_DWELL_MS)
    }

    private fun attach() {
        if (root != null) return
        val wm = context.getSystemService(WindowManager::class.java) ?: return
        val density = context.resources.displayMetrics.density
        val config = context.resources.configuration

        // LightOS's grid: 27 units across the screen, and every inset and height is units of it.
        val unit = config.screenWidthDp / 27f * density
        // And its type scale: design pixels over a 600dp-tall reference screen. 24.5 design px is
        // `paragraph` — a step down from the `button` size bar labels use, because this is a glance
        // and not a control, and the strip is half the height it was.
        val labelSp = 24.5f * config.screenHeightDp / 600f

        val text = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, labelSp)
            letterSpacing = 0.15f
            gravity = Gravity.START
            // One line, always: the strip's whole point is being short, and a stream name wrapping
            // would double its height at the moment it is covering someone's app.
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
            // The label opens the panel. It is the tap target now that the bar is a slider, and it
            // is the better one anyway: the label is what says which volume this is.
            isClickable = true
            setOnClickListener { runCatching { onTap?.invoke() } }
        }
        val slider = LevelBar(context).apply {
            onSet = { stream, level -> runCatching { onSetLevel?.invoke(stream, level) } }
            onGrab = { dragAt = android.os.SystemClock.uptimeMillis(); handler.removeCallbacks(hide) }
            onRelease = {
                dragAt = 0L
                handler.removeCallbacks(hide)
                // Longer than a glance: a strip you have just used is one you may use again.
                handler.postDelayed(hide, PIN_DWELL_MS)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (unit * TOUCH_UNITS).toInt().coerceAtLeast((28 * density).toInt()),
            ).apply { topMargin = (unit * 0.1f).toInt() }
        }
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = ColorDrawable(Color.BLACK)
            val padH = unit.toInt()
            // Half what it was. The first pass was a LightOS bar's worth of padding, which is right
            // for something you tap and too much for something that appears for a second: the strip
            // covered the top of every app it flashed over.
            val padV = (unit * 0.38f).toInt()
            setPadding(padH, padV, padH, padV)
            addView(text)
            addView(slider)
            // Touchable, so the strip can be used at all. The window is only as tall as the strip
            // and `FLAG_NOT_TOUCH_MODAL` lets everything outside it through, so what this costs is
            // precisely: a touch landing on a thin bar at the very top of the screen, during the
            // seconds it is visible, goes here instead of to the app. That is the whole of it — and
            // it can never cost a *key*, because the window stays unfocusable.
            isClickable = true
            setOnClickListener { runCatching { onTap?.invoke() } }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // Not focusable, deliberately and permanently: an overlay that took key focus would
            // swallow the very presses it exists to report, which is worse than no HUD at all.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP
        }

        runCatching { wm.addView(box, params) }
            .onSuccess {
                root = box
                title = text
                bar = slider
                pickerUp = false
            }
    }

    private fun detach() {
        val box = root ?: return
        root = null
        title = null
        bar = null
        pickerUp = false
        dragAt = 0L
        val wm = context.getSystemService(WindowManager::class.java) ?: return
        runCatching { wm.removeView(box) }
    }

    /** Called when the service is unbound, so a stray overlay can't outlive it. */
    fun dismiss() {
        handler.removeCallbacks(hide)
        detach()
    }

    /**
     * The level as one solid bar, and a slider you can drag.
     *
     * It was notches — one box per press, with a gutter between them — on the argument that the
     * control is discrete, so the bar should answer "how many more presses". On a black strip the
     * gutters *are* the background, so what the eye actually read was a row of black lines through
     * the bar, and at fifteen media steps they were most of it. A bar is a bar. The step count
     * still decides where the white ends, so the level is exact; nothing draws the gap.
     *
     * **The view is much taller than the bar it draws.** The bar is a few pixels high, which is
     * right to look at and impossible to hit — so the touchable height is a finger's worth and the
     * bar is drawn down the middle of it. A control you have to aim at is not one you can use with
     * the phone half out of a pocket.
     */
    private class LevelBar(context: Context) : View(context) {

        private val fill = Paint().apply {
            color = Color.WHITE
            isAntiAlias = false
        }
        private val empty = Paint().apply {
            // contentSecondary, the SDK's third and last color, dimmed for a black background.
            color = Color.parseColor(DIM)
            isAntiAlias = false
        }

        var onSet: ((Int, Int) -> Unit)? = null
        var onGrab: (() -> Unit)? = null
        var onRelease: (() -> Unit)? = null

        private var stream = -1
        private var level = 0
        private var max = 1

        fun bind(stream: Int, level: Int, max: Int) {
            this.stream = stream
            this.max = max.coerceAtLeast(1)
            this.level = level.coerceIn(0, this.max)
            invalidate()
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (stream < 0) return false
            return when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // The window is not touch-modal and the parent has a click listener on it.
                    // Claiming the gesture here is what stops a drag that starts on the bar being
                    // read as a tap on the strip.
                    parent?.requestDisallowInterceptTouchEvent(true)
                    runCatching { onGrab?.invoke() }
                    moveTo(event.x)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    moveTo(event.x)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    moveTo(event.x)
                    runCatching { onRelease?.invoke() }
                    true
                }
                else -> false
            }
        }

        /**
         * Where the finger is, as an index on this stream's own scale.
         *
         * Rounded to the nearest step rather than truncated, so both ends are reachable: with
         * truncation the top of the scale would need the very last pixel, and on a seven-step
         * ringer that is a worse aim than the notch it is setting.
         */
        private fun moveTo(x: Float) {
            val w = width.toFloat()
            if (w <= 0f) return
            val next = ((x / w).coerceIn(0f, 1f) * max).roundToInt().coerceIn(0, max)
            if (next == level) return
            level = next
            invalidate()
            runCatching { onSet?.invoke(stream, next) }
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            // Drawn down the middle of a much taller touch target.
            val thickness = (BAR_DP * resources.displayMetrics.density).coerceAtMost(h)
            val top = (h - thickness) / 2f
            val bottom = top + thickness
            canvas.drawRect(0f, top, w, bottom, empty)
            if (level <= 0) return
            canvas.drawRect(0f, top, w * level / max, bottom, fill)
        }
    }

    private companion object {
        /** Long enough to read after the last press, short enough not to sit on the screen. */
        const val DWELL_MS = 1_400L

        /** Longer, once a stream is pinned or a bar has been dragged: it is in use. */
        const val PIN_DWELL_MS = 4_000L

        /** The panel is read and then aimed at, which takes longer than a glance. */
        const val PICKER_DWELL_MS = 8_000L

        /** How tall a bar is to the finger, in grid units. About a thumb. */
        const val TOUCH_UNITS = 1.8f

        /** How tall it is to the eye. */
        const val BAR_DP = 4f

        /** How long a touch with no release still counts as a finger on the bar. */
        const val DRAG_GRACE_MS = 2_000L

        /** contentSecondary, dimmed for black. */
        const val DIM = "#4A4A4A"
    }
}
