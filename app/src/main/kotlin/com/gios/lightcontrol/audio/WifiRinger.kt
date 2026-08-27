package com.gios.lightcontrol.audio

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.gios.lightcontrol.Prefs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The ringer, decided by which Wi-Fi the phone is on.
 *
 * The office is a network. So is the flat, and the studio, and a friend's living room — and each
 * of them is a place where the answer to "should this thing make a noise" is already known and is
 * not the same. On a phone with no automation, no profiles and no Do Not Disturb schedule, the
 * ringer is a switch you remember to flip and then forget to flip back, which is a missed morning
 * of calls about once a month.
 *
 * So: mark a network silent, mark a network loud, and joining it does the flip. Everything else is
 * left alone — an unmarked network is no opinion at all, which is the state the vast majority of
 * networks stay in. See [RingerDecision] for the rules, which are separate and tested.
 *
 * ## What this needs, and what it does when it doesn't have it
 *
 * Two grants, neither of which LightOS has a screen for:
 *
 *  - **Notification policy access**, to mute. `setRingerMode(SILENT)` from an app without it
 *    throws a `SecurityException` — Android treats muting a phone as a Do Not Disturb operation.
 *    `cmd notification allow_dnd com.gios.lightcontrol`.
 *  - **A location permission**, to read the network's name at all. Since Android 10 the SSID is
 *    redacted from any app that cannot locate the phone, on the reasoning that knowing which
 *    network you are on is knowing where you are. This app does nothing else with it, and asks for
 *    the background flavour too because the whole point is a phone in a pocket.
 *
 * Both are reported on the settings screen rather than assumed, because the failure without them is
 * silent in the literal sense: no name means no rule matches, and nothing happens for ever.
 */
class WifiRinger(context: Context, private val prefs: Prefs) {

    private val app = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())

    /**
     * The apply is posted rather than run where the event arrives.
     *
     * A single Wi-Fi association produces a burst: available, then capabilities changed two or
     * three times as the network validates, then a broadcast. Each of them is the same news, and
     * writing the ringer mode four times in a second is four ringer-mode broadcasts back out —
     * one of which lands on the volume HUD.
     */
    private val debounced = Runnable { apply("network") }

    private val cm = app.getSystemService(ConnectivityManager::class.java)

    private val networks = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = schedule()
        override fun onLost(network: Network) = schedule()
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = schedule()
    }

    /**
     * The broadcast, as well as the callback.
     *
     * `NETWORK_STATE_CHANGED_ACTION` carries the association itself, which arrives before the
     * network validates — so on a captive portal or a network with no internet, where the callback
     * may never fire at all, this is the only notice that the phone joined something.
     */
    private val joined = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            runCatching { schedule() }
        }
    }

    /**
     * Somebody moved the ringer. If it went up while this app was holding it down, the claim is
     * dropped and the rule stops applying on this network until the phone leaves it.
     *
     * This is the difference between a feature and a fight. Without it, the next capabilities
     * change — and there is always a next one — would put the phone straight back to silent.
     */
    private val ringerChanged = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            runCatching {
                if (!prefs.wifiRingerOn) return
                if (silent()) return
                val held = prefs.wifiRingerSilencedFor
                if (held.isBlank()) return
                prefs.wifiRingerSilencedFor = ""
                // Only an override if it happened on the network the rule belongs to. Off it, the
                // dropped claim is enough.
                if (held == ssid()) {
                    prefs.wifiRingerOverriddenFor = held
                    note("ringer turned up by hand on $held — leaving it")
                }
            }
        }
    }

    fun start() {
        runCatching {
            cm?.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .build(),
                networks,
            )
        }
        runCatching {
            ContextCompat.registerReceiver(
                app,
                joined,
                IntentFilter(WifiManager.NETWORK_STATE_CHANGED_ACTION),
                ContextCompat.RECEIVER_EXPORTED,
            )
        }
        runCatching {
            ContextCompat.registerReceiver(
                app,
                ringerChanged,
                IntentFilter(AudioManager.RINGER_MODE_CHANGED_ACTION),
                ContextCompat.RECEIVER_EXPORTED,
            )
        }
        // Once at startup. The service is bound at boot and after every update, and the network the
        // phone is on at that moment is a network it "joined" as far as anybody is concerned.
        schedule()
    }

    fun stop() {
        handler.removeCallbacks(debounced)
        runCatching { cm?.unregisterNetworkCallback(networks) }
        runCatching { app.unregisterReceiver(joined) }
        runCatching { app.unregisterReceiver(ringerChanged) }
    }

    private fun schedule() {
        handler.removeCallbacks(debounced)
        handler.postDelayed(debounced, SETTLE_MS)
    }

    /**
     * Read where the phone is, decide, and write at most one thing.
     *
     * Public because the settings screen calls it: a rule you just set should apply to the network
     * you are standing on, without waiting for the next association.
     */
    fun apply(reason: String) {
        runCatching {
            val ssid = ssid()
            // Remembered whether or not the feature is on, so switching it on presents a list of
            // your own networks instead of an empty screen. This is the only list there can be:
            // nothing unprivileged can enumerate saved networks.
            if (ssid != null) prefs.noteWifiSeen(ssid)
            if (!prefs.wifiRingerOn) return
            // An override belongs to one network. Leaving it clears it, so the rule applies again
            // next time you arrive.
            if (prefs.wifiRingerOverriddenFor.isNotBlank() &&
                prefs.wifiRingerOverriddenFor != ssid
            ) {
                prefs.wifiRingerOverriddenFor = ""
            }
            val move = RingerDecision.decide(
                RingerDecision.State(
                    ssid = ssid,
                    rule = ssid?.let { prefs.wifiRule(it) },
                    alreadySilent = silent(),
                    silencedFor = prefs.wifiRingerSilencedFor,
                    overriddenFor = prefs.wifiRingerOverriddenFor,
                    restore = prefs.wifiRingerRestore,
                ),
            )
            when (move) {
                RingerDecision.Move.Leave -> Unit

                is RingerDecision.Move.Silence -> {
                    if (setMode(AudioManager.RINGER_MODE_SILENT)) {
                        prefs.wifiRingerSilencedFor = move.ssid
                        note("silent on ${move.ssid} ($reason)")
                    } else {
                        // The one failure worth a line of its own: the rule is right, the grant is
                        // missing, and nothing on the phone would otherwise say so.
                        note("could not silence on ${move.ssid} — needs DND access")
                    }
                }

                is RingerDecision.Move.Ring -> {
                    if (setMode(AudioManager.RINGER_MODE_NORMAL)) {
                        prefs.wifiRingerSilencedFor = ""
                        note("ringing — ${move.why}")
                    } else {
                        note("could not restore the ringer — ${move.why}")
                    }
                }

                is RingerDecision.Move.Forget -> {
                    prefs.wifiRingerSilencedFor = ""
                }
            }
        }
    }

    /** The network the phone is joined to, or null — including when the name is redacted. */
    fun ssid(): String? = runCatching {
        val wifi = app.getSystemService(WifiManager::class.java) ?: return null
        @Suppress("DEPRECATION")
        val raw = wifi.connectionInfo?.ssid ?: return null
        if (raw.isBlank() || raw == UNKNOWN) return null
        raw.trim('"').ifBlank { null }
    }.getOrNull()

    fun silent(): Boolean = runCatching {
        val audio = app.getSystemService(AudioManager::class.java) ?: return false
        audio.ringerMode != AudioManager.RINGER_MODE_NORMAL
    }.getOrDefault(false)

    /** Whether muting is permitted at all. Without it the silent half of this is inert. */
    fun canSilence(): Boolean = runCatching {
        app.getSystemService(NotificationManager::class.java)
            ?.isNotificationPolicyAccessGranted == true
    }.getOrDefault(false)

    fun canReadNames(): Boolean = runCatching {
        app.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    fun canReadNamesInBackground(): Boolean = runCatching {
        app.checkSelfPermission(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    /**
     * Whether the phone's location service is switched on.
     *
     * Holding the permission is not enough: with location off, the SSID is redacted from a
     * permitted app exactly as it is from an unpermitted one, and the two look identical from here.
     */
    fun locationOn(): Boolean = runCatching {
        app.getSystemService(LocationManager::class.java)?.isLocationEnabled == true
    }.getOrDefault(false)

    private fun setMode(mode: Int): Boolean = runCatching {
        val audio = app.getSystemService(AudioManager::class.java) ?: return false
        audio.ringerMode = mode
        // Read back rather than trusting the write. `setRingerMode` is one of the calls that can
        // be swallowed instead of throwing, and a claim recorded for a mute that never happened is
        // a ringer this app would later turn up for no reason.
        audio.ringerMode == mode
    }.getOrDefault(false)

    private fun note(line: String) {
        prefs.wifiRingerLast = "${stamp.format(Date())} · $line"
    }

    private companion object {
        /** Long enough for one association's burst of events to finish. */
        const val SETTLE_MS = 1_500L

        const val UNKNOWN = "<unknown ssid>"

        val stamp = SimpleDateFormat("HH:mm", Locale.US)
    }
}
