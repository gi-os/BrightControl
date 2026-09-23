package com.gios.lightcontrol.notify

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.gios.lightcontrol.lock.LightType
import kotlin.math.abs

/**
 * An incoming call, as a box at the top of whatever app you are in.
 *
 * The lock face already draws a call card, but only on a locked phone. On an unlocked phone in a
 * sideloaded app, nothing said the phone was ringing except the ringer. This is the same box as
 * [NoteBanner] -- black, one hairline, one grid unit in from three sides, layer 31 -- with the
 * two buttons a call needs: DECLINE and ANSWER.
 *
 * It differs from [NoteBanner] in three ways:
 *
 *  - **No timer.** It stays for as long as the phone rings. The service takes it down when
 *    [com.gios.lightcontrol.lock.LockCall] says the ring is over (answered, declined, missed).
 *  - **Swipe up hides the box and does not decline.** The phone keeps ringing and the call can
 *    still be answered from the dialer. Declining must be a deliberate press on DECLINE.
 *  - **A tap on the name opens the dialer's own call screen**, for somebody who wants the whole
 *    screen before answering.
 *
 * Presses are reported and never acted on here, for the reason [NoteBanner] gives: the service
 * owns every activity start and every call action.
 */
class CallBanner(private val context: Context) {

    private val type = LightType(context)

    private var root: FrameLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var whoLine: TextView? = null
    private var subLine: TextView? = null
    private var slide: ValueAnimator? = null
    private var leaving = false

    var onAnswer: (() -> Unit)? = null
    var onDecline: (() -> Unit)? = null
    var onOpen: (() -> Unit)? = null

    /** Swiped away by the user. The service remembers it for the rest of this ring. */
    var onSwipedAway: (() -> Unit)? = null

    val showing: Boolean get() = root != null

    private val restY: Int get() = type.gridPx(1f)

    /** Put the box up, or change the text of the box that is already up. */
    fun show(who: String, sub: String) {
        val fresh = root == null
        if (fresh) attach()
        if (root == null) return
        whoLine?.text = who.ifBlank { "Incoming call" }
        subLine?.let {
            it.text = sub
            it.visibility = if (sub.isBlank()) View.GONE else View.VISIBLE
        }
        if (fresh) slideIn()
    }

    fun dismiss(animated: Boolean = true) {
        val view = root ?: return
        if (!animated) {
            slide?.cancel()
            detach()
            return
        }
        if (leaving) return
        val height = view.height
        if (height <= 0) {
            detach()
            return
        }
        leaving = true
        animateTo(from = params?.y ?: restY, to = restY - height, out = true)
    }

    private fun slideIn() {
        val view = root ?: return
        view.visibility = View.INVISIBLE
        view.post {
            val current = root ?: return@post
            val layout = params ?: return@post
            current.visibility = View.VISIBLE
            val height = current.height
            if (height <= 0) {
                layout.y = restY
                push()
                return@post
            }
            layout.y = restY - height
            push()
            animateTo(from = restY - height, to = restY, out = false)
        }
    }

    private fun animateTo(from: Int, to: Int, out: Boolean) {
        slide?.cancel()
        val layout = params ?: return
        val next = ValueAnimator.ofInt(from, to).apply {
            duration = if (out) OUT_MS else IN_MS
            interpolator = if (out) AccelerateInterpolator() else DecelerateInterpolator()
            addUpdateListener {
                layout.y = it.animatedValue as Int
                push()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    slide = null
                    if (out) detach()
                }
            })
        }
        slide = next
        next.start()
    }

    private fun push() {
        val view = root ?: return
        val layout = params ?: return
        val wm = context.getSystemService(WindowManager::class.java) ?: return
        runCatching { wm.updateViewLayout(view, layout) }
    }

    private fun button(label: String, filled: Boolean, press: () -> Unit) =
        TextView(context).apply {
            text = label
            typeface = type.medium
            setTextSize(TypedValue.COMPLEX_UNIT_SP, type.paragraph)
            letterSpacing = type.buttonTracking
            gravity = Gravity.CENTER
            isSingleLine = true
            setTextColor(if (filled) Color.BLACK else Color.WHITE)
            background = GradientDrawable().apply {
                // Same square-ish corners as the lock face's call card, so the two read as one.
                cornerRadius = type.gridPx(0.3f).toFloat()
                if (filled) setColor(Color.WHITE) else setStroke(maxOf(2, type.gridPx(0.08f)), Color.WHITE)
            }
            setPadding(0, type.gridPx(0.55f), 0, type.gridPx(0.55f))
            isClickable = true
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                runCatching { press() }
            }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

    private fun attach() {
        val wm = context.getSystemService(WindowManager::class.java) ?: return

        val source = TextView(context).apply {
            text = "INCOMING CALL"
            setTextColor(SOURCE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, type.superfine)
            typeface = type.medium
            letterSpacing = type.buttonTracking
            isSingleLine = true
        }
        val who = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, type.heading)
            typeface = type.medium
            letterSpacing = type.subheadingTracking
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = type.gridPx(0.15f) }
        }
        val sub = TextView(context).apply {
            setTextColor(BODY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, type.detail)
            typeface = type.regular
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = type.gridPx(0.1f) }
        }
        // DECLINE on the left and ANSWER on the right, the same order as the lock face's card.
        // A thumb that learned one of them has learned both.
        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(button("DECLINE", filled = false) { onDecline?.invoke() })
            addView(View(context), LinearLayout.LayoutParams(type.gridPx(0.8f), 1))
            addView(button("ANSWER", filled = true) { onAnswer?.invoke() })
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = type.gridPx(0.7f) }
        }

        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.BLACK)
                setStroke(maxOf(1, context.resources.displayMetrics.density.toInt()), EDGE)
            }
            val padH = type.gridPx(1.0f)
            val padV = type.gridPx(0.7f)
            setPadding(padH, padV, padH, padV)
            addView(source)
            addView(who)
            addView(sub)
            addView(buttons)
        }

        // The buttons take their own taps. Everything else on the box comes here: a tap opens
        // the dialer's call screen, and a swipe up hides the box for this ring.
        val frame = object : FrameLayout(context) {
            private var downX = 0f
            private var downY = 0f
            private var downAt = 0L
            private var gone = false

            override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
                // Watch a drag that starts on a button, so swipe up works from anywhere on the box.
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.rawX
                        downY = event.rawY
                        downAt = event.eventTime
                        gone = false
                    }
                    MotionEvent.ACTION_MOVE ->
                        if (event.rawY - downY < -type.gridPx(1f)) return true
                }
                return false
            }

            override fun onTouchEvent(event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.rawX
                        downY = event.rawY
                        downAt = event.eventTime
                        gone = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (!gone && event.rawY - downY < -type.gridPx(1f)) {
                            gone = true
                            this@CallBanner.dismiss()
                            onSwipedAway?.invoke()
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        if (gone) return true
                        val slop = ViewConfiguration.get(this@CallBanner.context).scaledTouchSlop
                        val quick = event.eventTime - downAt < ViewConfiguration.getLongPressTimeout()
                        val still = abs(event.rawY - downY) < slop && abs(event.rawX - downX) < slop
                        if (quick && still) {
                            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            onOpen?.invoke()
                        }
                    }
                }
                return true
            }
        }.apply {
            background = ColorDrawable(Color.TRANSPARENT)
            // Sides only, never top padding. See NoteBanner: transparent padding at the top of
            // the screen would take the touches meant for the shade.
            setPadding(type.gridPx(1f), 0, type.gridPx(1f), 0)
            addView(
                box,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }

        val layout = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP
            y = this@CallBanner.restY
        }

        runCatching { wm.addView(frame, layout) }
            .onSuccess {
                params = layout
                root = frame
                whoLine = who
                subLine = sub
            }
    }

    private fun detach() {
        val view = root ?: return
        val wm = context.getSystemService(WindowManager::class.java) ?: return
        root = null
        params = null
        whoLine = null
        subLine = null
        leaving = false
        slide = null
        runCatching { wm.removeView(view) }
            .onFailure { runCatching { wm.removeViewImmediate(view) } }
    }

    private companion object {
        const val IN_MS = 190L
        const val OUT_MS = 150L
        val SOURCE = Color.argb(87, 255, 255, 255)
        val BODY = Color.argb(179, 255, 255, 255)
        val EDGE = Color.argb(128, 255, 255, 255)
    }
}

/**
 * Whether the call banner should be up. Pure, so the rule is tested and not guessed.
 *
 * The lock face owns a locked phone, and LightOS's own call screen owns itself: a box over the
 * top of a screen that already has ANSWER on it would only hide part of that screen.
 */
object CallBannerRule {
    fun shows(
        enabled: Boolean,
        ringing: Boolean,
        awake: Boolean,
        front: String?,
        dialer: String?,
        swipedAway: Boolean,
        switcherUp: Boolean,
    ): Boolean {
        if (!enabled || !ringing || !awake || swipedAway || switcherUp) return false
        if (front == null) return true
        // LightOS is the dialer on this phone, and its call screen is part of the same package
        // as its dashboard. Any LightOS window in front during a ring is treated as the call
        // screen. The dashboard case loses the box, and LightOS handles that case itself.
        if (front.startsWith("com.lightos")) return false
        if (dialer != null && front == dialer) return false
        return true
    }
}
