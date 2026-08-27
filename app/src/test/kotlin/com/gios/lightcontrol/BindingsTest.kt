package com.gios.lightcontrol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The binding vocabulary, which is stored as strings and read back on every key press.
 *
 * The failure this guards is quiet and total: an action whose [Action.store] string has no
 * matching case in [Action.parse] reads back as null, [Prefs.action] falls through to the
 * default, and the button silently does something else. Nothing crashes and nothing is logged —
 * the setting simply does not stick. One round trip per action is the only way to notice.
 */
class BindingsTest {

    /** Every action there is, so a new one cannot be added without appearing here. */
    private val all = listOf(
        Action.PassThrough,
        Action.None,
        Action.Torch,
        Action.OpenCamera,
        Action.DefaultHome,
        Action.LightOsHome,
        Action.Resume,
        Action.Back,
        Action.Switcher,
        Action.OpenSettings,
        Action.Shade,
        Action.QuickSettings,
        Action.Screenshot,
        Action.LockNow,
        Action.PowerMenu,
        Action.ColorFlip,
        Action.SwitchTurn,
        Action.ShowLock,
        Action.Hotspot,
        Action.VolumeUp,
        Action.VolumeDown,
        Action.BrightnessUp,
        Action.BrightnessDown,
        Action.Launch("com.vandam.zero"),
    )

    @Test
    fun `every action survives being stored and read back`() {
        all.forEach { assertEquals(it, Action.parse(it.store())) }
    }

    @Test
    fun `no two actions store the same string`() {
        val stored = all.map { it.store() }
        assertEquals(stored.size, stored.toSet().size)
    }

    @Test
    fun `an unknown string is null rather than a wrong action`() {
        assertNull(Action.parse("nonsense"))
        assertNull(Action.parse(null))
    }

    /** Null is the caller's cue to substitute the app's own name; everything else answers. */
    @Test
    fun `every action but Launch has a word for the edge indicator`() {
        all.filter { it !is Action.Launch }.forEach { assertNotNull(it.edgeLabel) }
        assertNull(Action.Launch("com.vandam.zero").edgeLabel)
    }

    /**
     * The two double taps the phone shipped with, which used to be booleans. Nothing else has
     * one, and that is what keeps every other button's tap immediate.
     */
    @Test
    fun `the wheel and home keep their double taps and nothing else gains one`() {
        assertEquals(Action.SwitchTurn, Action.default(Button.WheelClick, Gesture.DoubleTap))
        assertEquals(Action.Switcher, Action.default(Button.Home, Gesture.DoubleTap))
        listOf(Button.Camera, Button.VolumeUp, Button.VolumeDown).forEach {
            assertTrue(!Action.default(it, Gesture.DoubleTap).acts)
        }
    }

    /** A double tap must not cost the volume keys their volume. */
    @Test
    fun `the volume keys are untouched on all three gestures`() {
        listOf(Button.VolumeUp, Button.VolumeDown).forEach { button ->
            Gesture.entries.forEach { gesture ->
                assertEquals(Action.PassThrough, Action.default(button, gesture))
            }
        }
    }

    /** Home's own defaults, which the whole of `ControlService.onHome` is written around. */
    @Test
    fun `home still taps to home and holds to LightOS`() {
        assertEquals(Action.DefaultHome, Action.default(Button.Home, Gesture.Tap))
        assertEquals(Action.LightOsHome, Action.default(Button.Home, Gesture.Hold))
    }

    /**
     * Starting an activity from a service is the one thing that fails in silence, so the set of
     * actions that do it is checked rather than assumed. Settings is the newcomer.
     */
    @Test
    fun `settings is known to start an activity`() {
        assertTrue(Action.OpenSettings.needsActivityStart)
        assertTrue(Action.OpenSettings.picksDestination)
        assertTrue(!Action.Shade.needsActivityStart)
        assertTrue(!Action.Screenshot.needsActivityStart)
    }
}
