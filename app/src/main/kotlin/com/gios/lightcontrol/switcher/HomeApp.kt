package com.gios.lightcontrol.switcher

import android.content.Intent
import android.content.pm.PackageManager
import com.gios.lightcontrol.Action
import com.gios.lightcontrol.Button
import com.gios.lightcontrol.Gesture
import com.gios.lightcontrol.Prefs

/**
 * Where home goes, and why it is not a row like the others.
 *
 * Every other row in the switcher is somewhere you *were*. Home is where you go to leave wherever
 * you were, which makes it the one entry that is always worth offering and never worth ranking by
 * recency — so it is pinned to the bottom of the list, drawn as **Home** with a house, and taken
 * out of the recents above it. A launcher that appears twice, once as itself and once as Home, is
 * the list telling you two different things about one press.
 *
 * ### The HOME role is not an answer on this phone
 *
 * v3.97 resolved this from the [Button.Home] [Gesture.Tap] binding, on the reasoning that home is
 * whatever a single press reaches. That is true and it was not enough. The shipped tap is
 * [Action.DefaultHome], which fires a `CATEGORY_HOME` intent, and **LightOS holds that role on
 * every one of these phones** — it has to, or it crash-loops. So the row resolved to LightOS for
 * anybody who had not deliberately re-bound their home button, including everyone using Luma.
 * Faithful to the binding, and useless.
 *
 * So when the tap names nothing of its own and the system's answer is LightOS, this looks for a
 * launcher that is not LightOS and uses that instead. The signal is stronger than it sounds: on
 * this phone the HOME role carries almost no information, and nobody sideloads a second launcher
 * onto a Light Phone III by accident. **Exactly one** — two installed launchers is a question this
 * cannot answer, and it falls back to the system's home rather than guessing between them.
 *
 * ### The binding is still the override
 *
 * A tap bound to a package wins outright, and so does one bound to LightOS. Point **Buttons → Home
 * button → Tap** at your launcher and the row follows it exactly — and so does the button, which is
 * the configuration where the two finally agree.
 *
 * A tap bound to something that is not a home at all — Back, the switcher, the torch — still lands
 * on [Action.DefaultHome] or on the launcher found beside it. The pinned row's promise is that
 * there is always a way out of the list; it is not a second copy of whatever the button is doing.
 */
object HomeApp {

    /** LightOS's dashboard. Named, not resolved — see the class note. */
    const val LIGHTOS = "com.lightos"

    /** The framework's disambiguation activity, which is not an app anybody meant. */
    private const val RESOLVER = "android"

    /**
     * The pinned row: what it does, and which package it lands on.
     *
     * [pkg] is null only when nothing could be resolved, which costs the row its App info hold and
     * nothing else. It is the package taken *out* of the recents list, so a null here means the
     * list is left exactly as it was — the honest answer when we do not know where home is.
     */
    data class Target(val pkg: String?, val action: Action)

    fun target(prefs: Prefs, pm: PackageManager): Target {
        val tap = runCatching { prefs.action(Button.Home, Gesture.Tap) }.getOrNull()
        return pick(tap, systemHome(pm), otherLaunchers(pm))
    }

    /**
     * The whole rule, as arithmetic on three facts.
     *
     * Pure and tested on purpose. Everything else in this file is package lookups that only exist
     * on a phone, and the part that can be wrong in a way nobody notices is this one — a bad answer
     * here does not throw, it silently removes the wrong app from the switcher and sends Home to
     * the wrong place.
     *
     * [otherLaunchers] is expected to arrive already stripped of LightOS and of this app.
     */
    fun pick(tap: Action?, systemHome: String?, otherLaunchers: List<String>): Target = when (tap) {
        // A named destination is the user having answered this question already.
        is Action.Launch -> Target(tap.pkg, tap)
        Action.LightOsHome -> Target(LIGHTOS, Action.LightOsHome)
        else -> {
            val system = systemHome?.takeIf { it.isNotBlank() && it != RESOLVER }
            // Only when the system's answer is the one that carries no information. If the default
            // home really is your launcher, DefaultHome already reaches it and following the system
            // beats naming a package -- change the default and the row changes with it.
            val roleIsMeaningless = system == null || system.startsWith(LIGHTOS)
            val only = otherLaunchers.singleOrNull()
            if (roleIsMeaningless && only != null) {
                // Launch, not DefaultHome: a CATEGORY_HOME intent would go straight back to
                // whoever holds the role, which is the thing being worked around.
                Target(only, Action.Launch(only))
            } else {
                Target(system, Action.DefaultHome)
            }
        }
    }

    /** Whoever holds the HOME role. On this phone, always LightOS — see the class note. */
    private fun systemHome(pm: PackageManager): String? = runCatching {
        pm.resolveActivity(homeProbe(), PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo
            ?.packageName
    }.getOrNull()

    /**
     * Every installed launcher that is neither LightOS nor this app.
     *
     * `queryIntentActivities` rather than a list of known launcher packages, so a launcher nobody
     * here has heard of counts the same as Luma. It needs `QUERY_ALL_PACKAGES`, which this app
     * already holds for the per-app override list.
     */
    private fun otherLaunchers(pm: PackageManager): List<String> = runCatching {
        pm.queryIntentActivities(homeProbe(), 0)
            .map { it.activityInfo.packageName }
            .filter { it.isNotBlank() && it != RESOLVER && !it.startsWith(LIGHTOS) }
            .filter { it != "com.gios.lightcontrol" }
            .distinct()
    }.getOrDefault(emptyList())

    private fun homeProbe(): Intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)

    /**
     * The name to show beside the **Home is pinned** setting.
     *
     * The setting used to say only ON or OFF, which left "where does Home actually go" as something
     * you found out by opening the switcher. On a rule with a fallback in it, that is the one fact
     * worth putting on the screen — v3.97 shipped resolving to LightOS for nearly everybody and
     * nothing anywhere said so.
     */
    fun label(prefs: Prefs, pm: PackageManager, name: (String) -> String): String {
        val pkg = target(prefs, pm).pkg ?: return "the system's home"
        return runCatching { name(pkg) }.getOrDefault(pkg)
    }
}
