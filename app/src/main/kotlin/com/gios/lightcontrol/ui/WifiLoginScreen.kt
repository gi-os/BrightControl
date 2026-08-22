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
        guide = "Hotel and café Wi-Fi often wants a webpage signed before it lets you through — " +
            "and this phone has no browser to sign it with, so the network connects and then " +
            "goes nowhere. This opens that page, watches the connection, and closes itself the " +
            "moment the network lets you through.",
    ) {
        SectionLabel("THIS NETWORK")
        MenuRow(
            label = state.first,
            detail = "↻",
            sub = state.second,
            onClick = { state = wifiState(context) },
        )
        Rule()

        SectionLabel("SIGN IN")
        MenuRow(
            label = "Open the login page",
            detail = "›",
            sub = "the network's own sign-in page, with the wheel scrolling it",
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
