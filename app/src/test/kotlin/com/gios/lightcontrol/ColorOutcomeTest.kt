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

    @Test
    fun `the line names the whole package`() {
        // The bare last segment is what light-reports#38 arrived with: three rules lost to
        // something called `edgegestures`, which is not a package id and cannot be looked up.
        assertEquals(
            "16:04:13 com.gios.lightchat COLOR want 0/-1 got 1/0 superseded",
            ColorOutcome.line("16:04:13", "com.gios.lightchat", "Color", 0 to -1, 1 to 0, 1 to 0),
        )
    }

    // ---- repaints: what the per-line outcome cannot see -----------------------

    private fun log(vararg lines: String) = lines.toList()

    @Test
    fun `a baseline write over another app's colour is a repaint`() {
        // The shape of light-reports#44, and every line in it says `ok`.
        assertEquals(
            1,
            ColorOutcome.repaints(
                log(
                    "11:54:12 com.lightos DEFAULT want 1/0 got 1/0 ok",
                    "11:54:11 com.gios.lightchat COLOR want 0/-1 got 0/-1 ok",
                ),
            ),
        )
    }

    @Test
    fun `a headline of held and overwritten hides it entirely`() {
        // Every one of these is `ok`, so the old title read "6 held, 0 overwritten" over a phone
        // that was being repainted grey three times.
        val lines = log(
            "12:00:23 com.lightos DEFAULT want 1/0 got 1/0 ok",
            "12:00:22 com.waze COLOR want 0/-1 got 0/-1 ok",
            "12:00:17 com.lightos DEFAULT want 1/0 got 1/0 ok",
            "12:00:15 com.gios.lightcamera COLOR want 0/-1 got 0/-1 ok",
            "12:00:10 com.lightos DEFAULT want 1/0 got 1/0 ok",
            "12:00:09 com.gios.lightchat COLOR want 0/-1 got 0/-1 ok",
        )
        assertEquals(6, lines.count { it.endsWith("ok") })
        assertEquals(3, ColorOutcome.repaints(lines))
    }

    @Test
    fun `a baseline long after the colour is not a repaint`() {
        // Ten seconds later is somebody putting the phone down, not the screen being taken back.
        assertEquals(
            0,
            ColorOutcome.repaints(
                log(
                    "11:54:21 com.lightos DEFAULT want 1/0 got 1/0 ok",
                    "11:54:11 com.gios.lightchat COLOR want 0/-1 got 0/-1 ok",
                ),
            ),
        )
    }

    @Test
    fun `an app restating its own baseline is not a repaint`() {
        assertEquals(
            0,
            ColorOutcome.repaints(
                log(
                    "11:54:12 com.gios.lightchat DEFAULT want 1/0 got 1/0 ok",
                    "11:54:11 com.gios.lightchat COLOR want 0/-1 got 0/-1 ok",
                ),
            ),
        )
    }

    @Test
    fun `a run of baselines over one colour write counts once`() {
        // One window state raising three baseline writes is one thing going wrong.
        assertEquals(
            1,
            ColorOutcome.repaints(
                log(
                    "11:54:13 com.lightos DEFAULT want 1/0 got 1/0 ok",
                    "11:54:12 com.lightos DEFAULT want 1/0 got 1/0 ok",
                    "11:54:12 com.lightos DEFAULT want 1/0 got 1/0 ok",
                    "11:54:11 com.gios.lightchat COLOR want 0/-1 got 0/-1 ok",
                ),
            ),
        )
    }

    @Test
    fun `mono counts as a repaint too`() {
        assertEquals(
            1,
            ColorOutcome.repaints(
                log(
                    "09:00:02 com.ss.edgegestures MONO want 1/0 got 1/0 ok",
                    "09:00:01 com.gios.lightcamera COLOR want 0/-1 got 0/-1 ok",
                ),
            ),
        )
    }

    @Test
    fun `a colour write with nothing after it is not a repaint`() {
        assertEquals(
            0,
            ColorOutcome.repaints(log("09:00:01 com.gios.lightcamera COLOR want 0/-1 got 0/-1 ok")),
        )
    }

    @Test
    fun `an unreadable log is not a crash`() {
        assertEquals(0, ColorOutcome.repaints(emptyList()))
        assertEquals(0, ColorOutcome.repaints(log("", "empty", "not a line at all")))
    }

    @Test
    fun `a log crossing midnight is left uncounted rather than guessed at`() {
        assertEquals(
            0,
            ColorOutcome.repaints(
                log(
                    "00:00:01 com.lightos DEFAULT want 1/0 got 1/0 ok",
                    "23:59:59 com.gios.lightchat COLOR want 0/-1 got 0/-1 ok",
                ),
            ),
        )
    }

    @Test
    fun `the line this reads is the line the app writes`() {
        // The parser and the formatter have to agree, and only one of them is on this screen.
        val written = ColorOutcome.line(
            "11:54:12", "com.lightos", "Default", 1 to 0, 1 to 0, 1 to 0,
        )
        val colour = ColorOutcome.line(
            "11:54:11", "com.gios.lightchat", "Color", 0 to -1, 0 to -1, 0 to -1,
        )
        assertEquals(1, ColorOutcome.repaints(listOf(written, colour)))
    }

    @Test
    fun `the outcome stays at the end of the line`() {
        // The Color screen's headline counts held, overwritten and superseded by the line's
        // ending, so anything appended after the outcome would silently zero those counts.
        val line =
            ColorOutcome.line("09:00:00", "com.gios.lightcamera", "Default", 1 to 0, 1 to 0, null)
        assertEquals(true, line.endsWith("ok"))
    }
}
