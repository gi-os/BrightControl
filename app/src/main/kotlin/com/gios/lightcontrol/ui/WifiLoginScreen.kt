package com.gios.lightcontrol.ui

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.gios.lightcontrol.portal.PortalActivity
import com.gios.lightcontrol.portal.PortalDiagnostics

/**
 * The door to [PortalActivity], plus enough of a readout to know whether you need it.
 *
 * The state row answers the question the phone otherwise leaves you guessing at: is this network
 * already through, waiting on a login page, or just quietly not working. It reads the same
 * capability bits the platform sets — [NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL] when a
 * portal announced itself, VALIDATED once real traffic flows — so it agrees with what the system
 * concluded rather than running a probe of its own.
 */
@Composable
fun WifiLoginScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var state by remember { mutableStateOf(wifiState(context)) }

    SectionScaffold(
        title = "Wi-Fi login",
        onBack = onBack,
        guide = "IN DEVELOPMENT. This does not reliably work yet. LightOS Settings never gets a login page through, on purpose, so this screen is the attempt — and since v4.12 it keeps a log of everything it tries. When it fails in a way it can recognise (no WebView, no network, the page never loading, nothing answering) it files that log as a report by itself; the LOG button shows it on the phone and SEND LOG files it by hand. The rows under THIS PHONE say up front whether a login page can be drawn at all.\n\n" +
            "Hotel and café Wi-Fi often wants a webpage signed before it lets you through — " +
            "and this phone has no browser to sign it with, so the network connects and then " +
            "goes nowhere. This opens that page and watches the connection, closing itself once " +
            "the network lets you through.\n\nIt does not join networks. Picking a network and " +
            "typing its password is still LightOS Settings — this is only the webpage some " +
            "networks put in front of the internet afterwards, and on a network without one there " +
            "is nothing here to do.",
    ) {
        SectionLabel("THIS NETWORK")
        MenuRow(
            label = state.first,
            detail = "↻",
            sub = state.second,
            onClick = { state = wifiState(context) },
        )
        Rule()

        SectionLabel("THIS PHONE")
        val webView = remember { PortalDiagnostics.webView() }
        val mode = remember { PortalDiagnostics.captiveModeLine(context.contentResolver) }
        MenuRow(
            label = if (PortalDiagnostics.hasWebView()) "WebView present" else "No WebView",
            sub = if (PortalDiagnostics.hasWebView()) {
                "$webView — the login page has something to draw into"
            } else {
                "$webView — without one the login page cannot be drawn on this phone, whatever the network does"
            },
            dim = true,
        )
        MenuRow(
            label = "Login-page detection: ${mode.first}",
            sub = mode.second,
            dim = true,
        )
        Rule()

        SectionLabel("SIGN IN")
        MenuRow(
            label = "Open the login page",
            detail = "›",
            // The row says what opening it will get you. On an already-validated network the
            // honest answer is "nothing", and the screen used to answer that by opening, probing
            // 204 within a moment, and closing itself again -- which reads as a broken button, or
            // worse, as the app signing you out, since a portal's page for a device it has already
            // admitted is usually its sign-out page.
            sub = if (state.first == "Online") {
                "nothing to sign on this network — opens the network's own page anyway"
            } else {
                "the network's own sign-in page, with the wheel scrolling it"
            },
            onClick = { context.startActivity(Intent(context, PortalActivity::class.java)) },
        )
        MenuRow(
            label = "If the page never loads",
            sub = "a few portals want a full browser. Then: sign in from a computer whose " +
                "Wi-Fi MAC is set to this phone's — the portal remembers devices by MAC, so " +
                "the phone walks through the door the computer opened.",
            dim = true,
        )
    }
}

private fun wifiState(context: Context): Pair<String, String> {
    val cm = context.getSystemService(ConnectivityManager::class.java)
    @Suppress("DEPRECATION")
    val wifi = cm.allNetworks.firstOrNull { n ->
        cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    } ?: return "No Wi-Fi" to
        "join the network in LightOS Settings first, then come back here"
    val caps = cm.getNetworkCapabilities(wifi)
    return when {
        caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL) == true ->
            "Sign-in required" to "the network answered with a login page — open it below"
        caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true ->
            "Online" to "this network already reaches the internet; nothing to sign"
        else ->
            "Connected, not yet online" to "no login page announced itself, which is common — " +
                "open the page below to force the question"
    }
}
