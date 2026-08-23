package com.gios.lightcontrol.hotspot

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager

/**
 * What the phone itself is connected to. Two questions only: are we on a trusted Wi-Fi
 * network (so leave the trigger alone), and does the phone even have a usable uplink to
 * share (no point raising a hotspot with nothing behind it).
 */
class Connectivity(context: Context) {

    private val app = context.applicationContext
    private val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val wifi = app.getSystemService(Context.WIFI_SERVICE) as WifiManager

    /** The SSID of the Wi-Fi the phone is joined to, without the quotes Android wraps it in, or null. */
    fun currentSsid(): String? {
        @Suppress("DEPRECATION")
        val raw = wifi.connectionInfo?.ssid ?: return null
        if (raw.isBlank() || raw == "<unknown ssid>") return null
        return raw.trim('"').ifBlank { null }
    }

    fun onTrustedWifi(trusted: Set<String>): Boolean {
        val ssid = currentSsid() ?: return false
        return ssid in trusted
    }

    /**
     * The phone has a cellular uplink worth sharing. If it is itself on Wi-Fi we do not
     * raise a hotspot from it -- that would be sharing the very network the iPad could
     * join directly -- so "has uplink to share" specifically means a validated cellular
     * transport with no Wi-Fi in the way.
     */
    fun hasCellularToShare(): Boolean {
        val active = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(active) ?: return false
        val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        val cellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
        val onWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        return validated && cellular && !onWifi
    }
}
