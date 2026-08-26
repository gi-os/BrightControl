package com.gios.lightcontrol

import com.gios.lightcontrol.lock.CallerText
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The two lines of the call card, across every combination of sources this phone produces.
 *
 * The card has been blank twice, and neither time was the bug in how a name was read -- it was in
 * what got drawn when a source was empty. The first case below is the one that shipped broken:
 * LightOS's dialer posts no notification at all, so everything on the left is null and the answer
 * has to come from telephony.
 */
class CallerTextTest {

    private fun lines(
        noteWho: String? = null,
        noteText: String? = null,
        name: String? = null,
        number: String? = null,
        ringing: Boolean = true,
    ) = CallerText.of(noteWho, noteText, name, number, ringing)

    @Test
    fun `no notification, a contact name and a number`() {
        val l = lines(name = "Alex", number = "(555) 123-4567")
        assertEquals("Alex", l.who)
        assertEquals("(555) 123-4567", l.sub)
    }

    @Test
    fun `no notification and nobody in contacts`() {
        val l = lines(number = "(555) 123-4567")
        assertEquals("(555) 123-4567", l.who)
        // Not the number twice. One line saying it is the whole point of the line.
        assertEquals("", l.sub)
    }

    @Test
    fun `the dialer's own name wins over contacts`() {
        // A dialer that wrote a name has often done something this app cannot -- a business
        // lookup, a spam label, the name on the SIM.
        val l = lines(noteWho = "Pizza Place", name = "Alex", number = "(555) 123-4567")
        assertEquals("Pizza Place", l.who)
        assertEquals("(555) 123-4567", l.sub)
    }

    @Test
    fun `a placeholder title loses to the contact`() {
        val l = lines(noteWho = "Unknown", name = "Alex")
        assertEquals("Alex", l.who)
    }

    @Test
    fun `the notification's own second line is kept when it says something`() {
        val l = lines(noteWho = "Alex", noteText = "Mobile", number = "(555) 123-4567")
        assertEquals("Alex", l.who)
        assertEquals("Mobile", l.sub)
    }

    @Test
    fun `a second line that repeats the card's heading is dropped`() {
        val l = lines(noteWho = "Alex", noteText = "Incoming call", number = "(555) 123-4567")
        assertEquals("Alex", l.who)
        assertEquals("(555) 123-4567", l.sub)
    }

    @Test
    fun `nothing anywhere still reads as a phone ringing`() {
        assertEquals("Incoming call", lines().who)
        assertEquals("", lines().sub)
        assertEquals("On a call", lines(ringing = false).who)
    }

    @Test
    fun `an anonymous call says so`() {
        val l = lines(noteWho = "Private number")
        assertEquals("Private number", l.who)
        assertEquals("", l.sub)
    }
}
