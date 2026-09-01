package com.gios.lightcontrol

import com.gios.lightcontrol.lock.NavText
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The numbers under the turn, US-style.
 *
 * BrightWay hands over metres because Google's routes are metric; the face speaks feet and
 * miles. A conversion is exactly the arithmetic that ships off by a factor of ten when no test
 * pins it, so the boundary cases are written out as distances a walk actually produces.
 */
class NavTextTest {

    @Test
    fun `short distances are feet, rounded to tens`() {
        assertEquals("0 FT", NavText.distance(0))
        assertEquals("100 FT", NavText.distance(30))
        assertEquals("450 FT", NavText.distance(137))
    }

    @Test
    fun `around a fifth of a mile the units switch`() {
        // 305 m is 1000-odd feet. "1000 FT" is a number nobody says; the sign flips to miles.
        assertEquals("0.2 MI", NavText.distance(305))
        assertEquals("1.6 MI", NavText.distance(2500))
    }

    @Test
    fun `long distances drop the decimal`() {
        assertEquals("12 MI", NavText.distance(20000))
    }

    @Test
    fun `eta reads as minutes, then hours`() {
        assertEquals("12 MIN", NavText.eta(12))
        assertEquals("1 HR", NavText.eta(60))
        assertEquals("1 HR 5 MIN", NavText.eta(65))
        assertEquals("2 HR 5 MIN", NavText.eta(125))
    }

    @Test
    fun `a trip that exists takes at least a minute`() {
        // The provider contract says etaMinutes is never 0 while anything remains; this is the
        // same promise made from the reading side, so a misbehaving row cannot print "0 MIN".
        assertEquals("1 MIN", NavText.eta(0))
    }

    @Test
    fun `the secondary line, whole`() {
        assertEquals("450 FT · 12 MIN · 3/8", NavText.secondary(137, 12, 2, 8))
    }

    @Test
    fun `a row with no step count keeps its numbers`() {
        // Absent rather than broken, down to the last field: a provider that answered zero steps
        // loses the counter, not the row.
        assertEquals("450 FT · 12 MIN", NavText.secondary(137, 12, 0, 0))
    }
}
