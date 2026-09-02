package com.gios.lightcontrol.wifi

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import com.gios.lightcontrol.wifi.WifiShell.Security
import com.gios.lightcontrol.wifi.WifiShell.Seen

/**
 * Joining Wi-Fi without the shell — because the shell needs Wi-Fi.
 *
 * v4.13 joined networks over wireless debugging and met the obvious problem on its first outing:
 * *"the connection is gone and could not be picked back up"*, on a phone standing next to the
 * network it wanted. Android's `AdbDebuggingManager` will not start the wireless daemon unless the
 * phone is already on a Wi-Fi network, and switches it off the moment Wi-Fi drops. The shell can
 * therefore never be the thing that joins the *first* network. Chicken, egg.
 *
 * What a plain app *can* do on Android 10+ is **suggest** a network ([WifiNetworkSuggestion]).
 * The system, not the app, then joins it — on its next scan, if nothing better is around, and
 * only once the user has approved this app's suggestions. That approval is the catch: it is asked
 * through a notification LightOS has no shade for. Two ways round it, both used:
 *
 *  - **Self-grant over the shell, while on Wi-Fi.** `cmd wifi network-suggestions-set-user-approved
 *    <pkg> yes` is a `SelfGrant` step now, so a phone that has run ADB & grants at home arrives at
 *    the café already approved.
 *  - **Press the notification's own ALLOW.** This app's notification listener sees the system's
 *    request; [approvalAction] finds its "Allow" button and the screen offers to press it.
 *
 * Scanning is [WifiManager.getScanResults], which needs fine location granted *and* location
 * switched on — Android's price for knowing which networks are near. Both are checked and named.
 */
object WifiSuggest {

    const val APPROVE_COMMAND = "cmd wifi network-suggestions-set-user-approved com.gios.lightcontrol yes"
    const val APPROVED_QUERY = "cmd wifi network-suggestions-has-user-approved com.gios.lightcontrol"

    sealed interface Scan {
        data class Heard(val networks: List<Seen>) : Scan
        data object NoLocationPermission : Scan
        data object LocationOff : Scan
        data object WifiOff : Scan
        data class Failed(val why: String) : Scan
    }

    fun locationGranted(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun locationOn(context: Context): Boolean =
        context.getSystemService(LocationManager::class.java)?.isLocationEnabled == true

    fun wifiOn(context: Context): Boolean =
        context.applicationContext.getSystemService(WifiManager::class.java)?.isWifiEnabled == true

    /** Kick a scan, wait, read. Blocking; call off the main thread. */
    fun scan(context: Context): Scan {
        val wm = context.applicationContext.getSystemService(WifiManager::class.java)
            ?: return Scan.Failed("no WifiManager")
        if (!wm.isWifiEnabled) return Scan.WifiOff
        if (!locationGranted(context)) return Scan.NoLocationPermission
        if (!locationOn(context)) return Scan.LocationOff
        // Deprecated and throttled (four a couple of minutes in the foreground), but the cached
        // results below are what the system's own last scan found, so a refused kick still reads.
        @Suppress("DEPRECATION")
        val kicked = runCatching { wm.startScan() }.getOrDefault(false)
        if (kicked) Thread.sleep(3_500)
        val results = runCatching { wm.scanResults }.getOrElse { return Scan.Failed("${it::class.java.simpleName}: ${it.message}") }
        val seen = results.mapNotNull { r ->
            @Suppress("DEPRECATION")
            val ssid = r.SSID?.removeSurrounding("\"")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Seen(ssid = ssid, bssid = r.BSSID.lowercase(), rssi = r.level, frequency = r.frequency, flags = r.capabilities ?: "")
        }
        return Scan.Heard(
            seen.groupBy { it.ssid }.map { (_, aps) -> aps.maxBy { it.rssi } }.sortedByDescending { it.rssi },
        )
    }

    /**
     * Hand the network to the system. Returns a line for the screen, or `error: …`.
     *
     * Suggestions already given are kept, not cleared: a saved suggestion is exactly what lets the
     * phone rejoin this network next week without this screen, and a duplicate is reported as
     * such by the platform rather than being an error worth stopping for.
     */
    fun suggest(context: Context, ssid: String, security: Security, passphrase: String?): String {
        val wm = context.applicationContext.getSystemService(WifiManager::class.java)
            ?: return "error: no WifiManager"
        val b = WifiNetworkSuggestion.Builder().setSsid(ssid)
        when (security) {
            Security.Open -> Unit
            Security.Owe -> b.setIsEnhancedOpen(true)
            Security.Wpa2 -> b.setWpa2Passphrase(passphrase ?: return "error: a password is needed")
            Security.Wpa3 -> b.setWpa3Passphrase(passphrase ?: return "error: a password is needed")
            Security.Enterprise -> return "error: enterprise networks need a certificate this app cannot supply"
            Security.Wep -> return "error: Android no longer joins WEP networks"
        }
        val status = runCatching { wm.addNetworkSuggestions(listOf(b.build())) }
            .getOrElse { return "error: ${it::class.java.simpleName}: ${it.message}" }
        // A second scan is what makes the system act on the suggestion now rather than whenever.
        @Suppress("DEPRECATION")
        runCatching { wm.startScan() }
        return when (status) {
            WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS -> "suggested"
            WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_DUPLICATE -> "suggested (already known)"
            WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_APP_DISALLOWED ->
                "error: the system has been told not to accept this app's networks — approve it (below) and try again"
            WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_EXCEEDS_MAX_PER_APP -> "error: too many suggested networks; forget some"
            // 6 and 7 are ADD_NOT_ALLOWED and ADD_INVALID, API 30 constants spelled out for a minSdk of 29.
            6 -> "error: suggestions are not allowed on this phone"
            7 -> "error: the platform called that network invalid"
            else -> "error: addNetworkSuggestions returned $status"
        }
    }

    /** Everything this app has suggested, so the screen can mark them and forget them. */
    fun suggested(context: Context): List<String> = runCatching {
        if (Build.VERSION.SDK_INT < 30) return emptyList()
        context.applicationContext.getSystemService(WifiManager::class.java)?.networkSuggestions
            ?.mapNotNull { it.ssid }.orEmpty()
    }.getOrDefault(emptyList())

    fun forget(context: Context, ssid: String): Boolean = runCatching {
        if (Build.VERSION.SDK_INT < 30) return false
        val wm = context.applicationContext.getSystemService(WifiManager::class.java) ?: return false
        val mine = wm.networkSuggestions.filter { it.ssid == ssid }
        if (mine.isEmpty()) return false
        wm.removeNetworkSuggestions(mine) == WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS
    }.getOrDefault(false)
}
