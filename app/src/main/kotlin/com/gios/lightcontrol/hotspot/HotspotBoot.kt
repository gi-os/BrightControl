package com.gios.lightcontrol.hotspot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.gios.lightcontrol.Prefs
import com.gios.lightcontrol.adb.AdbManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Getting the shell back after a reboot, so nobody has to.
 *
 * **This is the whole reason the hotspot moved into this app.** The privileged call needs a shell
 * UID; Shizuku's way of handing one over is the wireless-debugging pairing flow, and Android tears
 * that flow down on every reboot. So the old shape was a setup step repeated forever, which is not
 * a setup step at all — it is why an app that worked read as one nobody could start.
 *
 * This app's connection is different in exactly the way that matters. The pairing is a key
 * exchange that is done once and kept; reconnecting afterwards is discovery, not pairing. The
 * daemon re-advertises `_adb-tls-connect._tcp` on a fresh port after each boot, `connectAuto`
 * finds it, and the stored key is still good. Nothing to type, nothing to approve, no six digits
 * to read off a dialog.
 *
 * The one thing that must stay switched on is Wireless debugging itself, which is a setting and
 * survives the reboot on its own.
 *
 * ### Why it waits, and why it keeps trying
 *
 * mDNS needs the network up, and at `BOOT_COMPLETED` it very often is not — Wi-Fi associates some
 * seconds later, and the daemon advertises after that. One attempt at boot would fail on a cold
 * start almost every time and succeed on a warm one, which is the worst kind of intermittent. So
 * it retries on a widening interval and gives up after [ATTEMPTS], by which point the phone has
 * been awake for several minutes and something else is wrong.
 *
 * Nothing here is fatal. A failed reconnect leaves the hotspot loop running and reading `SoftAp`
 * as "no adb connection" — the screen says so, and the connection can be made by hand exactly as
 * it always could.
 */
class HotspotBoot : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val app = context.applicationContext
        val prefs = Prefs(app)
        // Nothing to wake up for. The reconnect is only worth doing because something wants the
        // shell; an app that quietly re-established an adb connection on every boot for its own
        // sake would be a thing to be suspicious of.
        if (!prefs.hotspotAuto) return

        // goAsync rather than a coroutine on the receiver's own thread: a broadcast receiver is
        // dead the moment onReceive returns, and everything below is waiting.
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                var wait = FIRST_WAIT_MS
                repeat(ATTEMPTS) { attempt ->
                    delay(wait)
                    wait = (wait * 2).coerceAtMost(MAX_WAIT_MS)
                    val adb = AdbManager.getInstance(app)
                    if (adb.connected()) return@repeat
                    val ok = runCatching { adb.connectAuto(app, CONNECT_TIMEOUT_MS) }
                        .getOrDefault(false)
                    Log.d(TAG, "reconnect attempt ${attempt + 1}: $ok")
                    if (ok) return@repeat
                }
                HotspotService.start(app)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "BrightHotspotBoot"

        /** Long enough for Wi-Fi to associate before the first look. */
        const val FIRST_WAIT_MS = 10_000L
        const val MAX_WAIT_MS = 60_000L
        const val ATTEMPTS = 6
        const val CONNECT_TIMEOUT_MS = 15_000L
    }
}
