package com.gios.lightcontrol

import com.gios.lightcontrol.color.ColorRequests
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The order of the four sources a colour rule can come from. Four `?:` in a row is exactly the
 * kind of thing that looks obviously right and is not — the same argument the wheel's
 * [Policy.builtInRuleFor] makes for having its own test.
 */
class ColorResolveTest {

    @Test
    fun `a user override beats everything`() {
        assertEquals(
            ColorRule.Mono,
            Policy.resolveColorRule(
                stored = ColorRule.Mono,
                asked = ColorRule.Color,
                declared = ColorRule.Color,
                preset = ColorRule.Color,
            ),
        )
    }

    @Test
    fun `what the app asks for beats what its manifest says`() {
        // The manifest is one opinion for the whole app; a request knows which screen is up.
        assertEquals(
            ColorRule.Mono,
            Policy.resolveColorRule(
                stored = null,
                asked = ColorRule.Mono,
                declared = ColorRule.Color,
                preset = ColorRule.Default,
            ),
        )
    }

    @Test
    fun `a request beats a Passthrough preset`() {
        // The one that makes the rollout safe in either order. A migrated app is still carrying
        // the preset from when it wrote the settings itself, and Passthrough means "ignore this
        // app" — so reading the table before the request would answer a polite request with
        // silence, and the screen would go grey with nothing in the log to explain it.
        assertEquals(
            ColorRule.Color,
            Policy.resolveColorRule(
                stored = null,
                asked = ColorRule.Color,
                declared = null,
                preset = ColorRule.Passthrough,
            ),
        )
    }

    @Test
    fun `an app that is not asking keeps its preset`() {
        // The other half of the same property: an app that has not been migrated yet still writes
        // its own settings, and must still be left alone.
        assertEquals(
            ColorRule.Passthrough,
            Policy.resolveColorRule(
                stored = null,
                asked = null,
                declared = null,
                preset = ColorRule.Passthrough,
            ),
        )
    }

    @Test
    fun `a manifest declaration beats the table`() {
        assertEquals(
            ColorRule.Color,
            Policy.resolveColorRule(
                stored = null,
                asked = null,
                declared = ColorRule.Color,
                preset = ColorRule.Default,
            ),
        )
    }

    @Test
    fun `an app nobody has an opinion about resolves to the baseline`() {
        assertEquals(
            ColorRule.Default,
            Policy.resolveColorRule(null, null, null, ColorRule.Default),
        )
    }

    @Test
    fun `an explicit AUTO is a choice and stops the search`() {
        // Which is why Prefs.storedColorRule exists at all. If "no choice made" and "chose AUTO"
        // were the same answer, every app on the phone would stop at step one and neither the
        // request nor the manifest would ever be consulted.
        assertEquals(
            ColorRule.Default,
            Policy.resolveColorRule(
                stored = ColorRule.Default,
                asked = ColorRule.Color,
                declared = ColorRule.Color,
                preset = ColorRule.Color,
            ),
        )
    }

    // ------------------------------------------------------------------ the wire vocabulary

    @Test
    fun `the three states are the whole vocabulary`() {
        assertEquals(ColorRule.Color, ColorRequests.ruleOf(ColorRequests.STATE_COLOUR))
        assertEquals(ColorRule.Mono, ColorRequests.ruleOf(ColorRequests.STATE_MONO))
        assertNull(ColorRequests.ruleOf(ColorRequests.STATE_CLEAR))
    }

    @Test
    fun `a state this build does not know is read as wanting nothing`() {
        // A newer library against an older BrightControl. Forcing a colour nobody asked for is the
        // worse guess, and there is no version of "I do not understand" that should repaint a
        // screen.
        listOf(3, 99, -7, Int.MAX_VALUE, Int.MIN_VALUE).forEach { state ->
            assertNull("state $state", ColorRequests.ruleOf(state))
        }
    }

    @Test
    fun `nothing in the wire vocabulary can name a package or a setting`() {
        // Stated as a test because it is the security property, and the way it would be lost is
        // somebody adding a String to `want` for a good reason. Every constant here is an Int.
        val states = listOf(
            ColorRequests.STATE_CLEAR,
            ColorRequests.STATE_COLOUR,
            ColorRequests.STATE_MONO,
        )
        assertEquals(states.size, states.distinct().size)
        assertEquals(
            listOf(ColorRequests.SERVING, ColorRequests.INERT, ColorRequests.REFUSED).size,
            listOf(ColorRequests.SERVING, ColorRequests.INERT, ColorRequests.REFUSED)
                .distinct().size,
        )
    }
}
