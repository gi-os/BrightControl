package com.gios.lightcontrol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
        assertEquals(AppRule.Off, Policy.builtInRuleFor("com.gios.brighthermes"))
    }

    /**
     * The stored-rule bypass, which is the bug this function exists to close.
     *
     * `ruleFor` prefers an explicit per-app rule, and rightly — but a ScrollThrough stored before
     * an app started using its click then eats that click on every build for ever, because the
     * built-in fix never gets consulted again. The claim in the service therefore asks *this*
     * question, from the built-in list alone, after the stored rule has had its say about
     * everything else. First reported as "click to unlock the dial doesn't work": the app said
     * click, the service spent the click on the torch, and there was nothing on the phone that
     * could say so.
     */
    @Test
    fun `wheel ownership is answered from the built-in list, whatever anyone stored`() {
        assertTrue(Policy.ownsWheelClick("com.gios.lightcamera"))
        assertTrue(Policy.ownsWheelClick("com.gios.brightrecorder"))
        assertTrue(Policy.ownsWheelClick("com.gios.brighthermes"))
        assertFalse(Policy.ownsWheelClick("com.gios.lightnoise"))
        assertFalse(Policy.ownsWheelClick("com.example.whatever"))
        assertFalse(Policy.ownsWheelClick(null))
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

    /** Phono's package id, which does not look like ours and so cannot ride any prefix. */
    @Test
    fun `BrightMusic is color out of the box`() {
        assertEquals(ColorRule.Color, Policy.builtInColorRuleFor("com.lightphone.spotify"))
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
    // ------------------------------------------------------- where the volume strip may be drawn

    /**
     * The order between the user's list and the built-in table, which is the whole of this rule.
     *
     * Four reports, against four different apps, all of them "the strip is over something that
     * already has a volume control". The table can only ever know about Light's own screens, so
     * the list has to be able to say the thing the table cannot — including about a screen the
     * table has already made an exception for.
     */
    @Test
    fun `an app on the user's list never gets a strip`() {
        assertFalse(Policy.volumeHudAllowed("com.fenleon.audiobooks", setOf("com.fenleon.audiobooks"), inCall = false))
        // And in a call, which is the case the table's own exception would otherwise win.
        assertFalse(Policy.volumeHudAllowed("com.lightos.dialer", setOf("com.lightos.dialer"), inCall = true))
    }

    @Test
    fun `Light's own screens are refused by the table, and the dialer only until a call`() {
        assertFalse(Policy.volumeHudAllowed("com.lightos.dashboard", emptySet(), inCall = false))
        assertFalse(Policy.volumeHudAllowed("com.thelightphone.notes", emptySet(), inCall = false))
        // The exception the table exists to make: LightOS's dialer has no volume UI of its own and
        // is in front for the whole call, so a call that is too quiet needs the strip.
        assertTrue(Policy.volumeHudAllowed("com.lightos.dialer", emptySet(), inCall = true))
    }

    @Test
    fun `everything else keeps the strip, including nothing known in front`() {
        assertTrue(Policy.volumeHudAllowed("com.gios.brightmusic", emptySet(), inCall = false))
        // Unlike the edge strips, an unknown package is allowed. Guessing wrong here costs one
        // readout; guessing wrong there eats the edge of a screen nobody has identified.
        assertTrue(Policy.volumeHudAllowed(null, emptySet(), inCall = false))
    }

    @Test
    fun `the table is asked separately so the settings row can say ALWAYS OFF`() {
        assertTrue(Policy.volumeHudRefusedByTable("com.lightos.dashboard"))
        assertFalse(Policy.volumeHudRefusedByTable("com.gios.brightmusic"))
    }

}
