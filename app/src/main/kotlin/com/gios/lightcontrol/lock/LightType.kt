package com.gios.lightcontrol.lock

import android.content.Context
import android.graphics.Typeface
import android.graphics.fonts.SystemFonts
import android.os.Build

/**
 * LightOS's own type scale and grid, for a screen built out of plain Views.
 *
 * Ported from `lightphone/light-sdk`'s `sdk/ui` module, which is the real LightOS design system
 * and is MIT licensed. The app's Compose screens already use it; this is the same numbers in the
 * form a `TextView` can take, because the lock face is a window owned by a service and hosting a
 * composition there would be far more machinery than a handful of TextViews.
 *
 * Two rules, and neither is negotiable if the screen is to look like the phone it is on:
 *
 *  - **Type is named and scales with the screen height**, never a fixed sp. The SDK's
 *    `designVerticalPxToSp` is `px * screenHeightDp / 600` — the design pixels below are measured
 *    against a 600px-tall reference, so a hardcoded `textSize = 68f` is right on exactly one
 *    device and subtly wrong everywhere else, including the emulator profile.
 *  - **Space is grid units, never dp.** The grid is 27 wide by 31 tall and
 *    `gridUnitsAsDp` is `screenWidthDp / 27 * units`. The top bar is 3 units, the horizontal
 *    inset is 1.
 *
 * Weights follow the app's Compose theme so the two halves of the app agree: the clock light, body
 * regular, the small tracked labels medium.
 */
class LightType(context: Context) {

    private val metrics = context.resources.displayMetrics
    private val screenHeightDp = metrics.heightPixels / metrics.density
    private val screenWidthDp = metrics.widthPixels / metrics.density

    /** The SDK's `designVerticalPxToSp`. */
    private fun sp(designPx: Float) = designPx * screenHeightDp / 600f

    /** The SDK's `gridUnitsAsDp`, returned in px because View padding is px. */
    fun gridPx(units: Float): Int = (screenWidthDp / 27f * units * metrics.density).toInt()

    // The LP3 design pixel values, straight out of the SDK. Named, because "title" survives a
    // change of screen and "90sp" does not.
    val title = sp(115f)
    val subtitle = sp(52f)
    val heading = sp(38f)
    val subheading = sp(30f)
    val copy = sp(30f)
    val button = sp(30f)
    val paragraph = sp(24.5f)
    val detail = sp(20f)
    val fine = sp(25f)
    val superfine = sp(16f)

    /** Tracking is in em, which is what the SDK's percentages already are. */
    val buttonTracking = 0.15f
    val subheadingTracking = 0.03f

    val light: Typeface? by lazy { akkurat(300) }
    val regular: Typeface? by lazy { akkurat(400) }
    val medium: Typeface? by lazy { akkurat(500) }

    /**
     * Akkurat, off the system font list, at the nearest weight available.
     *
     * LightOS ships it and uses it for everything, so anything else on this screen reads as a
     * different phone. Resolved by weight rather than by family name because `Typeface.create`
     * would give back a synthesised bold of the default font if Akkurat were missing, which looks
     * deliberate and is not.
     */
    private fun akkurat(weight: Int): Typeface? = runCatching {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return@runCatching null
        SystemFonts.getAvailableFonts()
            .asSequence()
            .filter { it.file?.name?.startsWith("Akkurat", ignoreCase = true) == true }
            .filter { it.style.slant == 0 }
            .minByOrNull { kotlin.math.abs(it.style.weight - weight) }
            ?.file
            ?.let { Typeface.createFromFile(it) }
    }.getOrNull()
}
