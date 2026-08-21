package com.gios.lightcontrol.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.gios.lightcontrol.keys.Grants
import com.gios.lightcontrol.keys.LightKeys

/**
 * Every grant this app needs, in one place, each with the adb line that sets it — and a door to
 * the ADB screen, which can run those lines against the phone itself with no computer attached.
 */
@Composable
fun SetupScreen(onAdb: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current

    val serviceOn = Grants.serviceEnabled(context)
    val canWrite = Grants.canWriteSettings(context)
    val canOverlay = Grants.canDrawOverlays(context)
    val canSecure = Grants.canWriteSecureSettings(context)

    SectionScaffold(
        title = "Setup & guide",
        onBack = onBack,
        guide = "LightOS has no Settings screens for the things this app needs, so each is granted " +
            "once over adb. Every row below shows its command; tap a row to see it. Or open the " +
            "ADB screen and let the phone grant itself everything.",
    ) {
        MenuRow(
            label = "Grant everything on the phone",
            detail = "›",
            sub = "no computer — the phone connects to its own wireless debugging and runs the " +
                "commands below itself",
            onClick = onAdb,
        )
        Rule()

        SectionLabel("GRANTS")
        GrantRow(
            label = "Key service (accessibility)",
            ok = serviceOn,
            fix = "adb shell settings put secure enabled_accessibility_services " +
                "com.gios.lightcontrol/com.gios.lightcontrol.keys.ControlService",
            sub = "the one grant everything else needs — without it nothing works",
        )
        GrantRow(
            label = "Brightness (WRITE_SETTINGS)",
            ok = canWrite,
            fix = "adb shell appops set com.gios.lightcontrol WRITE_SETTINGS allow",
        )
        GrantRow(
            label = "Overlay (SYSTEM_ALERT_WINDOW)",
            ok = canOverlay,
            fix = "adb shell appops set com.gios.lightcontrol SYSTEM_ALERT_WINDOW allow",
            sub = "the level readouts, and opening an app from the service",
        )
        GrantRow(
            label = "Colour (WRITE_SECURE_SETTINGS)",
            ok = canSecure,
            fix = "adb shell pm grant com.gios.lightcontrol " +
                "android.permission.WRITE_SECURE_SETTINGS",
            sub = "per-app colour. Inert without it, never a crash",
        )
        if (!LightKeys.wheelLabelsPresent()) {
            MenuRow(
                label = "Wheel keycodes",
                detail = "SCANCODE",
                sub = "this build doesn't publish WHEEL_CW; falling back to raw scancodes, which " +
                    "still works",
                dim = true,
            )
        }
    }
}
