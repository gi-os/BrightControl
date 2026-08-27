package com.gios.lightcontrol.audio

import com.gios.lightcontrol.RingerRule

/**
 * What joining a network should do to the ringer. No Android in it, so it can be tested.
 *
 * The whole feature is this function plus the plumbing that feeds it, and it is separate for the
 * reason [com.gios.lightcontrol.hotspot.TriggerEngine] is: the interesting part of a rule engine is
 * the cases nobody hits until the phone is in a pocket — you leave a network while it is silenced,
 * you arrive on one with no rule, you turn the ringer back up yourself and the office Wi-Fi
 * flickers. Those are answered here, in a `when`, with a test beside it.
 *
 * Two things are load-bearing and neither is the rule:
 *
 *  - **[State.silencedFor]** is the only warrant this app has to turn a ringer *up*. A phone
 *    somebody muted by hand is not this app's to unmute, so nothing is restored unless this app is
 *    the one that muted it and the network it muted for is behind us.
 *  - **[State.overriddenFor]** is the user winning. Turn the ringer up while standing on a network
 *    set to silent and the rule stops applying for as long as you stay there — because the
 *    alternative is a phone that re-mutes itself every time the access point blinks, which reads as
 *    a broken ringer and not as a setting.
 */
object RingerDecision {

    /**
     * Everything the decision is made from.
     *
     * @param ssid the network the phone is on, or null when it is on no Wi-Fi at all.
     * @param rule what that network is set to, if anything.
     * @param alreadySilent whether the ringer is already down.
     * @param silencedFor the network this app last silenced for, or blank.
     * @param overriddenFor the network the user overrode the rule on, or blank.
     * @param restore whether leaving a silenced network puts the ringer back.
     */
    data class State(
        val ssid: String?,
        val rule: RingerRule?,
        val alreadySilent: Boolean,
        val silencedFor: String,
        val overriddenFor: String,
        val restore: Boolean,
    )

    /** What to do about the ringer. [Leave] is the answer far more often than not. */
    sealed interface Move {
        /** Touch nothing, and change nothing about what is remembered. */
        data object Leave : Move

        /** Go silent, and remember that this app is the one that did it. */
        data class Silence(val ssid: String) : Move

        /** Ring, and forget any silence this app was holding. */
        data class Ring(val why: String) : Move

        /**
         * Write nothing; forget the silence. Reached when the ringer is up and this app still
         * thinks it holds it down — somebody turned it up. The claim goes, the ringer stays.
         */
        data class Forget(val why: String) : Move
    }

    fun decide(state: State): Move {
        val ssid = state.ssid
        val stale = state.silencedFor.isNotBlank() && state.silencedFor != ssid

        return when {
            // The user turned the ringer up on a network set to silent, and is still on it. Their
            // phone, their call.
            state.rule == RingerRule.Silent && ssid != null && state.overriddenFor == ssid ->
                Move.Leave

            // On a network that wants silence. Written even when the ringer is already down,
            // because the point of the write is the *marker*: without it, a phone muted by hand
            // before the network was joined would be unmuted by this app on the way out, which is
            // the one thing the marker exists to prevent. Already holding it for this network is
            // the genuine no-op.
            state.rule == RingerRule.Silent && ssid != null ->
                if (state.alreadySilent && state.silencedFor == ssid) {
                    Move.Leave
                } else {
                    Move.Silence(ssid)
                }

            // On a network that wants the ringer. An explicit opinion, so it beats the marker and
            // beats an override — you asked for this network to be loud.
            state.rule == RingerRule.Ring && ssid != null ->
                if (state.alreadySilent) Move.Ring("on $ssid") else Move.Forget("on $ssid")

            // No opinion here — off Wi-Fi, or on a network with no rule — and the network that
            // asked for silence is behind us.
            stale && state.restore && state.alreadySilent -> Move.Ring("left ${state.silencedFor}")

            // Same, but the ringer is already up, or restoring is off. Either way the claim is
            // spent: hold it any longer and the *next* network's silence would be attributed to
            // the last one.
            stale -> Move.Forget("left ${state.silencedFor}")

            // Holding a silence for the network we are on, and the ringer is up: turned up by hand.
            state.silencedFor.isNotBlank() && !state.alreadySilent ->
                Move.Forget("turned up by hand")

            else -> Move.Leave
        }
    }
}
