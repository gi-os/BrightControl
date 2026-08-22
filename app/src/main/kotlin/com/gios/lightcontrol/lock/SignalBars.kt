package com.gios.lightcontrol.lock

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/**
 * Four bars, the way every phone draws them.
 *
 * A view rather than a string, because the carrier name was the wrong answer to the question the
 * top bar is asked. "T-MOBILE" is nine characters that never change and do not tell you whether a
 * message is going to arrive; four bars are the thing you actually glance at.
 *
 * Unlit bars are drawn rather than left out. A bar chart that shrinks is unreadable at arm's
 * length on a matte panel — the outline is what makes one bar legible as *one out of four* rather
 * than as some small mark near the corner.
 */
class SignalBars(context: Context) : View(context) {

    /** 0..[BARS]. Below zero means "no idea", which is drawn as an empty set. */
    var level: Int = -1
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    private val lit = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val unlit = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0x55, 0x55, 0x55)
        style = Paint.Style.FILL
    }

    private val rect = RectF()

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        // Wider bars and a tighter gap than the old 3:1. Hairline gaps between broad bars is what
        // separates a current status glyph from one drawn in 2011.
        val gap = w / (BARS * 5f - 1f)
        val bar = gap * 4f
        val radius = bar * 0.3f
        for (i in 0 until BARS) {
            // Shortest bar is a third of the height, tallest is all of it — enough difference to
            // read the shape without the first bar becoming a dot.
            val fraction = (i + 1).toFloat() / BARS
            val barHeight = h * (0.34f + 0.66f * fraction)
            val left = i * (bar + gap)
            rect.set(left, h - barHeight, left + bar, h)
            // Rounded on every corner, including where the bar meets the baseline. Rounding only
            // the top reads as a bar chart; rounding all four reads as an icon.
            canvas.drawRoundRect(rect, radius, radius, if (i < level) lit else unlit)
        }
    }

    companion object {
        const val BARS = 4
    }
}
