package com.gios.lightcontrol.portal

import android.content.ContentResolver
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.provider.Settings
import android.webkit.WebView

/**
 * The facts about this phone that decide whether a Wi-Fi login can work at all.
 *
 * Three of them, none of which the screen used to check before trying:
 *
 *  - **Is there a WebView?** It is an installable system component, and a LightOS build that
 *    removed the browser may have removed the thing browsers are made of. Without it the login
 *    page has nothing to draw into, full stop.
 *  - **Has LightOS switched captive-portal detection off?** Android decides whether a network has
 *    a login page in front of it by probing a 204-endpoint at connect time, and a ROM can turn
 *    that off (`captive_portal_mode = 0`) or point it somewhere that never answers. Off, the
 *    system never announces a portal, never offers "sign in to network", and treats a gated
 *    network as ordinary — which is exactly "Wi-Fi connects and then goes nowhere". These are
 *    world-readable `Settings.Global` keys, so the screen can at least say what it found.
 *  - **Which networks exist, and what does the system think of each?** A portal network has the
 *    CAPTIVE_PORTAL bit and lacks VALIDATED; the bound one must be the right one.
 *
 * All read-only, all permission-free, and every read is guarded — a diagnostic that crashes the
 * screen it is diagnosing would be the worst outcome available.
 */
object PortalDiagnostics {

    /** The WebView provider as "package version", or a reason there is none. */
    fun webView(): String = runCatching {
        val p = WebView.getCurrentWebViewPackage()
        if (p == null) "none (getCurrentWebViewPackage returned null)"
        else "${p.packageName} ${p.versionName}"
    }.getOrElse { "unreadable (${it::class.java.simpleName}: ${it.message})" }

    /** True when the WebView package can be named. Cheaper than constructing one. */
    fun hasWebView(): Boolean = runCatching { WebView.getCurrentWebViewPackage() != null }.getOrDefault(false)

    /**
     * The platform's captive-portal settings, as key → value (null when unset, so the platform
     * default applies: mode 1 = prompt, detection on, Google's connectivitycheck host).
     */
    fun captiveSettings(cr: ContentResolver): List<Pair<String, String?>> = CAPTIVE_KEYS.map { key ->
        key to runCatching { Settings.Global.getString(cr, key) }.getOrElse { "unreadable" }
    }

    /**
     * One line for what `captive_portal_mode` means, for the settings screen. The platform's
     * enum: 0 ignore (never detect), 1 prompt (default), 2 avoid (detect and drop the network).
     */
    fun captiveModeLine(cr: ContentResolver): Pair<String, String> {
        val mode = runCatching { Settings.Global.getString(cr, "captive_portal_mode") }.getOrNull()
        val enabled = runCatching { Settings.Global.getString(cr, "captive_portal_detection_enabled") }.getOrNull()
        return when {
            mode == "0" || enabled == "0" ->
                "Off" to "LightOS has told Android not to look for login pages (captive_portal_mode=$mode, " +
                    "detection_enabled=$enabled). The system will never announce one; this screen forces the question anyway."
            mode == "2" ->
                "Avoid" to "Android detects login pages and drops the network instead of offering them (mode 2)"
            mode == null || mode == "1" ->
                "On (default)" to "Android probes each new network for a login page and flags it when it finds one"
            else -> "Unknown ($mode)" to "an unexpected captive_portal_mode value"
        }
    }

    /** Every network the system knows, one line each, most informative first. */
    fun networks(cm: ConnectivityManager): List<String> = runCatching {
        val active = cm.activeNetwork
        @Suppress("DEPRECATION")
        cm.allNetworks.map { n -> describe(cm, n, n == active) }
    }.getOrElse { listOf("allNetworks unreadable: ${it::class.java.simpleName}: ${it.message}") }

    fun describe(cm: ConnectivityManager, n: Network, isDefault: Boolean = false): String = runCatching {
        val caps = cm.getNetworkCapabilities(n)
        val lp = cm.getLinkProperties(n)
        val transports = buildList {
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) add("wifi")
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true) add("cell")
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) add("vpn")
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true) add("eth")
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) == true) add("bt")
        }.ifEmpty { listOf("?") }
        val flags = buildList {
            fun has(c: Int) = caps?.hasCapability(c) == true
            if (has(NetworkCapabilities.NET_CAPABILITY_INTERNET)) add("INTERNET")
            if (has(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) add("VALIDATED")
            if (has(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)) add("CAPTIVE_PORTAL")
            if (has(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) add("not-metered")
            if (has(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)) add("not-suspended")
        }
        buildString {
            append(n.toString())
            if (isDefault) append(" [default]")
            append(" ").append(transports.joinToString("+"))
            append(" {").append(flags.joinToString(", ")).append("}")
            if (lp != null) {
                append(" iface=").append(lp.interfaceName ?: "?")
                append(" dns=").append(lp.dnsServers.joinToString(",") { it.hostAddress ?: "?" }.ifEmpty { "none" })
                lp.httpProxy?.let { append(" proxy=").append(it.host).append(':').append(it.port) }
                if (lp.linkAddresses.isEmpty()) append(" no-address")
            } else {
                append(" no-link-properties")
            }
        }
    }.getOrElse { "$n unreadable: ${it::class.java.simpleName}: ${it.message}" }

    /** The VPN network, if one is up. Its existence is what makes every bind to Wi-Fi fail with EPERM. */
    fun vpn(cm: ConnectivityManager): Network? = runCatching {
        @Suppress("DEPRECATION")
        cm.allNetworks.firstOrNull { n ->
            cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        }
    }.getOrNull()

    /**
     * The always-on VPN package, when one is set — the only name for the VPN a plain app can read.
     * A VPN started by hand leaves this blank, and the owner UID of the VPN network is hidden from
     * ordinary apps since API 30.
     */
    fun alwaysOnVpnApp(cr: ContentResolver): String? = runCatching {
        Settings.Secure.getString(cr, "always_on_vpn_app")
    }.getOrNull()?.takeIf { it.isNotBlank() }

    private val CAPTIVE_KEYS = listOf(
        "captive_portal_mode",
        "captive_portal_detection_enabled",
        "captive_portal_use_https",
        "captive_portal_http_url",
        "captive_portal_https_url",
        "captive_portal_fallback_url",
        "captive_portal_server",
    )
}
