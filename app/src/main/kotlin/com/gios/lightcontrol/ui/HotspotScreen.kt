package com.gios.lightcontrol.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import com.gios.lightcontrol.hotspot.BleScanner
import com.gios.lightcontrol.hotspot.Bt
import com.gios.lightcontrol.hotspot.Connectivity
import com.gios.lightcontrol.hotspot.HotspotService
import com.gios.lightcontrol.hotspot.SoftAp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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

    /**
     * **The scan needs asking for, and forgetting to ask is silent.** A BLE scan with no
     * permission does not throw — it returns nothing, for ever, which is indistinguishable from
     * an iPad that is not there. Android also insists on a location permission for it: a scan is
     * a location signal as far as the platform is concerned, whatever the app means by it.
     */
    var scanAllowed by remember {
        mutableStateOf(
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val askScan = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        scanAllowed = granted[Manifest.permission.BLUETOOTH_SCAN] == true
    }

    // Heard in the last window, so the screen can say whether the iPad is being resolved at all.
    var heard by remember { mutableStateOf<Set<String>>(emptySet()) }

    val running by HotspotService.running.collectAsState()
    val clients by HotspotService.clients.collectAsState()
    val lastEvent by HotspotService.lastEvent.collectAsState()
    val noUplink by HotspotService.noUplink.collectAsState()

    // The access point can be turned on and off from LightOS too, so the readout follows the
    // phone rather than remembering what this screen last did. A settings screen that disagreed
    // with the thing it configures is worse than no readout.
    LaunchedEffect(running, clients) { apWords = ap.apStateWords() }

    /**
     * A scan of our own while this screen is open.
     *
     * Separate from the service's, and running whether or not auto mode is on, because the
     * question this screen has to answer is "can this phone even hear the iPad" — and that has to
     * be answerable *before* you switch anything on. Stopped on the way out: a BLE scan left
     * running behind a closed settings screen is a battery bug nobody would ever connect back to
     * having looked at a list.
     */
    LaunchedEffect(scanAllowed) {
        if (!scanAllowed) return@LaunchedEffect
        val scanner = BleScanner(context)
        scanner.start()
        try {
            while (true) {
                heard = scanner.recentlySeen(HEARD_WINDOW_MS)
                delay(2_000)
            }
        } finally {
            scanner.stop()
        }
    }

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
            detail = when {
                !scanAllowed -> "ALLOW"
                auto -> "ON"
                else -> "OFF"
            },
            sub = when {
                // The refusal that would otherwise look like the scan not working. Above
                // lastEvent on purpose: it is the answer to the question you are asking while
                // you stare at a hotspot that has not come up.
                !scanAllowed ->
                    "needs the Bluetooth scan first — tap to allow it"
                running && noUplink ->
                    "the iPad is here, but this phone has nothing to share — no data, or the " +
                        "carrier does not allow tethering on this SIM"
                running -> lastEvent.ifBlank { "watching" }
                else -> "off — the hotspot is yours to raise by hand"
            },
            onClick = {
                // **Asks before it starts, because starting without the permission crashed.**
                // A `connectedDevice` foreground service has to hold the Bluetooth permissions
                // when startForeground runs or Android 14 throws out of a system callback. So
                // the switch cannot be the thing that discovers the permission is missing.
                if (!scanAllowed) {
                    askScan.launch(
                        arrayOf(
                            Manifest.permission.BLUETOOTH_SCAN,
                            Manifest.permission.BLUETOOTH_CONNECT,
                            Manifest.permission.ACCESS_FINE_LOCATION,
                        ),
                    )
                    return@MenuRow
                }
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
        // **There is no "add" here, and there cannot be.** This list is Android's own paired-device
        // list. A device has to be paired through LightOS's Bluetooth settings, and the pairing is
        // not a formality: an iPad advertises under an address that rotates every few minutes
        // precisely so it cannot be followed, and the only thing that can turn that back into "this
        // is my iPad" is the identity key the two exchanged when they paired. No pairing, no
        // resolution, and nothing this app can do about it from its side.
        GuideText(
            if (bonded.isEmpty()) {
                "Nothing is paired with this phone yet.\n\nPAIR FROM THE PHONE, NOT FROM THE " +
                    "IPAD. iOS only lists accessories it knows how to be — speakers, " +
                    "keyboards — so an Android phone never appears in the iPad's Bluetooth " +
                    "list, however long you wait.\n\nOpen Settings → Bluetooth on the iPad and " +
                    "leave it on that screen, which is what makes the iPad advertise. Then in " +
                    "LightOS's Bluetooth settings, scan: the iPad appears there. Tap it, and " +
                    "accept the six-digit code on both."
            } else {
                "Devices paired with this phone. Turn on the one whose arrival should raise the " +
                    "hotspot. Nothing has to be installed on it."
            },
        )
        bonded.forEach { device ->
            val resolving = device.address in heard
            MenuRow(
                label = device.name,
                detail = if (device.address in triggers) "ON" else "OFF",
                sub = if (resolving) {
                    "heard just now — this phone can recognise it"
                } else {
                    device.address
                },
                onClick = {
                    prefs.toggleHotspotTrigger(device.address)
                    triggers = prefs.hotspotTriggers
                },
            )
        }
        Rule()

        SectionLabel("CAN IT HEAR THE IPAD?")
        // The one question the whole feature turns on, and the one nothing else can answer for
        // you. Ported back from BrightHotspot's diagnostic screen, which existed for exactly this
        // and which I dropped when the feature moved -- leaving a list you could switch things on
        // in with no way to find out whether switching them on meant anything.
        if (!scanAllowed) {
            GuideText(
                "The scan has not been allowed yet. Android counts a Bluetooth scan as a location " +
                    "signal, so it asks for both — this app does nothing with a location, and " +
                    "the scan only ever looks for the devices you paired above.",
            )
            BigButton(
                label = "ALLOW THE SCAN",
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            ) {
                askScan.launch(
                    arrayOf(
                        Manifest.permission.BLUETOOTH_SCAN,
                        Manifest.permission.BLUETOOTH_CONNECT,
                        Manifest.permission.ACCESS_FINE_LOCATION,
                    ),
                )
            }
        } else {
            val resolved = bonded.count { it.address in heard }
            GuideText(
                when {
                    bonded.isEmpty() ->
                        "Nothing paired to listen for yet."
                    resolved > 0 ->
                        "Good — $resolved paired device(s) heard and recognised. The rotating " +
                            "address is resolving, which is the part that had to work. Presence " +
                            "triggering will do what it says."
                    heard.isEmpty() ->
                        "Nothing heard at all yet. Wake the iPad, unlock it, and hold it near the " +
                            "phone for half a minute — a sleeping iPad advertises rarely."
                    else ->
                        "Hearing ${heard.size} device(s), but none of them are yours. If this " +
                            "never changes with the iPad awake and close, the pairing did not " +
                            "exchange the identity key on this phone, and presence triggering " +
                            "cannot work as built. Worth telling me — there are two fallbacks."
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

/**
 * How long a device stays "heard" for the readout above.
 *
 * Longer than the service's own window on purpose: this one is a person watching a screen and
 * waiting for a line to change, and a readout that flickered back to "nothing heard" between
 * advertisements would be read as a fault rather than as a gap.
 */
private const val HEARD_WINDOW_MS = 60_000L
