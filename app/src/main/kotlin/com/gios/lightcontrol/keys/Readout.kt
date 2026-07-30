package com.gios.lightcontrol.keys

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The brightness level, briefly, over whatever app is in front.
 *
 * Without it the wheel is guesswork: the backlight change is the only feedback, and at the
 * dim end of the scale it's nearly invisible. A [Toast] was the tempting shortcut, but
 * toasts queue and rate-limit, so a fast turn would still be showing the first notch a
 * second after you stopped.
 *
 * So it's one overlay window, created once, text swapped in place. Needs the
 * `SYSTEM_ALERT_WINDOW` appop — the same grant that lets the camera button start an
 * activity from the service — and simply doesn't appear without it, since a missing readout
 * is a cosmetic loss and the brightness change itself still happened.
 *
 * Views, not Compose: this is a service with no lifecycle owner, and a `TextView` in an
 * overlay is a fraction of the machinery of hosting a composition here.
 */
class Readout(private val context: Context) {

    private val handler = Handler(Looper.getMainLooper())
    private val hide = Runnable { detach() }

    private var root: LinearLayout? = null
    private var label: TextView? = null

    fun show(text: String) {
        if (!allowed()) return
        attach()
        label?.text = text
        handler.removeCallbacks(hide)
        handler.postDelayed(hide, DWELL_MS)
    }

    fun allowed(): Boolean =
        runCatching { Settings.canDrawOverlays(context) }.getOrDefault(false)

    private fun attach() {
        if (root != null) return
        val wm = context.getSystemService(WindowManager::class.java) ?: return
        val density = context.resources.displayMetrics.density

        val text = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            letterSpacing = 0.12f
            gravity = Gravity.CENTER
        }
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = ColorDrawable(Color.BLACK)
            val padH = (18 * density).toInt()
            val padV = (12 * density).toInt()
            setPadding(padH, padV, padH, padV)
            addView(text)
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // Not focusable and not touchable: the readout must never take a tap away from
            // the app underneath, and it must never become the thing holding key focus.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            // Bottom, so it never covers what is being read.
            gravity = Gravity.BOTTOM
        }

        runCatching { wm.addView(box, params) }
            .onSuccess {
                root = box
                label = text
            }
    }

    private fun detach() {
        val box = root ?: return
        root = null
        label = null
        val wm = context.getSystemService(WindowManager::class.java) ?: return
        runCatching { wm.removeView(box) }
    }

    /** Called when the service is unbound, so a stray overlay can't outlive it. */
    fun dismiss() {
        handler.removeCallbacks(hide)
        detach()
    }

    private companion object {
        const val DWELL_MS = 900L
    }
}
