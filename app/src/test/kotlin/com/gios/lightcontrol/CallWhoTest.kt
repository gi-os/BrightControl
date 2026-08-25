package com.gios.lightcontrol

import com.gios.lightcontrol.lock.CallWho
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Who the lock card says is calling.
 *
 * The card drew "Incoming call" over every call this phone ever took, because it read
 * `EXTRA_TITLE` and a CallStyle notification does not put the caller there. These are the
 * candidate lists that came out of a real `dumpsys notification` on the LPIII, in the order the
 * listener offers them.
 */
class CallWhoTest {

    @Test
    fun `the person wins, which is where CallStyle puts the caller`() {
        val candidates = listOf("Alex", "", null, "tel:+15551234567")
        assertEquals("Alex", CallWho.pick(candidates))
    }

    @Test
    fun `an empty title falls through to the number`() {
        val candidates = listOf(null, "", "  ", CallWho.fromUri("tel:+15551234567"))
        assertEquals("+15551234567", CallWho.pick(candidates))
    }

    @Test
    fun `a placeholder loses to anything real behind it`() {
        assertEquals("+15551234567", CallWho.pick(listOf("Unknown", "+15551234567")))
        assertEquals("Alex", CallWho.pick(listOf("Incoming call", "Alex")))
    }

    @Test
    fun `an anonymous call still says what the dialer said`() {
        // Nothing real anywhere in the list, so the first placeholder is the answer. "Private
        // number" is a fact about the call; an empty line is a bug in this app.
        assertEquals("Private number", CallWho.pick(listOf("Private number", "unknown")))
    }

    @Test
    fun `nothing at all is empty, not a phrase invented here`() {
        // The caller of this decides what to draw for a call that described itself in no way at
        // all -- LockCall says "Incoming call" -- and it does that by seeing an empty string.
        assertEquals("", CallWho.pick(listOf(null, "", "   ")))
        assertEquals("", CallWho.pick(emptyList()))
    }

    @Test
    fun `the usual placeholders`() {
        listOf("Unknown", "unknown caller", "No caller ID", "Private", "Calling", "-", "", null)
            .forEach { assertTrue(it.orEmpty(), CallWho.isPlaceholder(it)) }
    }

    @Test
    fun `a name is not a placeholder`() {
        listOf("Alex", "Mum", "+15551234567", "Unknown Pizza Co")
            .forEach { assertFalse(it, CallWho.isPlaceholder(it)) }
    }

    @Test
    fun `tel and sip uris give a number`() {
        assertEquals("+15551234567", CallWho.fromUri("tel:+15551234567"))
        assertEquals("+15551234567", CallWho.fromUri("tel:%2B15551234567"))
        assertEquals("alex", CallWho.fromUri("sip:alex@example.com"))
    }

    @Test
    fun `the plus survives`() {
        // URLDecoder is a form decoder and reads a literal plus as a space, which quietly turned
        // +1 555... into 1 555... -- a number you cannot call back. Both spellings land in the
        // same place.
        assertEquals("+15551234567", CallWho.fromUri("tel:+1 555 123 4567".replace(" ", "")))
        assertEquals("+441234567890", CallWho.fromUri("tel:%2B441234567890"))
        assertEquals("+441234567890", CallWho.fromUri("tel:+441234567890"))
    }

    @Test
    fun `a contacts uri is a row id and is dropped`() {
        // An id on a lock screen is worse than nothing: it is not a caller, and it looks like one.
        assertNull(CallWho.fromUri("content://com.android.contacts/contacts/lookup/2814i7b"))
        assertNull(CallWho.fromUri("name:Alex"))
        assertNull(CallWho.fromUri(null))
        assertNull(CallWho.fromUri("  "))
        assertNull(CallWho.fromUri("tel:"))
    }
}
