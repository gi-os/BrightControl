package com.gios.lightcontrol.portal

/**
 * Which address the login page is at, when the obvious one cannot be reached.
 *
 * Pure string and integer work, unit-tested, deliberately holding no Android type: the callers in
 * [PortalActivity] read `LinkProperties` and hand the answers in as text. The decisions here are
 * the ones that were wrong on real phones, and they should be checkable without one.
 */
object PortalRoute {

    /** Google's 204 endpoint, the same one Android's own probe uses. Needs DNS. */
    const val PROBE_URL = "http://connectivitycheck.gstatic.com/generate_204"

    /**
     * `ConnectivityManager.EXTRA_CAPTIVE_PORTAL_URL`, which is `@SystemApi` and so not a constant
     * this app may name.
     *
     * The string is stable -- it is what the platform's own `CaptivePortalLogin` reads out of the
     * very intent that launched *this* activity -- and reading an extra by name is not reaching
     * past the SDK for an implementation. It is the URL the system probed and was redirected from,
     * which is a better first guess at the portal than a hostname of our choosing: the system got
     * an answer at it a moment ago.
     */
    const val EXTRA_PORTAL_URL = "android.net.extra.CAPTIVE_PORTAL_URL"

    /** WebView's `ERROR_HOST_LOOKUP`. Named here so the DNS branch reads as one. */
    const val ERROR_HOST_LOOKUP = -2

    /**
     * Where to start.
     *
     * The system's URL when it gave us one, ours when it did not. Checked rather than trusted: an
     * extra is whatever the sender put there, and a `loadUrl` of `javascript:` or `file:` out of an
     * intent this activity exports is a hole, not a fallback.
     */
    fun startUrl(fromSystem: String?): String {
        val url = fromSystem?.trim().orEmpty()
        val http = url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)
        return if (http && !url.contains(' ')) url else PROBE_URL
    }

    /**
     * The address to try when a hostname will not resolve.
     *
     * light-reports #286 and #287: the bind worked, the network was flagged CAPTIVE_PORTAL, and
     * every name died with `ERR_NAME_NOT_RESOLVED` after eight and a half seconds. A portal that
     * answers DNS only for its own names -- or not at all until a device is admitted -- is common
     * in cheap hotel gear, and it makes every hostname on the phone useless, including the one
     * this screen loads. The portal itself is still there, at an address, and the phone already
     * knows three candidates for it.
     *
     * In order: the DHCP server, which handed out the lease and is the portal on almost every
     * network of this kind; the default route's gateway; then any resolver the network gave us
     * that is a private address, because on that network the resolver *is* the gateway
     * (`dns=192.168.44.1` in both reports). A public resolver is never tried -- 8.8.8.8 has no
     * login page and asking it for one is a five-second wait for nothing.
     */
    fun gatewayUrl(dhcpServer: String?, defaultGateway: String?, dnsServers: List<String>): String? {
        val candidates = buildList {
            dhcpServer?.let(::add)
            defaultGateway?.let(::add)
            addAll(dnsServers.filter { isPrivate(it) })
        }
        val pick = candidates.map { it.trim().trimStart('/') }
            .firstOrNull { it.isNotBlank() && it != "0.0.0.0" && it != "::" }
            ?: return null
        // A bare IPv6 literal in a URL has to be bracketed or it parses as host:port.
        val host = if (pick.contains(':') && !pick.startsWith("[")) "[$pick]" else pick
        return "http://$host/"
    }

    /**
     * Whether an address is one of the network's own rather than somewhere on the internet.
     *
     * RFC 1918 and the link-local range, by text. A private resolver on a captive network is the
     * gateway; a public one is Google's, and no amount of asking it will produce a login page.
     */
    fun isPrivate(address: String): Boolean {
        val a = address.trim().trimStart('/')
        if (a.startsWith("10.") || a.startsWith("192.168.") || a.startsWith("169.254.")) return true
        if (a.startsWith("fe80:") || a.startsWith("fd") || a.startsWith("fc")) return true
        val octets = a.split('.')
        if (octets.size == 4 && octets[0] == "172") {
            val second = octets[1].toIntOrNull() ?: return false
            return second in 16..31
        }
        return false
    }

    /** Whether a thrown probe was DNS rather than the network being dead. */
    fun isDnsThrow(exception: String?, message: String?): Boolean =
        exception?.contains("UnknownHost", ignoreCase = true) == true ||
            message?.contains("Unable to resolve host", ignoreCase = true) == true
}
