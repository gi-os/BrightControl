package com.gios.lightcontrol.hotspot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.gios.lightcontrol.Prefs
import com.gios.lightcontrol.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The presence loop: watch for the iPad, and raise the hotspot when it looks like it needs one.
 *
 * A foreground service because that is the only way LightOS will let a sideloaded app keep a BLE
 * scan alive with the screen off — which is the entire point, since the hotspot is meant to come
 * up while the phone sits in a pocket.
 *
 * Each tick it takes one honest reading of the world and hands it to [TriggerEngine], then does
 * exactly what the engine says. This holds no policy of its own; if the behavior is wrong, the fix
 * is in the engine, where a test can pin it.
 *
 * **Its own service rather than a job on [com.gios.lightcontrol.keys.ControlService].** The key
 * filter is the thing this phone's buttons depend on, and a BLE scan that misbehaves must not be
 * able to take it down — an accessibility service that throws often enough gets stood down by the
 * system, and standing down the key filter to fix a hotspot would be a poor trade. Separate
 * process lifetimes, separate blast radii.
 */
class HotspotService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loop: Job? = null

    private lateinit var prefs: Prefs
    private lateinit var connectivity: Connectivity
    private lateinit var ap: SoftAp
    private lateinit var scanner: BleScanner
    private val engine = TriggerEngine()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        connectivity = Connectivity(this)
        ap = SoftAp(this)
        scanner = BleScanner(this)
        ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, notification("Watching for your iPad"))
        if (loop == null) loop = scope.launch { run() }
        running.value = true
        return START_STICKY
    }

    private suspend fun run() {
        if (!scanner.start()) note("Bluetooth scan unavailable — is Bluetooth on?")
        while (scope.isActive) {
            val nearby = scanner.recentlySeen(SEEN_WINDOW_MS)
            val triggers = prefs.hotspotTriggers
            val snap = Snapshot(
                triggerNearby = triggers.isNotEmpty() && nearby.any { it in triggers },
                onTrustedWifi = connectivity.onTrustedWifi(prefs.hotspotTrustedSsids),
                apActive = ap.apEnabled(),
                clientCount = ap.clientCount(),
                hasUplink = connectivity.hasCellularToShare(),
            )
            clients.value = snap.clientCount
            phase.value = engine.phase

            // Said out loud, because a hotspot that declines to come up looks exactly like one
            // that did not notice the iPad, and this is the commonest honest reason for it --
            // no data plan, no signal, or a carrier that does not allow tethering on this SIM.
            noUplink.value = snap.triggerNearby && !snap.hasUplink && !snap.onTrustedWifi

            when (engine.evaluate(System.currentTimeMillis(), snap)) {
                Action.START_AP -> {
                    val out = ap.start(prefs.hotspotSsid, prefs.hotspotPassword)
                    // **Says what the phone said.** The version of this that used Shizuku could
                    // only report "not ready", and the commonest failure — the AP simply never
                    // coming up — was indistinguishable from the scan not having found anything.
                    note(if (out.ok) "iPad nearby — hotspot on" else "Could not start: ${out.said}")
                }
                Action.STOP_AP -> {
                    ap.stop()
                    note(
                        if (engine.inBackoff(System.currentTimeMillis())) {
                            "No one joined — standing down for a while"
                        } else {
                            "Idle — hotspot off"
                        },
                    )
                }
                Action.NONE -> Unit
            }
            delay(TICK_MS)
        }
    }

    override fun onDestroy() {
        running.value = false
        phase.value = Phase.IDLE
        runCatching { scanner.stop() }
        loop = null
        scope.cancel()
        super.onDestroy()
    }

    private fun note(msg: String) {
        Log.d(TAG, msg)
        lastEvent.value = msg
        runCatching {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIF_ID, notification(msg))
        }
    }

    private fun notification(text: String): Notification =
        Notification.Builder(this, CHANNEL)
            .setContentTitle("Hotspot")
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .build()

    companion object {
        private const val TAG = "BrightHotspot"
        private const val CHANNEL = "hotspot"
        private const val NOTIF_ID = 4711

        /** How long a device stays "nearby" after its last advertisement. */
        private const val SEEN_WINDOW_MS = 45_000L

        /** One reading of the world per tick. Unhurried on purpose; see [Timings]. */
        private const val TICK_MS = 15_000L

        val running = MutableStateFlow(false)

        /** The iPad is here and the phone has nothing to give it. See the note at the use site. */
        val noUplink = MutableStateFlow(false)
        val phase = MutableStateFlow(Phase.IDLE)
        val clients = MutableStateFlow(SoftAp.UNKNOWN)
        val lastEvent = MutableStateFlow("")

        fun start(context: Context) {
            val intent = Intent(context, HotspotService::class.java)
            runCatching { context.startForegroundService(intent) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, HotspotService::class.java)) }
        }

        private fun ensureChannel(context: Context) {
            runCatching {
                val nm = context.getSystemService(NotificationManager::class.java) ?: return
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, "Hotspot", NotificationManager.IMPORTANCE_LOW),
                )
            }
        }
    }
}
