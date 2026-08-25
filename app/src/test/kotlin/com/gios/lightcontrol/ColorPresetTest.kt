package com.gios.lightcontrol

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The preset table, which is the whole point of the feature on a phone nobody has configured.
 *
 * A preset that ships wrong is invisible: the app resolves to [ColorRule.Default], Default forces
 * the baseline, and the baseline on this phone is mono — so the symptom is an app that will not stay
 * in colour, which reads as the colour feature being broken rather than as a missing row.
 */
class ColorPresetTest {

    @Test
    fun `BrightNotebook ships in colour`() {
        assertEquals(ColorRule.Color, Policy.builtInColorRuleFor("com.gios.lightnotebook"))
    }

    @Test
    fun `apps that drive the filter themselves are left alone`() {
        assertEquals(ColorRule.Passthrough, Policy.builtInColorRuleFor("com.gios.lightcamera"))
        assertEquals(ColorRule.Passthrough, Policy.builtInColorRuleFor("com.gios.lightchat"))
        // LightOS owns the daltonizer and has a colour setting per tool of its own.
        assertEquals(ColorRule.Passthrough, Policy.builtInColorRuleFor("com.lightos"))
    }

    @Test
    fun `the stock camera is forced, having no grant to ask with`() {
        assertEquals(ColorRule.Color, Policy.builtInColorRuleFor("com.android.camera2"))
    }

    @Test
    fun `everything else stays mono`() {
        assertEquals(ColorRule.Default, Policy.builtInColorRuleFor("com.gios.lightauth"))
        assertEquals(ColorRule.Default, Policy.builtInColorRuleFor("com.example.whatever"))
    }
}
