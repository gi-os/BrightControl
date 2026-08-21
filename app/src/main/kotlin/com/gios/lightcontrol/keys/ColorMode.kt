package com.gios.lightcontrol.keys

import android.content.Context
import android.provider.Settings
import com.gios.lightcontrol.ColorRule
import com.gios.lightcontrol.Prefs

/**
 * Per-app colour, on a phone that only has one colour switch.
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
     * Drive the daltonizer to whatever [pkg] asks for. Null (no app known) is treated as
     * Default. A no-op when the master switch is off, so turning the feature off leaves the
     * phone exactly as the user last set it rather than forcing a baseline.
     */
    fun applyFor(pkg: String?) {
        if (!prefs.colorAutoSwitch) return
        captureBaseline()
        val rule = if (pkg == null) ColorRule.Default else prefs.colorRuleFor(pkg)
        when (rule) {
            ColorRule.Color -> set(enabled = 0, mode = prefs.colorBaselineMode)
            ColorRule.Mono -> set(enabled = 1, mode = 0)
            ColorRule.Default -> set(
                enabled = prefs.colorBaselineEnabled.coerceAtLeast(0),
                mode = prefs.colorBaselineMode,
            )
        }
    }

    /**
     * Put the phone back to its captured baseline and forget the capture. Called when the
     * feature is switched off and on unbind, so nothing is left forced by an app that is no
     * longer being watched.
     */
    fun restoreBaseline() {
        if (prefs.colorBaselineEnabled == -1) return
        set(enabled = prefs.colorBaselineEnabled.coerceAtLeast(0), mode = prefs.colorBaselineMode)
    }

    private fun set(enabled: Int, mode: Int) {
        // Only write on a difference: these settings notify observers, and LightOS's own colour
        // pipeline is one of them, so a redundant write is a redundant repaint of the whole panel.
        runCatching {
            if (read(MODE, mode) != mode) {
                Settings.Secure.putInt(context.contentResolver, MODE, mode)
            }
            if (read(ENABLED, enabled) != enabled) {
                Settings.Secure.putInt(context.contentResolver, ENABLED, enabled)
            }
        }
    }

    private fun read(key: String, fallback: Int): Int =
        runCatching { Settings.Secure.getInt(context.contentResolver, key) }.getOrDefault(fallback)

    private companion object {
        const val ENABLED = "accessibility_display_daltonizer_enabled"
        const val MODE = "accessibility_display_daltonizer"
    }
}
