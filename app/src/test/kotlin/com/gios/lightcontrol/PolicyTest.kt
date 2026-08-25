package com.gios.lightcontrol

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which built-in rule an app gets, and — more to the point — in which order the lists are asked.
 *
 * Three prefix lists overlap here on purpose, so the order is the behavior rather than a detail
 * of it. `com.gios.brightrecorder` sits inside `com.gios.`, and `com.lightphone.spotify` sits
 * inside `com.lightphone.`; in both cases the more specific entry has to win or the app silently
 * gets the weaker treatment.
 */
class PolicyTest {

    @Test
    fun `an app that owns the whole wheel is left alone entirely`() {
        // ScrollThrough would pass its turns but keep the click, and the click's default is the
        // torch — so the press would light the torch instead of reaching the app, with nothing
        // the app could do about it. BrightRecorder uses the press as play/stop.
        assertEquals(AppRule.Off, Policy.builtInRuleFor("com.gios.brightrecorder"))
        // Roll's dial lock is unlocked by a wheel click and by nothing else, so under
        // ScrollThrough the lock could be set and then never cleared: the app told you to click
        // the wheel while the click was being spent on the torch one layer above it.
        assertEquals(AppRule.Off, Policy.builtInRuleFor("com.gios.lightcamera"))
    }

    @Test
    fun `other gios apps still only get their turns`() {
        // The stronger rule must not leak to the rest of the family: they want their buttons.
        assertEquals(AppRule.ScrollThrough, Policy.builtInRuleFor("com.gios.lightnoise"))
        assertEquals(AppRule.ScrollThrough, Policy.builtInRuleFor("com.gios.lightcontrol"))
    }

    @Test
    fun `Light's own software stays hands off`() {
        assertEquals(AppRule.Off, Policy.builtInRuleFor("com.lightos.dashboard"))
        assertEquals(AppRule.Off, Policy.builtInRuleFor("com.thelightphone.tracker"))
        assertEquals(AppRule.Off, Policy.builtInRuleFor("com.android.systemui"))
    }

    @Test
    fun `the phono fork keeps its bindings despite its Light-looking id`() {
        // Sits inside com.lightphone., which is hands-off; scroll-aware is checked first.
        assertEquals(AppRule.ScrollThrough, Policy.builtInRuleFor("com.lightphone.spotify"))
        assertEquals(AppRule.Off, Policy.builtInRuleFor("com.lightphone.something.else"))
    }

    @Test
    fun `anything unknown falls through to the default`() {
        assertEquals(AppRule.Default, Policy.builtInRuleFor("com.example.whatever"))
    }

    /**
     * The two apps that hold WRITE_SECURE_SETTINGS themselves are left alone rather than forced
     * to colour. Forcing them would be correct about the colour and wrong about who decides — and
     * two writers on one setting is what a flickering screen is.
     */
    @Test
    fun `apps that drive the filter themselves are passed through`() {
        assertEquals(ColorRule.Passthrough, Policy.builtInColorRuleFor("com.gios.lightcamera"))
        assertEquals(ColorRule.Passthrough, Policy.builtInColorRuleFor("com.gios.lightchat"))
    }

    /** The stock camera holds no grant and cannot ask, so this app asks on its behalf. */
    @Test
    fun `the stock camera is color out of the box`() {
        assertEquals(ColorRule.Color, Policy.builtInColorRuleFor("com.android.camera2"))
    }

    @Test
    fun `LightOS drives its own colour`() {
        // It owns the daltonizer and has a per-tool colour setting; Default would flatten every
        // tool it draws to the baseline, which is mono. See colorPresets.
        assertEquals(ColorRule.Passthrough, Policy.builtInColorRuleFor("com.lightos"))
    }

    /**
     * The one app on the table that holds the grant *and* is forced anyway.
     *
     * It asks for the whole app, the whole time it is in front, rather than having a per-screen
     * opinion — so this and the notebook are asking for the same thing and cannot fight over it.
     * Stating it here is what makes the notebook come up in colour on a phone where the notebook
     * itself was never granted anything; without a row it resolved to Default, and Default forces
     * the baseline, which is mono.
     */
    @Test
    fun `BrightNotebook is color out of the box`() {
        assertEquals(ColorRule.Color, Policy.builtInColorRuleFor("com.gios.lightnotebook"))
    }

    @Test
    fun `everything else stays mono, including the rest of our own apps`() {
        // The whole point of the table being ids rather than the com.gios. prefix: sharing a
        // package prefix with a camera is not a reason to go colour. The notebook used to be the
        // example here and is now a deliberate entry above, so the recorder makes the point — it
        // has a tape and a level meter on screen and no use for a hue.
        assertEquals(ColorRule.Default, Policy.builtInColorRuleFor("com.gios.brightrecorder"))
        assertEquals(ColorRule.Default, Policy.builtInColorRuleFor("com.gios.lightauth"))
        assertEquals(ColorRule.Default, Policy.builtInColorRuleFor("com.example.whatever"))
    }
}
