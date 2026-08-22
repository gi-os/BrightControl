package com.gios.lightcontrol.lock

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/**
 * The battery, drawn rather than written.
 *
 * It used to be the string "85%", which is four characters of arithmetic to read a thing you
 * glance at. A filled outline says the same at arm's length without being read — and on a
 * grayscale panel at this size, shape carries further than digits do.
 *
 * Deliberately the same box as [SignalBars], so the two ends of the top bar have the same weight.
 * Two status glyphs of different heights read as one being more important, which is not true.
 */
class BatteryIcon(context: Context) : View(context) {

    /** 0..100, or below zero for "not known", which draws as an empty shell. */
    var level: Int = -1
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    var charging: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val bolt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        // Punched out of the fill rather than drawn over it, so the bolt stays visible at any
        // charge level. At 100% a white bolt on a white body would be nothing at all.
        color = Color.BLACK
        style = Paint.Style.FILL
    }

    private val body = RectF()
    private val inner = RectF()

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val stroke = (h * 0.075f).coerceAtLeast(1.5f)
        outline.strokeWidth = stroke

        // The terminal nub on the right, and the gap before it.
        val nubWidth = w * 0.06f
        val nubGap = w * 0.04f
        val bodyRight = w - nubWidth - nubGap

        val half = stroke / 2f
        body.set(half, half, bodyRight - half, h - half)
        val radius = h * 0.28f
        canvas.drawRoundRect(body, radius, radius, outline)

        // Nub, centered vertically, a third of the height.
        val nubHeight = h * 0.34f
        val nubTop = (h - nubHeight) / 2f
        val nubRect = RectF(bodyRight + nubGap, nubTop, w, nubTop + nubHeight)
        canvas.drawRoundRect(nubRect, nubWidth / 2f, nubWidth / 2f, fill)

        if (level < 0) return

        // The fill sits inside the outline with a hairline of ground around it, so a full
        // battery still reads as an outline containing something rather than a solid block.
        val pad = stroke * 1.6f
        val trackLeft = body.left + pad
        val trackRight = body.right - pad
        val span = (trackRight - trackLeft).coerceAtLeast(0f)
        val filled = span * (level.coerceIn(0, 100) / 100f)
        if (filled <= 0f) return

        inner.set(trackLeft, body.top + pad, trackLeft + filled, body.bottom - pad)
        val innerRadius = radius * 0.5f
        canvas.drawRoundRect(inner, innerRadius, innerRadius, fill)

        if (charging) {
            // The bolt has to survive crossing the edge of the fill. Punching it black works only
            // where there is white under it — at 45% that left the bolt half drawn, reading as a
            // bite out of the fill rather than as a bolt. So it is drawn twice, clipped: black
            // over the charged part, white over the empty part.
            canvas.save()
            canvas.clipRect(inner)
            drawBolt(canvas, body, bolt)
            canvas.restore()

            canvas.save()
            canvas.clipRect(inner.right, body.top, body.right, body.bottom)
            drawBolt(canvas, body, fill)
            canvas.restore()
        }
    }

    /** A lightning bolt centered on the body, in whichever color the region under it needs. */
    private fun drawBolt(canvas: Canvas, r: RectF, paint: Paint) {
        val cx = r.centerX()
        val cy = r.centerY()
        val hh = r.height() * 0.34f
        val hw = r.width() * 0.10f
        val path = android.graphics.Path().apply {
            moveTo(cx + hw * 0.9f, cy - hh)
            lineTo(cx - hw * 1.1f, cy + hh * 0.18f)
            lineTo(cx + hw * 0.05f, cy + hh * 0.18f)
            lineTo(cx - hw * 0.9f, cy + hh)
            lineTo(cx + hw * 1.1f, cy - hh * 0.18f)
            lineTo(cx - hw * 0.05f, cy - hh * 0.18f)
            close()
        }
        canvas.drawPath(path, paint)
    }
}
