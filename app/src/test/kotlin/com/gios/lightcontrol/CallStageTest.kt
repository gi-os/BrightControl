package com.gios.lightcontrol

import com.gios.lightcontrol.lock.CallStage
import com.gios.lightcontrol.lock.LockCallState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which stage a call is at, from three sources that do not always agree.
 *
 * The first test is the bug this object was written for: a call from a withheld or unknown number
 * on a phone whose dialer posts no notification. Telephony says RINGING and nothing else says
 * anything -- the audio mode does not always move for a call that is not going to ring out loud --
 * and before this the card was never drawn at all.
 */
class CallStageTest {

    private fun stage(
        activeMode: Boolean = false,
        ringingMode: Boolean = false,
        telRinging: Boolean = false,
        telActive: Boolean = false,
        noteIncoming: Boolean? = null,
    ) = CallStage.of(activeMode, ringingMode, telRinging, telActive, noteIncoming)

    @Test
    fun `telephony alone is enough to say the phone is ringing`() {
        assertEquals(LockCallState.Stage.Ringing, stage(telRinging = true))
    }

    @Test
    fun `nothing anywhere is no call`() {
        assertNull(stage())
    }

    @Test
    fun `answered beats ringing, whichever source says which`() {
        // The mode moves a beat before the dialer rebuilds anything, and drawing ANSWER over a
        // call already in progress is worse than being a second late.
        assertEquals(LockCallState.Stage.Active, stage(activeMode = true, ringingMode = true))
        assertEquals(LockCallState.Stage.Active, stage(telActive = true, telRinging = true))
        assertEquals(
            LockCallState.Stage.Active,
            stage(activeMode = true, telRinging = true, noteIncoming = true),
        )
    }

    @Test
    fun `off-hook telephony is a call in progress`() {
        assertEquals(LockCallState.Stage.Active, stage(telActive = true))
    }

    @Test
    fun `the audio mode still answers where telephony is not granted`() {
        assertEquals(LockCallState.Stage.Ringing, stage(ringingMode = true))
    }

    @Test
    fun `a notification is the last source, not the first`() {
        assertEquals(LockCallState.Stage.Ringing, stage(noteIncoming = true))
        assertEquals(LockCallState.Stage.Active, stage(noteIncoming = false))
        // A notification that is not incoming does not turn a live ring into an answered call.
        assertEquals(
            LockCallState.Stage.Ringing,
            stage(telRinging = true, noteIncoming = false),
        )
    }

    @Test
    fun `a ring telephony has stopped asserting is over`() {
        // The expiry lives in LockCall; what matters here is that a false telRinging leaves
        // nothing behind. A stuck card is a lock screen that cannot be got rid of.
        assertNull(stage(telRinging = false))
    }
}
