package com.gios.lightcontrol.keys

/**
 * How many volume presses this app has seen, and how many volume broadcasts.
 *
 * A counter pair, on screen, because the difference between them is the one fact that decides what
 * a missing volume strip means — and twice now it has been the fact nobody had.
 *
 * [VolumeWatcher] has two ways of noticing a volume change. The broadcast is meant to be the main
 * one; reading the level back after a key is the fallback for a build that does not send it. Which
 * of those is actually alive on a given phone is unknowable from the outside, and it changes what a
 * bug in either path costs: on a phone where the broadcast never arrives, the fallback *is* the
 * feature, and a guard added to it can take the whole HUD off the screen. That is exactly what
 * v3.90 did.
 *
 * So the settings screen reports both. `keys 12 · broadcasts 0` says the fallback is carrying it
 * alone; equal-ish numbers say both paths work and either could fail unnoticed.
 *
 * A plain object with two counters: the service and the settings screen share a process — the same
 * assumption [OwnWindow] rests on — so there is no IPC here and nothing to keep in sync. Reset when
 * the process is, which the screen says, because a count since boot would need storage and this is
 * a diagnostic rather than a record.
 */
object VolumeSignals {

    /** Volume keys this app was told about, whether or not anything came of them. */
    @Volatile
    var keys: Int = 0

    /** `VOLUME_CHANGED_ACTION` broadcasts received, before any of them is filtered. */
    @Volatile
    var broadcasts: Int = 0

    /** What to put on a settings row. */
    fun summary(): String = "$keys press(es) · $broadcasts broadcast(s) since the service started"

    /**
     * Whether the broadcast has ever arrived. Only meaningful once a key has been seen — before
     * that, zero and zero says nothing at all.
     */
    fun broadcastSilent(): Boolean = keys > 0 && broadcasts == 0
}
