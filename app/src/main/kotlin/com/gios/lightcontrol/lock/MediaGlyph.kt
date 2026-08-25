package com.gios.lightcontrol.lock

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View

/**
 * One transport control, drawn rather than shipped as a drawable.
 *
 * Same reasoning as [SignalBars] and [BatteryIcon]: this app has no icon set of its own, the Light
 * SDK's icons are Compose vectors a `View` cannot take, and the shapes are four triangles and two
 * bars. Drawing them keeps the glyph sized in grid units and the stroke weight matched to the rest
 * of the face, at any screen density, with no resource lookup on a screen that is built while the
 * panel is off.
 *
 * The **glyph is smaller than the view**. A tap target on a lock screen has to be a thumb wide;
 * a play triangle a thumb wide is a road sign. [INSET] is the difference, and it is why the view
 * is three grid units while what you see is one and a half.
 */
class MediaGlyph(context: Context, private var kind: Kind) : View(context) {

    enum class Kind { PREVIOUS, PLAY, PAUSE, NEXT }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    private val path = Path()
    private val rect = RectF()

    fun show(next: Kind) {
        if (kind == next) return
        kind = next
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val size = minOf(w, h) * INSET
        val left = (w - size) / 2f
        val top = (h - size) / 2f

        when (kind) {
            Kind.PLAY -> triangle(canvas, left, top, size, pointRight = true)
            Kind.PAUSE -> {
                // Two bars at a third and a third, with a third of air between them -- the
                // proportion the SDK's own pause uses. Square ends, because every other mark on
                // this face is square.
                val bar = size / 3f
                rect.set(left, top, left + bar, top + size)
                canvas.drawRect(rect, paint)
                rect.set(left + size - bar, top, left + size, top + size)
                canvas.drawRect(rect, paint)
            }
            Kind.NEXT, Kind.PREVIOUS -> {
                val right = kind == Kind.NEXT
                // A skip is two half-width triangles, not a triangle and a bar. The bar form reads
                // as "go to the end"; this one reads as "the next one".
                val half = size / 2f
                triangle(canvas, left, top, half, pointRight = right, height = size)
                triangle(canvas, left + half, top, half, pointRight = right, height = size)
            }
        }
    }

    private fun triangle(
        canvas: Canvas,
        left: Float,
        top: Float,
        width: Float,
        pointRight: Boolean,
        height: Float = width,
    ) {
        path.reset()
        val midY = top + height / 2f
        if (pointRight) {
            path.moveTo(left, top)
            path.lineTo(left + width, midY)
            path.lineTo(left, top + height)
        } else {
            path.moveTo(left + width, top)
            path.lineTo(left, midY)
            path.lineTo(left + width, top + height)
        }
        path.close()
        canvas.drawPath(path, paint)
    }

    private companion object {
        /** How much of the tap target the mark fills. The rest is what makes it pressable. */
        const val INSET = 0.5f
    }
}
