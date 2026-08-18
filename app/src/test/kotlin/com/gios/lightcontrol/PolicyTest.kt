package com.gios.lightcontrol

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which built-in rule an app gets, and — more to the point — in which order the lists are asked.
 *
 * Three prefix lists overlap here on purpose, so the order is the behaviour rather than a detail
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
    }

    @Test
    fun `other gios apps still only get their turns`() {
        // The stronger rule must not leak to the rest of the family: they want their buttons.
        assertEquals(AppRule.ScrollThrough, Policy.builtInRuleFor("com.gios.lightnoise"))
        assertEquals(AppRule.ScrollThrough, Policy.builtInRuleFor("com.gios.lightcamera"))
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
}
