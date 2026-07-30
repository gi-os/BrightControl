package com.gios.lightcontrol.keys

import android.content.Context
import android.provider.Settings
import kotlin.math.roundToInt

/**
 * Stepping the system screen brightness.
 *
 * This is the real setting, not a per-window override: a service has no window of its own,
 * and a global control that only dimmed one app would be a lie. That makes `WRITE_SETTINGS`
 * load-bearing rather than optional — without the appop there is nothing to fall back on,
 * which is why the settings screen leads with the grant.
 *
 * The scale is derived rather than assumed. Android's brightness maximum is an internal
 * resource — 255 on most phones, but 1023, 2047 and 4095 all ship — so it is recovered from
 * the platform's own two mirrors of the same value: `screen_brightness` (int) over
 * `screen_brightness_float` (0..1) is the maximum. If the float row is missing, 255.
 */
class Brightness(private val context: Context) {

    private val cr = context.contentResolver

    fun canWrite(): Boolean =
        runCatching { Settings.System.canWrite(context) }.getOrDefault(false)

    /**
     * Move brightness by [notches] (positive is brighter) across [steps] stops, and report
     * the resulting level as a percentage. Returns null if the appop is missing.
     */
    fun step(notches: Int, steps: Int): Int? {
        if (!canWrite()) return null
        val max = max()
        val floor = (max * 0.01f).roundToInt().coerceAtLeast(1)
        val current = runCatching {
            Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS)
        }.getOrDefault(max / 2)

        // At least one raw unit per notch, however coarse the scale turns out to be.
        val delta = (max.toFloat() / steps * notches).roundToInt()
            .let { if (it == 0) notches.coerceIn(-1, 1) else it }
        val next = (current + delta).coerceIn(floor, max)

        return runCatching {
            // Auto-brightness would fight every write and win a second later.
            Settings.System.putInt(
                cr,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
            )
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, next)
            // Keep the float row consistent when the platform is using it, or the display
            // service can restore the old value from it.
            if (floatValue() >= 0f) {
                Settings.System.putFloat(cr, SCREEN_BRIGHTNESS_FLOAT, next.toFloat() / max)
            }
            (next * 100f / max).roundToInt()
        }.getOrNull()
    }

    /** The current level as a percentage, for the settings screen. */
    fun percent(): Int? {
        val max = max()
        val current = runCatching {
            Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS)
        }.getOrNull() ?: return null
        return (current * 100f / max).roundToInt()
    }

    /** The derived maximum, exposed so the settings screen can show what it worked out. */
    fun max(): Int {
        val int = runCatching {
            Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS)
        }.getOrDefault(-1)
        val float = floatValue()
        // Below 2% the division amplifies rounding error into nonsense.
        if (int > 0 && float >= 0.02f && float <= 1f) {
            return (int / float).roundToInt().coerceIn(64, 65_535)
        }
        return DEFAULT_MAX
    }

    private fun floatValue(): Float =
        runCatching { Settings.System.getFloat(cr, SCREEN_BRIGHTNESS_FLOAT) }.getOrDefault(-1f)

    private companion object {
        const val DEFAULT_MAX = 255

        /** `Settings.System.SCREEN_BRIGHTNESS_FLOAT` is @hide; the key name is not. */
        const val SCREEN_BRIGHTNESS_FLOAT = "screen_brightness_float"
    }
}
