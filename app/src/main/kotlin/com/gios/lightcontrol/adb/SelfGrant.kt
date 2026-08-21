package com.gios.lightcontrol.adb

/**
 * The exact grants this app needs, as the shell lines that set them — the same commands the
 * settings screens print for a computer, run against the phone's own ADB daemon instead.
 *
 * Kept here as data so the ADB screen can run them one by one and show which succeeded, and so
 * the list is in one place rather than scattered across the screens that describe each grant.
 */
object SelfGrant {

    private const val PKG = "com.gios.lightcontrol"

    data class Step(val label: String, val command: String)

    /**
     * Ordered so the accessibility service — the one grant everything else leans on — goes last,
     * because enabling it rebinds the service and can interrupt the shell stream mid-run.
     *
     * The service line *appends* to the enabled list rather than replacing it: `settings put`
     * overwrites, and overwriting would silently switch off any other accessibility service
     * (LightVoice's push-to-talk, a password manager). So it reads the current value and adds to
     * it, colon-joined, only if this service is not already there.
     */
    val steps: List<Step> = listOf(
        Step(
            "Brightness (WRITE_SETTINGS)",
            "appops set $PKG WRITE_SETTINGS allow",
        ),
        Step(
            "Overlay (SYSTEM_ALERT_WINDOW)",
            "appops set $PKG SYSTEM_ALERT_WINDOW allow",
        ),
        Step(
            "Colour (WRITE_SECURE_SETTINGS)",
            "pm grant $PKG android.permission.WRITE_SECURE_SETTINGS",
        ),
        Step(
            "Signal bars (READ_PHONE_STATE)",
            "pm grant $PKG android.permission.READ_PHONE_STATE",
        ),
        Step(
            "Lock-screen notifications",
            "cmd notification allow_listener $PKG/.lock.LockNotifications",
        ),
        Step(
            "Key service (accessibility)",
            enableServiceCommand(),
        ),
    )

    /**
     * A one-liner that adds this service to `enabled_accessibility_services` without dropping
     * whatever else is in there. Run as a single `sh -c` so the read and the write happen in one
     * shell — the daemon gives each command its own process, so a value read in one and written
     * in the next would not see intervening state.
     */
    private fun enableServiceCommand(): String {
        val component = "$PKG/$PKG.keys.ControlService"
        return "sh -c '" +
            "cur=\$(settings get secure enabled_accessibility_services); " +
            "case \":\$cur:\" in *:$component:*) echo already;; " +
            "*) if [ \"\$cur\" = null ] || [ -z \"\$cur\" ]; then " +
            "settings put secure enabled_accessibility_services $component; " +
            "else settings put secure enabled_accessibility_services \"\$cur:$component\"; fi; " +
            "settings put secure accessibility_enabled 1; echo done;; esac'"
    }
}
