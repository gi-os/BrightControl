package com.gios.lightcontrol

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which apps the built-in table refuses the back strip.
 *
 * Deliberately separate from the wheel's table, and this is the test that says why: an app can own
 * the whole wheel and still want a way back. Roll and BrightRecorder both resolve to
 * [AppRule.Off] for the wheel, and both get the strip.
 */
class BackSwipeTableTest {

    @Test
    fun `Light's own software is refused`() {
        assertTrue(Policy.backSwipeRefusedByTable("com.lightos"))
        assertTrue(Policy.backSwipeRefusedByTable("com.lightos.launcher"))
        assertTrue(Policy.backSwipeRefusedByTable("com.thelightphone.notes"))
        assertTrue(Policy.backSwipeRefusedByTable("app.lightphonekeyboard"))
        assertTrue(Policy.backSwipeRefusedByTable("com.android.systemui"))
    }

    @Test
    fun `a sideloaded app gets the strip`() {
        assertFalse(Policy.backSwipeRefusedByTable("app.luma"))
        assertFalse(Policy.backSwipeRefusedByTable("com.gios.lightnotebook"))
        assertFalse(Policy.backSwipeRefusedByTable("com.lightfastread"))
    }

    @Test
    fun `owning the whole wheel says nothing about the left edge`() {
        // Both of these are AppRule.Off for the wheel. Reusing that answer here would have taken
        // the back gesture away from a camera and a tape recorder, neither of which has one.
        assertFalse(Policy.backSwipeRefusedByTable("com.gios.lightcamera"))
        assertFalse(Policy.backSwipeRefusedByTable("com.gios.brightrecorder"))
    }

    @Test
    fun `this app gets its own strip`() {
        // Its settings have a back arrow, so this is the one place the gesture can be tried
        // without leaving the screen that switched it on.
        assertFalse(Policy.backSwipeRefusedByTable("com.gios.lightcontrol"))
    }
}
