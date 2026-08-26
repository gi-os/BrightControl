package com.gios.lightcontrol.adb

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.concurrent.Executors

/**
 * The state behind one-tap pairing, shared by the ADB screen and [AdbPairReader].
 *
 * ## Why a reader service and not a text box
 *
 * Every route that asks the user to carry the six digits somewhere is closed, and it is worth
 * writing down why so nobody reopens them:
 *
 * - **Type it in this app.** `WirelessDebuggingFragment.onPause()` calls `removeDialog(...)`, and
 *   `AdbWirelessDialog.onStop()` calls `dismiss()` and then `onDismiss()`, which calls
 *   `mAdbManager.disablePairing()`. The session dies when Settings *pauses*, not when it is
 *   covered — so Home kills the pairing exactly as dead as Back does. Earlier builds of this
 *   screen told users to leave the dialog with Home. That advice was wrong, and it is why
 *   pairing here so often failed with a code that looked correct.
 * - **Type it into a notification.** Shizuku's trick, and it needs a notification shade with
 *   direct reply. LightOS has no shade to type into.
 * - **Ask the system.** Settings does not read its own dialog: it calls
 *   `IAdbManager.enablePairingByPairingCode()` and gets the digits back in a broadcast. That
 *   path needs `MANAGE_DEBUGGING`, which is `signature|privileged` with no `development` flag,
 *   so `pm grant` cannot hand it over even after a successful pairing.
 * - **A floating panel.** An overlay is the only window that does not pause Settings, which is
 *   why it nearly worked — but it has to survive this process being backgrounded, and on this
 *   phone it usually does not.
 *
 * What is left needs no window and no typing at all: read the digits off the dialog while
 * Settings is still the foreground app. The code is a plain `TextView` in
 * `adb_wireless_dialog.xml` and the dialog sets no `FLAG_SECURE`, so an accessibility service
 * can see it.
 *
 * ## What this object guarantees
 *
 * The reader is inert unless [arm] has been called, and it disarms itself the moment it finds a
 * code or [WINDOW_MS] passes. Nothing is read outside that window, and the service is declared
 * with `packageNames="com.android.settings"` so it cannot see any other app even when armed.
 */
object AdbPairSession {

    enum class Phase { Idle, Waiting, Pairing, Granting, Done, Failed }

    /** How long the reader stays armed after the user asks to pair. */
    const val WINDOW_MS = 90_000L

    var phase by mutableStateOf(Phase.Idle)
        private set

    /** One line for the user, always the truth about what just happened. */
    var message by mutableStateOf("")
        private set

    /** Grant results, filled in after a successful connect. */
    var grants by mutableStateOf(listOf<String>())
        private set

    /**
     * What the reader actually saw on the last screen it could not make sense of.
     *
     * Light ships its own build of Settings, and the one thing that cannot be checked from a
     * desk is whether that build still renders the code as readable text. When the read fails
     * this holds the text it did find, so the screen can show it and the answer takes one run
     * instead of a guessing match. Memory only, cleared on the next attempt.
     */
    var unreadable by mutableStateOf<String?>(null)
        private set

    @Volatile
    var armed = false
        private set

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var expire: Runnable? = null

    /** True once the user has enabled the reader in Settings → Accessibility. */
    fun readerEnabled(context: Context): Boolean =
        runCatching {
            val flat = android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ).orEmpty()
            flat.split(':').any { it.equals(READER_COMPONENT, ignoreCase = true) }
        }.getOrDefault(false)

    fun arm() {
        cancelExpiry()
        grants = emptyList()
        unreadable = null
        phase = Phase.Waiting
        message = "waiting for the pairing dialog — open Wireless debugging → Pair device with pairing code"
        armed = true
        expire = Runnable {
            if (armed) {
                armed = false
                phase = Phase.Failed
                message = "timed out after ${WINDOW_MS / 1000}s without seeing a pairing code"
            }
        }.also { main.postDelayed(it, WINDOW_MS) }
    }

    fun cancel() {
        cancelExpiry()
        armed = false
        phase = Phase.Idle
        message = ""
    }

    private fun cancelExpiry() {
        expire?.let { main.removeCallbacks(it) }
        expire = null
    }

    /**
     * Called by the reader for every Settings screen it sees while armed. Returns true once a
     * code has been taken, after which the reader is disarmed and this does nothing.
     */
    fun offerScreen(context: Context, text: String): Boolean {
        if (!armed) return false
        val code = AdbPairCode.extract(text) ?: run {
            // Only worth reporting if this really looks like the pairing dialog; the user walks
            // through several Settings screens on the way there.
            if (AdbPairCode.looksLikePairingDialog(text)) {
                main.post { unreadable = text.take(600) }
            }
            return false
        }

        armed = false
        cancelExpiry()
        val app = context.applicationContext
        main.post {
            unreadable = null
            phase = Phase.Pairing
            message = "found the code — pairing"
        }
        worker.execute { pairAndGrant(app, code) }
        return true
    }

    private fun pairAndGrant(context: Context, code: String) {
        val adb = AdbManager.getInstance(context)

        val paired = runCatching { adb.pairViaMdns(context, code, 60_000L) }.getOrDefault(false)
        if (!paired) {
            main.post {
                phase = Phase.Failed
                message = "read the code but pairing failed — the dialog may have closed. " +
                    "Reopen it and try again; the code is fresh each time."
            }
            return
        }

        val connected = runCatching { adb.connectAuto(context, 15_000L) }.getOrDefault(false)
        if (!connected) {
            main.post {
                phase = Phase.Failed
                message = "paired, but could not connect — use CONNECT below"
            }
            return
        }

        main.post {
            phase = Phase.Granting
            message = "connected — applying grants"
        }

        val results = SelfGrant.steps.map { step ->
            // Through [AdbManager.runVia]: the connection made a second ago is the one most likely
            // to have been reported up before the daemon settled, and `Stream closed` on the first
            // grant of the batch used to be reported as the grant failing.
            val out = AdbManager.runVia(context, step.command)
            val ok = out.isBlank() || out.contains("done") || out.contains("already")
            "${if (ok) "OK" else "??"}  ${step.label}${if (out.isBlank()) "" else " — ${out.take(80)}"}"
        }

        main.post {
            grants = results
            phase = Phase.Done
            message = "paired, connected, and granted. You can turn the pairing reader back off."
        }
    }

    const val READER_COMPONENT = "com.gios.lightcontrol/com.gios.lightcontrol.adb.AdbPairReader"
}
