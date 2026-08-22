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
import kotlin.math.roundToInt

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

    private val handler = Handler(Looper.getMainLooper())
    private val hide = Runnable { detach() }

    private var root: LinearLayout? = null
    private var title: TextView? = null
    private var bar: SegmentBar? = null

    /**
     * Show [level] of [max] for the stream named [stream].
     *
     * [note] replaces the percentage when a number is the wrong answer — the ringer set to vibrate
     * or silent, where the level is not what the state means.
     *
     * Segments are the stream's own steps, because that is what a press moves: 15 for media on
     * this phone, 7 for the ringer. A scale with more notches than a thumb can count is collapsed
     * to [MAX_SEGMENTS] rather than drawn as hairlines.
     */
    fun show(stream: String, level: Int, max: Int, note: String? = null, pinned: Boolean = false) {
        if (!allowed()) return
        // A HUD on a screen nobody is looking at is a window added and removed for nothing — and
        // volume can change while the phone is in a pocket, which is much of when it does.
        if (!screenOn()) return
        attach()
        val safeMax = max.coerceAtLeast(1)
        val pct = (level * 100f / safeMax).roundToInt()
        title?.text = "$stream · " + (note ?: if (level == 0) "SILENT" else "$pct%") +
            if (pinned) " · PIN" else ""
        val segments = if (safeMax <= MAX_SEGMENTS) safeMax else MAX_SEGMENTS
        val filled = if (safeMax <= MAX_SEGMENTS) {
            level
        } else {
            (level * segments.toFloat() / safeMax).roundToInt()
        }
        bar?.set(segments, filled.coerceIn(0, segments))
        handler.removeCallbacks(hide)
        // A pin is something you are in the middle of using, so it gets longer to be used in.
        handler.postDelayed(hide, if (pinned) PIN_DWELL_MS else DWELL_MS)
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
            }
    }

    private fun detach() {
        val box = root ?: return
        root = null
        title = null
        bar = null
        val wm = context.getSystemService(WindowManager::class.java) ?: return
        runCatching { wm.removeView(box) }
    }

    /** Called when the service is unbound, so a stray overlay can't outlive it. */
    fun dismiss() {
        handler.removeCallbacks(hide)
        detach()
    }

    /**
     * The level as notches: filled boxes for where you are, outlines for the rest.
     *
     * Discrete rather than a continuous bar because the control is discrete — one press is one
     * segment, so the bar answers "how many more presses" and not only "roughly how loud". Two
     * colors, square corners, no animation: the whole of LightOS's visual vocabulary.
     */
    private class SegmentBar(context: Context) : View(context) {

        private val fill = Paint().apply {
            color = Color.WHITE
            isAntiAlias = false
        }
        private val empty = Paint().apply {
            // contentSecondary, the SDK's third and last color, dimmed for a black background.
            color = Color.parseColor("#4A4A4A")
            isAntiAlias = false
        }

        private var count = 0
        private var filled = 0

        fun set(count: Int, filled: Int) {
            this.count = count
            this.filled = filled
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            if (count <= 0) return
            // Narrower gap to go with the shorter bar — at half the height the old 2dp gutters
            // read as more space than segment.
            val gap = 1.5f * resources.displayMetrics.density
            val slot = (width + gap) / count
            val w = slot - gap
            if (w <= 0f) return
            for (i in 0 until count) {
                val left = i * slot
                canvas.drawRect(
                    left,
                    0f,
                    left + w,
                    height.toFloat(),
                    if (i < filled) fill else empty,
                )
            }
        }
    }

    private companion object {
        /** Long enough to read after the last press, short enough not to sit on the screen. */
        const val DWELL_MS = 1_400L

        /** Longer, once a stream is pinned: the presses that use the pin come after the tap. */
        const val PIN_DWELL_MS = 4_000L

        /** Above this many steps the notches stop being countable and become a bar. */
        const val MAX_SEGMENTS = 20
    }
}
