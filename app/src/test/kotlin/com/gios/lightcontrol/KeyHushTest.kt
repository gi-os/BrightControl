package com.gios.lightcontrol

import com.gios.lightcontrol.keys.Hush
import com.gios.lightcontrol.keys.KeyHush
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Whether the phone still has working buttons, which is not a thing the phone can be asked.
 *
 * Every case here was a real state of a real phone at some point: a filter that stood down for an
 * alarm (right), for a text message (catastrophic, cost days), and for the whole of every call
 * (the bug this file was written for -- the buttons switched layers fine until someone answered
 * the phone, and then nothing did anything until thirty seconds after hanging up).
 */
class KeyHushTest {

    private fun hush(
        alarming: Boolean = false,
        ringing: Boolean = false,
        inCall: Boolean = false,
        alarmGrace: Boolean = false,
        ringGrace: Boolean = false,
    ) = KeyHush.of(alarming, ringing, inCall, alarmGrace, ringGrace)

    @Test
    fun `a quiet phone is nothing at all`() {
        assertEquals(Hush.None, hush())
    }

    @Test
    fun `an alarm takes every key`() {
        assertEquals(Hush.Ring, hush(alarming = true))
    }

    @Test
    fun `a ringing phone takes every key`() {
        assertEquals(Hush.Ring, hush(ringing = true))
    }

    @Test
    fun `a call in progress leaves the buttons working`() {
        assertEquals(Hush.Call, hush(inCall = true))
    }

    @Test
    fun `an alarm during a call is still an alarm`() {
        assertEquals(Hush.Ring, hush(alarming = true, inCall = true))
    }

    @Test
    fun `a silenced alarm keeps its keys for the grace window`() {
        assertEquals(Hush.Ring, hush(alarmGrace = true))
    }

    @Test
    fun `answering does not release an alarm's grace`() {
        // The one thing a call is not allowed to overrule. Nothing observable says an alarm's
        // STOP screen has been dealt with, so only the timer may end that window.
        assertEquals(Hush.Ring, hush(inCall = true, alarmGrace = true))
    }

    @Test
    fun `answering releases the ringtone's grace`() {
        // The whole point. The ringtone stopped because the call was picked up, so the screen the
        // grace protects is gone -- and without this the first thirty seconds of every call, which
        // is most calls, still has no buttons.
        assertEquals(Hush.Call, hush(inCall = true, ringGrace = true))
    }

    @Test
    fun `a ring that stopped and was not answered keeps its grace`() {
        assertEquals(Hush.Ring, hush(ringGrace = true))
    }

    @Test
    fun `only Ring stops the filter`() {
        // Stated as a rule rather than left implied: the caller refuses keys on Ring and on
        // nothing else, and a fourth state added later must decide this deliberately.
        assertEquals(Hush.Ring, hush(ringing = true))
        assertEquals(Hush.Call, hush(inCall = true))
        assertEquals(Hush.None, hush())
    }
}
