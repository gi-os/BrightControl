package com.gios.lightcontrol

import com.gios.lightcontrol.report.ShakeGesture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shake gesture, on the JVM.
 *
 * This is the half of shake-to-report that decides whether anything happens at all, and it is
 * the half that cannot be tested on the phone in any repeatable way — you cannot shake a device
 * the same way twice. Keeping it free of Android imports is what makes these possible, and is
 * the reason the class is shaped the way it is.
 *
 * Samples arrive at 50Hz on the real device, so the 20ms steps below are the real cadence.
 */
class ShakeGestureTest {

    /** One throw of the arm: several samples all on the same side of rest. */
    private fun ShakeGesture.throwOnce(from: Long, sign: Int, magnitude: Float = 0.7f): Boolean {
        var fired = false
        for (i in 0 until 3) {
            if (sample(from + i * 20L, 1f + sign * magnitude)) fired = true
        }
        return fired
    }

    @Test
    fun `a deliberate rattle fires once`() {
        val g = ShakeGesture()
        var fired = 0
        var at = 1_000L
        for (i in 0 until 4) {
            if (g.throwOnce(at, if (i % 2 == 0) 1 else -1)) fired++
            at += 60
        }
        assertEquals("four alternations is one report", 1, fired)
    }

    @Test
    fun `walking never fires`() {
        val g = ShakeGesture()
        // A brisk walk peaks around 0.3g, under the 0.46g floor, and bobs steadily.
        var at = 0L
        repeat(200) { i ->
            assertFalse(g.sample(at, 1f + (if (i % 10 < 5) 0.28f else -0.28f)))
            at += 20
        }
    }

    @Test
    fun `a hard single knock never fires`() {
        val g = ShakeGesture()
        // Setting the phone down hard clears the threshold easily, but only once, and does
        // not reverse. This is the case force alone cannot tell from a shake.
        assertFalse(g.throwOnce(0, 1, magnitude = 3.5f))
    }

    @Test
    fun `slow waves do not accumulate`() {
        val g = ShakeGesture()
        var fired = false
        // Same four reversals, but spread past the 500ms gap so each starts a new gesture.
        var at = 0L
        repeat(8) { i ->
            if (g.throwOnce(at, if (i % 2 == 0) 1 else -1)) fired = true
            at += 900
        }
        assertFalse("reversals more than 500ms apart are not a rattle", fired)
    }

    @Test
    fun `the cooldown stops one shake becoming three reports`() {
        val g = ShakeGesture()
        var fired = 0
        var at = 1_000L
        // Keep rattling well past the point of firing; the cooldown should swallow the rest.
        repeat(12) { i ->
            if (g.throwOnce(at, if (i % 2 == 0) 1 else -1)) fired++
            at += 60
        }
        assertEquals("one continuous rattle is one report", 1, fired)
    }

    @Test
    fun `reset abandons a half finished gesture`() {
        val g = ShakeGesture()
        g.throwOnce(1_000, 1)
        g.throwOnce(1_060, -1)
        assertTrue("two of four counted", g.turns in 1..3)
        g.reset()
        assertEquals(0, g.turns)
    }
}
