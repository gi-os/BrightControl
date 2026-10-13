package com.gios.lightcontrol

import com.gios.lightcontrol.adb.AdbFailure.Kind
import com.gios.lightcontrol.adb.PairConnect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the connect after a pairing tries, and what it says when every try fails. */
class PairConnectTest {

    @Test
    fun `the first try goes at once, and the waits stay short`() {
        assertEquals(0L, PairConnect.WAITS_MS.first())
        assertTrue(PairConnect.WAITS_MS.size >= 2)
        assertTrue(PairConnect.WAITS_MS.sum() <= 6_000L)
    }

    @Test
    fun `a refusal is the most telling failure`() {
        assertEquals(Kind.Refused, PairConnect.worst(listOf(Kind.NotFound, Kind.Closed, Kind.Refused)))
        assertEquals(Kind.NotFound, PairConnect.worst(listOf(Kind.Closed, Kind.NotFound)))
        assertEquals(Kind.Closed, PairConnect.worst(listOf(Kind.Other, Kind.Closed)))
        assertNull(PairConnect.worst(emptyList()))
        assertNull(PairConnect.worst(listOf(null)))
    }

    @Test
    fun `wireless debugging on is never blamed on the switch`() {
        // light-reports#700: the switch was on, and the message sent the user to a button that
        // only appears while it is off.
        for (kind in listOf(Kind.Refused, Kind.NotFound, Kind.Closed, Kind.Other, null)) {
            val said = PairConnect.failedMessage(wifiOn = true, kind = kind)
            assertFalse(said, said.contains("switched off"))
            assertFalse(said, said.contains("TURN WIRELESS DEBUGGING ON"))
            assertTrue(said, said.contains("GRANT ALL"))
        }
    }

    @Test
    fun `a refusal points at the full reset`() {
        val said = PairConnect.failedMessage(wifiOn = true, kind = Kind.Refused)
        assertTrue(said, said.contains("refused"))
        assertTrue(said, said.contains("START OVER AND PAIR"))
    }

    @Test
    fun `wireless debugging off points at the button that turns it on`() {
        val said = PairConnect.failedMessage(wifiOn = false, kind = Kind.NotFound)
        assertTrue(said, said.contains("TURN WIRELESS DEBUGGING ON"))
    }

    @Test
    fun `the report carries the state and the whole trail, without LAN addresses`() {
        val trail = listOf(
            "21:13:16  armed — waiting for the pairing box",
            "21:13:25  pairing accepted",
            "21:13:25  try 1: mDNS — IOException <- ConnectException: failed to connect to /192.168.4.7 (port 38217)",
        )
        val body = PairConnect.reportDetail(wifiOn = true, kind = Kind.Refused, trail = trail)
        assertTrue(body, body.contains("Wireless debugging after the last try: on"))
        assertTrue(body, body.contains("Most telling failure: Refused"))
        assertTrue(body, body.contains("21:13:16  armed — waiting for the pairing box"))
        assertTrue(body, body.contains("/<lan> (port 38217)"))
        assertFalse(body, body.contains("192.168"))
    }
}
