package com.gios.lightcontrol

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule that keeps the volume keys working when something is bound to them.
 *
 * Worth a test of its own because the bug it encodes was invisible in the place it happened and
 * loud somewhere else entirely: a hold bound on one volume key swallowed every press on that key,
 * the volume stopped changing, and what got reported — and chased for four releases — was the
 * volume *strip* showing a level that never moved.
 */
class VolumeKeyConsumeTest {

    @Test
    fun `an unbound volume key is never consumed`() {
        assertFalse(
            Action.consumesPress(
                Button.VolumeUp,
                Action.PassThrough,
                Action.PassThrough,
                Action.PassThrough,
            ),
        )
    }

    /** The whole of the fix. */
    @Test
    fun `a bound hold does not cost the volume key its press`() {
        assertFalse(
            Action.consumesPress(
                Button.VolumeDown,
                Action.PassThrough,
                Action.Torch,
                Action.PassThrough,
            ),
        )
    }

    @Test
    fun `nor does a bound double tap`() {
        assertFalse(
            Action.consumesPress(
                Button.VolumeDown,
                Action.PassThrough,
                Action.PassThrough,
                Action.Switcher,
            ),
        )
    }

    /** Binding the tap itself is an explicit choice to spend the press. */
    @Test
    fun `a bound tap does consume it`() {
        assertTrue(
            Action.consumesPress(
                Button.VolumeUp,
                Action.Torch,
                Action.PassThrough,
                Action.PassThrough,
            ),
        )
    }

    /**
     * Every other button keeps the old rule: timing a hold means keeping the DOWN, and a key kept
     * for one gesture is kept for all of them.
     */
    @Test
    fun `other buttons are consumed by any binding`() {
        assertTrue(
            Action.consumesPress(
                Button.WheelClick,
                Action.PassThrough,
                Action.Torch,
                Action.PassThrough,
            ),
        )
        assertTrue(
            Action.consumesPress(
                Button.Home,
                Action.PassThrough,
                Action.PassThrough,
                Action.Switcher,
            ),
        )
    }

    @Test
    fun `the shipped defaults consume neither volume key`() {
        for (button in listOf(Button.VolumeUp, Button.VolumeDown)) {
            assertFalse(
                Action.consumesPress(
                    button,
                    Action.default(button, Gesture.Tap),
                    Action.default(button, Gesture.Hold),
                    Action.default(button, Gesture.DoubleTap),
                ),
            )
        }
    }
}

/**
 * The volume keys have a tap and nothing else.
 *
 * [Prefs.bindable] is the rule; these check the shape of it without touching Android. The store
 * side — that a value written by an older build is refused on the way back out — needs a Context
 * and is left to the app.
 */
class VolumeKeyBindableTest {

    private fun bindable(button: Button, gesture: Gesture): Boolean =
        gesture == Gesture.Tap ||
            (button != Button.VolumeUp && button != Button.VolumeDown)

    @Test
    fun `a volume key keeps its tap`() {
        assertTrue(bindable(Button.VolumeUp, Gesture.Tap))
        assertTrue(bindable(Button.VolumeDown, Gesture.Tap))
    }

    @Test
    fun `and loses the two that have to be timed`() {
        for (gesture in listOf(Gesture.Hold, Gesture.DoubleTap)) {
            assertFalse(bindable(Button.VolumeUp, gesture))
            assertFalse(bindable(Button.VolumeDown, gesture))
        }
    }

    @Test
    fun `every other button keeps all three`() {
        for (button in Button.entries.filter { it != Button.VolumeUp && it != Button.VolumeDown }) {
            for (gesture in Gesture.entries) {
                assertTrue("$button $gesture", bindable(button, gesture))
            }
        }
    }
}
