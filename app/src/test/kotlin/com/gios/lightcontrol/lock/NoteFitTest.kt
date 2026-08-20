package com.gios.lightcontrol.lock

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The two sums the notification column is made of.
 *
 * Written because the bug being fixed here was not a crash and not a wrong pixel — it was the
 * third notification on the lock screen having its bottom half missing, every single time, which
 * is the kind of thing that reads as "this app is unfinished" and cannot be seen from a unit test
 * unless the arithmetic is out where a test can reach it.
 *
 * The heights below are notification-shaped on purpose: 60 is app name + headline, 90 is one with
 * a body under it, because the mix is exactly what makes "how many fit" impossible to answer with
 * a constant.
 */
class NoteFitTest {

    @Test
    fun `stops at the last whole notification instead of cutting one in half`() {
        // 200px of room and three rows that come to 240. The old column drew all three and the
        // third one lost its bottom 40px; this reports the height that ends where the second one
        // does, and the third is reached by dragging.
        assertEquals(150, NoteFit.visibleHeight(listOf(60, 90, 90), 200))
    }

    @Test
    fun `a row that ends exactly on the edge counts as fitting`() {
        // The off-by-one that would drop a notification the screen had room for.
        assertEquals(150, NoteFit.visibleHeight(listOf(60, 90, 90), 150))
    }

    @Test
    fun `everything fitting is left alone`() {
        assertEquals(150, NoteFit.visibleHeight(listOf(60, 90), 400))
    }

    @Test
    fun `nothing whole fitting gives back nothing, and the caller shows what it can`() {
        // One long notification in a short gap. Zero is the signal to leave the clamped height as
        // it is: a clipped notification beats a blank space where notifications go.
        assertEquals(0, NoteFit.visibleHeight(listOf(90), 60))
        assertEquals(0, NoteFit.visibleHeight(emptyList(), 200))
    }

    @Test
    fun `a released drag settles on the nearest notification boundary`() {
        val tops = listOf(0, 60, 150, 240)
        assertEquals(60, NoteFit.snapTarget(tops, maxScroll = 240, scrollY = 70))
        assertEquals(150, NoteFit.snapTarget(tops, maxScroll = 240, scrollY = 130))
        assertEquals(0, NoteFit.snapTarget(tops, maxScroll = 240, scrollY = 20))
    }

    @Test
    fun `the end of the list is a boundary too, so the last one sits flush`() {
        // The bottom is 200, which is no row's top. Without it as a candidate the column would
        // rest at 150 and the last notification would stay half off the screen — the original bug
        // moved to the other end.
        assertEquals(200, NoteFit.snapTarget(listOf(0, 60, 150), maxScroll = 200, scrollY = 190))
    }

    @Test
    fun `a boundary past the end is not somewhere it can go`() {
        // Row tops below maxScroll are the only reachable ones; 150 is past 100 and must not be
        // chosen, or the column would try to scroll further than it has content for.
        assertEquals(100, NoteFit.snapTarget(listOf(0, 60, 150), maxScroll = 100, scrollY = 140))
    }

    @Test
    fun `a column with nothing to scroll stays where it is`() {
        assertEquals(0, NoteFit.snapTarget(listOf(0, 60), maxScroll = 0, scrollY = 0))
    }
}
