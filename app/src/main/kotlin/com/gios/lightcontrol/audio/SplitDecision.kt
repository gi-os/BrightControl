package com.gios.lightcontrol.audio

import com.gios.lightcontrol.SplitMode

/**
 * Two levels out of one, decided. No Android in it, so it can be tested.
 *
 * ### The platform fact this works around
 *
 * `AudioService` aliases `STREAM_NOTIFICATION` onto `STREAM_RING` when
 * `config_alias_ring_notif_stream_types` is true, which it is on every phone with a radio in it and
 * is on this one. The alias is a framework resource compiled into the ROM. It is not a setting, it
 * is not reachable through `WRITE_SECURE_SETTINGS`, and no permission an app can hold changes it.
 * This codebase found it the hard way long before it was a feature: `keys.VolumeWatcher` drops
 * duplicate broadcasts because "a notification volume mirrors the ringer", which is the alias seen
 * from the other side.
 *
 * So there is one number, and the phone plays a ring and a text message at the same loudness. What
 * can be separated is not the number but *when* it applies, and that is what this decides.
 *
 * ### Assert once, learn always
 *
 * The level is written on exactly two edges — into the ring, and out of it — and never in between
 * and never at rest. Everything else here exists to keep it from writing at a moment somebody else
 * meant something. The rule the whole file is shaped by is [com.gios.lightcontrol.keys.CallAudio]'s:
 * a level re-asserted while a phone is in somebody's hand is a volume control that cannot be turned
 * down, and turning it down is precisely what a person does when a ring is too loud in a quiet room.
 *
 * The other half is [learn]. The two levels are never typed in. The hardware keys already set the
 * ring volume and always have; all this adds is remembering *which of the two numbers* you were
 * setting, which the phone can tell from whether it was ringing at the time. Turn it down mid-ring
 * and you have set the ring level. Turn it down while nothing is happening and you have set the
 * notification level. There is no third thing a person could have meant.
 *
 * ### Who else writes this number
 *
 * `audio.WifiRinger` and the user. Both win, and the checks are ordered so that they win before any
 * question about calls is asked:
 *
 *  - A **Wi-Fi claim** ([State.wifiHolding]) means a network asked for silence and got it. A place
 *    that wants a silent phone wants a silent phone during a call too.
 *  - A **ringer that is down** ([State.ringerSilent]) is somebody's own decision, made with a
 *    hardware key or by a rule, and writing an index into it would unmute the phone. Nothing here
 *    ever raises a ringer that is not up already.
 */
object SplitDecision {

    /**
     * Everything the decision is made from.
     *
     * @param mode what the user asked for. Only [SplitMode.TwoLevels] reaches any of this.
     * @param ringing whether the line is ringing *now*. Answered, and the ringtone has stopped, so
     *   an answered call is not a ring — see `audio.RingerSplit`.
     * @param holdingBoost whether this app believes it raised the level and has not put it back.
     *   Persisted, because a process that dies mid-ring must not leave a phone loud for ever.
     * @param ringLevel the level a ring should be at.
     * @param notifyLevel the level everything else should be at.
     * @param current the `STREAM_RING` index right now.
     * @param max that stream's maximum, for the clamp.
     * @param ringerSilent the ringer is on vibrate or silent.
     * @param wifiHolding `audio.WifiRinger` is holding this phone silent for a network.
     */
    data class State(
        val mode: SplitMode,
        val ringing: Boolean,
        val holdingBoost: Boolean,
        val ringLevel: Int,
        val notifyLevel: Int,
        val current: Int,
        val max: Int,
        val ringerSilent: Boolean,
        val wifiHolding: Boolean,
    )

    /** What to do about the level. [Leave] is the answer almost every time this is asked. */
    sealed interface Move {
        /** Touch nothing, remember nothing new. */
        data object Leave : Move

        /** Write [level] to `STREAM_RING`; [hold] is what the boost marker becomes. */
        data class Write(val level: Int, val hold: Boolean, val why: String) : Move

        /**
         * Write nothing, drop the marker.
         *
         * The case where this app is holding a boost it can no longer honestly undo: the level has
         * moved since, or the phone went silent underneath it. Putting a number back would be
         * guessing at somebody else's intention with the one control they can hear.
         */
        data class Release(val why: String) : Move

        /**
         * Take the marker without writing anything.
         *
         * A ring that arrives with the level already where a ring wants it. The write is a no-op
         * but the *marker* is not: without it the restore at the end of the call never happens.
         * The same shape as `RingerDecision.Move.Silence` being issued for a phone that is already
         * silent, and for the same reason.
         */
        data class Hold(val why: String) : Move
    }

    fun decide(state: State): Move {
        val ring = state.ringLevel.coerceIn(FLOOR, state.max.coerceAtLeast(FLOOR))
        val rest = state.notifyLevel.coerceIn(FLOOR, state.max.coerceAtLeast(FLOOR))

        // Anything that stands this feature down has to also unwind a boost it is standing down
        // in the middle of. Hence the pair on every branch rather than a bare `Leave`.
        val standDown = { why: String ->
            when {
                !state.holdingBoost -> Move.Leave
                state.ringerSilent -> Move.Release("$why · ringer is down")
                // Only undo a level that still looks like the one this app wrote. Moved since, and
                // it was moved by somebody with a reason.
                state.current == ring -> Move.Write(rest, hold = false, why = why)
                else -> Move.Release("$why · level moved to ${state.current}")
            }
        }

        return when {
            state.mode != SplitMode.TwoLevels -> standDown("two levels off")

            // A network that asked for silence outranks a call. See the class note.
            state.wifiHolding -> standDown("Wi-Fi holds this phone silent")

            // Vibrate or silent, by hand or by rule. Never written into, and never raised out of.
            state.ringerSilent -> standDown("ringer is down")

            // Both numbers the same is a feature switched on and not yet told anything. Writing
            // them at each other would be two broadcasts a call to say nothing.
            ring == rest -> standDown("both levels are $ring")

            state.ringing && state.holdingBoost -> Move.Leave

            state.ringing && state.current == ring -> Move.Hold("ringing · already at $ring")

            state.ringing -> Move.Write(ring, hold = true, why = "ringing · ${state.current} → $ring")

            state.holdingBoost -> standDown("call over")

            // At rest, and holding nothing. The resting level is whatever the person left it at,
            // which is the definition of the notification level — see [learn]. Asserting it here
            // would be this app arguing with the volume keys once a second.
            else -> Move.Leave
        }
    }

    /** One of the two numbers, and which one. */
    data class Learned(val ring: Boolean, val level: Int)

    /**
     * Somebody moved the ring volume. Work out which of the two levels they meant.
     *
     * @param level the new `STREAM_RING` index, as the broadcast reported it.
     * @return the level to remember, or null when this is not a change to learn from.
     *
     * Three things are deliberately not learned:
     *
     *  - **Zero.** A person who walks the ring level to the bottom has put the phone on vibrate.
     *    That is a ringer *mode* and belongs to `audio.WifiRinger` and the panel, not here — and a
     *    notification level of zero would mean the restore at the end of every call wrote a zero,
     *    which is this app silencing a phone as a side effect of a call ending.
     *  - **A change this app made.** Handled upstream, in `audio.RingerSplit`, because whether a
     *    broadcast is an echo of our own write is a question about clocks and not about levels.
     *  - **Anything at all, when the feature is off.** A level learned while the feature was off is
     *    a number from a week ago waiting to be applied to a call.
     */
    fun learn(state: State, level: Int): Learned? {
        if (state.mode != SplitMode.TwoLevels) return null
        if (level <= 0) return null
        if (level > state.max && state.max > 0) return null
        // Mid-ring, or mid-restore: either way the number in front of the person is the ring.
        val ring = state.ringing || state.holdingBoost
        val known = if (ring) state.ringLevel else state.notifyLevel
        if (known == level) return null
        return Learned(ring = ring, level = level)
    }

    /**
     * The lowest level this will ever write.
     *
     * One, not zero. Writing zero to `STREAM_RING` does not merely make it quiet, it moves the
     * phone into `RINGER_MODE_VIBRATE` — which broadcasts, which `audio.WifiRinger` reads as the
     * user muting the phone, which drops a claim it is holding. A restore at the end of a call must
     * not be able to reach into another feature's state.
     */
    const val FLOOR = 1
}
