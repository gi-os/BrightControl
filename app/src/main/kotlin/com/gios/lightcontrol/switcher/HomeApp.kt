package com.gios.lightcontrol.switcher

import android.content.Intent
import android.content.pm.PackageManager
import com.gios.lightcontrol.Action
import com.gios.lightcontrol.Prefs

/**
 * Which app the switcher's pinned **Home** row opens.
 *
 * Every other row in the switcher is somewhere you *were*. Home is where you go to leave wherever
 * you were, which makes it the one entry always worth offering and never worth ranking by recency —
 * so it is pinned to the bottom, drawn as Home with a house, and taken out of the recents above it.
 *
 * ### It is a choice, not a deduction
 *
 * Two releases were spent trying to work this out from the phone. v3.97 read it off the home
 * button's tap binding, which is faithful and useless: the shipped tap fires a `CATEGORY_HOME`
 * intent, and **LightOS holds that role on every one of these phones** — it has to, or it
 * crash-loops — so Home resolved to LightOS for everybody using Luma. v3.98 added a rule on top:
 * if the role holder is LightOS and exactly one other launcher is installed, use that one instead.
 * It gave the right answer on most phones and it was three rules deep, with a fallback nobody could
 * see the output of.
 *
 * So it is a setting. **Buttons → Home button → Home app** is a list, you pick the app, and that is
 * the whole rule. Unset means the system's home, which is the honest default: it is what a home
 * intent does, and anybody it is wrong for is one screen away from saying so.
 *
 * The one thing still decided here is that a choice pointing at an app that is no longer installed
 * falls back rather than opening nothing — an uninstall must not leave a dead row at the bottom of
 * the switcher.
 */
object HomeApp {

    /** LightOS's dashboard. Named, not resolved. */
    const val LIGHTOS = "com.lightos"

    /** The framework's disambiguation activity, which is not an app anybody meant. */
    private const val RESOLVER = "android"

    /** Stored to mean "no app chosen — follow the system's home". */
    const val SYSTEM = ""

    /**
     * The pinned row: what it does, and which package it lands on.
     *
     * [pkg] is null only when nothing could be resolved, which costs the row its App info hold and
     * nothing else. It is also the package taken *out* of the recents list, so a null there leaves
     * the list exactly as it was — the honest answer when we do not know where home is.
     */
    data class Target(val pkg: String?, val action: Action)

    fun target(prefs: Prefs, pm: PackageManager): Target {
        val chosen = runCatching { prefs.switcherHomePkg }.getOrDefault(SYSTEM)
        return pick(chosen, installed(pm, chosen), systemHome(pm))
    }

    /**
     * The whole rule, as arithmetic on three facts. Pure, and tested.
     *
     * A bad answer here does not throw. It silently removes the wrong app from the switcher and
     * sends Home somewhere nobody asked for, which is exactly how the last two releases went.
     */
    fun pick(chosen: String?, chosenInstalled: Boolean, systemHome: String?): Target {
        val pkg = chosen?.takeIf { it.isNotBlank() && chosenInstalled }
        return when {
            pkg == null -> Target(systemHome?.takeIf { it.isNotBlank() && it != RESOLVER }, Action.DefaultHome)
            // Picking LightOS from the list gets LightOS's own action rather than a launch, because
            // arriving that way is what makes it a *visit* -- the state where the home button
            // belongs to LightOS so you can walk through its menu. See ControlService.visitHome.
            pkg.startsWith(LIGHTOS) -> Target(pkg, Action.LightOsHome)
            else -> Target(pkg, Action.Launch(pkg))
        }
    }

    /** Whether a chosen package is still on the phone. An uninstall must not leave a dead row. */
    private fun installed(pm: PackageManager, pkg: String): Boolean =
        pkg.isNotBlank() && runCatching { pm.getApplicationInfo(pkg, 0); true }.getOrDefault(false)

    /** Whoever holds the HOME role. On this phone, always LightOS — see the class note. */
    private fun systemHome(pm: PackageManager): String? = runCatching {
        pm.resolveActivity(homeProbe(), PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo
            ?.packageName
    }.getOrNull()

    private fun homeProbe(): Intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)

    /**
     * The name to show beside the **Home app** setting.
     *
     * A setting that reads only ON leaves "where does Home actually go" as something you find out
     * by opening the switcher. v3.97 shipped resolving to LightOS for nearly everybody with nothing
     * anywhere saying so, and that is the fix that outlives the rule it was written for.
     */
    fun label(prefs: Prefs, pm: PackageManager, name: (String) -> String): String {
        val pkg = target(prefs, pm).pkg ?: return "the system's home"
        return runCatching { name(pkg) }.getOrDefault(pkg)
    }
}
