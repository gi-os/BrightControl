package com.gios.lightcontrol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which apps the built-in table refuses an edge strip.
 *
 * Deliberately separate from the wheel's table, and this is the test that says why: an app can own
 * the whole wheel and still want a way back. Roll and BrightRecorder both resolve to
 * [AppRule.Off] for the wheel, and both get the strip.
 */
class EdgeSwipeTableTest {

    @Test
    fun `Light's own software is refused`() {
        assertTrue(Policy.edgeSwipeRefusedByTable("com.lightos"))
        assertTrue(Policy.edgeSwipeRefusedByTable("com.lightos.launcher"))
        assertTrue(Policy.edgeSwipeRefusedByTable("app.lightphonekeyboard"))
        assertTrue(Policy.edgeSwipeRefusedByTable("com.android.systemui"))
    }

    @Test
    fun `an SDK tool is refused, because the SDK draws it a back button`() {
        // `com.thelightphone.` is the light-sdk namespace, not a list of Light's apps. A tool
        // there navigates with `navigateTo` rather than the Android back stack, so a strip would
        // cost an edge and GLOBAL_ACTION_BACK would do nothing with it.
        assertTrue(Policy.edgeSwipeRefusedByTable("com.thelightphone.sdk"))
        assertTrue(Policy.edgeSwipeRefusedByTable("com.thelightphone.lightnycsubway"))
    }

    @Test
    fun `a sideloaded app gets the strip`() {
        assertFalse(Policy.edgeSwipeRefusedByTable("app.luma"))
        assertFalse(Policy.edgeSwipeRefusedByTable("com.gios.lightnotebook"))
        assertFalse(Policy.edgeSwipeRefusedByTable("com.lightfastread"))
    }

    @Test
    fun `a Light-looking id is not Light's software`() {
        // The bug this test exists for. These four are ordinary sideloaded APKs that happen to
        // sit under `com.lightphone.`, a prefix no software of Light's uses -- Light's tools are
        // all inside `com.lightos`. Borrowing the wheel's hands-off list refused every one of
        // them a back gesture and said nothing about it.
        assertFalse(Policy.edgeSwipeRefusedByTable("com.lightphone.spotify"))
        assertFalse(Policy.edgeSwipeRefusedByTable("com.lightphone.audiobooks"))
        assertFalse(Policy.edgeSwipeRefusedByTable("com.lightphone.chats"))
        assertFalse(Policy.edgeSwipeRefusedByTable("com.lightphone.passes"))
    }

    @Test
    fun `the wheel still keeps its hands off a Light-looking id`() {
        // The two tables are separate now, so this is the half that must not have moved: the
        // wheel's answer for those packages is unchanged.
        assertEquals(AppRule.ScrollThrough, Policy.builtInRuleFor("com.lightphone.spotify"))
        assertEquals(AppRule.Off, Policy.builtInRuleFor("com.lightphone.audiobooks"))
        assertEquals(AppRule.Off, Policy.builtInRuleFor("com.thelightphone.radio"))
    }

    @Test
    fun `owning the whole wheel says nothing about the left edge`() {
        // Both of these are AppRule.Off for the wheel. Reusing that answer here would have taken
        // the back gesture away from a camera and a tape recorder, neither of which has one.
        assertFalse(Policy.edgeSwipeRefusedByTable("com.gios.lightcamera"))
        assertFalse(Policy.edgeSwipeRefusedByTable("com.gios.brightrecorder"))
    }

    @Test
    fun `this app gets its own strip`() {
        // Its settings have a back arrow, so this is the one place the gesture can be tried
        // without leaving the screen that switched it on.
        assertFalse(Policy.edgeSwipeRefusedByTable("com.gios.lightcontrol"))
    }
}
