package com.gios.lightcontrol

import com.gios.lightcontrol.keys.ColorMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which color writes are declined.
 *
 * The one that caused a bug report: an overlay app with no rule raised a window-state event, and
 * `Default` means *restore the baseline*, which on this phone is monochrome. The panel went mono
 * under whatever the user was actually looking at, and half the color apps in a row appeared to
 * open wrong depending on whether the overlay happened to fire after them.
 */
class ColorBaselineGuardTest {

    @Test
    fun `a floating window with no rule may not restore the baseline`() {
        assertTrue(ColorMode.skipBaseline(ColorRule.Default, realScreen = false))
    }

    @Test
    fun `a real app screen with no rule still restores the baseline`() {
        assertFalse(ColorMode.skipBaseline(ColorRule.Default, realScreen = true))
    }

    @Test
    fun `a package with an opinion is applied whatever window it raised`() {
        assertFalse(ColorMode.skipBaseline(ColorRule.Color, realScreen = false))
        assertFalse(ColorMode.skipBaseline(ColorRule.Mono, realScreen = false))
    }
}
