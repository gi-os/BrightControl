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

/**
 * A back gesture, on a phone that has no back button.
 *
 * LightOS removed the navigation bar and put a gesture-navigation switch in its own settings, and
 * that switch reaches Light's own tools. Everything sideloaded is left with no way back at all:
 * an app that pushes a screen has to draw its own arrow, and one that forgot to is a dead end
 * until you press home. So this is the missing gesture, supplied from outside: a thin strip down
 * the left edge of the screen, a drag to the right, and `GLOBAL_ACTION_BACK`.
 *
 * ### The strip consumes what it watches, and that is the whole cost of the feature
 *
 * There is no way to observe a touch this service does not own. Gesture detection through the
 * accessibility API needs touch exploration switched on, which changes how the entire phone is
 * driven; `dispatchGesture` sends touches and cannot receive them. What is left is an overlay
 * window, and an overlay window that receives a touch has taken it — a swallowed
 * [MotionEvent] cannot be handed back to the app underneath once the stroke has begun.
 *
 * So the honest description of this feature is: touches that begin within the strip's width of the
 * left edge go to BrightControl instead of to the app. `FLAG_NOT_TOUCH_MODAL` keeps every
 * touch outside the strip going where it always went, and the window is never focusable, so this
 * can never cost a *key* — which is the rule the rest of this app is built on. It is also why the
 * feature is off until it is switched on, why the width is a setting, and why any app can be
 * excluded from it: the strip is narrow, but it is not free, and the apps where the left edge is a
 * control are exactly the ones the user knows about and this app does not.
 *
 * ### Two windows, not one
 *
 * The strip is 14 dp wide by default, which is no room to draw anything in. The indicator is
 * therefore its own window — untouchable, positioned at the finger, and up only while a stroke is
 * in flight. The same shape BrightMusic's lock-screen overlay arrived at, for the same reason.
 *
 * Views, not Compose: a service has no lifecycle owner, and this is two rectangles and a chevron.
 */
class BackSwipe(private val context: Context) {

    /**
     * Go back. Returns whether the platform accepted it, which is all the service can know: the
     * global action answers for the dispatch and not for what the app in front did with it.
     *
     * Set by the service, because every action this app takes goes through one log line there.
     */
    var onBack: (() -> Boolean)? = null

    /** Told when a stroke was thrown away as a scroll, for the key log. */
    var onCancelled: (() -> Unit)? = null

    private val handler = Handler(Looper.getMainLooper())

    private var strip: View? = null
    private var hud: HudView? = null
    private var hudUp = false

    private var gesture: BackGesture? = null

    /** Whether the indicator is drawn at all. Read at attach, so a change needs no restart. */
    private var indicator = true

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
        triggerDp: Int,
        slopDp: Int,
        showIndicator: Boolean,
    ) {
        if (!wanted) {
            hide()
            return
        }
        if (!allowed()) return
        indicator = showIndicator
        // The width sizes the window and the other two are baked into the gesture at attach, so a
        // change to any of them needs the strip rebuilt. Rebuilt rather than adjusted in place: a
        // window resized mid-stroke retargets the touch it is holding, and a gesture whose trigger
        // moves under a finger already down cannot be reasoned about at all.
        val shape = "$widthDp:$triggerDp:$slopDp"
        if (strip != null) {
            if (shape == stripShape) return
            hide()
        }
        attach(widthDp, triggerDp, slopDp)
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
     * The three numbers the live strip was built from, as one string.
     *
     * One value rather than three fields because the only question ever asked of them is "are these
     * the numbers the window on screen already has", and a field per number is three places for
     * that question to be asked incompletely. It was, once: the width was compared and the trigger
     * was not, so moving the trigger in the settings changed nothing until the next app switch.
     */
    private var stripShape = ""

    private fun attach(widthDp: Int, triggerDp: Int, slopDp: Int) {
        val wm = context.getSystemService(WindowManager::class.java) ?: return
        val density = context.resources.displayMetrics.density
        val g = BackGesture(
            triggerPx = triggerDp * density,
            slopPx = slopDp * density,
        )

        val view = object : View(context) {}
        view.setBackgroundColor(Color.TRANSPARENT)
        view.setOnTouchListener { _, event -> onTouch(g, event) }

        val params = WindowManager.LayoutParams(
            (widthDp * density).toInt().coerceAtLeast(1),
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // Never focusable: an overlay holding key focus would swallow the wheel and the
            // buttons, which is the one thing this app must not do to itself.
            // FLAG_NOT_TOUCH_MODAL is what keeps the other 97% of the screen behaving normally.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.START or Gravity.TOP
        }

        runCatching { wm.addView(view, params) }
            .onSuccess {
                strip = view
                gesture = g
                stripWidthDp = widthDp
            }
    }

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
                val fire = g.up()
                if (fire) {
                    val ok = runCatching { onBack?.invoke() }.getOrNull() ?: false
                    flashHud(ok)
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
        val view = hud ?: HudView(context).also { hud = it }
        view.set(g.travel, g.stage == BackStage.Armed, fired = false, ok = true)
        val params = hudParams(g.anchorY)
        if (!hudUp) {
            runCatching { wm.addView(view, params) }.onSuccess { hudUp = true }
        } else {
            runCatching { wm.updateViewLayout(view, params) }
        }
    }

    /** The confirmation: the same box, filled, for long enough to see and no longer. */
    private fun flashHud(ok: Boolean) {
        val view = hud
        if (!indicator || view == null || !hudUp) {
            detachHud()
            return
        }
        view.set(1f, armed = true, fired = true, ok = ok)
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
            gravity = Gravity.START or Gravity.TOP
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
     * Three states, and the difference between them has to read at arm's length on a matte
     * greyscale panel: dim outline while the stroke has not travelled far enough, white and
     * labelled once lifting would go back, inverted for the moment after it did. No animation, no
     * rounded corners, no tint — LightOS's whole visual vocabulary is a rectangle and two shades.
     *
     * The chevron points *left*, the direction of travel of the screen you are going back to,
     * which is the way every phone has drawn it for fifteen years. The finger goes the other way.
     */
    private class HudView(context: Context) : View(context) {

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
        private val label = Paint().apply {
            isAntiAlias = true
            textSize = 11f * density
            letterSpacing = 0.18f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }

        private var travel = 0f
        private var armed = false
        private var fired = false
        private var ok = true

        fun set(travel: Float, armed: Boolean, fired: Boolean, ok: Boolean) {
            this.travel = travel.coerceIn(0f, 1f)
            this.armed = armed
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

            val filled = fired && ok
            canvas.drawRect(0f, 0f, boxW, h, if (filled) invert else ground)
            // An outline, always: on a black app behind a black box there is otherwise nothing to
            // see until the chevron.
            outline.color = if (filled) Color.BLACK else Color.WHITE
            canvas.drawRect(
                0.5f * density,
                0.5f * density,
                boxW - 0.5f * density,
                h - 0.5f * density,
                outline,
            )

            val ink = when {
                filled -> Color.BLACK
                armed -> Color.WHITE
                // contentSecondary, the SDK's third and last colour.
                else -> Color.parseColor("#4A4A4A")
            }
            stroke.color = ink
            label.color = ink

            // The chevron, at the leading edge of the box.
            val cx = boxW - 15f * density
            val cy = h / 2f
            val arm = 5f * density
            canvas.drawLine(cx + arm * 0.6f, cy - arm, cx - arm * 0.4f, cy, stroke)
            canvas.drawLine(cx - arm * 0.4f, cy, cx + arm * 0.6f, cy + arm, stroke)

            if (!armed) return
            val text = if (fired && !ok) "NO BACK" else "BACK"
            val tw = label.measureText(text)
            val tx = cx - arm - 8f * density - tw
            if (tx > 4f * density) {
                canvas.drawText(text, tx, cy - (label.ascent() + label.descent()) / 2f, label)
            }
        }
    }

    private companion object {
        /** How long the confirmation stays after the finger has gone. */
        const val FLASH_MS = 420L

        /** A third of the screen at most: the box is a measure, not a panel. */
        const val HUD_WIDTH_DP = 116f
        const val HUD_HEIGHT_DP = 34f
    }
}
