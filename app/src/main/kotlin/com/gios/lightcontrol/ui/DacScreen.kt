package com.gios.lightcontrol.ui

import android.Manifest
import android.hardware.usb.UsbManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.gios.lightcontrol.Prefs
import com.gios.lightcontrol.usb.DacSignals
import com.gios.lightcontrol.usb.DacUnlock
import com.gios.lightcontrol.usb.UacVolume
import kotlinx.coroutines.delay

/**
 * Wired headphones through a USB-C adapter, as loud as the adapter goes.
 *
 * The screen has to carry three things that are all easy to get wrong in a settings row:
 *
 * - **What the bug is**, because it is a measurement somebody else made and the number is the
 *   whole reason to believe any of this.
 * - **Why it wants a microphone permission**, which is the single most alarming thing about the
 *   feature and has an honest answer.
 * - **What actually happened last time**, because success and every one of the eight failures
 *   look identical from the phone: you plug your headphones in, and either they are louder than
 *   you remember or they are not.
 *
 * Live, like the volume diagnostics screen: the readout is re-read on a tick rather than
 * remembered at composition, because the interesting moment is the one where a cable goes in
 * while this screen is open.
 */
@Composable
fun DacScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }

    var on by remember { mutableStateOf(prefs.dacUnlock) }
    var trim by remember { mutableStateOf(prefs.dacTrim) }
    var tick by remember { mutableIntStateOf(0) }

    // Re-read on every tick rather than remembered. A permission can be granted, a cable plugged
    // in, or an adapter raised while this screen is open, and a value cached at composition is
    // exactly the bug this readout exists to find.
    @Suppress("UNUSED_EXPRESSION") tick
    val canRead = DacUnlock.hasRecordAudio(context)
    val attached = remember(tick) {
        runCatching {
            context.getSystemService(UsbManager::class.java)
                ?.deviceList
                ?.values
                ?.firstOrNull { DacUnlock.hasAudioControl(it) }
                ?.let { DacUnlock.name(it) }
        }.getOrNull()
    }
    val outcome = remember(tick) { DacSignals.lastOutcome() }
    val summary = remember(tick) { DacSignals.summary() }
    val level = remember(tick) { DacSignals.lastLevel }
    val device = remember(tick) { DacSignals.lastDevice }
    val verified = remember(tick) { DacSignals.lastVerified }

    val askMic = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        tick++
        // Granted at the moment somebody is standing here with headphones in. Try the adapter that
        // is already plugged in rather than making them unplug it to prove the grant worked.
        if (granted && prefs.dacUnlock) {
            DacUnlock.apply(context.applicationContext, null, why = "permission granted")
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            tick++
        }
    }

    SectionScaffold(
        title = "Wired headphones",
        onBack = onBack,
        guide = "A USB-C to 3.5 mm adapter has a volume of its own, and Android never sets it. " +
            "Measured against an iPhone through the same Apple adapter, the LPIII is 23.5 dB " +
            "quieter — the same gap at every level, which is a fixed attenuation rather than a " +
            "limiter. This sets the adapter's control when you plug one in. Experimental: it " +
            "works on the adapter it was written against and is an educated guess on the rest.",
    ) {
        SectionLabel("EXPERIMENTAL")
        MenuRow(
            label = "Raise the adapter on connect",
            detail = if (on) "ON" else "OFF",
            sub = if (on) {
                "every time an adapter is plugged in, its own volume goes to the top. It forgets " +
                    "when you unplug it, so this happens on every connect."
            } else {
                "off. Nothing is asked for and no dialog appears when you plug an adapter in."
            },
            onClick = {
                on = !on
                prefs.dacUnlock = on
                // The manifest component is the dialog. See DacUnlock.setEnabled.
                DacUnlock.setEnabled(context, on)
                if (on && !DacUnlock.hasRecordAudio(context)) {
                    askMic.launch(Manifest.permission.RECORD_AUDIO)
                } else if (on) {
                    DacUnlock.apply(context.applicationContext, null, why = "switched on")
                }
                tick++
            },
        )
        MenuRow(
            label = "Turn the phone down if audio is playing",
            detail = if (trim) "ON" else "OFF",
            sub = "the unlock is worth about 23 dB, and the ordinary way to use an adapter is to " +
                "plug it in while something is already playing. This drops media volume to about " +
                "two thirds in that one case, once, and never puts it back — turn it up and it " +
                "stays up. Nothing playing, nothing moved.",
            dim = !on,
            onClick = {
                trim = !trim
                prefs.dacTrim = trim
            },
        )
        Rule()

        SectionLabel("THE MICROPHONE PERMISSION")
        MenuRow(
            label = if (canRead) "Granted" else "Not granted",
            detail = if (canRead) "OK" else "GRANT",
            sub = if (canRead) {
                "Android hides USB audio devices from any app without it — a USB audio device is " +
                    "a microphone as often as it is a speaker. This app records nothing, ever, " +
                    "and holds no other use for it."
            } else {
                "without it the adapter is invisible to this app and every reading below says " +
                    "there is nothing plugged in. Tap to grant. Nothing here records anything."
            },
            dim = canRead,
            onClick = if (canRead) null else ({ askMic.launch(Manifest.permission.RECORD_AUDIO) }),
        )
        Rule()

        SectionLabel("RIGHT NOW")
        MenuRow(
            label = attached ?: "No adapter attached",
            detail = if (attached != null) "•" else "",
            sub = when {
                !canRead -> "or there is one and this app cannot see it — grant above"
                attached != null -> "a USB audio device this app can see"
                else -> "plug one in with this screen open and the line below will say what happened"
            },
            dim = attached == null,
        )
        MenuRow(
            label = "Raise it now",
            detail = "›",
            sub = if (attached == null) {
                "nothing attached to raise"
            } else {
                "audio through the adapter stops for a moment while this runs — the driver has to " +
                    "let go of the interface for it. That is why it normally happens on connect, " +
                    "before anything is playing."
            },
            dim = attached == null || !on,
            onClick = if (attached == null || !on) {
                null
            } else {
                ({
                    DacUnlock.apply(context.applicationContext, null, why = "asked from settings")
                    tick++
                })
            },
        )
        Rule()

        SectionLabel("WHAT HAPPENED")
        MenuRow(
            label = outcome ?: "Nothing tried yet",
            sub = if (outcome == null) {
                "the last attempt and its reason land here, whether it worked or not"
            } else {
                summary
            },
            dim = true,
        )
        if (level != null) {
            MenuRow(
                label = device ?: "Adapter",
                detail = UacVolume.formatDb(level),
                sub = if (verified) {
                    "written, and the adapter read the same value back. That is proof the control " +
                        "took the write — it is not proof of how much louder anything got, which " +
                        "only a measurement can say."
                } else {
                    "written, but the adapter did not read the value back. The transfer was " +
                        "accepted and the firmware may have ignored it."
                },
                dim = true,
            )
        }
        Rule()

        SectionLabel("IF IT DOES NOTHING")
        MenuRow(
            label = "The dialog on the first connect",
            sub = "the first time you plug an adapter in, LightOS asks which app should handle " +
                "it. Answer with this app and tick the box to use it by default — that is the " +
                "permission this needs, and answering “just once” means the next " +
                "connect asks again.",
            dim = true,
        )
        MenuRow(
            label = "Not every adapter has this control",
            sub = "the volume is a standard USB control, but a cheap adapter can leave it out, " +
                "and one that powers up at full already has nothing to unlock. Both read the " +
                "same from here: no volume control found.",
            dim = true,
        )
        MenuRow(
            label = "Calls are a different path",
            sub = "a call's audio never passes through this — its level comes from the modem side " +
                "and lives in the phone's own audio HAL. Music, podcasts and video go through the " +
                "adapter's volume; a phone call does not.",
            dim = true,
        )
    }
}
