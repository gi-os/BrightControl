package com.gios.lightcontrol

import com.gios.lightcontrol.portal.PortalLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Wi-Fi login screen's own account of itself. Two things matter: the timestamps are relative
 * to the screen opening (a report reads "the page never came in 25s", not wall-clock), and when
 * the log overflows it is the *beginning* that goes — the end is where the failure is.
 */
class PortalLogTest {

    @Test
    fun linesAreStampedRelativeToOpening() {
        var t = 1_000L
        val log = PortalLog(now = { t })
        log.add("opened")
        t += 1_234
        log.add("page started")
        assertEquals("  0.000  opened\n  1.234  page started", log.dump())
    }

    @Test
    fun newlinesInsideALineAreFlattened() {
        val log = PortalLog(now = { 0L })
        log.add("a\nb")
        assertEquals("  0.000  a b", log.dump())
    }

    @Test
    fun overflowDropsTheOldestAndSaysSo() {
        val log = PortalLog(now = { 0L }, maxChars = 60)
        repeat(10) { log.add("line $it") }
        val out = log.dump()
        assertTrue(out.startsWith("… "))
        assertTrue(out.contains("earlier lines dropped"))
        assertTrue(out.endsWith("line 9"))
        assertTrue(!out.contains("line 0\n"))
        assertTrue(log.size() < 10)
    }

    @Test
    fun observerSeesEachLine() {
        val log = PortalLog(now = { 0L })
        val seen = mutableListOf<String>()
        log.onLine = { seen += it }
        log.add("x")
        log.add("y")
        assertEquals(2, seen.size)
        assertEquals(log.last(), seen.last())
    }

    @Test
    fun sendLogBookkeepingDoesNotCountAsSignal() {
        val log = PortalLog(now = { 0L })
        log.add("findWifi: 0 wifi network(s)")
        log.add("FAIL: could not find a Wi-Fi network to sign in to")
        val before = log.signal()
        log.add("SEND LOG tapped")
        log.add("report posted or queued")
        log.add("report queued (no token in this build)")
        log.add("SEND LOG: nothing new since the last one, not sent again")
        assertEquals(before, log.signal())
        log.add("page finished")
        assertEquals(before + 1, log.signal())
    }

    @Test
    fun signalKeepsCountingPastOverflow() {
        val log = PortalLog(now = { 0L }, maxChars = 60)
        repeat(10) { log.add("line $it") }
        assertEquals(10, log.signal())
    }
}
