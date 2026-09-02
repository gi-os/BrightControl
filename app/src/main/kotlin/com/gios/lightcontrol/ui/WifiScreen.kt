package com.gios.lightcontrol.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import com.gios.lightcontrol.adb.AdbWifi
import com.gios.lightcontrol.lock.LockNotes
import com.gios.lightcontrol.portal.PortalActivity
import com.gios.lightcontrol.portal.PortalDiagnostics
import com.gios.lightcontrol.wifi.WifiShell
import com.gios.lightcontrol.wifi.WifiSuggest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Wi-Fi: the networks LightOS Settings refuses, and the login pages it has no browser for.
 *
 * Settings on this phone answers an open network with *"This network is not supported by The
 * Light Phone."* — a rule in Light's Settings app, not in the radio. This screen goes around it,
 * by one of two routes, chosen by whether the phone is already on Wi-Fi:
 *
 *  - **Off Wi-Fi (the usual case): suggest.** Wireless debugging — the shell this app holds — only
 *    runs while the phone is on a Wi-Fi network, so it can never join the first one. Instead the
 *    network is handed to the system as a [android.net.wifi.WifiNetworkSuggestion] and the
 *    system joins it on its next scan. See [WifiSuggest] for the approval this needs.
 *  - **On Wi-Fi, shell up: `cmd wifi`.** Immediate, and the only route that can save a network
 *    for later or forget one. See [WifiShell].
 *
 * Either way, once a network is joined the screen watches whether the system validates it. One
 * that connects and does not has a login page in front of it, and the [PortalActivity] opens on
 * its own to show it. Everything slow runs off the main thread and narrates into `busy`/`note`.
 */
@Composable
fun WifiScreen(onBack: () -> Unit, onAdb: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var state by remember { mutableStateOf(wifiState(context)) }
    var paired by remember { mutableStateOf(AdbManager.hasPairing(context)) }
    /** Whether the shell answered on this screen's last try. Null until tried. */
    var shellUp by remember { mutableStateOf<Boolean?>(null) }
    var approved by remember { mutableStateOf<Boolean?>(null) }
    var status by remember { mutableStateOf<WifiShell.Status?>(null) }
    var nearby by remember { mutableStateOf<List<WifiShell.Seen>?>(null) }
    var saved by remember { mutableStateOf<List<WifiShell.Saved>>(emptyList()) }
    var suggested by remember { mutableStateOf(WifiSuggest.suggested(context)) }
    var busy by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var needLocation by remember { mutableStateOf(false) }
    var locationOff by remember { mutableStateOf(false) }
    var allowPending by remember { mutableStateOf(false) }
    var asking by remember { mutableStateOf<WifiShell.Seen?>(null) }
    var password by remember { mutableStateOf("") }

    val askLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        needLocation = !ok
    }

    fun refreshLocal() {
        state = wifiState(context)
        suggested = WifiSuggest.suggested(context)
        allowPending = LockNotes.approvalAction(context) != null
    }

    /**
     * Whether the shell can be reached right now, and what it knows if so. Cheap when the answer
     * is no: with no Wi-Fi, or wireless debugging off, it is not even attempted.
     */
    suspend fun probeShell() {
        val possible = paired && state.first != "No Wi-Fi" && AdbWifi.on(context) != false
        if (!possible) { shellUp = false; return }
        val alive = withContext(Dispatchers.IO) { AdbManager.ensureAlive(context, 6_000) }
        shellUp = alive
        if (!alive) return
        withContext(Dispatchers.IO) {
            status = WifiShell.status(context).getOrNull()
            saved = WifiShell.saved(context).getOrDefault(emptyList())
            approved = AdbManager.runVia(context, WifiSuggest.APPROVED_QUERY, 8_000)
                .takeUnless { it.startsWith("error:") }?.contains("yes", ignoreCase = true)
        }
    }

    fun scan() {
        if (busy != null) return
        busy = "scanning…"
        note = null
        scope.launch {
            refreshLocal()
            probeShell()
            if (shellUp == true) {
                withContext(Dispatchers.IO) { WifiShell.scan(context) }
                    .onSuccess { nearby = it; if (it.isEmpty()) note = "Nothing heard." }
                    .onFailure { note = "Scan over the shell failed: ${it.message}" }
            } else {
                when (val r = withContext(Dispatchers.IO) { WifiSuggest.scan(context) }) {
                    is WifiSuggest.Scan.Heard -> {
                        nearby = r.networks
                        needLocation = false; locationOff = false
                        if (r.networks.isEmpty()) note = "Nothing heard. Android hands out scan results a few " +
                            "seconds after a scan; try again."
                    }
                    WifiSuggest.Scan.NoLocationPermission -> { needLocation = true; nearby = emptyList() }
                    WifiSuggest.Scan.LocationOff -> { locationOff = true; nearby = emptyList() }
                    WifiSuggest.Scan.WifiOff -> { nearby = emptyList(); note = "Wi-Fi is switched off." }
                    is WifiSuggest.Scan.Failed -> { nearby = emptyList(); note = "Scan failed: ${r.why}" }
                }
            }
            refreshLocal()
            busy = null
        }
    }

    /** After a join by either route: wait for the network, then for the system's verdict. */
    suspend fun watch(ssid: String, joinedBy: String) {
        var connected = false
        for (i in 0 until 15) {
            delay(2_000)
            busy = "joining $ssid ($joinedBy)… (${(i + 1) * 2}s)"
            state = wifiState(context)
            if (state.first != "No Wi-Fi") { connected = true; break }
            // The system's approval question may have gone up in the meantime.
            if (i == 3) allowPending = LockNotes.approvalAction(context) != null
            if (allowPending) break
        }
        if (!connected) {
            note = if (allowPending) {
                "Android is asking whether this app may suggest networks. Tap ALLOW below, then try again."
            } else {
                "Nothing joined after 30s. " + when (joinedBy) {
                    "suggestion" -> "The system joins a suggested network on its own schedule and only if it " +
                        "has approved this app's suggestions — see APPROVAL below. A wrong password looks like this too."
                    else -> "A wrong password looks exactly like this."
                }
            }
            return
        }
        var verdict: String? = null
        for (i in 0 until 6) {
            delay(2_000)
            busy = "connected — checking for a login page… (${(i + 1) * 2}s)"
            state = wifiState(context)
            if (state.first == "Online") { verdict = "online"; break }
            if (state.first == "Sign-in required") { verdict = "portal"; break }
        }
        when (verdict) {
            "online" -> note = "Joined $ssid — online."
            else -> {
                note = "Joined $ssid, but the internet is not coming through — opening its login page."
                context.startActivity(Intent(context, PortalActivity::class.java))
            }
        }
    }

    fun join(seen: WifiShell.Seen, passphrase: String?) {
        if (busy != null) return
        note = null
        busy = "joining ${seen.ssid}…"
        scope.launch {
            val viaShell = shellUp == true
            val out = withContext(Dispatchers.IO) {
                if (viaShell) WifiShell.connect(context, seen.ssid, seen.security, passphrase)
                else WifiSuggest.suggest(context, seen.ssid, seen.security, passphrase)
            }
            if (out.startsWith("error:")) {
                note = "Could not join: ${out.removePrefix("error: ")}"
                busy = null
                refreshLocal()
                return@launch
            }
            watch(seen.ssid, if (viaShell) "shell" else "suggestion")
            refreshLocal()
            probeShell()
            busy = null
        }
    }

    LaunchedEffect(Unit) {
        paired = AdbManager.hasPairing(context)
        scan()
    }

    SectionScaffold(
        title = "Wi-Fi",
        onBack = onBack,
        guide = "LightOS Settings answers open networks with \"not supported by The Light Phone\". That is " +
            "a rule in Light's Settings app, not in the radio, so this screen goes around it. Off Wi-Fi, " +
            "it hands the network to Android as a suggestion and Android joins it — once Android has " +
            "been told, one time, that this app may suggest networks. On Wi-Fi, it uses the phone's own " +
            "shell, which is faster and can also save or forget networks. Either way, a network that " +
            "connects but does not reach the internet has a login page in front of it, and that page " +
            "opens here on its own.",
    ) {
        SectionLabel("THIS NETWORK")
        MenuRow(
            label = status?.connectedTo?.let { "$it — ${state.first}" } ?: state.first,
            detail = "↻",
            sub = state.second,
            onClick = { if (busy == null) scope.launch { refreshLocal(); probeShell() } },
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
        if (allowPending) {
            BigButton(
                label = "ALLOW — ANSWER ANDROID'S QUESTION",
                filled = true,
                enabled = busy == null,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            ) {
                val press = LockNotes.approvalAction(context)
                val ok = press?.invoke() == true
                note = if (ok) "Allowed. Pick the network again." else "The question is no longer up."
                allowPending = false
                refreshLocal()
            }
        }
        if (needLocation) {
            MenuRow(
                label = "Scanning needs location",
                detail = "GRANT",
                sub = "Android only hands out nearby network names to apps holding fine location. " +
                    "ADB & grants gives it silently; this asks the normal way.",
                onClick = { askLocation.launch(Manifest.permission.ACCESS_FINE_LOCATION) },
            )
        }
        if (locationOff) {
            MenuRow(
                label = "Location is switched off",
                detail = "›",
                sub = "the permission is not enough on its own; the phone's location toggle has to be on " +
                    "for any app to hear networks",
                onClick = {
                    runCatching { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
                        .onFailure { note = "This phone has no location settings screen to open." }
                },
            )
        }
        if (!WifiSuggest.wifiOn(context) && busy == null) {
            MenuRow(
                label = "Wi-Fi is off",
                detail = "TURN ON",
                sub = if (shellUp == true) "over the shell" else "opens the system's Wi-Fi panel, if this phone has one",
                onClick = {
                    if (shellUp == true) scope.launch {
                        busy = "turning Wi-Fi on…"
                        withContext(Dispatchers.IO) { WifiShell.setEnabled(context, true) }
                        delay(2_000); busy = null; scan()
                    } else {
                        runCatching { context.startActivity(Intent(Settings.Panel.ACTION_WIFI)) }
                            .onFailure { note = "No Wi-Fi panel on this phone. Turn Wi-Fi on in LightOS Settings, then scan." }
                    }
                },
            )
        }
        val list = nearby
        when {
            list == null && busy == null -> MenuRow(label = "Scan", detail = "›", sub = "listen for networks", onClick = { scan() })
            list != null -> {
                list.forEach { seen ->
                    val savedHere = saved.any { it.ssid == seen.ssid } || seen.ssid in suggested
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
                            if (savedHere && !here) append(" · known")
                            if (cannot) append(" · needs a username and certificate, which this cannot supply")
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

        SectionLabel("APPROVAL")
        MenuRow(
            label = when (approved) {
                true -> "Android accepts this app's networks"
                false -> "Android has not approved this app's networks yet"
                null -> if (shellUp == true) "Approval unknown" else "Approval — checked when on Wi-Fi"
            },
            detail = if (approved == false && shellUp == true) "APPROVE" else null,
            sub = when {
                approved == true -> "suggested networks will be joined; nothing more to do"
                shellUp == true -> "one shell command, and every network this screen suggests from now on is joined"
                else -> "off Wi-Fi the shell cannot be reached to ask. Either answer Android's ALLOW when it " +
                    "appears above, or run ADB & grants once while on any Wi-Fi — it is one of the steps now."
            },
            dim = approved == true,
            onClick = if (approved == false && shellUp == true && busy == null) {
                {
                    scope.launch {
                        busy = "approving…"
                        val out = withContext(Dispatchers.IO) { AdbManager.runVia(context, WifiSuggest.APPROVE_COMMAND, 8_000) }
                        note = if (out.startsWith("error:")) "Could not approve: ${out.removePrefix("error: ")}" else null
                        probeShell()
                        busy = null
                    }
                }
            } else null,
        )
        if (!paired) {
            MenuRow(
                label = "Set up the shell",
                detail = "›",
                sub = "ADB & grants pairs this app with the phone's own wireless debugging once; it is what " +
                    "approves suggestions and what joins networks instantly while on Wi-Fi",
                onClick = onAdb,
            )
        }
        Rule()

        if (saved.isNotEmpty() || suggested.isNotEmpty()) {
            SectionLabel("KNOWN TO THIS PHONE")
            saved.forEach { s ->
                MenuRow(
                    label = s.ssid.ifBlank { "(hidden)" },
                    detail = if (shellUp == true) "FORGET" else null,
                    sub = "${s.security} · saved by the phone (id ${s.id})",
                    dim = true,
                    onClick = if (shellUp == true && busy == null) {
                        {
                            scope.launch {
                                busy = "forgetting ${s.ssid}…"
                                val out = withContext(Dispatchers.IO) { WifiShell.forget(context, s.id) }
                                note = if (out.startsWith("error:")) "Could not forget: ${out.removePrefix("error: ")}" else "Forgot ${s.ssid}."
                                probeShell(); refreshLocal(); busy = null
                            }
                        }
                    } else null,
                )
            }
            suggested.filter { ssid -> saved.none { it.ssid == ssid } }.forEach { ssid ->
                MenuRow(
                    label = ssid,
                    detail = "FORGET",
                    sub = "suggested by this app",
                    dim = true,
                    onClick = if (busy == null) {
                        {
                            note = if (WifiSuggest.forget(context, ssid)) "Forgot $ssid." else "Could not forget $ssid."
                            refreshLocal()
                        }
                    } else null,
                )
            }
            Rule()
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
        MenuRow(
            label = "Shell: " + when (shellUp) {
                true -> "reachable"
                false -> if (state.first == "No Wi-Fi") "unreachable off Wi-Fi (by design)" else "unreachable"
                null -> "not tried yet"
            },
            sub = "wireless debugging only runs while the phone is on a Wi-Fi network, so the shell can " +
                "never join the first one — that is what suggestions are for",
            dim = true,
        )
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
