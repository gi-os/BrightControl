package com.gios.lightcontrol.lock

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import androidx.exifinterface.media.ExifInterface
import com.gios.lightcontrol.Prefs
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import org.json.JSONArray
import org.json.JSONObject

/**
 * The picture behind the lock face, and the filter stack that makes it wearable.
 *
 * Lifted from BrightChat's per-chat wallpaper, which solved the same problem one screen over: a
 * photograph on a greyscale matte panel is a bad background by default, and the fix is not "dim it
 * 50%" but a *stack* of small pixel passes the user assembles themselves. An ordered Bayer dither
 * quantises the photo to pure black-and-white halftone at a chosen cell size — 8× reads chunky and
 * deliberate rather than like a rendering fault. Fade pulls the whole thing toward the black the
 * face already paints, which is what keeps the clock readable. The two corner effects melt the
 * edges, one into blur and one into the black itself, so the picture sits *behind* the time
 * instead of competing with it.
 *
 * The pipeline below is BrightChat's, unchanged: dependency-free `Bitmap` work, no render effects
 * and no GPU passes. What changed is everything around it — there is one background rather than one
 * per conversation, it is stored in [Prefs] rather than a message store, and it renders to a plain
 * [Bitmap] because the lock face is Views rather than Compose.
 */
object LockBackground {

    /** How the photo lands on the screen-shaped canvas, before any filter runs. */
    enum class ScaleMode(val label: String) {
        /** Fill the screen, cropping whatever overflows. The default. */
        FILL("Fill"),

        /** The whole photo, letterboxed on black — which the corner effects and Fade then blend
         *  into, so the bars read as intent rather than absence. */
        FIT("Fit"),

        /** Stretch to the screen's shape, aspect be damned. Sometimes the warp is the look. */
        STRETCH("Stretch"),
    }

    /** One filter application: which effect, and how hard. [amount]'s meaning is per-type. */
    data class Filter(val type: FilterType, val amount: Int)

    /**
     * The five effects, with the range their [Filter.amount] moves in.
     *
     * Percentage types adjust by ± [step] between [min] and [max]; DITHER instead doubles and
     * halves, because halftone cells read in octaves — the step from 7 to 8 is invisible where 4 to
     * 8 is the whole point. Its amount is in *quarter-pixels* (4 = a 1px cell) so the ladder
     * extends below one pixel and settles back down to the canvas, which reads as a softer, greyer
     * halftone rather than hard 1px checkering.
     */
    enum class FilterType(
        val label: String,
        val min: Int,
        val max: Int,
        val step: Int,
        val default: Int,
    ) {
        /** Ordered Bayer dither to pure black/white. Amount in quarter-px: 1..64 = 0.25×..16×. */
        DITHER("Dither", 1, 64, 0, 32),

        /** Plain luminance greyscale. No amount — it either is or isn't. */
        MONO("Black & white", 0, 0, 0, 0),

        /** How much of the image survives over the black behind it, in percent. */
        FADE("Opacity", 10, 90, 10, 40),

        /** Blur growing from a sharp centre out to the corners, percent strength. */
        CORNER_BLUR("Corner blur", 10, 100, 10, 50),

        /** The same reach, but into black instead of blur. */
        CORNER_FADE("Corner fade", 10, 100, 10, 50),
        ;

        val hasAmount: Boolean get() = min != max

        /** How the amount reads in a row: "8×" (or "0.5×") for a cell, "40%" for the rest. */
        fun display(amount: Int): String = when (this) {
            DITHER -> if (amount >= 4) "${amount / 4}×" else "0.${if (amount == 2) "5" else "25"}×"
            MONO -> ""
            else -> "$amount%"
        }

        fun bump(amount: Int, up: Boolean): Int = when (this) {
            DITHER -> if (up) min(max, amount * 2) else max(min, amount / 2)
            else -> if (up) min(max, amount + step) else max(min, amount - step)
        }
    }

    /**
     * The chosen photo, copied here rather than referenced.
     *
     * A copy, unlike v2.5's persistable SAF grant, because the pipeline needs to re-read the
     * original every time the recipe changes and a document permission is one revoked grant away
     * from a lock screen that quietly goes black. The copy also means the phone still has a
     * background after the photo is deleted from the camera roll — which is the behaviour BrightChat
     * settled on for exactly the same reason.
     */
    fun sourceFile(context: Context): File =
        File(File(context.filesDir, "lockbg").apply { mkdirs() }, "source")

    fun has(context: Context): Boolean = sourceFile(context).length() > 0L

    /** The saved filter stack, oldest-applied first. */
    fun filters(prefs: Prefs): List<Filter> {
        val json = prefs.lockBackground ?: return emptyList()
        return runCatching {
            val array = JSONObject(json).getJSONArray("filters")
            (0 until array.length()).mapNotNull { i ->
                val o = array.getJSONObject(i)
                val type = FilterType.entries.firstOrNull { it.name == o.getString("type") }
                type?.let { Filter(it, o.optInt("amount", it.default).coerceIn(it.min, it.max)) }
            }
        }.getOrDefault(emptyList())
    }

    fun scale(prefs: Prefs): ScaleMode {
        val json = prefs.lockBackground ?: return ScaleMode.FILL
        val name = runCatching { JSONObject(json).optString("scale") }.getOrDefault("")
        return ScaleMode.entries.firstOrNull { it.name == name } ?: ScaleMode.FILL
    }

    /** Where the FILL crop sits in the photo's slack, each axis 0..1 (0.5 = centred). */
    fun offset(prefs: Prefs): Pair<Float, Float> {
        val json = prefs.lockBackground ?: return 0.5f to 0.5f
        return runCatching {
            val o = JSONObject(json)
            o.optDouble("ox", 0.5).toFloat().coerceIn(0f, 1f) to
                o.optDouble("oy", 0.5).toFloat().coerceIn(0f, 1f)
        }.getOrDefault(0.5f to 0.5f)
    }

    /** The photo's decoded shape, EXIF-upright, for the editor's drag maths. */
    fun sourceSize(file: File): Pair<Int, Int>? =
        decodeUpright(file, 64)?.let { it.width to it.height }

    fun save(
        context: Context,
        prefs: Prefs,
        source: File?,
        filters: List<Filter>,
        scale: ScaleMode,
        ox: Float,
        oy: Float,
    ) {
        val dest = sourceFile(context)
        if (source != null && source.canonicalPath != dest.canonicalPath) {
            runCatching { source.copyTo(dest, overwrite = true) }
        }
        prefs.lockBackground = JSONObject().apply {
            put("scale", scale.name)
            put("ox", ox.toDouble())
            put("oy", oy.toDouble())
            put("filters", JSONArray().apply {
                filters.forEach {
                    put(JSONObject().apply {
                        put("type", it.type.name)
                        put("amount", it.amount)
                    })
                }
            })
        }.toString()
        prefs.lockBackgroundStamp = System.currentTimeMillis()
    }

    fun remove(context: Context, prefs: Prefs) {
        sourceFile(context).delete()
        prefs.lockBackground = null
        prefs.lockBackgroundStamp = System.currentTimeMillis()
    }

    /**
     * The finished background at the panel's own shape, or null when there is none.
     *
     * Not cached here. The lock face holds the one result it needs, keyed on
     * [Prefs.lockBackgroundStamp], and re-renders only when the recipe changed — three passes over
     * a million pixels while the screen is off is not worth an LruCache of one.
     */
    fun render(context: Context, prefs: Prefs, aspect: Float, maxDim: Int): Bitmap? {
        val file = sourceFile(context)
        if (file.length() == 0L) return null
        return runCatching {
            render(file, filters(prefs), scale(prefs), aspect, maxDim, offset(prefs).first, offset(prefs).second)
        }.getOrNull()
    }

    /** As [render] but small — the editor's live preview, re-run on every tap of −/+. */
    fun preview(
        file: File,
        filters: List<Filter>,
        scale: ScaleMode,
        aspect: Float,
        ox: Float,
        oy: Float,
    ): Bitmap? = runCatching { render(file, filters, scale, aspect, PREVIEW_DIM, ox, oy) }.getOrNull()

    /**
     * Decode at no more than [maxDim] on the long side, then stand the result upright.
     *
     * A phone photo is 12 megapixels against a panel under one, so decoding at full size to scale
     * it down is tens of megabytes for pixels nobody sees. The EXIF pass is a no-op for the common
     * upright case, so it does not needlessly copy the bitmap.
     */
    internal fun decodeUpright(file: File, maxDim: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > maxDim || bounds.outHeight / sample > maxDim) sample *= 2
        val bitmap = runCatching {
            BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
        }.getOrNull() ?: return null
        return upright(bitmap, file)
    }

    private fun upright(bitmap: Bitmap, file: File): Bitmap {
        val orientation = runCatching {
            ExifInterface(file.path)
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(270f); matrix.postScale(-1f, 1f) }
            else -> return bitmap
        }
        return runCatching {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }.getOrDefault(bitmap)
    }

    // ---- the pipeline ----

    /**
     * Decode, place on a screen-shaped black canvas per [mode], run the stack.
     *
     * The canvas comes first so every filter works in *screen* space: corner
     * effects put their corners where the screen's corners will be, and FIT's
     * letterbox bars are part of the image the filters see — which is exactly
     * what lets a corner fade dissolve the photo's edge into them.
     */
    private fun render(
        file: File,
        filters: List<Filter>,
        mode: ScaleMode,
        aspect: Float,
        maxDim: Int,
        ox: Float = 0.5f,
        oy: Float = 0.5f,
    ): Bitmap? {
        val decoded = decodeUpright(file, maxDim) ?: return null
        return runStack(compose(decoded, mode, aspect, maxDim, ox, oy), filters)
    }

    /** A flat colour at the screen's shape, run through the same stack — dither on
     *  a mid-grey is a halftone *texture*, corner fade a vignette; the filters are
     *  what make a colour background more than a colour. */
    private fun renderColor(color: Int, filters: List<Filter>, aspect: Float, maxDim: Int): Bitmap {
        val h = if (aspect < 1f) maxDim else max(1, (maxDim / aspect).roundToInt())
        val w = if (aspect < 1f) max(1, (maxDim * aspect).roundToInt()) else maxDim
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.eraseColor(0xFF000000.toInt() or color)
        return runStack(out, filters)
    }

    private fun runStack(start: Bitmap, filters: List<Filter>): Bitmap {
        var bitmap = start
        for (filter in filters) {
            bitmap = when (filter.type) {
                FilterType.MONO -> mono(bitmap)
                FilterType.FADE -> fade(bitmap, filter.amount)
                FilterType.DITHER -> dither(bitmap, filter.amount)
                FilterType.CORNER_BLUR -> cornerBlur(bitmap, filter.amount)
                FilterType.CORNER_FADE -> cornerFade(bitmap, filter.amount)
            }
        }
        return bitmap
    }

    /** The screen-shaped canvas, black, with [src] drawn on per [mode]. [ox]/[oy]
     *  slide the FILL crop through its slack — 0 shows the photo's leading edge,
     *  1 its trailing one, 0.5 the centre. Only FILL has slack to spend them on. */
    private fun compose(
        src: Bitmap,
        mode: ScaleMode,
        aspect: Float,
        maxDim: Int,
        ox: Float = 0.5f,
        oy: Float = 0.5f,
    ): Bitmap {
        if (aspect <= 0f) return src
        // Portrait screen: height is the long side and gets the budget.
        val h = if (aspect < 1f) maxDim else max(1, (maxDim / aspect).roundToInt())
        val w = if (aspect < 1f) max(1, (maxDim * aspect).roundToInt()) else maxDim
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out) // starts all-transparent-black; the eraseColor makes it opaque
        out.eraseColor(0xFF000000.toInt())
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        val srcRect = Rect(0, 0, src.width, src.height)
        val dst = when (mode) {
            ScaleMode.STRETCH -> Rect(0, 0, w, h)
            ScaleMode.FILL -> {
                // Scale up to cover; the offsets pick which slice of the overflow
                // stays on the canvas.
                val scale = max(w.toFloat() / src.width, h.toFloat() / src.height)
                val dw = (src.width * scale).roundToInt()
                val dh = (src.height * scale).roundToInt()
                val left = (-(dw - w) * ox.coerceIn(0f, 1f)).roundToInt()
                val top = (-(dh - h) * oy.coerceIn(0f, 1f)).roundToInt()
                Rect(left, top, left + dw, top + dh)
            }
            ScaleMode.FIT -> {
                // Scale down to be contained, centred; the rest stays black.
                val scale = min(w.toFloat() / src.width, h.toFloat() / src.height)
                val dw = (src.width * scale).roundToInt()
                val dh = (src.height * scale).roundToInt()
                Rect((w - dw) / 2, (h - dh) / 2, (w - dw) / 2 + dw, (h - dh) / 2 + dh)
            }
        }
        canvas.drawBitmap(src, srcRect, dst, paint)
        return out
    }

    private fun mono(src: Bitmap): Bitmap {
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val pixels = IntArray(out.width * out.height)
        out.getPixels(pixels, 0, out.width, 0, 0, out.width, out.height)
        for (i in pixels.indices) {
            val g = gray(pixels[i])
            pixels[i] = 0xFF shl 24 or (g shl 16) or (g shl 8) or g
        }
        out.setPixels(pixels, 0, out.width, 0, 0, out.width, out.height)
        return out
    }

    /** Scales every channel toward black — black behind the face — by 100−[amount]%. */
    private fun fade(src: Bitmap, amount: Int): Bitmap {
        val keep = amount.coerceIn(0, 100)
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val pixels = IntArray(out.width * out.height)
        out.getPixels(pixels, 0, out.width, 0, 0, out.width, out.height)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = ((p shr 16 and 0xFF) * keep) / 100
            val g = ((p shr 8 and 0xFF) * keep) / 100
            val b = ((p and 0xFF) * keep) / 100
            pixels[i] = 0xFF shl 24 or (r shl 16) or (g shl 8) or b
        }
        out.setPixels(pixels, 0, out.width, 0, 0, out.width, out.height)
        return out
    }

    /**
     * Ordered Bayer dither to pure black-and-white. [quarters] is the cell size in
     * quarter-pixels — 32 is an 8px halftone cell.
     *
     * At 4 and above (whole cells) it works at 1/cell scale and blows back up with
     * no filtering, so an 8× dither is literally 8×8 blocks of solid black or
     * white — the chunky, deliberate look — rather than a fine dither the panel
     * would smear. *Below* 4 it goes the other way: dither on a 2× or 4× oversized
     * copy and settle back down bilinear, so neighbouring sub-pixel cells average —
     * a softer, grayer halftone finer than the panel's own grid could hold.
     */
    private fun dither(src: Bitmap, quarters: Int): Bitmap {
        val q = quarters.coerceIn(1, 128)
        if (q < 4) {
            val up = 4 / q // 2 for 0.5×, 4 for 0.25×
            val big = Bitmap.createScaledBitmap(src, src.width * up, src.height * up, true)
            val dithered = ditherWhole(big, 1)
            return Bitmap.createScaledBitmap(dithered, src.width, src.height, true)
        }
        return ditherWhole(src, q / 4)
    }

    private fun ditherWhole(src: Bitmap, cell: Int): Bitmap {
        val c = cell.coerceIn(1, 32)
        val w = max(1, src.width / c)
        val h = max(1, src.height / c)
        val small = if (c == 1) src.copy(Bitmap.Config.ARGB_8888, true) else Bitmap.createScaledBitmap(src, w, h, true)
        val pixels = IntArray(w * h)
        small.getPixels(pixels, 0, w, 0, 0, w, h)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val g = gray(pixels[row + x])
                val threshold = (BAYER_8[y and 7][x and 7] * 255 + 32) / 64
                pixels[row + x] = if (g > threshold) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
            }
        }
        small.setPixels(pixels, 0, w, 0, 0, w, h)
        return if (c == 1) small else Bitmap.createScaledBitmap(small, src.width, src.height, false)
    }

    /**
     * Where a corner effect begins, as the fraction of the centre-to-corner
     * distance that stays untouched. Shared by blur and fade so "50%" means the
     * same reach in both rows: a gentle step grazes the corners, and each step up
     * walks the effect further in — at 100% it starts at the centre itself.
     */
    private fun cornerStart(strength: Int): Float = 0.9f * (1f - strength / 100f)

    /**
     * Blur rising from a sharp centre out to the corners.
     *
     * The blurred copy is the cheap classic — downscale bilinear, upscale bilinear —
     * which at 1/20 scale is a heavy, smooth blur with no kernel code to get wrong.
     * [amount] moves both how strong that blur is and how far in it reaches.
     */
    private fun cornerBlur(src: Bitmap, amount: Int): Bitmap {
        val strength = amount.coerceIn(1, 100)
        // 1/6 scale at 10% up to 1/20 at 100% — visibly soft either way, heavier with more.
        val factor = 6 + (strength * 14) / 100
        val small = Bitmap.createScaledBitmap(
            src,
            max(1, src.width / factor),
            max(1, src.height / factor),
            true,
        )
        val blurred = Bitmap.createScaledBitmap(small, src.width, src.height, true)
        val soft = IntArray(src.width * src.height)
        blurred.getPixels(soft, 0, src.width, 0, 0, src.width, src.height)
        return cornerBlend(src, strength) { index, t, sharp ->
            val b = soft[index]
            val ti = (t * 256).toInt()
            val red = ((sharp shr 16 and 0xFF) * (256 - ti) + (b shr 16 and 0xFF) * ti) shr 8
            val green = ((sharp shr 8 and 0xFF) * (256 - ti) + (b shr 8 and 0xFF) * ti) shr 8
            val blue = ((sharp and 0xFF) * (256 - ti) + (b and 0xFF) * ti) shr 8
            0xFF shl 24 or (red shl 16) or (green shl 8) or blue
        }
    }

    /** The corner gradient into black: the same mask as [cornerBlur], blending
     *  toward the background instead of a blurred copy, so the picture's edges
     *  simply dissolve into the black behind the face. */
    private fun cornerFade(src: Bitmap, amount: Int): Bitmap {
        val strength = amount.coerceIn(1, 100)
        return cornerBlend(src, strength) { _, t, sharp ->
            val keep = ((1f - t) * 256).toInt()
            val r = ((sharp shr 16 and 0xFF) * keep) shr 8
            val g = ((sharp shr 8 and 0xFF) * keep) shr 8
            val b = ((sharp and 0xFF) * keep) shr 8
            0xFF shl 24 or (r shl 16) or (g shl 8) or b
        }
    }

    /** The shared radial-mask walk: computes each pixel's eased 0..1 corner factor
     *  and hands it (with the pixel) to [blend]. Skips the untouched centre. */
    private inline fun cornerBlend(
        src: Bitmap,
        strength: Int,
        blend: (index: Int, t: Float, pixel: Int) -> Int,
    ): Bitmap {
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val w = out.width
        val h = out.height
        val pixels = IntArray(w * h)
        out.getPixels(pixels, 0, w, 0, 0, w, h)

        val cx = (w - 1) / 2f
        val cy = (h - 1) / 2f
        val maxDist = sqrt(cx * cx + cy * cy)
        val start = cornerStart(strength)
        for (y in 0 until h) {
            val row = y * w
            val dy = (y - cy) / maxDist
            for (x in 0 until w) {
                val dx = (x - cx) / maxDist
                val d = sqrt(dx * dx + dy * dy)
                var t = ((d - start) / (1f - start)).coerceIn(0f, 1f)
                t *= t // ease in, so the transition has no visible ring
                if (t <= 0f) continue
                pixels[row + x] = blend(row + x, t, pixels[row + x])
            }
        }
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }

    private fun gray(pixel: Int): Int =
        ((pixel shr 16 and 0xFF) * 299 + (pixel shr 8 and 0xFF) * 587 + (pixel and 0xFF) * 114) / 1000

    /** The standard 8×8 Bayer matrix, values 0..63. */
    private val BAYER_8 = arrayOf(
        intArrayOf(0, 32, 8, 40, 2, 34, 10, 42),
        intArrayOf(48, 16, 56, 24, 50, 18, 58, 26),
        intArrayOf(12, 44, 4, 36, 14, 46, 6, 38),
        intArrayOf(60, 28, 52, 20, 62, 30, 54, 22),
        intArrayOf(3, 35, 11, 43, 1, 33, 9, 41),
        intArrayOf(51, 19, 59, 27, 49, 17, 57, 25),
        intArrayOf(15, 47, 7, 39, 13, 45, 5, 37),
        intArrayOf(63, 31, 55, 23, 61, 29, 53, 21),
    )

    /** The editor preview re-renders on every tap of −/+, so it works small. */
    private const val PREVIEW_DIM = 480
}
