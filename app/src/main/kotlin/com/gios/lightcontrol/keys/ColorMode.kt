package com.gios.lightcontrol.keys

import android.content.Context
import android.provider.Settings
import com.gios.lightcontrol.ColorRule
import com.gios.lightcontrol.Prefs

/**
 * Per-app color, on a phone that only has one color switch.
 *
 * LightOS forces the whole system to monochrome through the accessibility *daltonizer* — two
 * secure settings, [ENABLED] (0/1) and [MODE] (0 is monochromacy). This drives those two from
 * the app that is currently in front: a package with a [ColorRule.Color] rule turns the
 * daltonizer off while it is on screen; [ColorRule.Mono] turns it on; [ColorRule.Default]
 * restores the baseline the phone had before any rule fired.
 *
 * **It is written as state, never as a transition.** [applyFor] states what the daltonizer
 * should be *right now* for the given package and makes it so, idempotently. That is the whole
 * design and it is deliberate: BrightMusic's ColorMode once wrote on edges — lift on the way in,
 * restore on the way out — and a single missed edge stranded the panel in the wrong mode until
 * the process died. A function that re-asserts the desired state cannot be stranded; a missed
 * call self-corrects on the next one.
 *
 * Writing these needs `WRITE_SECURE_SETTINGS`, which LightOS has no screen to grant — the ADB
 * screen or a computer sets it once. Without it every write throws and is swallowed, so the
 * feature is inert rather than crashing, and [Grants.canWriteSecureSettings] reports it.
 */
class ColorMode(private val context: Context, private val prefs: Prefs) {

    /** Read the current daltonizer once and remember it, so Default can put it back. */
    fun captureBaseline() {
        if (prefs.colorBaselineEnabled != -1) return
        prefs.colorBaselineEnabled = read(ENABLED, 1)
        prefs.colorBaselineMode = read(MODE, 0)
    }

    /**
     * Drive the daltonizer to whatever [pkg] asks for. A no-op when the master switch is off, so
     * turning the feature off leaves the phone exactly as the user last set it rather than
     * forcing a baseline.
     *
     * **An unknown front app leaves the screen alone.** It used to be treated as [ColorRule
     * .Default], which forces the baseline — mono, on a stock phone. That is the wrong guess in
     * the one situation it actually arose: the service has just rebound after an app update and
     * has not seen a window-state event yet, so `foreground` is null while a color app is
     * sitting on screen. Every re-assert from that state actively repainted a color app mono,
     * and a screen off/on did it again. Not knowing which app is in front is a reason to do
     * nothing, not a reason to override.
     */
    fun applyFor(pkg: String?) {
        if (!prefs.colorAutoSwitch) return
        if (pkg.isNullOrBlank()) return
        captureBaseline()
        val rule = prefs.colorRuleFor(pkg)
        when (rule) {
            ColorRule.Color -> set(enabled = 0, mode = MODE_OFF)
            ColorRule.Mono -> set(enabled = 1, mode = 0)
            ColorRule.Default -> set(
                enabled = prefs.colorBaselineEnabled.coerceAtLeast(0),
                mode = prefs.colorBaselineMode,
            )
        }
    }

    /**
     * Put the phone back to its captured baseline. Called when the user switches the feature
     * off, so nothing is left forced by an app that is no longer being watched.
     *
     * The capture is deliberately kept rather than cleared. Clearing it would mean the next
     * [captureBaseline] reads whatever the daltonizer happens to be at that moment — and if that
     * moment falls while a Color app is in front, the baseline is recorded as "color" and every
     * app with no rule stays color from then on. The first capture, taken before this app had
     * forced anything, is the only honest one.
     *
     * Notably *not* called on service unbind. See ControlService.onUnbind.
     */
    fun restoreBaseline() {
        if (prefs.colorBaselineEnabled == -1) return
        set(enabled = prefs.colorBaselineEnabled.coerceAtLeast(0), mode = prefs.colorBaselineMode)
    }

    /**
     * State both settings, in the order that never shows the wrong screen in between.
     *
     * The two settings are not independent, and [MODE] alone is enough to keep the panel grey:
     * `0` *is* monochromacy. So switching an app to color by writing only `ENABLED = 0` leaves a
     * mode int that still says "grey", and any part of the pipeline that re-reads the pair —
     * LightOS does, whenever its own shell comes forward — reconstitutes monochrome from it. That
     * is the whole of the bug where color survived until you went back to LightOS and then never
     * came back: nothing was overriding this app, this app was writing a state that read as mono.
     * Off is now written as [MODE_OFF], which no reader can interpret as a filter.
     *
     * Order follows direction. Turning a filter **on**, the mode goes first, so the frame that
     * lands is never the previous filter. Turning one **off**, the enable flag goes first, for the
     * same reason in reverse. Writing them the other way round is a visible flash of the wrong
     * screen, and on a phone whose whole point is a calm display, that flash is the feature
     * failing.
     *
     * Only writes on a difference: these settings notify observers, and LightOS's own color
     * pipeline is one of them, so a redundant write is a redundant repaint of the whole panel.
     * That is also what makes [ControlService]'s settings observer safe — this app's own write
     * wakes it, the re-assert finds both values already right, and the loop stops there.
     */
    private fun set(enabled: Int, mode: Int) {
        runCatching {
            val writeMode = { if (read(MODE, mode) != mode) Settings.Secure.putInt(context.contentResolver, MODE, mode) }
            val writeEnabled = {
                if (read(ENABLED, enabled) != enabled) {
                    Settings.Secure.putInt(context.contentResolver, ENABLED, enabled)
                }
            }
            if (enabled == 1) {
                writeMode()
                writeEnabled()
            } else {
                writeEnabled()
                writeMode()
            }
        }
    }

    /** The live pair, for the diagnostic on the Color screen. Null when they cannot be read. */
    fun live(): Pair<Int, Int>? = runCatching {
        Settings.Secure.getInt(context.contentResolver, ENABLED) to
            Settings.Secure.getInt(context.contentResolver, MODE)
    }.getOrNull()

    private fun read(key: String, fallback: Int): Int =
        runCatching { Settings.Secure.getInt(context.contentResolver, key) }.getOrDefault(fallback)

    companion object {
        /**
         * The mode int that means "no filter at all". `0` is monochromacy, `11`/`12`/`13` are the
         * color-blindness corrections, and `-1` is the only value that is not a filter — which is
         * why off is written as this and not as `0`.
         */
        const val MODE_OFF = -1

        const val ENABLED = "accessibility_display_daltonizer_enabled"
        const val MODE = "accessibility_display_daltonizer"
    }
}
