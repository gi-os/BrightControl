package com.gios.lightcontrol

import com.gios.lightcontrol.keys.stripBounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where an edge strip's window starts and how tall it is.
 *
 * Small arithmetic, tested because getting it wrong is silent. A strip of the wrong height is not
 * an error and draws nothing either way — the only symptom is an edge gesture that stopped
 * happening, on a phone where the left edge is the only way back.
 */
class EdgeSwipeBoundsTest {

    /** `ViewGroup.LayoutParams.MATCH_PARENT`, which is a compile-time constant. */
    private val matchParent = -1

    @Test
    fun `no dead zone leaves the strip the full height`() {
        val b = stripBounds(screenPx = 1920, topDeadPx = 0)
        assertEquals(0, b.top)
        assertEquals("MATCH_PARENT, not the number that happens to equal it today", matchParent, b.height)
    }

    @Test
    fun `a dead zone moves the strip down and takes the same off its height`() {
        val b = stripBounds(screenPx = 1920, topDeadPx = 240)
        assertEquals(240, b.top)
        // The two have to add up, or the strip stops short of the bottom of the screen -- which
        // reads as the gesture working everywhere except where your thumb rests.
        assertEquals(1920, b.top + b.height)
    }

    @Test
    fun `a dead zone taller than the screen is cut to half of it`() {
        // The setting is capped in dp, and dp cannot know how tall this panel is. Uncapped, this
        // would be a window of negative height: WRAP_CONTENT on a view that draws nothing, which
        // is an edge gesture that silently stopped existing.
        val b = stripBounds(screenPx = 1920, topDeadPx = 4000)
        assertEquals(960, b.top)
        assertTrue("still a window with room in it", b.height > 0)
        assertEquals(1920, b.top + b.height)
    }

    @Test
    fun `a negative dead zone is no dead zone`() {
        val b = stripBounds(screenPx = 1920, topDeadPx = -50)
        assertEquals(0, b.top)
        assertEquals(matchParent, b.height)
    }

    @Test
    fun `a screen with no height does not produce a window with none either`() {
        // Belt and braces: displayMetrics has been known to answer 0 before the first layout, and
        // a strip built from that answer must not be one nothing can ever touch.
        val b = stripBounds(screenPx = 0, topDeadPx = 240)
        assertEquals(0, b.top)
        assertEquals(matchParent, b.height)
    }

    @Test
    fun `a keyboard takes the bottom off the strip`() {
        // A 1920 px screen with an 800 px keyboard up: the strip stops where the keys begin, so
        // the first touch on Q is a Q and not the start of a back gesture.
        val b = stripBounds(screenPx = 1920, topDeadPx = 0, bottomDeadPx = 800)
        assertEquals(0, b.top)
        assertEquals(1120, b.height)
    }

    @Test
    fun `the corner and the keyboard both come off`() {
        val b = stripBounds(screenPx = 1920, topDeadPx = 240, bottomDeadPx = 800)
        assertEquals(240, b.top)
        assertEquals(1920 - 240 - 800, b.height)
    }

    @Test
    fun `a keyboard that claims the whole screen still leaves a quarter to swipe in`() {
        val b = stripBounds(screenPx = 1920, topDeadPx = 0, bottomDeadPx = 5000)
        assertEquals(480, b.height)
    }

    @Test
    fun `a keyboard going down restores the full strip`() {
        val b = stripBounds(screenPx = 1920, topDeadPx = 0, bottomDeadPx = 0)
        assertEquals(matchParent, b.height)
    }
}
