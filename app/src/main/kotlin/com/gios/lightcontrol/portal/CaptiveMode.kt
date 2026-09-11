package com.gios.lightcontrol.portal

import android.content.ContentResolver
import android.content.Context
import android.provider.Settings
import com.gios.lightcontrol.adb.AdbManager
import com.gios.lightcontrol.keys.Grants

/**
 * Android's captive-portal detection, read and set.
 *
 * Every new network gets probed: the platform asks a known host for a 204, and a reply that is not
 * one means something stands between this phone and the internet. The network is then marked
 * `CAPTIVE_PORTAL` and **not** `VALIDATED`, which is what makes Android post *Sign in to network*
 * — and also what makes it route around the Wi-Fi, keep the mobile data on, and in time drop the
 * network altogether. On a phone with no notification shade the first half is invisible and the
 * second half is the whole experience: a hotel network that connects and then quietly stops being
 * used.
 *
 * Turning detection off is the other way through, and Gio's: with
 * `captive_portal_mode = 0` the platform stops probing, reports every network as validated, and
 * **stays on it**. Nothing announces the login page, so you fetch it yourself — load any plain
 * `http://` address and the gateway answers with its own page instead ([PortalRoute.PLAIN_URL]).
 * Sign in there and the network was never dropped in the first place.
 *
 * The cost is stated plainly wherever this is offered: with detection off, a network that genuinely
 * has no internet also looks fine to every app on the phone, and this app's own login screen loses
 * the one success signal that needs no socket — see [detectionOn], which gates it.
 *
 * The value lives in `Settings.Global`, so writing it needs `WRITE_SECURE_SETTINGS`. This app
 * usually holds that already (the colour grant), in which case the write happens in-process with
 * no ADB connection at all; when it does not, the same line runs over the phone's own shell.
 */
object CaptiveMode {

    const val KEY = "captive_portal_mode"
    const val DETECTION_KEY = "captive_portal_detection_enabled"

    /** The platform's enum. 0 ignore, 1 prompt (the default), 2 avoid. */
    const val IGNORE = 0
    const val PROMPT = 1
    const val AVOID = 2

    /** The shell line for a mode — the one a computer would run, minus the `adb shell`. */
    fun command(mode: Int): String = "settings put global $KEY $mode"

    /**
     * What a raw settings value means, in the words the screens use.
     *
     * Unset is not unknown: an absent `captive_portal_mode` is the platform default, which is 1.
     * `captive_portal_detection_enabled = 0` is the older switch for the same thing and wins when
     * it is set, so both are read.
     */
    fun label(mode: String?, detectionEnabled: String? = null): String = when {
        detectionEnabled == "0" -> "Off"
        mode == null || mode.isBlank() || mode == "null" -> "On (default)"
        mode == "0" -> "Off"
        mode == "1" -> "On (default)"
        mode == "2" -> "Avoid"
        else -> "Unknown ($mode)"
    }

    /** The raw value, or null when it has never been set. */
    fun raw(cr: ContentResolver): String? =
        runCatching { Settings.Global.getString(cr, KEY) }.getOrNull()

    private fun rawDetection(cr: ContentResolver): String? =
        runCatching { Settings.Global.getString(cr, DETECTION_KEY) }.getOrNull()

    fun label(cr: ContentResolver): String = label(raw(cr), rawDetection(cr))

    /**
     * Whether the platform is still probing.
     *
     * Load-bearing beyond the settings screen: when this is false the platform reports **every**
     * network as `VALIDATED` without asking anyone, so "the system says this network is validated"
     * stops being evidence that a gate opened. [PortalActivity] checks this before trusting it.
     */
    fun detectionOn(cr: ContentResolver): Boolean = when {
        rawDetection(cr) == "0" -> false
        raw(cr) == "0" -> false
        else -> true
    }

    /** What a write did, and by which route, so the button can say so. */
    data class Result(val ok: Boolean, val detail: String)

    /**
     * Set the mode and read it back.
     *
     * In-process when the permission is held, over the shell when it is not, and the read-back is
     * the answer either way — a `settings put` that the daemon refuses says nothing on stdout, so
     * a command that "ran" is not a mode that changed.
     */
    fun set(context: Context, mode: Int): Result {
        val cr = context.contentResolver
        val direct = Grants.canWriteSecureSettings(context) &&
            runCatching { Settings.Global.putInt(cr, KEY, mode) }.getOrDefault(false)
        // The older key is only ever cleared, never set: a ROM that ships it at 0 would otherwise
        // keep detection off while this one says it is on.
        if (direct && mode != IGNORE) {
            runCatching { Settings.Global.putInt(cr, DETECTION_KEY, 1) }
        }
        val shellOut = if (direct || raw(cr) == mode.toString()) {
            null
        } else {
            val line = if (mode == IGNORE) {
                command(mode)
            } else {
                "sh -c '${command(mode)}; settings put global $DETECTION_KEY 1'"
            }
            runCatching { AdbManager.runVia(context, line, 6_000) }.getOrElse { "${it::class.java.simpleName}: ${it.message}" }
        }
        val now = raw(cr)
        val ok = now == mode.toString() && (mode != IGNORE) == detectionOn(cr)
        val route = if (direct) "written directly" else "written over the shell"
        return Result(
            ok = ok,
            detail = when {
                ok -> "$route — detection is now ${label(cr).lowercase()}"
                shellOut.isNullOrBlank() ->
                    "$KEY still reads ${now ?: "unset"}. This needs WRITE_SECURE_SETTINGS or a connection."
                else -> "$KEY still reads ${now ?: "unset"} — ${shellOut.trim().take(200)}"
            },
        )
    }
}
