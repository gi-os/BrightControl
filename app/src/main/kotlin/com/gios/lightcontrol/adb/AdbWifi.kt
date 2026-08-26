package com.gios.lightcontrol.adb

import android.content.Context
import android.provider.Settings

/**
 * Whether the phone's own debugging daemon is listening, and switching it on when it is not.
 *
 * ## Why this exists
 *
 * Everything this app does with a shell depends on wireless debugging being on, and wireless
 * debugging goes off on its own: a reboot clears it, and so does a trip through Developer options.
 * When it goes, the daemon stops listening, the socket dies, and every screen here reports the
 * consequence rather than the cause — "the connection is gone and could not be picked back up",
 * which is true and useless. The pairing is still on disk; there is simply nothing to connect to.
 *
 * Until now the only way back was a cable, which on a phone whose whole point is not needing a
 * computer is a poor answer. But `adb_wifi_enabled` is an ordinary global setting: readable by
 * anybody, writable by anything holding `WRITE_SECURE_SETTINGS` — **which this app was granted in
 * its own first-run setup**, for the colour writes. So the daemon can be switched back on from
 * here, and the shell that was set up once keeps working without a computer ever being involved
 * again.
 *
 * ## Read is free, write is not silent
 *
 * [on] needs no permission at all, so the state can always be shown, and "wireless debugging is
 * off" is a diagnosis anybody can act on. [turnOn] is offered as a button rather than done
 * quietly: switching a phone's debugging daemon on is a real change to how exposed it is, and a
 * user who set this app up for one thing should not discover it doing that on its own. It reports
 * whether the write took, which is also the honest way to find out whether the grant is still
 * there.
 */
object AdbWifi {

    /** True, false, or null when the phone will not say. */
    fun on(context: Context): Boolean? = runCatching {
        Settings.Global.getInt(context.contentResolver, KEY) == 1
    }.getOrNull()

    /** Whether developer options are on at all. Wireless debugging cannot be without them. */
    fun developerOptionsOn(context: Context): Boolean? = runCatching {
        Settings.Global.getInt(
            context.contentResolver,
            Settings.Global.DEVELOPMENT_SETTINGS_ENABLED,
        ) == 1
    }.getOrNull()

    /**
     * Ask the phone to start listening, and say what happened in words worth showing.
     *
     * The read-back is the answer, not the write's return value: `putInt` reports whether the
     * *write* succeeded, and the daemon starting is a consequence of the setting changing, which
     * the framework does asynchronously. So this writes, waits a beat, and reads.
     */
    fun turnOn(context: Context): Result {
        if (on(context) == true) return Result(true, "already on")
        val wrote = runCatching {
            Settings.Global.putInt(context.contentResolver, KEY, 1)
        }.getOrElse { error ->
            return Result(
                false,
                when (error) {
                    is SecurityException ->
                        "this app is not allowed to change it — the WRITE_SECURE_SETTINGS grant is " +
                            "missing, so use GRANT ALL first or turn it on in Developer options"
                    else -> error.message ?: error.javaClass.simpleName
                },
            )
        }
        if (!wrote) return Result(false, "the phone refused the write")
        // The daemon comes up when the setting changes, not when the write returns.
        runCatching { Thread.sleep(SETTLE_MS) }
        val now = on(context)
        return when (now) {
            true -> Result(true, "wireless debugging is on")
            false -> Result(false, "the setting was written and did not stick")
            null -> Result(false, "the phone will not say whether it took")
        }
    }

    data class Result(val ok: Boolean, val said: String)

    /**
     * Long enough for the framework's observer to act on the change.
     *
     * Not a guess at how long a daemon takes to bind a port — that is what mDNS discovery is for,
     * and it retries. This is only long enough that the read-back is asking about the new value.
     */
    private const val SETTLE_MS = 700L

    /** `Settings.Global.ADB_WIFI_ENABLED`, which is `@hide` as a constant and plain as a string. */
    private const val KEY = "adb_wifi_enabled"
}
