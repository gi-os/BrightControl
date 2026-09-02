package com.gios.lightcontrol.wifi

import android.content.Context
import com.gios.lightcontrol.adb.AdbManager

/**
 * Joining Wi-Fi networks over the phone's own shell, because LightOS Settings will not.
 *
 * The screen in the photo: *This network ("DFS Guest") is not supported by The Light Phone.* That
 * is Light's Settings app declining an open network — a policy in one app, not a limit of the
 * phone. Underneath, the Android Wi-Fi stack is stock, and the shell it exposes over wireless
 * debugging has `cmd wifi`, the same tool the platform's own tests use to join anything:
 * `cmd wifi connect-network <ssid> open|owe|wpa2|wpa3 [passphrase]`. This app already holds that
 * shell (see [AdbManager]); this is the Wi-Fi vocabulary on top of it.
 *
 * Nothing here uses `WifiManager`. A third-party app on Android 10+ cannot join a network of its
 * own choosing — `addNetwork` returns -1, the suggestion API waits for the *system* to pick the
 * network up and needs a user approval LightOS has no UI for, and a `WifiNetworkSpecifier`
 * request yields a network the system refuses to route the internet over. The shell does not have
 * those problems, and this app has the shell.
 *
 * The parsers are pure and unit-tested; `cmd wifi`'s output is columns padded with spaces and
 * an SSID can contain spaces, so they read from the anchored fields inwards rather than splitting.
 */
object WifiShell {

    /** One network the radio can hear, folded across its access points. */
    data class Seen(
        val ssid: String,
        val bssid: String,
        val rssi: Int,
        val frequency: Int,
        val flags: String,
    ) {
        val security: Security get() = Security.of(flags)

        /** 0–4, the way every Wi-Fi icon has drawn it since 2009. */
        val bars: Int get() = when {
            rssi >= -55 -> 4
            rssi >= -66 -> 3
            rssi >= -77 -> 2
            rssi >= -88 -> 1
            else -> 0
        }

        val band: String get() = when {
            frequency >= 5925 -> "6 GHz"
            frequency >= 4900 -> "5 GHz"
            else -> "2.4 GHz"
        }
    }

    /** What `connect-network` needs to be told, worked out from the scan's capability flags. */
    enum class Security(val keyword: String?, val label: String, val needsPassword: Boolean) {
        Open("open", "open", false),
        Owe("owe", "open (enhanced)", false),
        Wpa2("wpa2", "WPA2", true),
        Wpa3("wpa3", "WPA3", true),
        /** 802.1X / EAP — a username and a certificate, not a passphrase. Not joinable from here. */
        Enterprise(null, "enterprise", false),
        /** WEP is dead and `cmd wifi` will not add it. */
        Wep(null, "WEP", false),
        ;

        companion object {
            fun of(flags: String): Security = when {
                flags.contains("EAP") -> Enterprise
                flags.contains("WEP") -> Wep
                // OWE advertises as `[RSN-OWE-CCMP]`, so it has to be recognised before RSN is
                // read as "a passphrase network".
                flags.contains("OWE") -> Owe
                // A WPA2/WPA3 transition AP carries both PSK and SAE; joining it as wpa2 works on
                // every AP that has PSK, and only pure-SAE networks need the wpa3 keyword.
                flags.contains("PSK") -> Wpa2
                flags.contains("SAE") || flags.contains("WPA3") -> Wpa3
                flags.contains("WPA2") || flags.contains("RSN") || flags.contains("WPA") -> Wpa2
                else -> Open
            }
        }
    }

    /** A network the phone has saved, as `list-networks` prints it. */
    data class Saved(val id: Int, val ssid: String, val security: String)

    /** What `cmd wifi status` says, reduced to the two facts that matter. */
    data class Status(val enabled: Boolean?, val connectedTo: String?)

    // ---- parsers (pure) ------------------------------------------------------------------------

    private val SCAN_LINE = Regex("""^\s*([0-9a-fA-F]{2}(?::[0-9a-fA-F]{2}){5})\s+(\d+)\s+(-?\d+)\s+([\d.]+)\s+(.*)$""")

    /**
     * `cmd wifi list-scan-results`: a header, then one line per access point —
     * `BSSID  Frequency  RSSI  Age(sec)  SSID  Flags`. The SSID may have spaces in it and the flags
     * always start with `[`, so the SSID is whatever sits between the age and the first bracket.
     * Folded to one entry per SSID (the strongest), hidden networks dropped, strongest first.
     */
    fun parseScan(out: String): List<Seen> {
        val all = out.lineSequence().mapNotNull { line ->
            val m = SCAN_LINE.find(line) ?: return@mapNotNull null
            val rest = m.groupValues[5]
            val bracket = rest.indexOf('[')
            val ssid = (if (bracket >= 0) rest.substring(0, bracket) else rest).trim().removeSurrounding("\"")
            val flags = if (bracket >= 0) rest.substring(bracket).trim() else ""
            if (ssid.isEmpty()) return@mapNotNull null
            Seen(
                ssid = ssid,
                bssid = m.groupValues[1].lowercase(),
                rssi = m.groupValues[3].toInt(),
                frequency = m.groupValues[2].toInt(),
                flags = flags,
            )
        }.toList()
        return all.groupBy { it.ssid }
            .map { (_, aps) -> aps.maxBy { it.rssi } }
            .sortedByDescending { it.rssi }
    }

    private val SAVED_LINE = Regex("""^\s*(\d+)\s+(.*?)\s+(\S+)\s*$""")

    /** `cmd wifi list-networks`: `Network Id  SSID  Security type`, or `No networks`. */
    fun parseSaved(out: String): List<Saved> = out.lineSequence().mapNotNull { line ->
        if (line.contains("Network Id", ignoreCase = true)) return@mapNotNull null
        val m = SAVED_LINE.find(line) ?: return@mapNotNull null
        Saved(m.groupValues[1].toInt(), m.groupValues[2].trim().removeSurrounding("\""), m.groupValues[3])
    }.toList()

    fun parseStatus(out: String): Status {
        val enabled = when {
            out.contains("Wifi is enabled", ignoreCase = true) -> true
            out.contains("Wifi is disabled", ignoreCase = true) -> false
            else -> null
        }
        val connected = Regex("""connected to "(.*?)"""").find(out)?.groupValues?.get(1)
            ?: Regex("""SSID:\s*"?([^",]+)"?""").find(out)?.groupValues?.get(1)
                ?.takeIf { it != "<unknown ssid>" && !out.contains("not connected", ignoreCase = true) }
        return Status(enabled, connected)
    }

    /**
     * The exact `cmd wifi connect-network` line for a network, or null when the shell cannot join
     * it (enterprise, WEP). Single-quoted for `sh`, because SSIDs have spaces and passphrases have
     * everything.
     */
    fun connectCommand(ssid: String, security: Security, passphrase: String?): String? {
        val kw = security.keyword ?: return null
        val pass = if (security.needsPassword) {
            val p = passphrase ?: return null
            " " + q(p)
        } else ""
        return "cmd wifi connect-network ${q(ssid)} $kw$pass"
    }

    fun forgetCommand(id: Int) = "cmd wifi forget-network $id"

    /** A word for `sh`. */
    fun q(s: String): String = "'" + s.replace("'", "'\"'\"'") + "'"

    // ---- the shell -----------------------------------------------------------------------------

    private const val SCAN_MS = 15_000L
    private const val CONNECT_MS = 25_000L

    /** Kick a scan, give the radio a moment, read what it heard. Blocking; call off the main thread. */
    fun scan(context: Context): Result<List<Seen>> {
        val kick = AdbManager.runVia(context, "cmd wifi start-scan", SCAN_MS)
        if (kick.startsWith("error:")) return Result.failure(IllegalStateException(kick.removePrefix("error: ")))
        Thread.sleep(3_500)
        val out = AdbManager.runVia(context, "cmd wifi list-scan-results", SCAN_MS)
        if (out.startsWith("error:")) return Result.failure(IllegalStateException(out.removePrefix("error: ")))
        return Result.success(parseScan(out))
    }

    fun status(context: Context): Result<Status> {
        val out = AdbManager.runVia(context, "cmd wifi status", SCAN_MS)
        if (out.startsWith("error:")) return Result.failure(IllegalStateException(out.removePrefix("error: ")))
        return Result.success(parseStatus(out))
    }

    fun saved(context: Context): Result<List<Saved>> {
        val out = AdbManager.runVia(context, "cmd wifi list-networks", SCAN_MS)
        if (out.startsWith("error:")) return Result.failure(IllegalStateException(out.removePrefix("error: ")))
        return Result.success(parseSaved(out))
    }

    fun setEnabled(context: Context, on: Boolean): String =
        AdbManager.runVia(context, "cmd wifi set-wifi-enabled ${if (on) "enabled" else "disabled"}", SCAN_MS)

    /** Join. Returns whatever the shell printed; `Connection initiated` is its word for "trying". */
    fun connect(context: Context, ssid: String, security: Security, passphrase: String?): String {
        val cmd = connectCommand(ssid, security, passphrase)
            ?: return "error: ${security.label} networks cannot be joined from the shell"
        return AdbManager.runVia(context, cmd, CONNECT_MS)
    }

    fun forget(context: Context, id: Int): String = AdbManager.runVia(context, forgetCommand(id), SCAN_MS)
}
