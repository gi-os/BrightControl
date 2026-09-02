package com.gios.lightcontrol.ui

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.gios.lightcontrol.adb.AdbManager
import com.gios.lightcontrol.portal.PortalActivity
import com.gios.lightcontrol.portal.PortalDiagnostics
import com.gios.lightcontrol.wifi.WifiShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Wi-Fi: the networks LightOS Settings refuses, and the login pages it has no browser for.
 *
 * Settings on this phone answers an open network with *"This network is not supported by The
 * Light Phone."* — a rule in Light's Settings app, not in the radio. This screen goes around it:
 * it scans and joins over the phone's own shell (`cmd wifi`, see [WifiShell]), which this app
 * already holds for its grants, and once a network is joined it watches whether the system
 * validates it. A network that connects and does not validate has a login page in front of it,
 * and the [PortalActivity] opens on its own to show it.
 *
 * Everything that talks to the shell runs off the main thread and reports its words back into
 * `note`, one line at a time, because a ten-second join with nothing on screen is a broken button.
 */
@Composable
fun WifiScreen(onBack: () -> Unit, onAdb: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var state by remember { mutableStateOf(wifiState(context)) }
    var paired by remember { mutableStateOf(AdbManager.hasPairing(context)) }
    var status by remember { mutableStateOf<WifiShell.Status?>(null) }
    var nearby by remember { mutableStateOf<List<WifiShell.Seen>?>(null) }
    var saved by remember { mutableStateOf<List<WifiShell.Saved>>(emptyList()) }
    var busy by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var asking by remember { mutableStateOf<WifiShell.Seen?>(null) }
    var password by remember { mutableStateOf("") }

    fun refreshLocal() { state = wifiState(context) }

    suspend fun readShell() {
        status = WifiShell.status(context).getOrNull()
        saved = WifiShell.saved(context).getOrDefault(emptyList())
    }

    fun scan() {
        if (busy != null) return
        busy = "scanning…"
        scope.launch {
            withContext(Dispatchers.IO) {
                readShell()
                WifiShell.scan(context)
                    .onSuccess { nearby = it; note = if (it.isEmpty()) "Nothing heard. Is Wi-Fi on?" else null }
                    .onFailure { note = "Scan failed: ${it.message}" }
            }
            refreshLocal()
            busy = null
        }
    }

    /**
     * Join, then wait for the system's verdict on the network. The verdict is what decides the
     * next step: VALIDATED is done; CAPTIVE_PORTAL, or connected-but-unvalidated after a fair
     * wait, is a login page — and that is what the portal screen is for, so it opens itself.
     */
    fun join(seen: WifiShell.Seen, passphrase: String?) {
        if (busy != null) return
        busy = "joining ${seen.ssid}…"
        note = null
        scope.launch {
            val out = withContext(Dispatchers.IO) { WifiShell.connect(context, seen.ssid, seen.security, passphrase) }
            if (out.startsWith("error:")) {
                note = "Could not join: ${out.removePrefix("error: ")}"
                busy = null
                return@launch
            }
            // `Connection initiated` is the shell's whole answer; whether it worked shows up in
            // `cmd wifi status` a few seconds later, and in the network's capability bits after.
            var connected = false
            for (i in 0 until 10) {
                delay(2_000)
                busy = "joining ${seen.ssid}… (${(i + 1) * 2}s)"
                val s = withContext(Dispatchers.IO) { WifiShell.status(context).getOrNull() }
                status = s
                if (s?.connectedTo == seen.ssid) { connected = true; break }
            }
            if (!connected) {
                note = "Joined nothing after 20s. Shell said: ${out.trim().take(200).ifBlank { "nothing" }}. " +
                    if (seen.security.needsPassword) "A wrong password looks exactly like this." else ""
                refreshLocal()
                busy = null
                withContext(Dispatchers.IO) { readShell() }
                return@launch
            }
            // Connected. Now: does the internet come through, or is there a page in the way?
            var verdict: String? = null
            for (i in 0 until 6) {
                delay(2_000)
                busy = "connected — checking for a login page… (${(i + 1) * 2}s)"
                val (label, _) = wifiState(context)
                if (label == "Online") { verdict = "online"; break }
                if (label == "Sign-in required") { verdict = "portal"; break }
            }
            refreshLocal()
            busy = null
            withContext(Dispatchers.IO) { readShell() }
            when (verdict) {
                "online" -> note = "Joined ${seen.ssid} — online."
                else -> {
                    note = "Joined ${seen.ssid}, but the internet is not coming through — opening its login page."
                    context.startActivity(Intent(context, PortalActivity::class.java))
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        paired = AdbManager.hasPairing(context)
        if (paired) scan()
    }

    SectionScaffold(
        title = "Wi-Fi",
        onBack = onBack,
        guide = "LightOS Settings answers open networks with \"not supported by The Light Phone\". That is " +
            "a rule in Light's Settings app, not in the radio: this screen joins networks over the " +
            "phone's own shell instead, the same shell this app uses for its grants. Once joined, it " +
            "watches whether the internet comes through; a network that connects but does not is a " +
            "hotel or café with a login page in front of it, and that page opens here on its own.",
    ) {
        SectionLabel("THIS NETWORK")
        MenuRow(
            label = status?.connectedTo?.let { "$it — ${state.first}" } ?: state.first,
            detail = "↻",
            sub = state.second,
            onClick = { refreshLocal(); if (paired && busy == null) scope.launch { withContext(Dispatchers.IO) { readShell() } } },
        )
        if (state.first != "Online" && state.first != "No Wi-Fi") {
            MenuRow(
                label = "Open the login page",
                detail = "›",
                sub = "the network's own sign-in page, with the wheel scrolling it",
                onClick = { context.startActivity(Intent(context, PortalActivity::class.java)) },
            )
        }
        Rule()

        if (!paired) {
            SectionLabel("NETWORKS")
            MenuRow(
                label = "Set up the shell first",
                detail = "›",
                sub = "joining networks runs over this app's own wireless debugging connection, which " +
                    "is not paired yet. ADB & grants sets it up once.",
                onClick = onAdb,
            )
            Rule()
        } else {
            SectionLabel("NETWORKS NEARBY")
            busy?.let { GuideText(it) }
            note?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            if (status?.enabled == false) {
                MenuRow(
                    label = "Wi-Fi is off",
                    detail = "TURN ON",
                    sub = "the radio is switched off; nothing can be heard until it is on",
                    onClick = {
                        if (busy == null) scope.launch {
                            busy = "turning Wi-Fi on…"
                            withContext(Dispatchers.IO) { WifiShell.setEnabled(context, true) }
                            delay(2_000)
                            busy = null
                            scan()
                        }
                    },
                )
            }
            val list = nearby
            when {
                list == null && busy == null -> MenuRow(label = "Scan", detail = "›", sub = "listen for networks", onClick = { scan() })
                list != null && list.isEmpty() && busy == null -> MenuRow(label = "Scan again", detail = "↻", onClick = { scan() })
                list != null -> {
                    list.forEach { seen ->
                        val savedHere = saved.any { it.ssid == seen.ssid }
                        val here = status?.connectedTo == seen.ssid
                        val cannot = seen.security.keyword == null
                        MenuRow(
                            label = seen.ssid,
                            detail = when {
                                here -> "JOINED"
                                cannot -> "—"
                                else -> "›"
                            },
                            sub = buildString {
                                append(bars(seen.bars)).append("  ").append(seen.band).append(" · ").append(seen.security.label)
                                if (savedHere && !here) append(" · saved")
                                if (cannot) append(" · needs a username and certificate, which the shell cannot supply")
                            },
                            dim = cannot,
                            onClick = if (cannot || here || busy != null) null else {
                                {
                                    if (seen.security.needsPassword && !savedHere) {
                                        asking = seen; password = ""
                                    } else {
                                        join(seen, null)
                                    }
                                }
                            },
                        )
                        if (asking?.ssid == seen.ssid) {
                            AdbField(
                                label = "PASSWORD FOR ${seen.ssid.uppercase()}",
                                value = password,
                                keyboard = KeyboardType.Password,
                                onChange = { password = it },
                            )
                            BigButton(
                                label = "JOIN",
                                enabled = password.length >= 8 && busy == null,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                            ) {
                                val s = asking ?: return@BigButton
                                asking = null
                                join(s, password)
                                password = ""
                            }
                        }
                    }
                    if (busy == null) MenuRow(label = "Scan again", detail = "↻", onClick = { scan() })
                }
            }
            Rule()

            if (saved.isNotEmpty()) {
                SectionLabel("SAVED ON THIS PHONE")
                saved.forEach { s ->
                    MenuRow(
                        label = s.ssid.ifBlank { "(hidden)" },
                        detail = "FORGET",
                        sub = "${s.security} · network id ${s.id}",
                        dim = true,
                        onClick = {
                            if (busy == null) scope.launch {
                                busy = "forgetting ${s.ssid}…"
                                val out = withContext(Dispatchers.IO) { WifiShell.forget(context, s.id) }
                                note = if (out.startsWith("error:")) "Could not forget: ${out.removePrefix("error: ")}" else "Forgot ${s.ssid}."
                                withContext(Dispatchers.IO) { readShell() }
                                refreshLocal()
                                busy = null
                            }
                        },
                    )
                }
                Rule()
            }
        }

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
        MenuRow(label = "Login-page detection: ${mode.first}", sub = mode.second, dim = true)
        Rule()

        SectionLabel("IF THE LOGIN PAGE NEVER LOADS")
        MenuRow(
            label = "The page keeps a log",
            sub = "LOG shows it on the phone and SEND LOG files it. Failures it can recognise — no " +
                "WebView, nothing answering, a page that never comes — are filed by themselves.",
            dim = true,
        )
        MenuRow(
            label = "Or sign in from a computer",
            sub = "set a laptop's Wi-Fi MAC to this phone's and sign in there — the portal remembers " +
                "devices by MAC, so the phone walks through the door the computer opened.",
            dim = true,
        )
    }
}

private fun bars(n: Int): String = "▂▄▆█".mapIndexed { i, c -> if (i < n) c else '·' }.joinToString("")

private fun wifiState(context: Context): Pair<String, String> {
    val cm = context.getSystemService(ConnectivityManager::class.java)
    @Suppress("DEPRECATION")
    val wifi = cm.allNetworks.firstOrNull { n ->
        cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    } ?: return "No Wi-Fi" to "not on any Wi-Fi network — pick one below"
    val caps = cm.getNetworkCapabilities(wifi)
    return when {
        caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL) == true ->
            "Sign-in required" to "the network answered with a login page — open it below"
        caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true ->
            "Online" to "this network reaches the internet"
        else ->
            "Connected, not yet online" to "no login page announced itself, which is common — " +
                "open the page below to force the question"
    }
}
