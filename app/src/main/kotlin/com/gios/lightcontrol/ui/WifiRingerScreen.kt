package com.gios.lightcontrol.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.gios.lightcontrol.Prefs
import com.gios.lightcontrol.RingerRule
import com.gios.lightcontrol.audio.WifiRinger
import kotlinx.coroutines.delay

/**
 * The ringer, per network.
 *
 * Mark the office silent and the flat loud, and joining either does the flip. Everything unmarked
 * is left alone, which is nearly everything.
 *
 * The list is built by remembering: no unprivileged app can enumerate the networks a phone has
 * saved, so what is offered here is what this phone has actually joined since the feature existed.
 * That is why the screen starts nearly empty and fills up over a week, and why it says so rather
 * than looking broken.
 */
@Composable
fun WifiRingerScreen(onBack: () -> Unit, onAdb: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    val ringer = remember { WifiRinger(context, prefs) }

    var on by remember { mutableStateOf(prefs.wifiRingerOn) }
    var restore by remember { mutableStateOf(prefs.wifiRingerRestore) }
    var seen by remember { mutableStateOf(prefs.wifiSeenSsids) }
    var here by remember { mutableStateOf(ringer.ssid()) }
    var last by remember { mutableStateOf(prefs.wifiRingerLast) }
    var silent by remember { mutableStateOf(ringer.silent()) }
    // Read once per composition rather than remembered: a grant can land while this screen is open,
    // from the ADB screen one tap away.
    val canSilence = ringer.canSilence()
    val canRead = ringer.canReadNames()
    val canReadAsleep = ringer.canReadNamesInBackground()
    val locationOn = ringer.locationOn()

    val askLocation = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { here = ringer.ssid() }

    // The network can change while the screen is open, and the whole point of the screen is knowing
    // which one you are on. Cheap: two system reads a second and a half.
    LaunchedEffect(on) {
        while (true) {
            here = ringer.ssid()
            silent = ringer.silent()
            last = prefs.wifiRingerLast
            seen = prefs.wifiSeenSsids
            delay(1_500)
        }
    }

    SectionScaffold(
        title = "Ringer by Wi-Fi",
        onBack = onBack,
        guide = "A network is a place. Mark the ones where this phone should be silent and the " +
            "ones where it should ring, and joining one sets the ringer. Networks you have not " +
            "marked are left alone — this never has an opinion you did not give it.",
    ) {
        MenuRow(
            label = if (on) "Following the network" else "Off",
            detail = if (on) "ON" else "OFF",
            sub = if (on) {
                "the rules below apply as the phone joins and leaves networks"
            } else {
                "nothing is changed. Networks are still remembered, so the list below fills up."
            },
            onClick = {
                on = !on
                prefs.wifiRingerOn = on
                // A rule set here should apply to the network you are standing on. Waiting for the
                // next association would mean walking out and back in to test it.
                ringer.apply("settings")
                last = prefs.wifiRingerLast
                silent = ringer.silent()
            },
        )
        MenuRow(
            label = "Ring again when you leave",
            detail = if (restore) "ON" else "OFF",
            sub = if (restore) {
                "leaving a network this app silenced puts the ringer back. Only a silence this " +
                    "app applied is ever undone — a phone you muted yourself stays muted."
            } else {
                "off. Silence follows you off the network that asked for it, until something " +
                    "else changes it."
            },
            onClick = {
                restore = !restore
                prefs.wifiRingerRestore = restore
            },
        )
        Rule()

        SectionLabel("RIGHT NOW")
        MenuRow(
            label = here ?: "Not on Wi-Fi",
            detail = here?.let { prefs.wifiRule(it)?.label ?: "—" } ?: "",
            sub = when {
                here != null -> "the network this phone is on. Tap below to give it a rule."
                !canRead -> "or the name cannot be read — see the two grants below"
                !locationOn -> "or the name is redacted because location is switched off"
                else -> "no Wi-Fi, so no rule applies"
            },
            dim = here == null,
        )
        MenuRow(
            label = "The ringer",
            detail = if (silent) "SILENT" else "ON",
            sub = if (prefs.wifiRingerSilencedFor.isNotBlank()) {
                "silenced by this app for ${prefs.wifiRingerSilencedFor}. Turn it up by hand and " +
                    "the rule stops applying until the phone leaves that network."
            } else {
                "not held down by this app"
            },
            dim = true,
        )
        if (last.isNotBlank()) {
            MenuRow(label = "Last change", sub = last, dim = true, subMaxLines = 3)
        }
        Rule()

        SectionLabel("WHAT IT NEEDS")
        // Both of these are the difference between a rule that fires and a rule that silently never
        // does, which is why they are on this screen and not only in Setup.
        GrantRow(
            label = "Muting the phone",
            ok = canSilence,
            fix = "adb shell cmd notification allow_dnd com.gios.lightcontrol",
            sub = if (canSilence) {
                "granted — a silent rule can actually mute"
            } else {
                "not granted. Android counts muting as a Do Not Disturb operation, so without " +
                    "this a silent rule does nothing at all. Ring rules still work."
            },
        )
        GrantRow(
            label = "Reading the network name",
            ok = canRead,
            fix = "adb shell pm grant com.gios.lightcontrol " +
                "android.permission.ACCESS_FINE_LOCATION",
            sub = if (canRead) {
                "granted — nothing here asks for a location, only for the name"
            } else {
                "not granted. Since Android 10 the network name is hidden from an app that " +
                    "cannot locate the phone, so no rule can match."
            },
        )
        GrantRow(
            label = "…with the screen off",
            ok = canReadAsleep,
            fix = "adb shell pm grant com.gios.lightcontrol " +
                "android.permission.ACCESS_BACKGROUND_LOCATION",
            sub = if (canReadAsleep) {
                "granted — which is the case that matters, since this is a phone in a pocket"
            } else {
                "not granted. Rules may work while you are looking at the phone and not while it " +
                    "is asleep, which is the harder bug to notice."
            },
        )
        if (!canRead) {
            BigButton(
                label = "ASK FOR IT INSTEAD",
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            ) {
                askLocation.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_BACKGROUND_LOCATION,
                    ),
                )
            }
        }
        if (!locationOn) {
            GuideText(
                "Location is switched off on this phone. The permission is not enough on its " +
                    "own: with location off the name is redacted from a permitted app exactly as " +
                    "it is from one with no permission, and the two are indistinguishable from " +
                    "here.",
            )
        }
        if (!canSilence || !canRead) {
            MenuRow(
                label = "Let the phone grant itself",
                detail = "›",
                sub = "the ADB screen runs every line above over this phone's own shell",
                onClick = onAdb,
            )
        }
        Rule()

        SectionLabel("NETWORKS")
        val listed = (seen + listOfNotNull(here)).sortedBy { it.lowercase() }
        if (listed.isEmpty()) {
            GuideText(
                "Nothing yet. Networks appear here as this phone joins them — there is no way " +
                    "for an app to read the list of networks you have saved, so this one is " +
                    "built by remembering. Join the Wi-Fi you want a rule for and come back.",
            )
        } else {
            GuideText(
                "Tap to walk a network through nothing, silent, and ring. A network with no rule " +
                    "is never touched.",
            )
        }
        listed.forEach { ssid ->
            val rule = prefs.wifiRule(ssid)
            MenuRow(
                label = ssid,
                detail = rule?.label ?: "—",
                sub = when {
                    rule == RingerRule.Silent && ssid == here -> "silent here, and you are here"
                    rule == RingerRule.Silent -> "this phone goes silent on this network"
                    rule == RingerRule.Ring && ssid == here -> "rings here, and you are here"
                    rule == RingerRule.Ring -> "this phone rings on this network"
                    ssid == here -> "no rule. You are on this one — tap to give it one."
                    else -> "no rule"
                },
                dim = rule == null,
                onClick = {
                    prefs.cycleWifiRule(ssid)
                    // The rule you just set applies now, not at the next association.
                    if (on) ringer.apply("settings")
                    last = prefs.wifiRingerLast
                    silent = ringer.silent()
                    // `seen` is the state this list is keyed on; nudging it recomposes the rows.
                    seen = prefs.wifiSeenSsids + ssid
                },
            )
        }
        if (listed.isNotEmpty()) {
            Gap(8)
            BigButton(
                label = "FORGET EVERY NETWORK",
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            ) {
                prefs.clearWifiRules()
                seen = emptySet()
                silent = ringer.silent()
            }
        }
    }
}
