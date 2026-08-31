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

    data class Step(val label: String, val command: String, val check: GrantCheck)

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
            GrantCheck.AppOp(PKG, "WRITE_SETTINGS", "allow"),
        ),
        Step(
            "Overlay (SYSTEM_ALERT_WINDOW)",
            "appops set $PKG SYSTEM_ALERT_WINDOW allow",
            GrantCheck.AppOp(PKG, "SYSTEM_ALERT_WINDOW", "allow"),
        ),
        Step(
            "Color (WRITE_SECURE_SETTINGS)",
            "pm grant $PKG android.permission.WRITE_SECURE_SETTINGS",
            GrantCheck.Permission(PKG, "android.permission.WRITE_SECURE_SETTINGS"),
        ),
        Step(
            "Signal bars (READ_PHONE_STATE)",
            "pm grant $PKG android.permission.READ_PHONE_STATE",
            GrantCheck.Permission(PKG, "android.permission.READ_PHONE_STATE"),
        ),
        Step(
            "Who is calling (READ_CALL_LOG)",
            "pm grant $PKG android.permission.READ_CALL_LOG",
            GrantCheck.Permission(PKG, "android.permission.READ_CALL_LOG"),
        ),
        Step(
            "Caller names (READ_CONTACTS)",
            "pm grant $PKG android.permission.READ_CONTACTS",
            GrantCheck.Permission(PKG, "android.permission.READ_CONTACTS"),
        ),
        Step(
            "Answer calls (ANSWER_PHONE_CALLS)",
            "pm grant $PKG android.permission.ANSWER_PHONE_CALLS",
            GrantCheck.Permission(PKG, "android.permission.ANSWER_PHONE_CALLS"),
        ),
        Step(
            "Silence the ringer (DND access)",
            "cmd notification allow_dnd $PKG",
            GrantCheck.PolicyAccess(PKG),
        ),
        Step(
            "Wi-Fi names (ACCESS_FINE_LOCATION)",
            "pm grant $PKG android.permission.ACCESS_FINE_LOCATION",
            GrantCheck.Permission(PKG, "android.permission.ACCESS_FINE_LOCATION"),
        ),
        // After the foreground one, deliberately: granting background location to a package that
        // does not hold the foreground permission is refused on this platform, so the order here
        // is the difference between two grants and one failure.
        Step(
            "Wi-Fi names with the screen off",
            "pm grant $PKG android.permission.ACCESS_BACKGROUND_LOCATION",
            GrantCheck.Permission(PKG, "android.permission.ACCESS_BACKGROUND_LOCATION"),
        ),
        Step(
            "Lock-screen notifications",
            "cmd notification allow_listener $PKG/.lock.LockNotifications",
            GrantCheck.SecureListHas(
                "enabled_notification_listeners",
                "$PKG/$PKG.lock.LockNotifications",
            ),
        ),
        Step(
            "Key service (accessibility)",
            enableServiceCommand(),
            GrantCheck.SecureListHas(
                "enabled_accessibility_services",
                "$PKG/$PKG.keys.ControlService",
            ),
        ),
        Step(
            "Keyboard replace (accessibility)",
            enableComponentCommand("$PKG/$PKG.keys.KeyboardService"),
            GrantCheck.SecureListHas(
                "enabled_accessibility_services",
                "$PKG/$PKG.keys.KeyboardService",
            ),
        ),
    )

    /**
     * A one-liner that adds this service to `enabled_accessibility_services` without dropping
     * whatever else is in there. Run as a single `sh -c` so the read and the write happen in one
     * shell — the daemon gives each command its own process, so a value read in one and written
     * in the next would not see intervening state.
     */
    private fun enableServiceCommand(): String = enableComponentCommand("$PKG/$PKG.keys.ControlService")

    private fun enableComponentCommand(component: String): String = "sh -c '" +
        "cur=\$(settings get secure enabled_accessibility_services); " +
        "case \":\$cur:\" in *:$component:*) echo already;; " +
        "*) if [ \"\$cur\" = null ] || [ -z \"\$cur\" ]; then " +
        "settings put secure enabled_accessibility_services $component; " +
        "else settings put secure enabled_accessibility_services \"\$cur:$component\"; fi; " +
        "settings put secure accessibility_enabled 1; echo done;; esac'"
}
