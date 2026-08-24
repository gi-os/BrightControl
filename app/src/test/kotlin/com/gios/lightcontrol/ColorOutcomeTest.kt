package com.gios.lightcontrol

import com.gios.lightcontrol.keys.ColorOutcome
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Telling "something else overwrote me" apart from "the next app asked for something else".
 *
 * The numbers below are the real ones off light-reports#37: `0/-1` is a Color rule (the
 * daltonizer off, written as mode -1 so no reader can reconstitute grey from it) and `1/0` is
 * the mono baseline.
 */
class ColorOutcomeTest {

    @Test
    fun `a write that reads back unchanged held`() {
        assertEquals("ok", ColorOutcome.of(0 to -1, 0 to -1, 0 to -1))
    }

    @Test
    fun `the next app's rule landing is not a loss`() {
        // 14:16:32 lightchat COLOR want 0/-1 got 1/0 — and the very next line is edgegestures
        // stating the baseline successfully. Nothing was lost; the question changed.
        assertEquals("superseded", ColorOutcome.of(0 to -1, 1 to 0, 1 to 0))
        // The same thing the other way round, one app switch later.
        assertEquals("superseded", ColorOutcome.of(1 to 0, 0 to -1, 0 to -1))
    }

    @Test
    fun `values nobody asked for are still lost`() {
        // LightOS reconstituting monochrome while a Color app is still the front app: the front
        // app's rule has not changed, so this is the real case the log exists to catch.
        assertEquals("LOST", ColorOutcome.of(0 to -1, 1 to 0, 0 to -1))
        // A third state matching neither the write nor what is wanted now.
        assertEquals("LOST", ColorOutcome.of(0 to -1, 1 to 11, 1 to 0))
    }

    @Test
    fun `nothing wanted yet cannot supersede`() {
        assertEquals("LOST", ColorOutcome.of(0 to -1, 1 to 0, null))
    }
}
