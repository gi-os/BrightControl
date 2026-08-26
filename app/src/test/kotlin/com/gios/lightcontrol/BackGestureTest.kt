package com.gios.lightcontrol

import com.gios.lightcontrol.keys.BackGesture
import com.gios.lightcontrol.keys.BackStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The back gesture, on the JVM.
 *
 * This is the whole of the feature's decision-making, and the only other place it could be
 * exercised is a thumb on a real phone -- where a stroke cannot be repeated twice the same way.
 * Keeping the class free of Android types is what makes these possible.
 *
 * Pixels here are pixels: 48 trigger and 34 slop are the defaults at 1x density.
 */
class BackGestureTest {

    private fun gesture() = BackGesture(triggerPx = 48f, slopPx = 34f)

    @Test
    fun `a short drag does not go back`() {
        val g = gesture()
        g.down(4f, 300f)
        g.move(20f, 302f)
        assertEquals(BackStage.Watching, g.stage)
        assertFalse("a stroke that never reached the trigger", g.up())
    }

    @Test
    fun `a drag past the trigger goes back on the lift`() {
        val g = gesture()
        g.down(4f, 300f)
        g.move(30f, 300f)
        assertEquals(BackStage.Watching, g.stage)
        g.move(60f, 304f)
        assertEquals(BackStage.Armed, g.stage)
        assertTrue(g.up())
    }

    @Test
    fun `crossing the trigger is not enough on its own`() {
        val g = gesture()
        g.down(4f, 300f)
        g.move(60f, 300f)
        assertEquals(BackStage.Armed, g.stage)
        // The whole reason the lift decides: coming back under the trigger is how somebody
        // changes their mind about a gesture already begun.
        g.move(20f, 300f)
        assertEquals(BackStage.Watching, g.stage)
        assertFalse("dragged back under the trigger", g.up())
    }

    @Test
    fun `a vertical stroke is cancelled and cannot be revived`() {
        val g = gesture()
        g.down(4f, 300f)
        g.move(8f, 260f)
        assertEquals(BackStage.Cancelled, g.stage)
        // Sideways afterwards must not rescue it. A long flick that drifts across at the end is
        // exactly the stroke this rule exists for.
        g.move(200f, 260f)
        assertEquals(BackStage.Cancelled, g.stage)
        assertFalse(g.up())
    }

    @Test
    fun `mostly across beats a little bit down`() {
        val g = gesture()
        g.down(4f, 300f)
        // Past the slop vertically, but further across than down, so it is still a back gesture.
        g.move(90f, 340f)
        assertEquals(BackStage.Armed, g.stage)
        assertTrue(g.up())
    }

    @Test
    fun `travel is progress toward the trigger, clamped`() {
        val g = gesture()
        g.down(0f, 100f)
        g.move(24f, 100f)
        assertEquals(0.5f, g.travel, 0.01f)
        g.move(96f, 100f)
        assertEquals("never past 1, so the indicator cannot overrun its box", 1f, g.travel, 0.01f)
    }

    @Test
    fun `the anchor is where the finger went down, not where it is now`() {
        val g = gesture()
        g.down(4f, 512f)
        g.move(70f, 480f)
        assertEquals("the indicator must not chase the thumb up the screen", 512f, g.anchorY, 0f)
    }

    @Test
    fun `a lift resets, so the next stroke starts clean`() {
        val g = gesture()
        g.down(4f, 300f)
        g.move(80f, 300f)
        assertTrue(g.up())
        assertEquals(BackStage.Idle, g.stage)
        assertEquals(0f, g.travel, 0f)
        // And a move with no finger down decides nothing.
        assertFalse(g.move(400f, 300f))
        assertFalse(g.up())
    }

    @Test
    fun `a leftward drag is nothing`() {
        val g = gesture()
        g.down(30f, 300f)
        g.move(0f, 300f)
        assertEquals(BackStage.Watching, g.stage)
        assertEquals("negative travel is clamped away", 0f, g.travel, 0f)
        assertFalse(g.up())
    }
}
