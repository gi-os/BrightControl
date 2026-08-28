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
 * ### Home is the home button's tap, not the HOME role
 *
 * Resolved from [Button.Home]'s [Gesture.Tap] binding, because that is the thing a person means by
 * "home" on this phone: whatever a single press of the home button actually reaches. Asking the
 * system who holds the HOME role gives the wrong answer here every time — LightOS holds it and has
 * to, or it crash-loops, no matter which launcher is being used.
 *
 * That is also what makes the hiding correct rather than a hard-coded list of launcher packages.
 * Bind home's tap to Luma and Luma stops appearing as an app, because it stopped being one the
 * moment it became the destination of the home button; bind it to something else and Luma is an
 * app again, listed by its own name, with no special case anywhere to remove.
 *
 * A tap bound to something that is not a home at all — Back, the switcher itself, the torch — falls
 * back to [Action.DefaultHome]. The pinned row's promise is that there is always a way out of the
 * list; it is not a second copy of whatever the home button happens to be doing.
 */
object HomeApp {

    /** LightOS's dashboard. Named, not resolved — see the class note. */
    const val LIGHTOS = "com.lightos"

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
        val action = homeAction(tap)
        return Target(pkg(action, pm), action)
    }

    /**
     * Which action the pinned row performs, given what home's tap is bound to.
     *
     * Pure, and tested, because it is the whole of the rule and the rest of this file is package
     * lookups that only exist on a phone.
     */
    fun homeAction(tap: Action?): Action = when (tap) {
        is Action.Launch -> tap
        Action.LightOsHome -> Action.LightOsHome
        else -> Action.DefaultHome
    }

    /** Which package [action] lands on, as far as this phone will say. */
    private fun pkg(action: Action, pm: PackageManager): String? = when (action) {
        is Action.Launch -> action.pkg
        Action.LightOsHome -> LIGHTOS
        else -> defaultHome(pm)
    }

    /**
     * Whoever holds the HOME role, minus the resolver.
     *
     * Only ever asked about [Action.DefaultHome], which is the one action that means "the system's
     * choice" — everywhere else the package is named and this would be a query with a known answer.
     * `android` is the disambiguation activity the framework substitutes when there is no default,
     * and it is not an app anybody wants an App info page for.
     */
    private fun defaultHome(pm: PackageManager): String? = runCatching {
        val probe = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        pm.resolveActivity(probe, PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo
            ?.packageName
            ?.takeIf { it.isNotBlank() && it != "android" }
    }.getOrNull()
}
