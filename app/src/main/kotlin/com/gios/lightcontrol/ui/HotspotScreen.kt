package com.gios.lightcontrol.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.gios.lightcontrol.Prefs
import com.gios.lightcontrol.hotspot.Bt
import com.gios.lightcontrol.hotspot.Connectivity
import com.gios.lightcontrol.hotspot.HotspotService
import com.gios.lightcontrol.hotspot.SoftAp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The hotspot that raises itself when the iPad is near.
 *
 * Folded in from BrightHotspot, which was a good app with one fatal setup step. Raising an access
 * point needs a shell UID; it borrowed one from Shizuku, and Shizuku's way in is a pairing flow
 * Android tears down on every reboot — so keeping it working meant repeating a setup step for
 * ever. This app has held a shell the whole time, by a route that reconnects itself, so here the
 * same feature has nothing to set up beyond saying which device to watch for.
 *
 * What it does: watch for a paired device advertising over BLE, and when it is near and this phone
 * is not on a network you have marked as trusted, guess that it wants a connection and raise the
 * hotspot. The device answers the guess by joining or not — a join confirms it, three minutes of
 * silence refutes it and earns a backoff, so a cafe with good Wi-Fi does not make the phone flap.
 * All of that is in `hotspot/TriggerEngine.kt`, with no Android in it and a test beside it.
 */
@Composable
fun HotspotScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { Prefs(context) }
    val ap = remember { SoftAp(context) }
    val connectivity = remember { Connectivity(context) }
    val bonded = remember { Bt.bonded(context) }

    var auto by remember { mutableStateOf(prefs.hotspotAuto) }
    var ssid by remember { mutableStateOf(prefs.hotspotSsid) }
    var password by remember { mutableStateOf(prefs.hotspotPassword) }
    var triggers by remember { mutableStateOf(prefs.hotspotTriggers) }
    var trusted by remember { mutableStateOf(prefs.hotspotTrustedSsids) }
    var note by remember { mutableStateOf<String?>(null) }
    var apWords by remember { mutableStateOf(ap.apStateWords()) }

    val running by HotspotService.running.collectAsState()
    val clients by HotspotService.clients.collectAsState()
    val lastEvent by HotspotService.lastEvent.collectAsState()

    // The access point can be turned on and off from LightOS too, so the readout follows the
    // phone rather than remembering what this screen last did. A settings screen that disagreed
    // with the thing it configures is worse than no readout.
    LaunchedEffect(running, clients) { apWords = ap.apStateWords() }

    SectionScaffold(
        title = "Hotspot",
        onBack = onBack,
        guide = "Watches for a device you have paired in Bluetooth settings — an iPad, a laptop — " +
            "and raises this phone's hotspot when it is near and you are not on a network you " +
            "trust. It joins a network it already knows, so there is nothing to tap on the iPad " +
            "and nothing installed on it.\n\nThe access point is raised over this app's own adb " +
            "shell. That is why there is no Shizuku here and nothing to redo after a reboot.",
    ) {
        SectionLabel("AUTO")
        MenuRow(
            label = "Watch for the device",
            detail = if (auto) "ON" else "OFF",
            sub = if (running) {
                lastEvent.ifBlank { "watching" }
            } else {
                "off — the hotspot is yours to raise by hand"
            },
            onClick = {
                auto = !auto
                prefs.hotspotAuto = auto
                if (auto) HotspotService.start(context) else HotspotService.stop(context)
            },
        )
        MenuRow(
            label = "Access point",
            detail = apWords.uppercase(),
            sub = when (clients) {
                SoftAp.UNKNOWN -> "clients not readable — needs the adb connection"
                0 -> "nobody joined"
                1 -> "sharing with 1"
                else -> "sharing with $clients"
            },
        )
        Rule()

        SectionLabel("THE NETWORK IT RAISES")
        GuideText(
            "This has to match the hotspot the iPad already knows, or it will see a network it " +
                "has never met and stay off it. READ FROM PHONE tries to fill both in from the " +
                "saved configuration; some builds will not give up the password, and then they " +
                "are two things to type once.",
        )
        AdbField("network name", ssid, KeyboardType.Text) {
            ssid = it
            prefs.hotspotSsid = it
        }
        AdbField("password (blank for open)", password, KeyboardType.Text) {
            password = it
            prefs.hotspotPassword = it
        }
        BigButton(
            label = "READ FROM PHONE",
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        ) {
            note = "reading…"
            scope.launch {
                val (s, p) = withContext(Dispatchers.IO) { ap.readSaved() }
                if (s != null) { ssid = s; prefs.hotspotSsid = s }
                if (p != null) { password = p; prefs.hotspotPassword = p }
                note = when {
                    s != null && p != null -> "Read both."
                    s != null -> "Read the name. This build will not print the password."
                    else -> "Nothing readable — type them in above."
                }
            }
        }
        note?.let { GuideText(it) }
        Rule()

        SectionLabel("WATCH FOR")
        GuideText(
            if (bonded.isEmpty()) {
                "Nothing is paired. Pair the iPad in LightOS's Bluetooth settings first — the " +
                    "pairing is what lets this phone recognise it later, because an iPad " +
                    "advertises under an address that rotates every few minutes and only a " +
                    "paired phone holds the key that resolves it back."
            } else {
                "Devices paired with this phone. Turn on the one whose arrival should raise the " +
                    "hotspot."
            },
        )
        bonded.forEach { device ->
            MenuRow(
                label = device.name,
                detail = if (device.address in triggers) "ON" else "OFF",
                sub = device.address,
                onClick = {
                    prefs.toggleHotspotTrigger(device.address)
                    triggers = prefs.hotspotTriggers
                },
            )
        }
        Rule()

        SectionLabel("LEAVE IT ALONE HERE")
        GuideText(
            "Networks where everything already has internet, so the guess would always be wrong. " +
                "Your home Wi-Fi is the one that matters.",
        )
        val here = connectivity.currentSsid()
        if (here != null) {
            MenuRow(
                label = here,
                detail = if (here in trusted) "TRUSTED" else "ADD",
                sub = "the network this phone is on now",
                onClick = {
                    prefs.toggleHotspotTrusted(here)
                    trusted = prefs.hotspotTrustedSsids
                },
            )
        }
        trusted.filter { it != here }.sorted().forEach { name ->
            MenuRow(
                label = name,
                detail = "REMOVE",
                onClick = {
                    prefs.toggleHotspotTrusted(name)
                    trusted = prefs.hotspotTrustedSsids
                },
            )
        }
        Rule()

        SectionLabel("BY HAND")
        BigButton(
            label = if (apWords == "on") "STOP HOTSPOT" else "START HOTSPOT NOW",
            filled = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        ) {
            scope.launch {
                val out = withContext(Dispatchers.IO) {
                    if (ap.apEnabled()) ap.stop() else ap.start(ssid, password)
                }
                apWords = ap.apStateWords()
                // **Says what the phone said.** Going through Shizuku, this could only ever
                // report "not ready", and the commonest failure — the access point simply never
                // coming up — was indistinguishable from nothing having been tried.
                note = if (out.ok) "Done." else out.said
            }
        }
        GuideText(
            "Skips the whole guess. Needs the adb connection, same as auto mode — if this says " +
                "there is none, the ADB screen is where to make one.",
        )
    }
}
