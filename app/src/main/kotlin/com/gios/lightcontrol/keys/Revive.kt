package com.gios.lightcontrol.keys

import android.content.ComponentName
import android.content.Context
import android.provider.Settings

/**
 * Bringing the key service back after a force-quit, without a computer.
 *
 * ## The state this fixes
 *
 * Force-quitting the app kills the accessibility service with it — and Android then **refuses to
 * rebind it**. That is not a bug in the phone: force-stop sets a stopped flag on the package, and
 * the system deliberately starts nothing belonging to a stopped app until a person launches it by
 * hand. Meanwhile the *setting* still lists the service as enabled, because force-quitting switched
 * nothing off, so every readout says the key filter is on while the wheel and the camera button do
 * nothing at all.
 *
 * It is worse than it sounds in this app's case, because the thing people force-quit is a screen
 * that looked stuck — and the price of that was the buttons.
 *
 * ## Why rewriting the setting works
 *
 * The system binds accessibility services from `enabled_accessibility_services`, and it acts on
 * *changes* to that string. Launching the app clears the stopped flag; writing the setting then
 * makes the system look at the list again and bind what is in it. So the recovery is: take our
 * component out, put it back, and let the framework do what it does when the list changes.
 *
 * Two writes rather than one, and the list is preserved both times: writing this setting is how an
 * app switches off every *other* accessibility service by accident — a password manager, LightVoice,
 * a screen reader — and that is a far worse thing to do than leaving a wheel broken.
 *
 * Needs `WRITE_SECURE_SETTINGS`, which this app grants itself during setup for the colour writes. No
 * grant, no recovery, and [nudge] says so rather than pretending.
 */
object Revive {

    enum class Result { NotNeeded, Rebound, NoPermission, Failed }

    /**
     * Rebind the key service if it should be running and is not.
     *
     * "Should be running" is the setting; "is not" is [ControlService.bound], which only the service
     * can know. Both are required: a service that is genuinely switched off must not be switched on
     * behind somebody's back, and one that is already bound must not be poked for no reason —
     * rewriting the setting while it is live tears the service down and builds it again, which
     * throws away the recents list the switcher keeps in memory.
     */
    fun nudge(context: Context): Result {
        if (!Grants.serviceEnabled(context)) return Result.NotNeeded
        if (ControlService.bound) return Result.NotNeeded

        val component = ComponentName(context, ControlService::class.java)
        val flat = "${component.packageName}/${component.className}"
        val current = runCatching {
            Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            )
        }.getOrNull().orEmpty()

        // Everything except us, kept exactly as it was written — short forms included, because
        // rewriting somebody else's entry in a longer form is a change to their setting too.
        val others = current.split(':')
            .map { it.trim() }
            .filter { it.isNotEmpty() && !sameService(context, it, component) }

        val without = others.joinToString(":")
        val with = (others + flat).joinToString(":")

        return runCatching {
            val resolver = context.contentResolver
            Settings.Secure.putString(
                resolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                without,
            )
            Settings.Secure.putString(
                resolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                with,
            )
            // Belt and braces: a phone that has had accessibility switched off entirely ignores the
            // list. This is the same line the shell command writes during setup.
            Settings.Secure.putInt(resolver, "accessibility_enabled", 1)
            Result.Rebound
        }.getOrElse { error ->
            if (error is SecurityException) Result.NoPermission else Result.Failed
        }
    }

    /** `pkg/.Class` and `pkg/pkg.Class` name the same service; both forms appear in the wild. */
    private fun sameService(context: Context, entry: String, expected: ComponentName): Boolean {
        val parts = entry.split('/')
        if (parts.size != 2) return false
        val pkg = parts[0]
        val cls = if (parts[1].startsWith(".")) pkg + parts[1] else parts[1]
        return ComponentName(pkg, cls) == expected
    }
}
