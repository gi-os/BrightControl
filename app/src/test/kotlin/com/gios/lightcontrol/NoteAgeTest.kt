package com.gios.lightcontrol

import com.gios.lightcontrol.lock.NoteAge
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The age on a notification row, as the lock face writes it.
 *
 * The cases are written as elapsed time rather than as timestamps, because the rule that matters is
 * "the first hour is minutes" and not "1723800000000 becomes 5M".
 */
class NoteAgeTest {

    private val now = 1_700_000_000_000L

    private fun label(agoMs: Long) = NoteAge.label(now - agoMs, now)

    private fun seconds(n: Long) = n * 1_000L
    private fun minutes(n: Long) = n * 60_000L
    private fun hours(n: Long) = n * 3_600_000L

    @Test
    fun `the first minute is now`() {
        assertEquals("NOW", label(0L))
        assertEquals("NOW", label(seconds(1)))
        assertEquals("NOW", label(seconds(59)))
    }

    @Test
    fun `minutes for the first hour`() {
        assertEquals("1M", label(minutes(1)))
        assertEquals("5M", label(minutes(5) + seconds(30)))
        assertEquals("59M", label(minutes(59)))
    }

    @Test
    fun `hours to a day`() {
        assertEquals("1H", label(hours(1)))
        assertEquals("1H", label(hours(1) + minutes(59)))
        assertEquals("7H", label(hours(7)))
        assertEquals("23H", label(hours(23)))
    }

    @Test
    fun `days after that`() {
        assertEquals("1D", label(hours(24)))
        assertEquals("1D", label(hours(47)))
        assertEquals("2D", label(hours(48)))
        assertEquals("9D", label(hours(24 * 9)))
    }

    /** A time change or an NTP correction, not a notification from the future. */
    @Test
    fun `a clock that went backwards reads as now`() {
        assertEquals("NOW", NoteAge.label(now + minutes(5), now))
    }

    /** Nothing rather than 1970: a post time this broken is better left unsaid. */
    @Test
    fun `no post time says nothing`() {
        assertEquals("", NoteAge.label(0L, now))
        assertEquals("", NoteAge.label(-1L, now))
    }
}
