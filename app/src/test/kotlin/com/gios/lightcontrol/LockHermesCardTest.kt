package com.gios.lightcontrol

import com.gios.lightcontrol.lock.LockHermesCard
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LockHermesCardTest {
    @Test
    fun `a card is live until its clock and only if it says something`() {
        val nowMs = 1_000_000_000_000L
        val soon = nowMs / 1000.0 + 60
        assertTrue(LockHermesCard("Garage open", "since 6:40", soon, null).live(nowMs))
        assertTrue(LockHermesCard("", "just text", soon, null).live(nowMs))
        assertFalse(LockHermesCard("", "", soon, null).live(nowMs))
        assertFalse(LockHermesCard("Old", "news", nowMs / 1000.0 - 1, null).live(nowMs))
    }
}
