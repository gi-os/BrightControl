package com.gios.lightcontrol.lock

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
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
class MediaGlyph(
    context: Context,
    private var kind: Kind,
    typeface: Typeface? = null,
) : View(context) {

    /**
     * Every mark this face has for a transport control.
     *
     * [SEEK_BACK] and [SEEK_FORWARD] carry the number, because a bare pair of triangles already
     * means skip on this face and would then mean two things at once. [STOP] is a square, which is
     * the one transport mark on a phone nobody has to be taught.
     */
    enum class Kind { PREVIOUS, PLAY, PAUSE, NEXT, SEEK_BACK, SEEK_FORWARD, STOP }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    /**
     * The **15** under the seek arrows.
     *
     * Its own paint, and given the face's typeface rather than the platform default, so that the
     * only text drawn by a `View` on this screen matches the text drawn by every `TextView` on it.
     * Sized off the glyph, like the marks are, so it holds at any density.
     */
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
        textAlign = Paint.Align.CENTER
        this.typeface = typeface ?: Typeface.DEFAULT_BOLD
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
            Kind.STOP -> {
                // A square, at the weight of the pause bars rather than the full mark, because a
                // filled square the size of the play triangle is much heavier than it and the two
                // sit side by side.
                val side = size * 0.82f
                val inset = (size - side) / 2f
                rect.set(left + inset, top + inset, left + inset + side, top + inset + side)
                canvas.drawRect(rect, paint)
            }
            Kind.SEEK_BACK, Kind.SEEK_FORWARD -> {
                // Arrows over a number, inside the same box as every other mark: the pair takes the
                // top three fifths, the digits sit under them. Nothing grows the row -- a control
                // set that changes height when a podcast starts would move the notifications above
                // it, and on a lock face that reads as a glitch.
                val right = kind == Kind.SEEK_FORWARD
                val arrowH = size * 0.58f
                val half = size / 2f
                triangle(canvas, left, top, half, pointRight = right, height = arrowH)
                triangle(canvas, left + half, top, half, pointRight = right, height = arrowH)
                label.textSize = size * 0.46f
                canvas.drawText(
                    STEP_LABEL,
                    left + size / 2f,
                    top + size + label.textSize * 0.06f,
                    label,
                )
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

        /**
         * Seconds, as drawn. Kept in step with `LockMedia.STEP_MS` by hand and on purpose: this is
         * the one place the number is a shape rather than a duration, and reading a `Long` of
         * milliseconds to print a two-character label would hide that.
         */
        const val STEP_LABEL = "15"
    }
}
