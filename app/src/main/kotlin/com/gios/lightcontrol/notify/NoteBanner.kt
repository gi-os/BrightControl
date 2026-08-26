package com.gios.lightcontrol.notify

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.gios.lightcontrol.lock.LightType
import kotlin.math.abs

/**
 * A notification, as a box over whatever the phone is showing.
 *
 * Ported from BrightChat's heads-up box, which is the shape this phone wants: solid black, one
 * hairline outline, square corners, two lines of message, no icon, no timestamp, no buttons and no
 * animation. The border is the only one in either app and it earns its place -- the box lands on
 * pixels we do not own, and a borderless black panel over another black app has no edge at all on
 * a screen with no colour to give it one.
 *
 * ### One window, where BrightChat needs two
 *
 * BrightChat draws this as a `TYPE_APPLICATION_OVERLAY` while the phone is awake and swaps to a
 * `showWhenLocked` activity when it is not, because an overlay at layer 11 sits *under* the
 * keyguard at 17 and cannot wake a panel. This app is an accessibility service, so it may add a
 * window at **layer 31** -- above the keyguard, above the bouncer, above everything -- and one
 * window covers both cases.
 *
 * That is not a tidiness argument. The activity BrightChat uses is the one thing this app must
 * never do: `showWhenLocked` marks the keyguard **occluded**, and AOSP's
 * `KeyguardUpdateMonitor.shouldListenForFingerprint` stops arming a power-button reader while it
 * is. v2.5 and v2.6 shipped exactly that and the thumb stopped unlocking the phone; see
 * [com.gios.lightcontrol.lock.LockOverlay]'s header. So the drawing and the waking are separated
 * here: this window paints, and [BannerWake] holds the panel on, and neither touches the keyguard.
 *
 * ### What it takes from the phone
 *
 * Touches on the box itself, and nothing else. `FLAG_NOT_FOCUSABLE` means no key -- power, wheel,
 * camera button -- is ever seen by this, and it implies `FLAG_NOT_TOUCH_MODAL`, so every touch
 * outside the box goes to the app below, which keeps running throughout. The window is
 * `WRAP_CONTENT`, so "outside the box" is the whole screen bar a strip at the top for a few
 * seconds.
 *
 * Views rather than Compose, like [com.gios.lightcontrol.keys.VolumeHud] and the lock face: a
 * service has no lifecycle owner, and hosting a composition without one means hand-building three
 * view-tree owners for a `TextView` and a rectangle.
 */
class NoteBanner(private val context: Context) {

    private val handler = Handler(Looper.getMainLooper())
    private val autoHide = Runnable { detach() }

    /** LightOS's own type scale and grid. Never a hardcoded sp or dp on a screen it will see. */
    private val type = LightType(context)

    private var root: FrameLayout? = null
    private var appLine: TextView? = null
    private var titleLine: TextView? = null
    private var bodyLine: TextView? = null

    /**
     * Tapped. Set per banner in [show], because what a tap opens is whatever posted this one.
     *
     * Reported, never acted on -- same seam as the lock face. Sending a `PendingIntent` from the
     * window that may be torn down by the result of sending it is a race; the service does it.
     */
    private var onTap: (() -> Unit)? = null

    /** Whether a box is on screen. Read by the service before it decides to draw another. */
    val showing: Boolean get() = root != null

    /**
     * Put the box up, or swap the text of the one already up, and re-arm the timer.
     *
     * Swapped rather than rebuilt, so a second notification landing during the first does not tear
     * a window down and add another one a frame later. The timer is re-armed and never stacked.
     *
     * [app] is the third line BrightChat's box does not have and this one needs: in BrightChat
     * every box is a message, and here it could be a score, a delivery or a calendar reminder. It
     * is the smallest thing on the box and it is what decides whether the phone is worth picking
     * up.
     */
    fun show(app: String, title: String, text: String, dwellMs: Long, onTap: () -> Unit) {
        this.onTap = onTap
        if (root == null) attach()
        if (root == null) return
        // Capped before it reaches a TextView: a pasted wall of text is not worth laying out to
        // find out it is going to be ellipsised at line two.
        val message = text.trim().take(300)
        appLine?.text = app.uppercase()
        titleLine?.let {
            it.text = title
            it.visibility = if (title.isBlank()) View.GONE else View.VISIBLE
        }
        bodyLine?.let {
            it.text = message
            it.visibility = if (message.isBlank()) View.GONE else View.VISIBLE
        }
        handler.removeCallbacks(autoHide)
        handler.postDelayed(autoHide, dwellMs)
    }

    /** Take it down now. Safe to call when nothing is up. */
    fun dismiss() {
        handler.removeCallbacks(autoHide)
        detach()
    }

    private fun attach() {
        val wm = context.getSystemService(WindowManager::class.java) ?: return

        val source = TextView(context).apply {
            setTextColor(SOURCE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, type.superfine)
            typeface = type.medium
            letterSpacing = type.buttonTracking
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
        }
        val heading = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, type.copy)
            typeface = type.regular
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = type.gridPx(0.25f) }
        }
        val body = TextView(context).apply {
            setTextColor(BODY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, type.paragraph)
            typeface = type.regular
            // Two lines is the whole design. The point of the box is knowing whether it is worth
            // picking the phone up, not reading the message on the lock screen.
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = type.gridPx(0.1f) }
        }

        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            // Square, and outlined. GradientDrawable only because a stroke needs one; there is no
            // gradient, no radius and no second colour anywhere on this box.
            background = GradientDrawable().apply {
                setColor(Color.BLACK)
                setStroke(hairline(), EDGE)
            }
            val padH = type.gridPx(1.2f)
            val padV = type.gridPx(0.9f)
            setPadding(padH, padV, padH, padV)
            addView(source)
            addView(heading)
            addView(body)
        }

        val frame = object : FrameLayout(context) {
            private var downY = 0f
            private var downX = 0f
            private var travelled = 0f
            private var downAt = 0L

            /**
             * Tap opens, swipe up sends away, everything else is ignored.
             *
             * Measured from where the finger went down, not from the last event, for the reason
             * BrightChat's box gives: one `MotionEvent`'s delta is a few pixels, so testing that
             * fires on a single jittery frame of a downward drag and never on a slow deliberate
             * upward one.
             *
             * A downward drag deliberately does nothing. This window is the only thing between a
             * pocket and the app underneath for a few seconds, and a phone in a pocket presses
             * everything.
             */
            override fun onTouchEvent(event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downY = event.rawY
                        downX = event.rawX
                        travelled = 0f
                        downAt = event.eventTime
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        travelled = event.rawY - downY
                        if (travelled < -dismissTravel) this@NoteBanner.dismiss()
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        val slop = ViewConfiguration.get(this@NoteBanner.context).scaledTouchSlop
                        val quick = event.eventTime - downAt < ViewConfiguration.getLongPressTimeout()
                        val still = abs(event.rawY - downY) < slop && abs(event.rawX - downX) < slop
                        if (quick && still) {
                            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            val act = this@NoteBanner.onTap
                            this@NoteBanner.dismiss()
                            act?.invoke()
                        }
                        return true
                    }
                }
                return true
            }
        }.apply {
            background = ColorDrawable(Color.TRANSPARENT)
            // Sides only. BrightChat clears the status-bar strip with 36dp of *padding*, which it
            // can afford because its window is one it may cover -- and this one is not. A window
            // reports every touch inside its own bounds whether or not a view handles it, so
            // transparent padding across the top of the screen is a swipe-down for the shade,
            // eaten, and a tap on empty black that launches an app. The gap is made with the
            // window's own `y` below instead, so those pixels are never ours.
            setPadding(type.gridPx(1f), 0, type.gridPx(1f), 0)
            addView(
                box,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            // 31, and the reason there is one window here and two in BrightChat. See the header.
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // NOT_FOCUSABLE is the whole trick: no key focus, no IME focus, and it implies
            // NOT_TOUCH_MODAL, so every touch outside this box reaches the app underneath. That
            // app is never paused -- which an activity, floating or not, could not manage.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP
            // Clear of the status bar. Measured from the true top of the screen, which is what
            // FLAG_LAYOUT_NO_LIMITS buys -- without it this would be an offset from an inset that
            // has already been applied, and the box would sit three units too low.
            //
            // Qualified, because `LayoutParams` has a `type` of its own -- the window type set two
            // lines above -- and a bare `type` in here is that Int, not the type scale.
            y = this@NoteBanner.type.gridPx(3f)
        }

        runCatching { wm.addView(frame, params) }
            .onSuccess {
                root = frame
                appLine = source
                titleLine = heading
                bodyLine = body
            }
    }

    private fun detach() {
        val view = root ?: return
        // Before the handle is dropped, not after. A null service here used to clear `root` and
        // return, leaving a window at layer 31 with nothing in the process still holding it --
        // which is precisely the black strip across the top of the phone the removal below exists
        // to prevent, arrived at by the code that prevents it.
        val wm = context.getSystemService(WindowManager::class.java) ?: return
        root = null
        appLine = null
        titleLine = null
        bodyLine = null
        onTap = null
        // removeView can be refused and is asynchronous; removeViewImmediate is the one that
        // cannot be put off. Same belt and braces as the lock face, and for the same reason: a
        // window this app has lost the handle to is a strip of black across the top of a phone
        // with nothing that will take it away.
        runCatching { wm.removeView(view) }
            .onFailure { runCatching { wm.removeViewImmediate(view) } }
    }

    /** How far up counts as meaning it. One grid unit -- a flick, not a graze. */
    private val dismissTravel: Int get() = type.gridPx(1f)

    /** 1dp, in px, and never less than a pixel -- a stroke rounded to zero is no border at all. */
    private fun hairline(): Int =
        maxOf(1, context.resources.displayMetrics.density.toInt())

    private companion object {
        /** The app name. contentSecondary, dimmed -- the smallest thing on the box. */
        val SOURCE = Color.argb(87, 255, 255, 255)

        /** The message. BrightChat's 70%, which is what stops it competing with the title. */
        val BODY = Color.argb(179, 255, 255, 255)

        /** The outline. 50%, and the only border in this app. */
        val EDGE = Color.argb(128, 255, 255, 255)
    }
}
