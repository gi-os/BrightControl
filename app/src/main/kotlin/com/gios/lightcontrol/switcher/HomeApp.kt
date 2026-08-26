package com.gios.lightcontrol.switcher

import android.content.pm.PackageManager

/**
 * Luma, the launcher most people on this phone press home to reach.
 *
 * It is an app, the way `PackageManager` counts apps, and in the switcher that is the wrong thing
 * to say about it. Every other row is somewhere you were; this one is where you go to get out of
 * wherever you were — so under [com.gios.lightcontrol.Prefs.switcherLumaAsHome] it is drawn as
 * **Home**, with a drawn house instead of its own icon, and stops looking like one more app in a
 * list of apps.
 *
 * Matched by package rather than by "whatever holds the HOME role", which on this phone is always
 * LightOS: LightOS has to keep that role or it crash-loops, so the role says nothing about which
 * launcher a person actually uses. The debug id is here because a Luma built from source carries
 * it, and someone running their own build is exactly the person who would notice this missing.
 */
object HomeApp {

    /** Luma's application ids — `app.luma` upstream, plus the suffix a source build gets. */
    private val PACKAGES = setOf("app.luma", "app.luma.debug")

    fun isHome(pkg: String): Boolean = pkg in PACKAGES

    /**
     * Whether Luma is on the phone at all.
     *
     * Asked so the setting can stay off the screen for somebody who does not have it. A toggle for
     * an app you have never installed is a line of settings that can only ever do nothing.
     *
     * `getApplicationInfo` rather than a launch intent: a launcher publishes `CATEGORY_HOME` and
     * need not publish `CATEGORY_LAUNCHER` at all, which is the same trap `Recents.openable` and
     * the colour screens already work around.
     */
    fun installed(pm: PackageManager): Boolean = PACKAGES.any { pkg ->
        runCatching { pm.getApplicationInfo(pkg, 0); true }.getOrDefault(false)
    }
}
