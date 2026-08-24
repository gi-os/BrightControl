package com.gios.lightcontrol.keys

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.format.DateFormat
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
     * An app on [ColorRule.Passthrough] leaves the screen alone for the same reason a null does:
     * declining to have an opinion is not the same as having the opinion "baseline". See that
     * rule for why apps that hold the secure-settings grant themselves are on it.
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
        // A nudge deliberately writes a value it is about to take back, and the service's settings
        // observer cannot tell that from LightOS interfering. Without this it re-asserts into the
        // middle of the nudge, sees a difference, writes, and nudges again — forever.
        if (nudging) return
        val rule = prefs.colorRuleFor(pkg)
        // Passthrough leaves before the baseline is captured, not just before the write. Capturing
        // here would record whatever the app in front had set for itself as this phone's idea of
        // normal, and every app with no rule would inherit it from then on.
        if (rule == ColorRule.Passthrough) return
        captureBaseline()
        val (enabled, mode) = when (rule) {
            ColorRule.Color -> 0 to MODE_OFF
            ColorRule.Mono -> 1 to 0
            ColorRule.Default ->
                prefs.colorBaselineEnabled.coerceAtLeast(0) to prefs.colorBaselineMode
            // Returned above. Listed so a rule added later cannot silently fall through to a write.
            ColorRule.Passthrough -> return
        }
        // Written down before the write, so a read-back still in flight can tell "something
        // overwrote me" from "the next app asked for something else". See [verify].
        wanted = enabled to mode
        // Nothing to say when nothing moved. A re-assert that finds the screen already right is
        // the overwhelmingly common case — logging it would push the interesting lines out of a
        // twelve-line ring within seconds.
        if (!set(enabled, mode)) return
        if (mode == MODE_OFF) nudge()
        verify(pkg, rule, enabled, mode)
    }

    /**
     * Make the "off" write look like a real change, invisibly.
     *
     * The settings provider drops a write of the value already stored, and a value that never
     * changes notifies nobody. That matters here because this app does not paint the screen — it
     * states a setting and something else acts on the notification. If that reader missed the
     * original transition and is now sitting on a stale idea of the filter, re-writing the same
     * ints will never tell it otherwise, which is the shape of a screen that stays grey while
     * every value reads correct.
     *
     * With [MODE] at [MODE_OFF] there is no filter at either end, so the enable flag can be taken
     * up and put back down and nothing on screen moves — a genuine change notification with no
     * visible cost. Only done for off, because the same trick around mono would flash the phone
     * out of monochrome to say so.
     */
    private fun nudge() {
        nudging = true
        runCatching {
            Settings.Secure.putInt(context.contentResolver, ENABLED, 1)
        }
        handler.postDelayed({
            runCatching { Settings.Secure.putInt(context.contentResolver, ENABLED, 0) }
            nudging = false
        }, NUDGE_MS)
    }

    /** True between the two halves of a [nudge]. See [applyFor]. */
    @Volatile
    private var nudging = false

    /**
     * Read the pair back a moment later and write down what happened.
     *
     * This exists because the failure has three possible causes that look identical from the
     * outside — the write not landing, the write landing on a system that ignores it, and
     * something else writing afterwards — and no way to tell them apart from the phone. The line
     * says which: `want` and `got` matching means this app's state is correct and unheeded, and
     * differing names the values whoever wrote last preferred. A rule that produces no line at
     * all was never applied, because the event announcing the app never arrived.
     *
     * The fourth possibility is not a fault at all and used to be reported as one — the next app
     * came forward and stated its own rule while this read-back was still pending. [ColorOutcome]
     * separates it out.
     */
    private fun verify(pkg: String, rule: ColorRule, enabled: Int, mode: Int) {
        handler.postDelayed({
            runCatching {
                val gotEnabled = read(ENABLED, -9)
                val gotMode = read(MODE, -9)
                val at = DateFormat.format("HH:mm:ss", System.currentTimeMillis()).toString()
                prefs.appendColorLog(
                    ColorOutcome.line(
                        at = at,
                        pkg = pkg,
                        rule = rule.name,
                        want = enabled to mode,
                        got = gotEnabled to gotMode,
                        wantedNow = wanted,
                    ),
                )
            }
        }, VERIFY_MS)
    }

    /** What the front app wants right now, as of the last [applyFor]. See [ColorOutcome]. */
    @Volatile
    private var wanted: Pair<Int, Int>? = null

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
    private fun set(enabled: Int, mode: Int): Boolean {
        var wrote = false
        runCatching {
            val writeMode = {
                if (read(MODE, mode) != mode) {
                    Settings.Secure.putInt(context.contentResolver, MODE, mode)
                    wrote = true
                }
            }
            val writeEnabled = {
                if (read(ENABLED, enabled) != enabled) {
                    Settings.Secure.putInt(context.contentResolver, ENABLED, enabled)
                    wrote = true
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
        return wrote
    }

    private val handler = Handler(Looper.getMainLooper())

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

        /** How long the invisible enable flag stays up. Long enough to be a change, not a state. */
        const val NUDGE_MS = 60L

        /** How long to wait before reading back. Past the nudge, and past a late overwrite. */
        const val VERIFY_MS = 900L

        const val ENABLED = "accessibility_display_daltonizer_enabled"
        const val MODE = "accessibility_display_daltonizer"
    }
}
