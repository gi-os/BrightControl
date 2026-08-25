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
     * The switcher's list: openable apps, most recent first, without the one you are looking at.
     *
     * The current app is left out because the switcher exists to leave it — an entry that lands
     * you where you already are is a row that can only waste a press. Anything with no way in is
     * left out too, resolved the same way `ControlService.launch` resolves it, so the list can
     * never offer a row that does nothing.
     */
    @Synchronized
    fun entries(
        pm: PackageManager,
        excluding: Set<String>,
        limit: Int,
        label: (String) -> String,
    ): List<SwitcherOverlay.Entry> = order.asSequence()
        .filter { it !in excluding }
        .filter { openable(pm, it) }
        .take(limit)
        .map { SwitcherOverlay.Entry(it, label(it)) }
        .toList()

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
