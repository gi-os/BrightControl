package com.gios.lightcontrol.keys

import android.os.SystemClock

/**
 * Why the volume strip did or did not appear, the last time something asked it to.
 *
 * This exists because three releases in a row were spent guessing at that question from the outside.
 * The HUD declines to draw for nine different reasons, every one of them a bare `return` on a path
 * with no UI attached, and from a phone all nine look identical: you press a key and nothing
 * happens. A settings row that names the actual reason turns a release cycle into a glance.
 *
 * It also answers the question underneath that one. The strip has two sources — the system's volume
 * broadcast, and reading the level back after a key — and which of them is alive on a given build is
 * not knowable from outside. It decides what a bug in either path costs: where the broadcast never
 * arrives, the read-back *is* the feature.
 *
 * A plain object with a few fields: the service and the settings screen share a process, which is
 * the assumption [OwnWindow] already rests on, so there is no IPC here. Reset when the process is,
 * which the screen says — a count that survived a reboot would need storage, and this is a
 * diagnostic, not a record.
 */
object VolumeSignals {

    /** Volume keys this app was told about, whether or not anything came of them. */
    @Volatile
    var keys: Int = 0

    /** `VOLUME_CHANGED_ACTION` broadcasts received, before any of them is filtered. */
    @Volatile
    var broadcasts: Int = 0

    /** How many times the strip actually reached the screen. */
    @Volatile
    var shown: Int = 0

    @Volatile
    private var outcome: String = ""

    @Volatile
    private var outcomeAt: Long = 0L

    /**
     * Record why the last attempt ended where it did. Called from the bare returns themselves, so
     * a path that grows a new one and forgets this reads as "nothing since", which is at least
     * visibly wrong rather than invisibly stale.
     */
    fun note(why: String) {
        outcome = why
        outcomeAt = SystemClock.elapsedRealtime()
    }

    fun noteShown(what: String) {
        shown++
        note("shown — $what")
    }

    /** The outcome and how long ago, or null when nothing has been tried yet. */
    fun lastOutcome(): String? {
        val why = outcome.ifBlank { return null }
        val ago = (SystemClock.elapsedRealtime() - outcomeAt) / 1000
        return when {
            ago < 60 -> "$why · ${ago}s ago"
            ago < 3600 -> "$why · ${ago / 60}m ago"
            else -> "$why · ${ago / 3600}h ago"
        }
    }

    fun summary(): String =
        "$keys press(es) · $broadcasts broadcast(s) · $shown shown, since the service started"

    /**
     * Whether the broadcast has ever arrived. Only meaningful once a key has been seen — before
     * that, zero and zero says nothing at all.
     */
    fun broadcastSilent(): Boolean = keys > 0 && broadcasts == 0
}
