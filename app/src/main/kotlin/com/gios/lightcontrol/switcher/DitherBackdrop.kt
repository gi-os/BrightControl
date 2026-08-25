package com.gios.lightcontrol.switcher

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * The switcher's background: black, filling with grey in an ordered dither that grows as it lands.
 *
 * ### Why a dither rather than a fade
 *
 * The LPIII's panel is black and white, and LightOS keeps the whole phone pinned to monochrome
 * through the accessibility daltonizer. A cross-fade on a screen like that is not a cross-fade —
 * the greys in between are quantised on the way to the glass, so a smooth ramp arrives as two or
 * three visible steps in a direction nobody chose. A dither is the same idea in the panel's own
 * language: the grey is *made* of black and grey cells in a fixed pattern, every frame is already
 * in tones the screen can hold, and the animation is a change in how many cells are lit rather
 * than a change in what colour they are.
 *
 * ### The motion
 *
 * Two things move at once, and they are the whole style of it.
 *
 *  - **The grain grows.** The pattern starts at an eighth of its final cell size — fine enough to
 *    read as a flat wash — and ends at twice it, a coarse, deliberate checker you can see the
 *    edges of. So the texture resolves *towards* you rather than appearing at rest.
 *  - **It fills top to bottom.** Coverage sweeps down the screen, so the top rows are already
 *    dense while the bottom is still black. The background does not appear, it fills in.
 *
 * ### How it is drawn, and why not per pixel
 *
 * The obvious implementation — one `IntArray` the size of the screen, thresholded per pixel every
 * frame — is a few million writes a frame at the fine end of that zoom, which is a stutter on the
 * one animation whose entire job is to feel immediate. Instead the 8×8 Bayer matrix is baked once
 * per coverage level into an 8×8 bitmap, and the screen is painted with that bitmap as a
 * `REPEAT` shader under a scale matrix. Growing the grain is then a number in a `Matrix`, and a
 * frame is a couple of dozen `drawRect` calls rather than a couple of million array writes.
 *
 * The sweep is done in horizontal bands, each with the tile for its own coverage. Sixty-odd bands
 * on a screen this tall is finer than the eye follows in a third of a second, and the tiles
 * themselves are cached — there are only 65 of them possible.
 */
class DitherBackdrop(context: Context) : View(context) {

    private val density = context.resources.displayMetrics.density

    /** The final cell size, before the zoom's 2×. Chunky on purpose. */
    private val baseCell = CELL_DP * density

    private val paint = Paint().apply {
        isFilterBitmap = false
        isAntiAlias = false
        isDither = false
    }

    private val matrix = Matrix()

    /** One 8×8 tile per coverage level, built on demand. 65 of them, 256 bytes each. */
    private val tiles = arrayOfNulls<BitmapShader>(LEVELS + 1)

    /** 0 at the press, 1 when the screen has filled. */
    private var progress = 0f
    private var animator: ValueAnimator? = null

    /** Start from nothing and fill. Called every time the window goes up. */
    fun ditherIn() {
        animator?.cancel()
        progress = 0f
        invalidate()
        val anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = FILL_MS
            interpolator = DecelerateInterpolator(1.4f)
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidate()
            }
        }
        animator = anim
        anim.start()
    }

    fun stop() {
        animator?.cancel()
        animator = null
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stop()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)
        if (width <= 0 || height <= 0) return

        // The grain: an eighth of a cell at the start, twice a cell at the end. Never below one
        // physical pixel, or the shader is sampling a tile smaller than the screen can show and
        // the pattern turns into a shimmer.
        val scale = (baseCell * lerp(START_SCALE, END_SCALE, progress)).coerceAtLeast(1f)

        // A band per grain row, so the sweep is always as fine as the pattern it is revealing.
        val band = (scale * 8f).coerceAtLeast(MIN_BAND_PX)
        val sweep = progress * (1f + TAIL)
        var y = 0f
        while (y < height) {
            val depth = y / height
            val coverage = (sweep - depth * TAIL).coerceIn(0f, 1f) * DENSITY
            val level = (coverage * LEVELS).toInt().coerceIn(0, LEVELS)
            if (level > 0) {
                val shader = tile(level) ?: return
                // Scale only — no translate. The tile is aligned to the screen rather than to
                // the band, because a pattern that restarts at every band boundary is sixty
                // visible seams down the middle of the effect.
                matrix.reset()
                matrix.setScale(scale, scale)
                shader.setLocalMatrix(matrix)
                paint.shader = shader
                canvas.drawRect(0f, y, width.toFloat(), (y + band).coerceAtMost(height.toFloat()), paint)
            }
            y += band
        }
        paint.shader = null
    }

    /**
     * The 8×8 tile for one coverage level, cached.
     *
     * A cell is lit when its Bayer threshold is under the level, which is the whole of ordered
     * dithering. The tile is one *cell* per pixel — the scale matrix is what turns it into
     * something you can see.
     */
    private fun tile(level: Int): BitmapShader? {
        tiles[level]?.let { return it }
        return runCatching {
            val bmp = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            val px = IntArray(64)
            for (y in 0 until 8) {
                for (x in 0 until 8) {
                    px[y * 8 + x] = if (BAYER[y][x] < level) GREY else Color.BLACK
                }
            }
            bmp.setPixels(px, 0, 8, 0, 0, 8, 8)
            BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT).also {
                tiles[level] = it
            }
        }.getOrNull()
    }

    private fun lerp(from: Float, to: Float, t: Float) = from + (to - from) * t

    private companion object {
        /** The resting cell size. Small enough to be texture, big enough to be a choice. */
        const val CELL_DP = 2.5f

        /** The grain's zoom: an eighth of a cell at the press, twice a cell at rest. */
        const val START_SCALE = 0.125f
        const val END_SCALE = 2f

        /** How long the fill takes. Long enough to be seen, short enough to be free. */
        const val FILL_MS = 320L

        /** How far the bottom of the screen lags the top. The whole of the sweep. */
        const val TAIL = 0.85f

        /** Where the fill stops. Over half the cells lit, which is a field and not a wash. */
        const val DENSITY = 0.62f

        /** Threshold steps, which is what an 8×8 matrix has. */
        const val LEVELS = 64

        /** No band thinner than this, or a fine grain costs a thousand draw calls. */
        const val MIN_BAND_PX = 6f

        /**
         * The grey the lit cells are painted in. Dark, because this is the ground a list of white
         * text stands on and the text has to stay the brightest thing on the screen.
         */
        val GREY: Int = Color.rgb(0x2E, 0x2E, 0x2E)

        /**
         * The 8×8 Bayer threshold matrix, on its standard 0..63 scale.
         *
         * Written out rather than generated. It is a fixed table that has not changed since 1973,
         * generating it costs a bit-interleave nobody reading this file wants to verify, and a
         * typo in a generated matrix looks like a rendering bug rather than a wrong number.
         */
        val BAYER = arrayOf(
            intArrayOf(0, 32, 8, 40, 2, 34, 10, 42),
            intArrayOf(48, 16, 56, 24, 50, 18, 58, 26),
            intArrayOf(12, 44, 4, 36, 14, 46, 6, 38),
            intArrayOf(60, 28, 52, 20, 62, 30, 54, 22),
            intArrayOf(3, 35, 11, 43, 1, 33, 9, 41),
            intArrayOf(51, 19, 59, 27, 49, 17, 57, 25),
            intArrayOf(15, 47, 7, 39, 13, 45, 5, 37),
            intArrayOf(63, 31, 55, 23, 61, 29, 53, 21),
        )
    }
}
