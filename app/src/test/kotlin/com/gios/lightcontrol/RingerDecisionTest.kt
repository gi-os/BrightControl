package com.gios.lightcontrol

import com.gios.lightcontrol.audio.RingerDecision
import com.gios.lightcontrol.audio.RingerDecision.Move
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ringer rules, which are all edge case.
 *
 * The happy path — walk into the office, phone goes quiet — is one test and was never in doubt.
 * What these are for is the two ways this feature could do real harm: leaving a phone muted after
 * you have left the network that muted it, and unmuting a phone that somebody muted on purpose.
 */
class RingerDecisionTest {

    private fun state(
        ssid: String? = null,
        rule: RingerRule? = null,
        alreadySilent: Boolean = false,
        silencedFor: String = "",
        overriddenFor: String = "",
        restore: Boolean = true,
    ) = RingerDecision.State(ssid, rule, alreadySilent, silencedFor, overriddenFor, restore)

    @Test
    fun `a silent network silences`() {
        val move = RingerDecision.decide(state(ssid = "Office", rule = RingerRule.Silent))
        assertEquals(Move.Silence("Office"), move)
    }

    @Test
    fun `an unmarked network is left alone`() {
        assertEquals(Move.Leave, RingerDecision.decide(state(ssid = "Cafe")))
    }

    @Test
    fun `no wifi and no claim is left alone`() {
        assertEquals(Move.Leave, RingerDecision.decide(state(ssid = null)))
    }

    /**
     * The whole reason the marker exists. A phone muted by hand before joining the network must not
     * be unmuted by this app afterwards — so the silence is claimed even when the ringer is already
     * down, and the claim is what licenses the later restore.
     */
    @Test
    fun `a silent network claims a silence it did not cause`() {
        val move = RingerDecision.decide(
            state(ssid = "Office", rule = RingerRule.Silent, alreadySilent = true),
        )
        assertEquals(Move.Silence("Office"), move)
    }

    @Test
    fun `already holding this network's silence writes nothing`() {
        val move = RingerDecision.decide(
            state(
                ssid = "Office",
                rule = RingerRule.Silent,
                alreadySilent = true,
                silencedFor = "Office",
            ),
        )
        assertEquals(Move.Leave, move)
    }

    @Test
    fun `leaving a silenced network rings again`() {
        val move = RingerDecision.decide(
            state(ssid = null, alreadySilent = true, silencedFor = "Office"),
        )
        assertTrue(move is Move.Ring)
    }

    @Test
    fun `leaving a silenced network for an unmarked one rings again`() {
        val move = RingerDecision.decide(
            state(ssid = "Cafe", alreadySilent = true, silencedFor = "Office"),
        )
        assertTrue(move is Move.Ring)
    }

    /** A phone this app never muted is not this app's to unmute. */
    @Test
    fun `a silence nobody claimed is never undone`() {
        val move = RingerDecision.decide(state(ssid = "Cafe", alreadySilent = true))
        assertEquals(Move.Leave, move)
    }

    @Test
    fun `restore off leaves the phone silent but drops the claim`() {
        val move = RingerDecision.decide(
            state(ssid = null, alreadySilent = true, silencedFor = "Office", restore = false),
        )
        assertTrue(move is Move.Forget)
    }

    /**
     * The claim is spent on the way out even when there is nothing to write, or the *next*
     * network's silence would be attributed to the last one.
     */
    @Test
    fun `leaving with the ringer already up drops the claim`() {
        val move = RingerDecision.decide(state(ssid = "Cafe", silencedFor = "Office"))
        assertTrue(move is Move.Forget)
    }

    @Test
    fun `turning the ringer up on the silenced network drops the claim`() {
        val move = RingerDecision.decide(
            state(ssid = "Office", rule = null, silencedFor = "Office"),
        )
        assertTrue(move is Move.Forget)
    }

    /** Having overridden the rule, standing on the same network must not re-silence. */
    @Test
    fun `an override on this network beats the rule`() {
        val move = RingerDecision.decide(
            state(ssid = "Office", rule = RingerRule.Silent, overriddenFor = "Office"),
        )
        assertEquals(Move.Leave, move)
    }

    @Test
    fun `an override on another network does not`() {
        val move = RingerDecision.decide(
            state(ssid = "Office", rule = RingerRule.Silent, overriddenFor = "Studio"),
        )
        assertEquals(Move.Silence("Office"), move)
    }

    /** An explicit ring rule is an instruction, so it outranks both the claim and an override. */
    @Test
    fun `a ring network rings even after an override`() {
        val move = RingerDecision.decide(
            state(
                ssid = "Flat",
                rule = RingerRule.Ring,
                alreadySilent = true,
                overriddenFor = "Flat",
            ),
        )
        assertTrue(move is Move.Ring)
    }

    @Test
    fun `a ring network with the ringer already up writes nothing`() {
        val move = RingerDecision.decide(state(ssid = "Flat", rule = RingerRule.Ring))
        assertTrue(move is Move.Forget)
    }
}
