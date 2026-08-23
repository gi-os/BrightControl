package com.gios.lightcontrol.hotspot

/**
 * The decision, with no Android in it so it can be tested on the JVM.
 *
 * This is the whole "act like Instant Hotspot" behaviour reduced to a state machine that
 * takes one snapshot of the world at a time and answers with one action. The Android side
 * (HotspotService) only gathers facts and obeys; every rule about *when* the hotspot should
 * come up or go down lives here, where it can be exercised without a phone.
 *
 * The problem it solves is that the phone cannot see whether the iPad has internet. So the
 * machine guesses -- iPad nearby and we are not on home Wi-Fi means "probably needs me" --
 * brings the access point up, and then lets the iPad answer the question by either joining
 * or not. A join confirms the guess. Three minutes of silence refutes it, and a refuted
 * guess earns a backoff so a cafe with good Wi-Fi does not make the phone flap.
 */
enum class Action { NONE, START_AP, STOP_AP }

/** One reading of the world, all gathered at the same instant by the caller. */
data class Snapshot(
    /** A bonded trigger device (the iPad) was seen advertising in the last scan window. */
    val triggerNearby: Boolean,
    /** The phone is already on a Wi-Fi network the user marked as trusted (home/work). */
    val onTrustedWifi: Boolean,
    /** The access point is currently up, however it was started. */
    val apActive: Boolean,
    /** Devices joined to the access point right now. -1 means "could not read it". */
    val clientCount: Int,
)

/**
 * Timings. Deliberately generous: the failure mode of being too eager is a hotspot that
 * broadcasts to nobody for a few minutes and burns a little battery, so the thresholds
 * are set to make that rare rather than to shave seconds off a connect.
 */
data class Timings(
    /** How long to hold the AP up waiting for the first client before giving up. */
    val clientWaitMs: Long = 3 * 60_000L,
    /** Once clients have come and all gone, how long to wait before tearing down. */
    val idleTeardownMs: Long = 10 * 60_000L,
    /** After a give-up (no client ever joined), how long to ignore the trigger. */
    val backoffMs: Long = 30 * 60_000L,
)

/**
 * The states are named for what the machine is waiting on, not for the hotspot's own
 * on/off, because the same "AP is up" can mean two very different things -- still hoping
 * for a client, or serving one -- and they tear down on different rules.
 */
enum class Phase { IDLE, WAITING_CLIENT, SERVING, DRAINING }

class TriggerEngine(private val t: Timings = Timings()) {

    var phase: Phase = Phase.IDLE
        private set

    // Absolute clock stamps rather than countdowns: the service is a foreground loop that
    // can be paused and resumed by Doze between ticks, so elapsed time has to be read from
    // the clock each tick, never accumulated.
    private var apUpSince = 0L
    private var drainingSince = 0L
    private var backoffUntil = 0L

    /** True while the trigger is being ignored because a recent guess went unanswered. */
    fun inBackoff(now: Long): Boolean = now < backoffUntil

    fun evaluate(now: Long, s: Snapshot): Action {
        // A hotspot that dies from underneath us (the shell went away, user toggled it in
        // system settings) resets the machine, and we spend this tick observing that and
        // nothing else. Returning here rather than falling through matters: the trigger
        // device is usually still nearby, so re-evaluating immediately would restart the
        // AP on the same tick it just died -- a tight flap if the AP is failing to hold.
        if (!s.apActive && phase != Phase.IDLE) {
            phase = Phase.IDLE
            return Action.NONE
        }

        return when (phase) {
            Phase.IDLE -> {
                val shouldStart = s.triggerNearby &&
                    !s.onTrustedWifi &&
                    !s.apActive &&
                    now >= backoffUntil
                if (shouldStart) {
                    phase = Phase.WAITING_CLIENT
                    apUpSince = now
                    Action.START_AP
                } else {
                    Action.NONE
                }
            }

            Phase.WAITING_CLIENT -> when {
                // Someone joined -- the guess was right, this is a real session now.
                s.clientCount > 0 -> {
                    phase = Phase.SERVING
                    Action.NONE
                }
                // Nobody joined in time -- the iPad had internet after all. Stand down and
                // stop guessing for a while so we do not do this every scan window.
                now - apUpSince >= t.clientWaitMs -> {
                    phase = Phase.IDLE
                    backoffUntil = now + t.backoffMs
                    Action.STOP_AP
                }
                else -> Action.NONE
            }

            Phase.SERVING -> {
                if (s.clientCount == 0) {
                    // Not a teardown yet: a device dropping off for a moment (screen off,
                    // roamed) should not kill a working hotspot. Start the drain clock.
                    phase = Phase.DRAINING
                    drainingSince = now
                }
                Action.NONE
            }

            Phase.DRAINING -> when {
                // Came back before the drain elapsed -- carry on serving.
                s.clientCount > 0 -> {
                    phase = Phase.SERVING
                    Action.NONE
                }
                now - drainingSince >= t.idleTeardownMs -> {
                    phase = Phase.IDLE
                    // No backoff here: clients were real, this is an ordinary end of use.
                    Action.STOP_AP
                }
                else -> Action.NONE
            }
        }
    }

    /** The user turned auto mode off, or forced the AP down. Forget everything but backoff. */
    fun reset() {
        phase = Phase.IDLE
        apUpSince = 0
        drainingSince = 0
    }
}
