package com.gios.lightcontrol

import com.gios.lightcontrol.audio.SplitDecision
import com.gios.lightcontrol.audio.SplitDecision.Move
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two-level ringer, which is all edge case.
 *
 * The happy path is two tests: a ring goes loud, and the end of the call puts it back. Everything
 * else here is one of the three ways this could do harm — leaving a phone loud after the call that
 * raised it, writing a level into a phone somebody deliberately silenced, and learning a number
 * from a moment the person meant something else by it.
 */
class SplitDecisionTest {

    private fun state(
        mode: SplitMode = SplitMode.TwoLevels,
        ringing: Boolean = false,
        holdingBoost: Boolean = false,
        ringLevel: Int = 6,
        notifyLevel: Int = 2,
        current: Int = 2,
        max: Int = 7,
        ringerSilent: Boolean = false,
        wifiHolding: Boolean = false,
    ) = SplitDecision.State(
        mode = mode,
        ringing = ringing,
        holdingBoost = holdingBoost,
        ringLevel = ringLevel,
        notifyLevel = notifyLevel,
        current = current,
        max = max,
        ringerSilent = ringerSilent,
        wifiHolding = wifiHolding,
    )

    // ------------------------------------------------------------------ the happy path

    @Test
    fun `a ring goes to the ring level and takes the marker`() {
        val move = SplitDecision.decide(state(ringing = true))
        assertEquals(Move.Write(6, hold = true, why = "ringing · 2 → 6"), move)
    }

    @Test
    fun `the end of the call puts the level back`() {
        val move = SplitDecision.decide(state(holdingBoost = true, current = 6))
        assertTrue(move is Move.Write)
        move as Move.Write
        assertEquals(2, move.level)
        assertEquals(false, move.hold)
    }

    @Test
    fun `at rest and holding nothing, nothing is written`() {
        assertEquals(Move.Leave, SplitDecision.decide(state()))
    }

    // ------------------------------------------------------------------ not fighting a person

    @Test
    fun `the level is asserted once and not again mid-ring`() {
        // Turned down during the ring. The next look must not put it back up.
        val move = SplitDecision.decide(state(ringing = true, holdingBoost = true, current = 3))
        assertEquals(Move.Leave, move)
    }

    @Test
    fun `a level moved during the call is not undone at the end of it`() {
        val move = SplitDecision.decide(state(holdingBoost = true, current = 3))
        assertTrue("expected a release, got $move", move is Move.Release)
    }

    @Test
    fun `a silent ringer is never written into`() {
        assertEquals(Move.Leave, SplitDecision.decide(state(ringing = true, ringerSilent = true)))
    }

    @Test
    fun `a phone silenced mid-ring is released rather than restored`() {
        // The restore would write an index, and writing an index to a silent phone unmutes it.
        val move = SplitDecision.decide(
            state(holdingBoost = true, current = 6, ringerSilent = true),
        )
        assertTrue("expected a release, got $move", move is Move.Release)
    }

    @Test
    fun `a Wi-Fi silence outranks a call`() {
        assertEquals(Move.Leave, SplitDecision.decide(state(ringing = true, wifiHolding = true)))
    }

    @Test
    fun `a Wi-Fi claim arriving mid-ring unwinds the boost`() {
        val move = SplitDecision.decide(
            state(ringing = true, holdingBoost = true, current = 6, wifiHolding = true),
        )
        assertEquals(Move.Write(2, hold = false, why = "Wi-Fi holds this phone silent"), move)
    }

    // ------------------------------------------------------------------ standing down

    @Test
    fun `switching the feature off restores the level it raised`() {
        val move = SplitDecision.decide(
            state(mode = SplitMode.Off, holdingBoost = true, current = 6),
        )
        assertEquals(Move.Write(2, hold = false, why = "two levels off"), move)
    }

    @Test
    fun `the quiet mode does not swap levels`() {
        assertEquals(Move.Leave, SplitDecision.decide(state(mode = SplitMode.Quiet, ringing = true)))
    }

    @Test
    fun `two equal levels are a no-op`() {
        val move = SplitDecision.decide(state(ringing = true, ringLevel = 4, notifyLevel = 4))
        assertEquals(Move.Leave, move)
    }

    @Test
    fun `a ring already at the ring level still takes the marker`() {
        // Without the marker there is no restore, and the phone stays loud after the call.
        val move = SplitDecision.decide(state(ringing = true, current = 6))
        assertTrue("expected a hold, got $move", move is Move.Hold)
    }

    // ------------------------------------------------------------------ the floor

    @Test
    fun `a notification level of zero is written as one`() {
        val move = SplitDecision.decide(state(holdingBoost = true, current = 6, notifyLevel = 0))
        assertEquals(Move.Write(1, hold = false, why = "call over"), move)
    }

    @Test
    fun `a ring level above the maximum is clamped to it`() {
        val move = SplitDecision.decide(state(ringing = true, ringLevel = 99, max = 7))
        assertEquals(Move.Write(7, hold = true, why = "ringing · 2 → 7"), move)
    }

    // ------------------------------------------------------------------ learning

    @Test
    fun `a change while ringing sets the ring level`() {
        val learned = SplitDecision.learn(state(ringing = true, holdingBoost = true), 4)
        assertEquals(SplitDecision.Learned(ring = true, level = 4), learned)
    }

    @Test
    fun `a change at rest sets the notification level`() {
        assertEquals(SplitDecision.Learned(ring = false, level = 3), SplitDecision.learn(state(), 3))
    }

    @Test
    fun `a change during the restore still counts as the ring`() {
        // The marker is dropped a moment after the write, and a key pressed in that window is
        // still somebody reacting to how loud the ring was.
        val learned = SplitDecision.learn(state(ringing = false, holdingBoost = true), 5)
        assertEquals(SplitDecision.Learned(ring = true, level = 5), learned)
    }

    @Test
    fun `zero is not learned`() {
        assertNull(SplitDecision.learn(state(), 0))
    }

    @Test
    fun `a level already known is not relearned`() {
        assertNull(SplitDecision.learn(state(notifyLevel = 2), 2))
    }

    @Test
    fun `nothing is learned while the feature is off`() {
        assertNull(SplitDecision.learn(state(mode = SplitMode.Off), 5))
        assertNull(SplitDecision.learn(state(mode = SplitMode.Quiet), 5))
    }

    @Test
    fun `a level past the maximum is not learned`() {
        assertNull(SplitDecision.learn(state(max = 7), 9))
    }
}
