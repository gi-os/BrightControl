package com.gios.lightcontrol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Tapping a row on the per-app colour list has to change the row.
 *
 * The interesting case is an app whose preset is Passthrough. AUTO resolves through the preset
 * table, so for those apps AUTO and PASS are the same outcome — and a step from PASS to AUTO
 * left the screen looking exactly as it did. Roll and BrightChat were both stuck like that.
 */
class ColorCycleTest {

    private fun resolve(store: ColorRule, builtIn: ColorRule) =
        if (store == ColorRule.Default) builtIn else store

    @Test
    fun `a passthrough preset can be moved off passthrough`() {
        val builtIn = Policy.builtInColorRuleFor("com.gios.lightcamera")
        assertEquals(ColorRule.Passthrough, builtIn)
        val store = Policy.nextColorRule(ColorRule.Passthrough, builtIn)
        assertEquals(ColorRule.Color, store)
        assertNotEquals(ColorRule.Passthrough, resolve(store, builtIn))
    }

    @Test
    fun `every tap changes what the row resolves to`() {
        for (builtIn in ColorRule.values()) {
            var resolved = builtIn
            repeat(8) {
                val store = Policy.nextColorRule(resolved, builtIn)
                val next = resolve(store, builtIn)
                assertNotEquals("stuck on $resolved with preset $builtIn", resolved, next)
                resolved = next
            }
        }
    }

    @Test
    fun `landing back on the preset clears the override`() {
        val builtIn = ColorRule.Color
        // COLOR -> MONO -> PASS -> back to the preset, stored as nothing.
        assertEquals(ColorRule.Mono, Policy.nextColorRule(ColorRule.Color, builtIn))
        assertEquals(ColorRule.Passthrough, Policy.nextColorRule(ColorRule.Mono, builtIn))
        assertEquals(ColorRule.Default, Policy.nextColorRule(ColorRule.Passthrough, builtIn))
    }

    @Test
    fun `an app with no preset still walks all four states`() {
        val builtIn = ColorRule.Default
        assertEquals(ColorRule.Color, Policy.nextColorRule(ColorRule.Default, builtIn))
        assertEquals(ColorRule.Mono, Policy.nextColorRule(ColorRule.Color, builtIn))
        assertEquals(ColorRule.Passthrough, Policy.nextColorRule(ColorRule.Mono, builtIn))
        assertEquals(ColorRule.Default, Policy.nextColorRule(ColorRule.Passthrough, builtIn))
    }
}
