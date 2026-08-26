package com.gios.lightcontrol.hotspot

import android.content.Context
import android.net.wifi.WifiManager
import com.gios.lightcontrol.adb.AdbManager

/**
 * The access point, raised and lowered over the shell this app already holds.
 *
 * ### Why this replaced Shizuku
 *
 * Raising a hotspot is `signature|privileged` and has been since Android 11, so it needs a shell
 * UID. BrightHotspot got one from Shizuku, and that was the whole problem with it: Shizuku's own
 * way in is the wireless-debugging pairing flow, and Android tears that down on every reboot. A
 * setup step you repeat forever is not a setup step, it is a fault, and it is why an app that
 * worked read as an app nobody could start.
 *
 * This app has held a shell the entire time. Same UID, by a route that reconnects itself over
 * mDNS with no pairing — which is what makes the whole feature survive a reboot without anybody
 * touching it. See [com.gios.lightcontrol.hotspot.HotspotBoot].
 *
 * ### The one thing that is worse than the binder
 *
 * A shell can only run commands, so this goes through `cmd -w wifi start-softap` rather than
 * `TetheringManager.startTethering`. That command takes the network's name and password as
 * arguments instead of using the one saved in Settings — so the two have to be told to match, or
 * the iPad sees a network it has never met and will not join it on its own. [Prefs] holds them,
 * and [readSaved] has a go at reading them off the phone so nobody has to type anything.
 *
 * Starting an AP this way does not overwrite the saved configuration; it starts *with* the one it
 * was handed. Turning the hotspot on from LightOS afterwards behaves exactly as it did.
 *
 * ### Failure is loud here, which it never was before
 *
 * Every call returns what the shell printed. Shizuku's failure mode was a silence you could not
 * tell from a working scan — the AP simply never came up. If `start-softap` is not on this build,
 * or the arguments are spelled differently, the screen says so in the words the phone used.
 */
class SoftAp(context: Context) {

    private val app = context.applicationContext
    private val wifi = app.getSystemService(Context.WIFI_SERVICE) as WifiManager

    /** What a shell call did, in enough detail for a screen to say something true. */
    data class Outcome(val ok: Boolean, val said: String)

    // ------------------------------------------------------------------ reading state

    /**
     * The AP state constants from `WifiManager`: 10 disabling, 11 disabled, 12 enabling,
     * 13 enabled, 14 failed.
     *
     * `getWifiApState` is hidden but callable on the ordinary manager with `ACCESS_WIFI_STATE` —
     * no shell needed just to look. Worth keeping off the shell deliberately: this is polled every
     * tick, and a poll that needed the adb connection would make the readout go blank whenever the
     * connection was between reconnects.
     */
    fun apState(): Int {
        // The direct route, which works on a build that has not blocklisted the method.
        runCatching {
            return wifi.javaClass.getMethod("getWifiApState").invoke(wifi) as Int
        }
        // **And the shell, because on Android 14 the direct route usually fails.**
        // `getWifiApState` is a hidden API, and reflection on a blocklisted method throws rather
        // than returning anything — which is caught above and reads as "not known" for ever. The
        // separate app carried a HiddenApiBypass dependency to get around that; this one already
        // has a shell that can simply ask, so it asks.
        val out = run("dumpsys wifi | grep -iE -m1 'ap state|mWifiApState'")
        if (!out.ok) return AP_STATE_UNKNOWN
        val said = out.said.uppercase()
        return when {
            said.contains("ENABLED") && !said.contains("DISABL") -> AP_STATE_ENABLED
            said.contains("ENABLING") -> AP_STATE_ENABLING
            said.contains("DISABLING") -> AP_STATE_DISABLING
            said.contains("DISABLED") -> AP_STATE_DISABLED
            said.contains("FAILED") -> AP_STATE_FAILED
            else -> AP_STATE_UNKNOWN
        }
    }

    fun apEnabled(): Boolean = apState() == AP_STATE_ENABLED

    fun apStateWords(): String = when (apState()) {
        AP_STATE_DISABLING -> "turning off"
        AP_STATE_DISABLED -> "off"
        AP_STATE_ENABLING -> "turning on"
        AP_STATE_ENABLED -> "on"
        AP_STATE_FAILED -> "failed"
        else -> "not known"
    }

    // ------------------------------------------------------------------ start / stop

    /**
     * Raise the access point as [ssid] with [password].
     *
     * An empty password means an open network, which the command spells differently rather than
     * as an empty argument — and which is worth being able to ask for, because a hotspot nobody
     * has the password to is a hotspot the iPad cannot join either.
     */
    fun start(ssid: String, password: String): Outcome {
        if (ssid.isBlank()) return Outcome(false, "No network name set")
        val security = if (password.isBlank()) "open" else "wpa2 ${quote(password)}"
        return run("cmd -w wifi start-softap ${quote(ssid)} $security")
    }

    fun stop(): Outcome = run("cmd -w wifi stop-softap")

    /**
     * How many devices have actually joined.
     *
     * This is the fact the whole guess-and-verify design turns on: a client appearing means the
     * iPad had no internet and took ours, which confirms the guess. There is no unprivileged API
     * that answers it, so this reads the kernel neighbour table — the same `ip neigh` a person
     * would run over adb — and counts live entries on a tethering interface.
     *
     * Deliberately forgiving. No shell, or a command shape this build spells differently, returns
     * [UNKNOWN] rather than guessing zero: a false zero would tear down a working hotspot, and the
     * engine treats unknown as "keep waiting", which times out on its own anyway.
     */
    fun clientCount(): Int {
        val out = run("ip neigh show").takeIf { it.ok }?.said ?: return UNKNOWN
        var n = 0
        for (line in out.lineSequence()) {
            if (line.isBlank()) continue
            val onAp = AP_IFACES.any { line.contains(" dev $it ") || line.endsWith(" dev $it") }
            // REACHABLE, STALE and DELAY all mean the lease is live; FAILED and INCOMPLETE do not.
            val live = line.contains("REACHABLE") || line.contains("STALE") || line.contains("DELAY")
            if (onAp && live) n++
        }
        return n
    }

    /**
     * The saved hotspot's name and password, if the phone will say.
     *
     * `dumpsys wifi` prints the soft-AP configuration on most builds, and on some it redacts the
     * passphrase — so this is an offer, not a guarantee, and the settings screen falls back to two
     * fields you fill in by hand. Better to try: the alternative is asking somebody to copy their
     * own hotspot password out of Settings to give it back to the phone that already knows it.
     */
    fun readSaved(): Pair<String?, String?> {
        val out = run("dumpsys wifi").takeIf { it.ok }?.said ?: return null to null
        val ssid = Regex("""SSID\s*[:=]\s*"?([^"\n,]+)"?""").find(out)?.groupValues?.get(1)?.trim()
        val pass = Regex("""(?:passphrase|preSharedKey)\s*[:=]\s*"?([^"\n,]+)"?""", RegexOption.IGNORE_CASE)
            .find(out)?.groupValues?.get(1)?.trim()
        return ssid?.takeIf { it.isNotBlank() && it != "<unknown ssid>" } to
            pass?.takeIf { it.isNotBlank() && !it.startsWith("<") }
    }

    /** Whether the shell is up at all. The screens gate on this rather than guessing. */
    fun shellReady(): Boolean =
        runCatching { AdbManager.getInstance(app).connected() }.getOrDefault(false)

    private fun run(command: String): Outcome = runCatching {
        val adb = AdbManager.getInstance(app)
        if (!adb.connected()) return Outcome(false, "No adb connection")
        // Bounded: `runCommand` reads until EOF and a stalled stream never reaches it, which on
        // this path would hang the hotspot toggle with no way back. See [AdbManager.runVia].
        val said = AdbManager.runVia(app, command).trim()
        // The wifi shell prints usage on a bad argument and says nothing at all on success, so
        // silence is the good answer and anything mentioning usage is the command not existing
        // in the shape we asked for.
        val bad = said.contains("Usage", ignoreCase = true) ||
            said.contains("Unknown command", ignoreCase = true) ||
            said.contains("Exception", ignoreCase = true)
        Outcome(!bad, said.ifBlank { "ok" })
    }.getOrElse { Outcome(false, it.message ?: it.javaClass.simpleName) }

    /** Single quotes, with any of its own escaped the only way `sh` allows. */
    private fun quote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    companion object {
        const val UNKNOWN = -1

        const val AP_STATE_DISABLING = 10
        const val AP_STATE_DISABLED = 11
        const val AP_STATE_ENABLING = 12
        const val AP_STATE_ENABLED = 13
        const val AP_STATE_FAILED = 14
        const val AP_STATE_UNKNOWN = -1

        /**
         * The soft-AP interface is named differently across vendors. Matching on the interface is
         * what keeps the phone's own Wi-Fi and Bluetooth neighbours out of the count.
         */
        private val AP_IFACES = listOf("wlan1", "ap0", "swlan0", "softap0")
    }
}
