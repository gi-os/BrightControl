package com.gios.lightcontrol.keys

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.gios.lightcontrol.EdgeGlyph
import com.gios.lightcontrol.EdgeLength
import com.gios.lightcontrol.EdgeSide

/** Where a strip window sits down the screen, and how tall it is. See [stripBounds]. */
data class StripBounds(val top: Int, val height: Int)

/**
 * The strip's window, given the screen's height and how much of the top to leave alone.
 *
 * **Why the dead zone is a hole in the window and not a rule in the touch listener.** A window is
 * what decides who receives a touch, and that decision is made before any listener runs — an
 * overlay that receives a touch has taken it, and returning false from `onTouch` cannot hand the
 * stroke down to the app underneath. A strip that covered the corner and merely ignored what
 * happened there would leave the back arrow *unreachable* rather than reachable, which is worse
 * than the problem it was fixing.
 *
 * Two rules, both of which have to hold for a window the WindowManager will accept:
 *
 *  - `MATCH_PARENT` when nothing is being left alone, rather than an explicit full height. The two
 *    are the same number until the screen changes size under a strip already up, and only one of
 *    them is still right afterwards.
 *  - Never more than half the screen. The setting is capped in [com.gios.lightcontrol.Prefs] too,
 *    but a dp cap cannot know how tall this panel is, and a dead zone taller than the screen is a
 *    window of negative height — which the WindowManager takes as `WRAP_CONTENT` on a view that
 *    draws nothing, i.e. an edge gesture that silently stopped existing.
 *
 * Free of Android types so it can be exercised without one. See `EdgeSwipeBoundsTest`.
 */
fun stripBounds(screenPx: Int, topDeadPx: Int): StripBounds {
    val top = topDeadPx.coerceIn(0, (screenPx / 2).coerceAtLeast(0))
    if (top <= 0) return StripBounds(0, WindowManager.LayoutParams.MATCH_PARENT)
    return StripBounds(top, (screenPx - top).coerceAtLeast(1))
}

/**
 * How one edge is set up: what its two swipes do, and what the indicator says about them.
 *
 * Passed in whole rather than read here, because turning a binding into a word means resolving a
 * package id to an app name and this class has no business knowing what an [com.gios.lightcontrol.Action]
 * is. See `ControlService.edgeFace`.
 */
data class EdgeFace(
    val shortLabel: String,
    val shortGlyph: EdgeGlyph,
    /** Null for an edge with no long swipe, which is what an unbound long binding means. */
    val longLabel: String?,
    val longGlyph: EdgeGlyph,
)

/**
 * An edge gesture, on a phone with no navigation bar.
 *
 * LightOS removed the navigation bar and put a gesture-navigation switch in its own settings, and
 * that switch reaches Light's own tools. Everything sideloaded is left with no way back at all, and
 * no way to the recents list either: an app that pushes a screen and forgot to draw its own arrow is
 * a dead end until you press home. So this is the missing set of gestures, supplied from outside. A
 * thin strip down one edge of the screen, a drag inwards, and **two** bindings per edge:
 *
 *  - a **short** drag performs one,
 *  - carrying on to the **long** threshold performs the other instead.
 *
 * Both are ordinary [com.gios.lightcontrol.Action] bindings, picked from the same screen the
 * buttons use. Out of the box the two edges mirror each other: left is back then switcher, right is
 * switcher then back.
 *
 * One class, two instances. Everything that differs is [side]: which way a stroke has to travel (in
 * [BackGesture]), which side of the screen the window sits on, and which way the box grows.
 *
 * ### The strip consumes what it watches, and that is the whole cost of the feature
 *
 * There is no way to observe a touch this service does not own. Gesture detection through the
 * accessibility API needs touch exploration switched on, which changes how the entire phone is
 * driven; `dispatchGesture` sends touches and cannot receive them. What is left is an overlay
 * window, and an overlay window that receives a touch has taken it — a swallowed [MotionEvent]
 * cannot be handed back to the app underneath once the stroke has begun.
 *
 * So the honest description of this feature is: touches that begin within the strip's width of that
 * edge go to BrightControl instead of to the app. `FLAG_NOT_TOUCH_MODAL` keeps every touch outside
 * the strip going where it always went, and the window is never focusable, so this can never cost a
 * *key* — which is the rule the rest of this app is built on. It is also why each edge is off until
 * it is switched on, why the width is a setting, and why any app can be excluded from both: the
 * strip is narrow, but it is not free, and the apps where an edge is a control are exactly the ones
 * the user knows about and this app does not.
 *
 * Note that a *long* swipe costs nothing more than a short one. The strip is the same width either
 * way; only how far the finger travels afterwards differs, and by then the touch is already ours.
 *
 * ### Two windows, not one
 *
 * The strip is 14 dp wide by default, which is no room to draw anything in. The indicator is
 * therefore its own window — untouchable, positioned at the finger, and up only while a stroke is
 * in flight. The same shape BrightMusic's lock-screen overlay arrived at, for the same reason.
 *
 * Views, not Compose: a service has no lifecycle owner, and this is two rectangles and a glyph.
 */
class EdgeSwipe(private val context: Context, private val side: EdgeSide) {

    /**
     * Do the thing bound to [EdgeLength]. Returns whether it was accepted, which for some actions
     * is less than it sounds — see [com.gios.lightcontrol.Action.Back].
     *
     * Set by the service, because every action this app takes goes through one log line there.
     */
    var onFire: ((EdgeLength) -> Boolean)? = null

    /** Told when a stroke was thrown away as a scroll, for the key log. */
    var onCancelled: (() -> Unit)? = null

    private val handler = Handler(Looper.getMainLooper())

    private var strip: View? = null
    private var hud: HudView? = null
    private var hudUp = false

    private var gesture: BackGesture? = null

    /** Whether the indicator is drawn at all, and what it says. Read at each stroke. */
    private var indicator = true
    private var face = EdgeFace("", EdgeGlyph.Mark, null, EdgeGlyph.Mark)

    private val hideHud = Runnable { detachHud() }

    val showing: Boolean get() = strip != null

    /**
     * Put the strip up, or take it down, to match [wanted].
     *
     * Called on every window-state event rather than only on a change, so the strip heals itself:
     * an overlay removed by anything else comes back at the next app switch instead of staying
     * gone until the service is rebound. Matching state costs a null check and one string compare.
     */
    fun set(
        wanted: Boolean,
        widthDp: Int,
        topDeadDp: Int,
        triggerDp: Int,
        longDp: Int,
        slopDp: Int,
        showIndicator: Boolean,
        face: EdgeFace,
    ) {
        if (!wanted) {
            hide()
            return
        }
        if (!allowed()) return
        indicator = showIndicator
        // Held rather than baked in, so a rebinding shows up on the next stroke without the window
        // being rebuilt. The thresholds below are the opposite: they are inside the gesture.
        this.face = face
        // The width sizes the window and the rest are baked into the gesture at attach, so a change
        // to any of them needs the strip rebuilt. Rebuilt rather than adjusted in place: a window
        // resized mid-stroke retargets the touch it is holding, and a gesture whose thresholds move
        // under a finger already down cannot be reasoned about at all.
        val shape = "$widthDp:$topDeadDp:$triggerDp:$longDp:$slopDp"
        if (strip != null) {
            if (shape == stripShape) return
            hide()
        }
        attach(widthDp, topDeadDp, triggerDp, longDp, slopDp)
        if (strip != null) stripShape = shape
    }

    fun hide() {
        detachHud()
        val view = strip ?: return
        // A stroke can be in flight when the strip goes: the lock face coming up mid-drag is the
        // case. Reset rather than dropped, because the same object is what the touch listener of a
        // window still being torn down would keep answering from.
        runCatching { gesture?.reset() }
        strip = null
        gesture = null
        stripShape = ""
        val wm = context.getSystemService(WindowManager::class.java) ?: return
        runCatching { wm.removeView(view) }
    }

    /** Called when the service is unbound, so no window can outlive it. */
    fun dismiss() {
        handler.removeCallbacks(hideHud)
        hide()
    }

    fun allowed(): Boolean =
        runCatching { Settings.canDrawOverlays(context) }.getOrDefault(false)

    /**
     * The four numbers the live strip was built from, as one string.
     *
     * One value rather than four fields because the only question ever asked of them is "are these
     * the numbers the window on screen already has", and a field per number is four places for that
     * question to be asked incompletely. It was, once: the width was compared and the trigger was
     * not, so moving the trigger in the settings changed nothing until the next app switch.
     */
    private var stripShape = ""

    private fun attach(widthDp: Int, topDeadDp: Int, triggerDp: Int, longDp: Int, slopDp: Int) {
        val wm = context.getSystemService(WindowManager::class.java) ?: return
        val density = context.resources.displayMetrics.density
        // A long threshold past the edge of the screen is a gesture that cannot be completed, and
        // the settings screen offers distances in dp without knowing how wide this panel is. So the
        // ceiling is applied here, where the answer is known.
        val reach = context.resources.displayMetrics.widthPixels * LONG_REACH
        val g = BackGesture(
            triggerPx = triggerDp * density,
            slopPx = slopDp * density,
            longPx = if (longDp <= 0) 0f else (longDp * density).coerceAtMost(reach),
            side = side,
        )

        val view = object : View(context) {}
        view.setBackgroundColor(Color.TRANSPARENT)
        view.setOnTouchListener { _, event -> onTouch(g, event) }

        val bounds = stripBounds(screenHeight(wm), (topDeadDp * density).toInt())
        val params = WindowManager.LayoutParams(
            (widthDp * density).toInt().coerceAtLeast(1),
            bounds.height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // Never focusable: an overlay holding key focus would swallow the wheel and the
            // buttons, which is the one thing this app must not do to itself.
            // FLAG_NOT_TOUCH_MODAL is what keeps the other 97% of the screen behaving normally.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = horizontal() or Gravity.TOP
            y = bounds.top
        }

        runCatching { wm.addView(view, params) }
            .onSuccess {
                strip = view
                gesture = g
            }
    }

    /** START or END, and never LEFT or RIGHT: the edges of an RTL screen are the other way round. */
    private fun horizontal(): Int =
        if (side == EdgeSide.Left) Gravity.START else Gravity.END

    /**
     * The whole panel's height in pixels, bars included.
     *
     * `FLAG_LAYOUT_IN_SCREEN` puts this window in the screen's own coordinates rather than the
     * app's, so the number the strip has to be measured against is the one that counts the status
     * bar. `currentWindowMetrics` says so directly; `displayMetrics` on a service context has been
     * known to answer with the decorated size, which would leave the strip a status bar short of
     * the bottom of the screen.
     */
    private fun screenHeight(wm: WindowManager): Int = runCatching {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            wm.currentWindowMetrics.bounds.height()
        } else {
            context.resources.displayMetrics.heightPixels
        }
    }.getOrDefault(context.resources.displayMetrics.heightPixels)

    private fun onTouch(g: BackGesture, event: MotionEvent): Boolean = try {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                g.down(event.rawX, event.rawY)
                showHud(g)
            }
            MotionEvent.ACTION_MOVE -> {
                if (g.move(event.rawX, event.rawY)) {
                    if (g.stage == BackStage.Cancelled) {
                        detachHud()
                        runCatching { onCancelled?.invoke() }
                    } else {
                        showHud(g)
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                val fires = g.up()
                if (fires != null) {
                    val ok = runCatching { onFire?.invoke(fires) }.getOrNull() ?: false
                    flashHud(fires, ok)
                } else {
                    detachHud()
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                g.reset()
                detachHud()
            }
        }
        // Always. The stroke is ours from the first event: a listener that returned false partway
        // through would leave the rest of the stroke going nowhere at all.
        true
    } catch (@Suppress("TooGenericExceptionCaught") ignored: Throwable) {
        // Same rule as the key filter, for the same reason: this must never be the thing that
        // takes the service down. The stroke is abandoned and the strip stays up.
        runCatching { g.reset() }
        runCatching { detachHud() }
        true
    }

    // ------------------------------------------------------------------ the indicator

    private fun showHud(g: BackGesture) {
        if (!indicator) return
        val wm = context.getSystemService(WindowManager::class.java) ?: return
        handler.removeCallbacks(hideHud)
        val view = hud ?: HudView(context, side).also { hud = it }
        view.set(
            travel = g.travel,
            stage = g.stage,
            face = face,
            fired = null,
            ok = true,
        )
        val params = hudParams(g.anchorY)
        if (!hudUp) {
            runCatching { wm.addView(view, params) }.onSuccess { hudUp = true }
        } else {
            runCatching { wm.updateViewLayout(view, params) }
        }
    }

    /** The confirmation: the same box, filled, for long enough to see and no longer. */
    private fun flashHud(fired: EdgeLength, ok: Boolean) {
        val view = hud
        if (!indicator || view == null || !hudUp) {
            detachHud()
            return
        }
        view.set(
            travel = 1f,
            stage = if (fired == EdgeLength.Long) BackStage.ArmedLong else BackStage.Armed,
            face = face,
            fired = fired,
            ok = ok,
        )
        handler.removeCallbacks(hideHud)
        handler.postDelayed(hideHud, FLASH_MS)
    }

    private fun hudParams(anchorY: Float): WindowManager.LayoutParams {
        val density = context.resources.displayMetrics.density
        val h = (HUD_HEIGHT_DP * density).toInt()
        val w = (HUD_WIDTH_DP * density).toInt()
        val screenH = context.resources.displayMetrics.heightPixels
        return WindowManager.LayoutParams(
            w,
            h,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = horizontal() or Gravity.TOP
            x = 0
            // Centred on the finger, and kept on the screen at the two ends where it cannot be.
            y = (anchorY - h / 2f).toInt().coerceIn(0, (screenH - h).coerceAtLeast(0))
        }
    }

    private fun detachHud() {
        handler.removeCallbacks(hideHud)
        val view = hud ?: return
        hud = null
        hudUp = false
        val wm = context.getSystemService(WindowManager::class.java) ?: return
        runCatching { wm.removeView(view) }
    }

    /**
     * The small thing on screen that says what the thumb is doing.
     *
     * Four states, and the difference between them has to read at arm's length on a matte greyscale
     * panel: a dim outline while the stroke has not travelled far enough, white and labelled once
     * lifting would act, the other label once the stroke has passed the long threshold, and
     * inverted for the moment after it fired. No animation, no rounded corners, no tint — LightOS's
     * whole visual vocabulary is a rectangle and two shades.
     *
     * **There was a tick across the box** marking where the long swipe took over. It is gone: on a
     * 3.9" matte panel a short grey line inside an outlined box does not read as a scale mark, it
     * reads as a smudge or as a second box behind the first, and it was the only thing drawn here
     * that had to be explained before it meant anything. The long threshold still announces itself
     * — the word changes and so does the glyph — and that is the announcement people were actually
     * reading.
     */
    private class HudView(context: Context, private val side: EdgeSide) : View(context) {

        private val density = context.resources.displayMetrics.density

        private val ground = Paint().apply {
            color = Color.BLACK
            isAntiAlias = false
        }
        private val invert = Paint().apply {
            color = Color.WHITE
            isAntiAlias = false
        }
        private val outline = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = 1f * density
            isAntiAlias = false
        }
        private val stroke = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f * density
            strokeCap = Paint.Cap.SQUARE
            isAntiAlias = true
        }
        private val fill = Paint().apply { isAntiAlias = false }
        private val label = Paint().apply {
            isAntiAlias = true
            textSize = 11f * density
            letterSpacing = 0.18f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }

        private var travel = 0f
        private var stage = BackStage.Watching
        private var face = EdgeFace("", EdgeGlyph.Mark, null, EdgeGlyph.Mark)
        private var fired: EdgeLength? = null
        private var ok = true

        fun set(
            travel: Float,
            stage: BackStage,
            face: EdgeFace,
            fired: EdgeLength?,
            ok: Boolean,
        ) {
            this.travel = travel.coerceIn(0f, 1f)
            this.stage = stage
            this.face = face
            this.fired = fired
            this.ok = ok
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val h = height.toFloat()
            // The box grows with the drag, so the thing on screen is a measure of the gesture and
            // not merely a notice that one is happening.
            val minW = 26f * density
            val fullW = width.toFloat()
            val boxW = minW + (fullW - minW) * travel
            // It grows away from its own edge, so the box always has one side against the screen
            // edge the thumb came in from.
            val left = if (side == EdgeSide.Left) 0f else fullW - boxW
            val right = left + boxW

            val filled = fired != null && ok
            canvas.drawRect(left, 0f, right, h, if (filled) invert else ground)
            // An outline, always: on a black app behind a black box there is otherwise nothing to
            // see until the glyph.
            outline.color = if (filled) Color.BLACK else Color.WHITE
            canvas.drawRect(
                left + 0.5f * density,
                0.5f * density,
                right - 0.5f * density,
                h - 0.5f * density,
                outline,
            )

            val long = stage == BackStage.ArmedLong
            val armed = long || stage == BackStage.Armed
            val ink = when {
                filled -> Color.BLACK
                armed -> Color.WHITE
                // contentSecondary, the SDK's third and last colour.
                else -> Color.parseColor("#4A4A4A")
            }
            stroke.color = ink
            label.color = ink
            fill.color = ink

            // The glyph, at the leading edge of the box: the end the thumb is dragging toward.
            val cy = h / 2f
            val inset = 15f * density
            val cx = if (side == EdgeSide.Left) right - inset else left + inset
            when (if (long) face.longGlyph else face.shortGlyph) {
                EdgeGlyph.Chevron -> chevron(canvas, cx, cy)
                EdgeGlyph.Cards -> cards(canvas, cx, cy, if (filled) invert else ground)
                EdgeGlyph.Mark -> mark(canvas, cx, cy)
            }

            if (!armed) return
            val word = if (long) face.longLabel ?: face.shortLabel else face.shortLabel
            val text = if (fired != null && !ok) "NO $word" else word
            val tw = label.measureText(text)
            val gap = 13f * density
            // The label sits on the far side of the glyph from the edge, so it is always inside the
            // box rather than off the end of it.
            val tx = if (side == EdgeSide.Left) cx - gap - tw else cx + gap
            val inside = tx > left + 4f * density && tx + tw < right - 4f * density
            if (inside) {
                canvas.drawText(text, tx, cy - (label.ascent() + label.descent()) / 2f, label)
            }
        }

        /** Pointing left: where the screen you are going back to comes from. */
        private fun chevron(canvas: Canvas, cx: Float, cy: Float) {
            val arm = 5f * density
            canvas.drawLine(cx + arm * 0.6f, cy - arm, cx - arm * 0.4f, cy, stroke)
            canvas.drawLine(cx - arm * 0.4f, cy, cx + arm * 0.6f, cy + arm, stroke)
        }

        /**
         * Two overlapping outlines: a list of apps, with no direction implied.
         *
         * The front card is filled with the box's own ground before it is stroked, so it occludes
         * the back card's lines. Without that the two outlines cross and the glyph reads as a
         * lattice rather than as one card in front of another, which at 9 dp on a matte panel is the
         * difference between a symbol and a smudge. [ground] is passed in rather than assumed black,
         * because the box inverts for the moment after the gesture fires.
         */
        private fun cards(canvas: Canvas, cx: Float, cy: Float, ground: Paint) {
            val r = 4.5f * density
            val step = 2.5f * density
            canvas.drawRect(cx - r - step, cy - r + step, cx + r - step, cy + r + step, stroke)
            val fl = cx - r + step
            val ft = cy - r - step
            val fr = cx + r + step
            val fb = cy + r - step
            canvas.drawRect(fl, ft, fr, fb, ground)
            canvas.drawRect(fl, ft, fr, fb, stroke)
        }

        /** A filled square, for every action with no shape of its own. The word says which. */
        private fun mark(canvas: Canvas, cx: Float, cy: Float) {
            val r = 3.5f * density
            canvas.drawRect(cx - r, cy - r, cx + r, cy + r, fill)
        }
    }

    private companion object {
        /** How long the confirmation stays after the finger has gone. */
        const val FLASH_MS = 420L

        /** A third of the screen at most: the box is a measure, not a panel. */
        const val HUD_WIDTH_DP = 116f
        const val HUD_HEIGHT_DP = 34f

        /**
         * How far across the screen a long threshold may sit.
         *
         * A threshold past the edge is a gesture nobody can complete, and a threshold *at* the edge
         * needs a thumb to reach the far side of the panel exactly. 0.8 leaves room to overshoot.
         */
        const val LONG_REACH = 0.8f
    }
}
