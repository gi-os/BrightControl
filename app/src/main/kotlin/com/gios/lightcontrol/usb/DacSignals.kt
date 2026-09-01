package com.gios.lightcontrol.usb

import android.os.SystemClock

/**
 * What happened the last time an adapter was plugged in, in words a person can act on.
 *
 * The same argument as [com.gios.lightcontrol.keys.VolumeSignals], and for a feature where it is
 * sharper still. This one declines for eight different reasons — the switch is off, the microphone
 * permission is missing, no adapter is attached, the attach dialog was never answered, the driver
 * would not give up the interface, no unit answered, the write was refused, the read-back
 * disagreed — and from the phone all eight are the same event: you plug your headphones in and
 * they are as quiet as they were yesterday.
 *
 * Worse, the *success* is nearly as quiet. Nothing on screen changes; the only proof is that music
 * is louder than it used to be, which nobody can judge against memory. So the screen has to say
 * which unit answered and what it was set to, and it has to be honest that a verified write is
 * not the same claim as a measured 23 dB.
 *
 * In memory and reset with the process, like every other diagnostic in this app. A record that
 * survived a reboot would need storage, and this is not a record.
 */
object DacSignals {

    /** Attach events seen, whether or not anything came of them. */
    @Volatile
    var attaches: Int = 0

    /** Adapters actually raised. */
    @Volatile
    var unlocks: Int = 0

    @Volatile
    private var outcome: String = ""

    @Volatile
    private var outcomeAt: Long = 0L

    /** The last device raised, and what it was raised to. Null until one is. */
    @Volatile
    var lastDevice: String? = null
        private set

    @Volatile
    var lastLevel: Int? = null
        private set

    @Volatile
    var lastVerified: Boolean = false
        private set

    fun onAttached(device: String) {
        attaches++
        note("$device attached")
    }

    fun onDetached() {
        note("the adapter was unplugged — its volume went with it")
    }

    /**
     * Record why the last attempt ended where it did.
     *
     * Called from the bare returns themselves, so a path that grows a new one and forgets to call
     * this reads as "nothing since", which is visibly wrong rather than invisibly stale.
     */
    fun note(why: String) {
        outcome = why
        outcomeAt = SystemClock.elapsedRealtime()
    }

    fun onUnlocked(
        device: String,
        unit: Int,
        channel: Int,
        target: Int,
        verified: Boolean,
        trimmed: Boolean,
    ) {
        unlocks++
        lastDevice = device
        lastLevel = target
        lastVerified = verified
        val where = "unit $unit" + if (channel == UacVolume.CH_MASTER) "" else " ch $channel"
        note(
            "raised $device to ${UacVolume.formatDb(target)} ($where)" +
                (if (verified) "" else " — the device did not read it back") +
                (if (trimmed) ", media volume trimmed because audio was playing" else ""),
        )
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
        "$attaches attach(es) · $unlocks raised, since the app started"
}
