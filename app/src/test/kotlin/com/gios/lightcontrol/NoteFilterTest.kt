package com.gios.lightcontrol

import com.gios.lightcontrol.lock.NoteFilter
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which notifications count as permanent.
 *
 * The flags are written out as their literal values rather than taken from
 * `android.app.Notification`, on purpose: these are the numbers on the wire, and a test that reads
 * the constant would pass for a filter that had been given the wrong constant. The one that had
 * been given the wrong constant is why this file exists -- `FLAG_NO_CLEAR` was missing, and
 * LightOS's own permanent notice therefore landed on the lock face and could not be swiped off.
 */
class NoteFilterTest {

    private companion object {
        const val ONGOING = 0x00000002
        const val NO_CLEAR = 0x00000020
        const val FOREGROUND_SERVICE = 0x00000040
        const val AUTO_CANCEL = 0x00000010
    }

    @Test
    fun `an ordinary message is not permanent`() {
        assertFalse(NoteFilter.isPersistent(AUTO_CANCEL, "msg"))
        assertFalse(NoteFilter.isPersistent(0, null))
    }

    @Test
    fun `an ongoing event is permanent`() {
        assertTrue(NoteFilter.isPersistent(ONGOING, null))
    }

    @Test
    fun `a foreground service is permanent`() {
        assertTrue(NoteFilter.isPersistent(FOREGROUND_SERVICE, null))
    }

    @Test
    fun `un-clearable is permanent, on its own`() {
        // The regression. NO_CLEAR says nothing about progress, so a notification carrying only
        // this flag passed both of the tests above -- which is exactly the shape of LightOS's
        // notice about itself.
        assertTrue(NoteFilter.isPersistent(NO_CLEAR, null))
        assertTrue(NoteFilter.isPersistent(NO_CLEAR or AUTO_CANCEL, "status"))
    }

    @Test
    fun `the service category is permanent even with no flags`() {
        assertTrue(NoteFilter.isPersistent(0, "service"))
    }

    @Test
    fun `a media notification is not decided here`() {
        // CATEGORY_TRANSPORT is dropped by the caller whatever this answers: what is playing has
        // its own row on the face, with controls. Stated as a test so the rule does not quietly
        // move in here later.
        assertFalse(NoteFilter.isPersistent(0, "transport"))
    }

    @Test
    fun `a reminder, an alarm and an event are time-critical`() {
        // The face's one exception to its importance gate: a well-formed BrightNotebook reminder
        // must not be droppable by an importance adjustment the user has no screen to undo.
        assertTrue(NoteFilter.isTimeCritical("reminder"))
        assertTrue(NoteFilter.isTimeCritical("alarm"))
        assertTrue(NoteFilter.isTimeCritical("event"))
    }

    @Test
    fun `nothing else is`() {
        // A demoted chat can wait in the shade. The exception is three categories, not a mood.
        assertFalse(NoteFilter.isTimeCritical("msg"))
        assertFalse(NoteFilter.isTimeCritical("transport"))
        assertFalse(NoteFilter.isTimeCritical(null))
    }

    @Test
    fun `time-critical does not soften persistence`() {
        // BrightWay's nav notification is CATEGORY_NAVIGATION and ongoing; a hypothetical ongoing
        // reminder would still be permanent. The importance exception runs after this test in the
        // caller, never instead of it.
        assertTrue(NoteFilter.isPersistent(ONGOING, "reminder"))
    }
}
