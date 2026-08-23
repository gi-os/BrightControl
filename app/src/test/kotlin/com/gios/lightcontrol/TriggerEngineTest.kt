package com.gios.lightcontrol

import com.gios.lightcontrol.hotspot.Action
import com.gios.lightcontrol.hotspot.Phase
import com.gios.lightcontrol.hotspot.Snapshot
import com.gios.lightcontrol.hotspot.Timings
import com.gios.lightcontrol.hotspot.TriggerEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The trigger machine is the whole product, so it is the thing worth pinning. Every rule
 * that decides when the hotspot lives or dies is checked here against a hand-driven clock,
 * with no Android in the way.
 */
class TriggerEngineTest {

    private val t = Timings(clientWaitMs = 1000, idleTeardownMs = 2000, backoffMs = 5000)

    private fun snap(
        nearby: Boolean = false, trusted: Boolean = false,
        ap: Boolean = false, clients: Int = 0,
    ) = Snapshot(nearby, trusted, ap, clients)

    @Test fun `idle does nothing without the trigger`() {
        val e = TriggerEngine(t)
        assertEquals(Action.NONE, e.evaluate(0, snap(nearby = false)))
        assertEquals(Phase.IDLE, e.phase)
    }

    @Test fun `trigger nearby off home wifi starts the ap`() {
        val e = TriggerEngine(t)
        assertEquals(Action.START_AP, e.evaluate(0, snap(nearby = true)))
        assertEquals(Phase.WAITING_CLIENT, e.phase)
    }

    @Test fun `home wifi suppresses the trigger`() {
        val e = TriggerEngine(t)
        assertEquals(Action.NONE, e.evaluate(0, snap(nearby = true, trusted = true)))
        assertEquals(Phase.IDLE, e.phase)
    }

    @Test fun `a client that joins moves to serving and no teardown`() {
        val e = TriggerEngine(t)
        e.evaluate(0, snap(nearby = true))                       // START_AP -> WAITING
        val a = e.evaluate(200, snap(nearby = true, ap = true, clients = 1))
        assertEquals(Action.NONE, a)
        assertEquals(Phase.SERVING, e.phase)
    }

    @Test fun `no client within the wait tears down and backs off`() {
        val e = TriggerEngine(t)
        e.evaluate(0, snap(nearby = true))                       // START_AP
        // still waiting before the deadline
        assertEquals(Action.NONE, e.evaluate(900, snap(nearby = true, ap = true, clients = 0)))
        // past the deadline: stop + backoff
        assertEquals(Action.STOP_AP, e.evaluate(1001, snap(nearby = true, ap = true, clients = 0)))
        assertEquals(Phase.IDLE, e.phase)
        assertTrue(e.inBackoff(1001))
    }

    @Test fun `backoff ignores the trigger until it lapses`() {
        val e = TriggerEngine(t)
        e.evaluate(0, snap(nearby = true))
        e.evaluate(1001, snap(nearby = true, ap = true))         // STOP_AP, backoffUntil = 6001
        assertEquals(Action.NONE, e.evaluate(3000, snap(nearby = true)))   // inside backoff
        assertEquals(Action.START_AP, e.evaluate(6001, snap(nearby = true))) // lapsed
    }

    @Test fun `serving survives a brief client drop but tears down after the drain`() {
        val e = TriggerEngine(t)
        e.evaluate(0, snap(nearby = true))
        e.evaluate(100, snap(nearby = true, ap = true, clients = 1))   // SERVING
        e.evaluate(200, snap(nearby = true, ap = true, clients = 0))   // -> DRAINING
        assertEquals(Phase.DRAINING, e.phase)
        // comes back before the drain elapses
        e.evaluate(500, snap(nearby = true, ap = true, clients = 1))
        assertEquals(Phase.SERVING, e.phase)
        // drops again and stays gone past the teardown
        e.evaluate(600, snap(nearby = true, ap = true, clients = 0))
        val a = e.evaluate(2601, snap(nearby = true, ap = true, clients = 0))
        assertEquals(Action.STOP_AP, a)
        assertFalse("ordinary end of use must not back off", e.inBackoff(2601))
    }

    @Test fun `ap dying underneath resets to idle`() {
        val e = TriggerEngine(t)
        e.evaluate(0, snap(nearby = true))
        e.evaluate(100, snap(nearby = true, ap = true, clients = 1))   // SERVING
        e.evaluate(200, snap(nearby = true, ap = false, clients = 0))  // AP gone
        assertEquals(Phase.IDLE, e.phase)
    }
}
