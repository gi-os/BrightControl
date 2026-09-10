package com.gios.lightcontrol.portal

import android.content.ComponentName
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
 * refused with EPERM. netd's rule -- a UID under a VPN may not select any other network -- has one
 * class of exception: apps holding `CONNECTIVITY_USE_RESTRICTED_NETWORKS`, and the platform's own
 * `CaptivePortalLogin` is one of them. That is how a stock phone signs in to hotel Wi-Fi with a VPN
 * up. It is on this phone too; LightOS simply has no shade to tap the *"Sign in to network"*
 * notification from, though it turns out ConnectivityService does post it (#329).
 *
 * Two ways in:
 *
 *  1. **The system's notification, through this app's notification listener.** ConnectivityService
 *     posts it as package `android` the moment a network is flagged CAPTIVE_PORTAL, and its
 *     `contentIntent` carries the [android.net.CaptivePortal] binder that lets a login app tell the
 *     system it succeeded. Firing that PendingIntent *is* tapping the notification.
 *  2. **Launching the activity directly**, with every extra the system gave us forwarded to it.
 *
 * ### The notification does not necessarily go where you think
 *
 * `ACTION_CAPTIVE_PORTAL_SIGN_IN` is the string `android.net.conn.CAPTIVE_PORTAL`, and this app's
 * [PortalActivity] answers it on purpose -- that filter is the only reason a Light Phone has any
 * sign-in page at all. So does `com.android.captiveportallogin`. Two activities answer the same
 * implicit intent, and light-reports #329 and #330 are the same phone twelve minutes apart: the
 * notification was fired, `→ true`, and what opened was **this app again**, with the system's
 * binder in the intent.
 *
 * That round trip is not a bug to be prevented -- it is how a hand-opened screen gets hold of the
 * binder it did not have. What was broken is what happened next: the direct launch carried the
 * network and nothing else, so the system's own app opened with no URL and no binder and had
 * nothing to draw. [open] now forwards the whole extras bundle and names the component, which
 * cannot resolve back here. One hop, and it converges.
 *
 * For a tester whose VPN is accountability software that cannot be switched off, this is the only
 * route there is -- and it is arguably the better one for everybody, since the page draws in an app
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

    /**
     * Open the system's sign-in page. Each line of what happened goes to [log].
     *
     * [handed] is the intent that launched [PortalActivity], when the system is what launched it.
     * Its extras are forwarded wholesale: the `CaptivePortal` binder, the portal URL, the probe
     * spec, the user agent -- the things the platform's own app reads and this one does not model.
     * Copying the bundle rather than the four names we know about means a ROM that adds a fifth
     * keeps working.
     *
     * When we already hold the binder the notification route is skipped. It is the same
     * notification that just launched us; firing it again is the round trip a second time, and by
     * now there is nothing left to gain from it.
     */
    fun open(context: Context, network: Network?, handed: Intent?, log: (String) -> Unit): Opened {
        val holdsBinder = handed?.hasExtra(ConnectivityManager.EXTRA_CAPTIVE_PORTAL) == true
        if (holdsBinder) {
            log("skipping the notification: this screen was launched by it and holds the system's binder")
        } else {
            log(
                "system notifications up: " +
                    LockNotes.systemNotes()
                        .joinToString("; ") { "${it.pkg}: ${it.title}" }
                        .ifBlank { "none seen" },
            )
            val tap = notificationTap()
            if (tap != null) {
                val ok = tap()
                log("fired the system's sign-in notification → $ok (it may resolve back to this app)")
                if (ok) return Opened.ViaNotification
            } else {
                log("no 'sign in to network' notification visible to the listener (granted: ${LockNotes.granted(context)})")
            }
        }

        val pkg = installed(context) ?: run {
            log("no CaptivePortalLogin package installed (${CANDIDATES.joinToString()})")
            return Opened.Failed("this phone has no system sign-in app")
        }
        val intent = Intent(ConnectivityManager.ACTION_CAPTIVE_PORTAL_SIGN_IN)
            .setPackage(pkg)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // Everything the system handed us, before our own network extra, so a bundle that already
        // names a network wins over the one this screen picked for itself.
        handed?.extras?.let { extras ->
            runCatching { intent.putExtras(extras) }
                .onSuccess { log("forwarded the system's extras: ${extras.keySet().joinToString()}") }
                .onFailure { log("could not forward the system's extras: ${it::class.java.simpleName}") }
        }
        if (network != null && !intent.hasExtra(ConnectivityManager.EXTRA_NETWORK)) {
            intent.putExtra(ConnectivityManager.EXTRA_NETWORK, network)
        }
        val resolved = runCatching {
            context.packageManager.resolveActivity(intent, PackageManager.MATCH_ALL)
        }.getOrNull()
        if (resolved == null) {
            log("$pkg installed but ACTION_CAPTIVE_PORTAL_SIGN_IN does not resolve to it")
            return Opened.Failed("the system sign-in app refuses to be opened directly")
        }
        // Named, not merely packaged. `setPackage` is already enough to keep this out of our own
        // activity, and naming the component says so in the log of whatever opens next.
        intent.component = ComponentName(resolved.activityInfo.packageName, resolved.activityInfo.name)
        log("launching ${intent.component?.flattenToShortString()}")
        return runCatching { context.startActivity(intent); Opened.ViaIntent(pkg) as Opened }
            .getOrElse {
                log("startActivity($pkg) threw ${it::class.java.simpleName}: ${it.message}")
                Opened.Failed("${it::class.java.simpleName}: ${it.message}")
            }
    }
}
