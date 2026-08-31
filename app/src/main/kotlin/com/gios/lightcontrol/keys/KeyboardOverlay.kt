package com.gios.lightcontrol.keys

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.gios.lightcontrol.lock.LightType

/**
 * The keyboard-replace band: a plain-Views QWERTY drawn over the bottom of a LightOS app.
 *
 * ### Why a window, and this kind
 *
 * Same seam as the lock face and the switcher — `TYPE_ACCESSIBILITY_OVERLAY` sits at layer 31,
 * above the app and above any in-app keyboard LightOS drew itself, and it is the only way a
 * service-owned UI can sit there without `SYSTEM_ALERT_WINDOW`. `FLAG_NOT_FOCUSABLE` is
 * load-bearing: a focusable window would steal key focus from the field underneath and the whole
 * point — type *into that field* — would collapse.
 *
 * The service draws, this window reports. Every key becomes a callback to [KeyboardService],
 * which owns the typing, the throttle and the fallbacks; a window that typed for itself would be
 * a second place text decisions happened.
 *
 * ### Why plain Views and not Compose
 *
 * Same rule as every other service-owned window in this app: hosting a composition is far more
 * machinery than a grid of TextViews, and this is one screen in black and white.
 */
class KeyboardOverlay(private val context: Context) {

    private val type = LightType(context)
    private val handler = Handler(Looper.getMainLooper())

    /** A character key was pressed, with the one character it types. */
    var onKey: ((String) -> Unit)? = null

    /** Backspace. */
    var onBackspace: (() -> Unit)? = null

    /** Enter / return. */
    var onEnter: (() -> Unit)? = null

    /** The user asked to put the band away. */
    var onHide: (() -> Unit)? = null

    private var root: FrameLayout? = null

    val showing: Boolean get() = root != null

    // The three QWERTY rows, upper-case labels on a phone with no shift state yet.
    private val rows = listOf(
        listOf("Q", "W", "E", "R", "T", "Y", "U", "I", "O", "P"),
        listOf("A", "S", "D", "F", "G", "H", "J", "K", "L"),
        listOf("Z", "X", "C", "V", "B", "N", "M"),
    )

    fun show() {
        if (root != null) return
        val wm = context.getSystemService(WindowManager::class.java) ?: return
        val view = build() ?: return

        val heightPx = type.gridPx(12f)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            heightPx,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // NOT_FOCUSABLE is the whole design. See the class comment.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.BOTTOM or Gravity.START }

        val added = runCatching { wm.addView(view, params); true }.getOrDefault(false)
        if (!added) return
        root = view
    }

    fun hide(): Boolean {
        val view = root ?: return true
        val wm = context.getSystemService(WindowManager::class.java) ?: return false
        val gone = runCatching { wm.removeView(view); true }
            .getOrElse { runCatching { wm.removeViewImmediate(view); true }.getOrDefault(false) }
        if (!gone) return false
        root = null
        return true
    }

    fun toggle() {
        if (showing) hide() else show()
    }

    private fun build(): FrameLayout? = runCatching {
        val frame = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(Color.BLACK)
            }
        }

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(type.gridPx(0.4f), type.gridPx(0.6f), type.gridPx(0.4f), type.gridPx(0.8f))
        }
        frame.addView(
            column,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )

        // A thin divider over the app beneath, so the band reads as a band and not as a clipped app.
        column.addView(
            View(context).apply {
                setBackgroundColor(Color.DKGRAY)
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                type.gridPx(0.08f),
            ),
        )

        rows.forEach { row ->
            column.addView(keyRow(row))
        }

        // Bottom row: space (wide), backspace, enter, and the way out.
        val bottom = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        bottom.addView(
            key(" ", space = true, onClick = { onKey?.invoke(" ") }),
            LinearLayout.LayoutParams(0, keyHeight, 3f).apply { gravity = Gravity.CENTER_VERTICAL },
        )
        bottom.addView(key("\u232B", space = false, onClick = { onBackspace?.invoke() }))
        bottom.addView(key("\u21B5", space = false, onClick = { onEnter?.invoke() }))
        bottom.addView(key("HIDE", space = false, onClick = { onHide?.invoke() }))
        column.addView(bottom)

        frame
    }.getOrNull()

    private val keyHeight: Int get() = type.gridPx(2.1f)

    private fun keyRow(chars: List<String>): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        chars.forEach { c ->
            row.addView(
                key(c, space = false, onClick = { onKey?.invoke(c) }),
                LinearLayout.LayoutParams(0, keyHeight, 1f),
            )
        }
        return row
    }

    private fun key(
        label: String,
        space: Boolean,
        onClick: (() -> Unit)? = null,
    ): TextView = TextView(context).apply {
        text = if (space) "\u2003" else label
        setTextColor(Color.WHITE)
        typeface = type.regular
        // LightOS's own copy size. The space bar keeps the same height as the letters beside it.
        textSize = type.copy
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = false
        setOnClickListener { onClick?.invoke() }
        setBackgroundColor(Color.TRANSPARENT)
    }
}
