package com.gios.lightcontrol.switcher

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.gios.lightcontrol.Prefs

/**
 * Which apps you have been in, most recent first.
 *
 * Android will not tell an ordinary app this. `getRecentTasks` has been privileged since
 * Lollipop and `UsageStatsManager` needs a special-access grant with a Settings screen LightOS
 * does not ship — so on this phone the honest source is the one signal this service already
 * receives for free: the window-state event that names the app coming to the front. The list is
 * therefore built from the moment the service starts, holds nothing across a reboot, and is
 * exactly as long as the phone has been awake. That is the right trade for a switcher; a
 * switcher is about the last few minutes, not the last few weeks.
 *
 * Nothing is stored. This lives in the service's process and dies with it.
 */
class Recents(private val prefs: Prefs) {

    /**
     * Loaded from storage, not started empty.
     *
     * Every release of this app rebinds the service, and an order held only in memory is empty
     * for the minutes right after an update — which are the minutes somebody tries the gesture.
     */
    private val order = ArrayDeque(prefs.recentApps())

    /** Note an app coming to the front. The most recent is always at the head. */
    @Synchronized
    fun note(pkg: String) {
        order.remove(pkg)
        order.addFirst(pkg)
        while (order.size > KEEP) order.removeLast()
        runCatching { prefs.setRecentApps(order.toList()) }
    }

    @Synchronized
    fun forget(pkg: String) {
        order.remove(pkg)
    }

    /**
     * The switcher's list: the app you are in now at the head, then openable apps most recent
     * first, with Home pinned to the bottom.
     *
     * **The current app is pinned first**, deliberately. It used to be left out — the switcher
     * exists to leave it, so a row that lands you where you already are looked like a wasted
     * press — but a switcher is read in the instant it opens, and a list that starts anywhere
     * else reads as if it had lost the app you were just in. It is pinned rather than trusted
     * to the recents order because it is not always in the recents: a LightOS tool is never
     * noted, and a fresh service's guess can predate the list. Pinning it covers both, and
     * the row always does something — see `ControlService.pickFromSwitcher`.
     *
     * Anything with no way in is left out, resolved the same way `ControlService.launch`
     * resolves it, so the list can never offer a row that does nothing.
     *
     * **Home is not one of the recents.** It is appended last, always, whether or not you have
     * been there, and the package it lands on is taken out of the rows above — see [HomeApp]. The
     * recents are shortened by one to pay for it, so the list is still exactly as tall as the
     * caller said it could be: the row that does not fit is the app furthest back, and this list
     * refuses to be scrolled by finger.
     */
    @Synchronized
    fun entries(
        pm: PackageManager,
        front: String?,
        limit: Int,
        label: (String) -> String,
    ): List<SwitcherOverlay.Entry> {
        val target = if (prefs.switcherHomeRow) HomeApp.target(prefs, pm) else null
        // Only the resolved home package is hidden. An unresolved home hides nothing, which is the
        // honest failure: a list missing a row for a reason nobody can see is worse than a list
        // with the launcher still in it.
        val hidden = setOfNotNull(target?.pkg)
        val room = if (target == null) limit else (limit - 1).coerceAtLeast(1)
        // The head is the current app, unless it is also home (sitting on the dashboard when you
        // open the switcher is already covered by the pinned row) or it has no way in.
        val head = if (front != null && front !in hidden && openable(pm, front)) {
            listOf(SwitcherOverlay.Entry(front, label(front)))
        } else {
            emptyList()
        }
        val recents = order.asSequence()
            .filter { it !in hidden && it != front }
            .filter { openable(pm, it) }
            .take((room - head.size).coerceAtLeast(0))
            .map { pkg -> SwitcherOverlay.Entry(pkg, label(pkg)) }
            .toList()
        val home = target?.let {
            SwitcherOverlay.Entry(
                pkg = it.pkg.orEmpty(),
                label = "Home",
                home = true,
                action = it.action,
            )
        }
        return head + recents + listOfNotNull(home)
    }

    private fun openable(pm: PackageManager, pkg: String): Boolean = runCatching {
        if (pm.getLaunchIntentForPackage(pkg) != null) return true
        // A launcher publishes no CATEGORY_LAUNCHER entry of its own, and one of those is a
        // perfectly ordinary thing to switch back to on a phone with two of them installed.
        val probe = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(pkg)
        pm.resolveActivity(probe, 0) != null
    }.getOrDefault(false)

    private companion object {
        /** Longer than the switcher shows, so filtering never empties the list. */
        const val KEEP = 24
    }
}

/** An app's own name, falling back to the last part of the package. */
fun appName(context: Context, pkg: String): String = runCatching {
    val pm = context.packageManager
    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
}.getOrDefault(pkg.substringAfterLast('.'))
