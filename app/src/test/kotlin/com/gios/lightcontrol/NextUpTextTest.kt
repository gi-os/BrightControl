package com.gios.lightcontrol

import com.gios.lightcontrol.lock.NextUpText
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The quiet line under the clock.
 *
 * "Tomorrow" is a timezone question, and a timezone question answered against the machine's own
 * clock is a test that passes everywhere except on the phone. Everything here names its zone.
 */
class NextUpTextTest {

    private val zone = ZoneId.of("America/New_York")

    private fun at(month: Int, day: Int, hour: Int, minute: Int): Long =
        ZonedDateTime.of(2026, month, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()

    @Test
    fun `a timed entry today is just its time`() {
        val label = NextUpText.label(at(9, 1, 9, 30), false, at(9, 1, 7, 0), zone, "Dentist")
        assertEquals("NEXT UP · 9:30 DENTIST", label)
    }

    @Test
    fun `a timed entry tomorrow says so`() {
        // 9:30 alone, read on Tuesday night, means Wednesday morning to nobody.
        val label = NextUpText.label(at(9, 2, 9, 30), false, at(9, 1, 22, 0), zone, "Dentist")
        assertEquals("NEXT UP · TOMORROW 9:30 DENTIST", label)
    }

    @Test
    fun `the clock is twelve hour`() {
        val label = NextUpText.label(at(9, 1, 21, 5), false, at(9, 1, 7, 0), zone, "Call")
        assertEquals("NEXT UP · 9:05 CALL", label)
    }

    @Test
    fun `an all day entry names the day, not a midnight`() {
        val today = NextUpText.label(at(9, 1, 0, 0), true, at(9, 1, 7, 0), zone, "Labor Day")
        assertEquals("NEXT UP · TODAY LABOR DAY", today)
        val tomorrow = NextUpText.label(at(9, 2, 0, 0), true, at(9, 1, 7, 0), zone, "Movers")
        assertEquals("NEXT UP · TOMORROW MOVERS", tomorrow)
    }

    @Test
    fun `past the 48 hour contract the weekday is the honest fallback`() {
        // 2026-09-04 is a Friday. The provider should never answer this; the line must still
        // not call Friday "tomorrow" if it does.
        val label = NextUpText.label(at(9, 4, 10, 0), false, at(9, 1, 7, 0), zone, "Flight")
        assertEquals("NEXT UP · FRI 10:00 FLIGHT", label)
    }

    @Test
    fun `a title is one line however it was written`() {
        val label = NextUpText.label(at(9, 1, 9, 30), false, at(9, 1, 7, 0), zone, "Team\n  sync")
        assertEquals("NEXT UP · 9:30 TEAM SYNC", label)
    }
}
