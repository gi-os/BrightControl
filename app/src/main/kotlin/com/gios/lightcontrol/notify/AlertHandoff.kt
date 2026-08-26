package com.gios.lightcontrol.notify

import android.content.Context
import android.content.Intent
import com.gios.lightcontrol.Prefs
import com.gios.lightcontrol.lock.LockNotes

/**
 * Tells the other Bright apps that this one draws the on-screen box now.
 *
 * BrightChat and BrightSports each have a heads-up box of their own, and this app's banner is
 * drawn off the very notification those apps post -- so with both switched on a message is one
 * buzz and *two* boxes, one on top of the other. One of them has to stand down, and it should be
 * theirs: this one knows about every app on the phone and theirs knows about one.
 *
 * ### Why a broadcast and not a query
 *
 * The alternative was each app asking this one, on its alert path, through a `ContentProvider`.
 * That is a binder call in the moment a message arrives, on a phone where that moment is the whole
 * product. A broadcast costs the consumers a boolean in their own prefs and nothing at the
 * instant it matters.
 *
 * What a broadcast buys in speed it owes in staleness, so it is sent **often and unprompted**
 * rather than only on the change: on every launch of this app, the moment the listener grant
 * lands, and at boot. A handoff that depended on one message having arrived would stay wrong
 * after the one message a phone happened to miss.
 *
 * ### What it is not
 *
 * Not a protocol, and not defended like one. Nothing verifies who sent it, because the worst a
 * forged broadcast can do is stop one app drawing its own box: the buzz and the shade notification
 * are never gated on this, on either side. A custom signature permission would not help anyway --
 * these apps' signing key is public, and has been since the first release.
 */
object AlertHandoff {

    const val ACTION = "com.gios.lightcontrol.action.ALERTS_OWNED"
    const val EXTRA_OWNED = "owned"

    /**
     * The apps with a heads-up box of their own.
     *
     * Named rather than discovered. A broadcast to a package that is not installed costs nothing,
     * and the alternative -- walking every installed app looking for one that might listen -- is
     * a permission and a guess to save a list of three.
     *
     * A box this app does not know about is a box that keeps drawing itself, and the only symptom
     * is two of them on screen at once. BrightNotebook was exactly that: it had had a reminder box
     * since long before this feature and nothing here named it, so v3.65 through v3.71 drew a
     * second box over its first every time a reminder came due.
     */
    val CONSUMERS = listOf(
        "com.gios.lightchat",
        "com.gios.lightsports",
        "com.gios.lightnotebook",
    )

    /**
     * Whether this app is actually drawing the box.
     *
     * Both halves matter. Banners switched on without the notification listener granted draws
     * nothing at all, and an app that stood down for a feature that cannot run is an app that
     * shows nothing anywhere -- which is worse than the two boxes this exists to prevent.
     */
    fun owned(context: Context): Boolean =
        Prefs(context).banner && LockNotes.granted(context)

    /** The consumers actually on the phone, for the settings row's count. */
    fun installed(context: Context): List<String> {
        val pm = context.packageManager
        return CONSUMERS.filter {
            runCatching { pm.getPackageInfo(it, 0) }.isSuccess
        }
    }

    /**
     * Say who owns the box, to each consumer by name.
     *
     * Explicit `setPackage` rather than a bare broadcast: an implicit one on Android 14 reaches
     * only manifest receivers that happen to be exported anyway, and naming the target is both
     * cheaper and the honest description of what this is.
     */
    fun announce(context: Context) {
        val app = context.applicationContext
        val owned = owned(app)
        CONSUMERS.forEach { pkg ->
            runCatching {
                app.sendBroadcast(
                    Intent(ACTION).setPackage(pkg).putExtra(EXTRA_OWNED, owned),
                )
            }
        }
        Prefs(app).handoffToldAt = System.currentTimeMillis()
    }
}
