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
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The volume level, at the top of the screen, over whatever app is in front.
 *
 * LightOS ships no volume UI at all. The keys work — the system changes the volume, media
 * responds — but nothing on screen says so, so the only way to find a level is to keep pressing
 * until it is too loud and then come back down. On the ring and alarm streams, where there is no
 * sound to judge in the moment, there is no feedback whatsoever: a silent phone and a phone at one
 * notch look identical until something arrives.
 *
 * So this is the missing HUD, and it is deliberately *only* a HUD. It reports; it never adjusts.
 * Nothing here consumes a key, which is the whole reason it is safe to put on the volume keys —
 * they are the one pair that already works, and this codebase's rule is that a key filter must
 * never remove a function to add one. See [VolumeWatcher] for what triggers it.
 *
 * Top of the screen rather than the bottom, unlike the brightness readout: brightness is judged by
 * looking at the screen, so its readout stays clear of what you are reading, while volume is what
 * you glance up at with a thumb already on the key.
 *
 * Views, not Compose — a service has no lifecycle owner, and this is a `TextView` and some
 * rectangles.
 */
class VolumeHud(private val context: Context) {

    /**
     * Tapped. Set after construction, because the thing that answers a tap is [VolumeWatcher] and
     * the watcher needs the hud handed to it first.
     *
     * This is the one part of the HUD that is not passive, and it is why the window is touchable at
     * all. See the flags in [attach] for what that costs.
     */
    var onTap: (() -> Unit)? = null

    /**
     * A row in the selector was chosen. The stream is the `AudioManager.STREAM_*` constant, and
     * what happens next is the watcher's business — this only reports the tap.
     */
    var onPick: ((Int) -> Unit)? = null

    private val handler = Handler(Looper.getMainLooper())
    private val hide = Runnable { detach() }

    private var root: LinearLayout? = null
    private var title: TextView? = null
    private var bar: SegmentBar? = null

    /**
     * Whether the window currently holds the selector rather than the one-line strip.
     *
     * The watcher asks, because a volume broadcast arriving while the list is open must not
     * quietly replace it with a strip — that would close the list under a thumb on its way to a
     * row.
     */
    val picking: Boolean get() = pickerUp && root != null

    /**
     * Derived from the window rather than kept as a plain flag, and deliberately.
     *
     * [VolumeWatcher] drops a volume change while this is true. A flag that could be left true with
     * no window on screen would therefore be a HUD that had stopped appearing at all, permanently,
     * with nothing on the phone to say why — the worst failure this file can have, and one that
     * would look exactly like the feature being broken rather than like a stuck boolean. Tying it
     * to [root] makes that state unreachable.
     */
    private var pickerUp = false

    /** One row of the selector: a stream, what it is called, and where it currently sits. */
    data class StreamRow(
        val stream: Int,
        val name: String,
        val level: Int,
        val max: Int,
        val note: String? = null,
        val current: Boolean = false,
    )

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
    fun show(stream: String, level: Int, max: Int, note: String? = null, pinned: Boolean = false) {
        if (!allowed()) return
        // A HUD on a screen nobody is looking at is a window added and removed for nothing — and
        // volume can change while the phone is in a pocket, which is much of when it does.
        if (!screenOn()) return
        // Coming back from the selector: the list's window is not this one.
        if (picking) detach()
        attach()
        val safeMax = max.coerceAtLeast(1)
        title?.text = stream +
            (note?.let { " · $it" } ?: if (level == 0) " · SILENT" else "") +
            if (pinned) " · PIN" else ""
        bar?.set(level, safeMax)
        handler.removeCallbacks(hide)
        // A pin is something you are in the middle of using, so it gets longer to be used in.
        handler.postDelayed(hide, if (pinned) PIN_DWELL_MS else DWELL_MS)
    }

    /**
     * Show every stream at once, so one can be chosen.
     *
     * This replaced a tap that walked the streams one at a time. Cycling meant the only way to
     * reach the alarm was to tap past the ringer, inside a strip that disappears — four taps to
     * arrive somewhere, each one changing which stream the keys would move if you stopped. A list
     * says what there is, says where each one currently sits, and needs one tap to land on the one
     * you meant.
     *
     * It is still the same window and the same rules: unfocusable, so no key can be swallowed, and
     * `FLAG_NOT_TOUCH_MODAL`, so every touch outside the list goes to the app underneath.
     */
    fun showPicker(rows: List<StreamRow>) {
        if (!allowed() || !screenOn() || rows.isEmpty()) return
        detach()
        val wm = context.getSystemService(WindowManager::class.java) ?: return
        val density = context.resources.displayMetrics.density
        val config = context.resources.configuration
        val unit = config.screenWidthDp / 27f * density
        val labelSp = 24.5f * config.screenHeightDp / 600f
        // `caption` on the SDK's scale, for the one line that is not a choice.
        val headSp = 19f * config.screenHeightDp / 600f

        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = ColorDrawable(Color.BLACK)
            setPadding(unit.toInt(), (unit * 0.38f).toInt(), unit.toInt(), (unit * 0.5f).toInt())
            // The list itself is the touch target. The box is clickable so a tap on the padding
            // between rows closes it rather than falling through to the app — a list that
            // sometimes swallows a tap and sometimes does not is worse than either.
            isClickable = true
            setOnClickListener { dismiss() }
        }
        box.addView(
            TextView(context).apply {
                text = "WHICH VOLUME"
                setTextColor(Color.parseColor(DIM))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, headSp)
                letterSpacing = 0.15f
                isSingleLine = true
            },
        )
        for (row in rows) {
            val line = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = (unit * 0.4f).toInt() }
                isClickable = true
                setOnClickListener {
                    // Down before up: the callback shows the strip for the chosen stream, and it
                    // has to find the window free when it does.
                    val chosen = row.stream
                    runCatching { onPick?.invoke(chosen) }
                }
            }
            line.addView(
                TextView(context).apply {
                    text = row.name +
                        (row.note?.let { " · $it" } ?: if (row.level == 0) " · SILENT" else "") +
                        if (row.current) " ·" else ""
                    setTextColor(if (row.current) Color.WHITE else Color.parseColor(DIM))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, labelSp)
                    letterSpacing = 0.15f
                    isSingleLine = true
                    ellipsize = android.text.TextUtils.TruncateAt.END
                },
            )
            line.addView(
                SegmentBar(context).apply {
                    set(row.level, row.max.coerceAtLeast(1))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        (unit * 0.28f).toInt().coerceAtLeast((2 * density).toInt()),
                    ).apply { topMargin = (unit * 0.2f).toInt() }
                },
            )
            box.addView(line)
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
                handler.removeCallbacks(hide)
                // Long enough to read eight rows and reach for one, and it is closed by the tap
                // that chooses anyway.
                handler.postDelayed(hide, PICKER_DWELL_MS)
            }
    }

    fun allowed(): Boolean =
        runCatching { Settings.canDrawOverlays(context) }.getOrDefault(false)

    private fun screenOn(): Boolean = runCatching {
        context.getSystemService(PowerManager::class.java)?.isInteractive ?: true
    }.getOrDefault(true)

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
        }
        val segments = SegmentBar(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (unit * 0.28f).toInt().coerceAtLeast((2 * density).toInt()),
            ).apply { topMargin = (unit * 0.25f).toInt() }
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
            addView(segments)
            // Touchable, so the strip can be tapped to choose which stream the keys move. The
            // window is only as tall as the strip and `FLAG_NOT_TOUCH_MODAL` lets everything
            // outside it through, so what this costs is precisely: a tap landing on a thin bar at
            // the very top of the screen, during the second it is visible, goes here instead of to
            // the app. That is the whole of it — and it can never cost a *key*, because the window
            // stays unfocusable.
            isClickable = true
            setOnClickListener { onTap?.invoke() }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // Not focusable, deliberately and permanently: an overlay that took key focus would
            // swallow the very presses it exists to report, which is worse than no HUD at all.
            // `FLAG_NOT_TOUCHABLE` is the one that had to go, because a tap has to be able to
            // reach the strip; `FLAG_NOT_TOUCH_MODAL` keeps every touch outside it going to the
            // app underneath, and the window is `WRAP_CONTENT` tall.
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
                bar = segments
                pickerUp = false
            }
    }

    private fun detach() {
        val box = root ?: return
        root = null
        title = null
        bar = null
        pickerUp = false
        val wm = context.getSystemService(WindowManager::class.java) ?: return
        runCatching { wm.removeView(box) }
    }

    /** Called when the service is unbound, so a stray overlay can't outlive it. */
    fun dismiss() {
        handler.removeCallbacks(hide)
        detach()
    }

    /**
     * The level as one solid bar: white as far as you are, grey the rest of the way.
     *
     * It was notches — one box per press, with a gutter between them — on the argument that the
     * control is discrete, so the bar should answer "how many more presses". On a black strip the
     * gutters *are* the background, so what the eye actually read was a row of black lines through
     * the bar, and at fifteen media steps they were most of it. A bar is a bar. The count still
     * decides where the white ends, so the level is exact; nothing draws the gap.
     */
    private class SegmentBar(context: Context) : View(context) {

        private val fill = Paint().apply {
            color = Color.WHITE
            isAntiAlias = false
        }
        private val empty = Paint().apply {
            // contentSecondary, the SDK's third and last color, dimmed for a black background.
            color = Color.parseColor(DIM)
            isAntiAlias = false
        }

        private var level = 0
        private var max = 1

        fun set(level: Int, max: Int) {
            this.max = max.coerceAtLeast(1)
            this.level = level.coerceIn(0, this.max)
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            canvas.drawRect(0f, 0f, w, h, empty)
            if (level <= 0) return
            canvas.drawRect(0f, 0f, w * level / max, h, fill)
        }
    }

    private companion object {
        /** Long enough to read after the last press, short enough not to sit on the screen. */
        const val DWELL_MS = 1_400L

        /** Longer, once a stream is pinned: the presses that use the pin come after the tap. */
        const val PIN_DWELL_MS = 4_000L

        /** The selector is read and then aimed at, which takes longer than a glance. */
        const val PICKER_DWELL_MS = 8_000L

        /** contentSecondary, dimmed for black. */
        const val DIM = "#4A4A4A"
    }
}
