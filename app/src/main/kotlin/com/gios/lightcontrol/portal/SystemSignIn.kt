package com.gios.lightcontrol.portal

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import com.gios.lightcontrol.lock.LockNotes

/**
 * Android's own captive-portal sign-in app, which this app cannot be, and can nevertheless open.
 *
 * light-reports #242: a phone on the portal network, under a VPN, and every bind to the Wi-Fi
 * refused with EPERM. netd's rule — a UID under a VPN may not select any other network — has one
 * class of exception: apps holding `CONNECTIVITY_USE_RESTRICTED_NETWORKS`, and the platform's own
 * `CaptivePortalLogin` is one of them. That is how a stock phone signs in to hotel Wi-Fi with a VPN
 * up. It is on this phone too; LightOS simply never shows the *"Sign in to network"* notification
 * that launches it, and has no shade to tap it from even if it did.
 *
 * Two ways in, tried in order:
 *
 *  1. **The system's notification, through this app's notification listener.** ConnectivityService
 *     posts it as package `android` the moment a network is flagged CAPTIVE_PORTAL, and its
 *     `contentIntent` carries the [android.net.CaptivePortal] binder that lets the login app tell
 *     the system it succeeded. Firing that PendingIntent *is* tapping the notification.
 *  2. **Launching the activity directly** with [ConnectivityManager.ACTION_CAPTIVE_PORTAL_SIGN_IN]
 *     and the network. No binder, so on success the app just closes; the system notices the gate
 *     opened on its next re-evaluation, which the shell can hurry (`cmd connectivity reevaluate`).
 *
 * For a tester whose VPN is accountability software that cannot be switched off, this is the only
 * route there is — and it is arguably the better one for everybody, since the page draws in an app
 * that is allowed to reach the network.
 */
object SystemSignIn {

    private val CANDIDATES = listOf(
        "com.android.captiveportallogin",
        "com.google.android.captiveportallogin",
    )

    /** The sign-in app's package, or null when this ROM shipped without one. */
    fun installed(context: Context): String? = CANDIDATES.firstOrNull { pkg ->
        runCatching { context.packageManager.getPackageInfo(pkg, 0); true }.getOrDefault(false)
    }

    /** Something that taps the system's "Sign in to network" notification, if it is up. */
    fun notificationTap(): (() -> Boolean)? = LockNotes.signInAction()

    sealed interface Opened {
        data object ViaNotification : Opened
        data class ViaIntent(val pkg: String) : Opened
        data class Failed(val why: String) : Opened
    }

    /** Try both routes. Each line of what happened goes to [log]. */
    fun open(context: Context, network: Network?, log: (String) -> Unit): Opened {
        log("system notifications up: " + LockNotes.systemNotes().joinToString("; ") { "${it.pkg}: ${it.title}" }.ifBlank { "none seen" })
        val tap = notificationTap()
        if (tap != null) {
            val ok = tap()
            log("fired the system's sign-in notification → $ok")
            if (ok) return Opened.ViaNotification
        } else {
            log("no 'sign in to network' notification visible to the listener (granted: ${LockNotes.granted(context)})")
        }
        val pkg = installed(context) ?: run {
            log("no CaptivePortalLogin package installed (${CANDIDATES.joinToString()})")
            return Opened.Failed("this phone has no system sign-in app")
        }
        val intent = Intent(ConnectivityManager.ACTION_CAPTIVE_PORTAL_SIGN_IN)
            .setPackage(pkg)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (network != null) intent.putExtra(ConnectivityManager.EXTRA_NETWORK, network)
        val resolved = runCatching { context.packageManager.resolveActivity(intent, PackageManager.MATCH_ALL) }.getOrNull()
        if (resolved == null) {
            log("$pkg installed but ACTION_CAPTIVE_PORTAL_SIGN_IN does not resolve to it")
            return Opened.Failed("the system sign-in app refuses to be opened directly")
        }
        return runCatching { context.startActivity(intent); Opened.ViaIntent(pkg) as Opened }
            .getOrElse {
                log("startActivity($pkg) threw ${it::class.java.simpleName}: ${it.message}")
                Opened.Failed("${it::class.java.simpleName}: ${it.message}")
            }
    }
}
