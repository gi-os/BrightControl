package com.gios.lightcontrol.keys

import android.content.ComponentName
import android.content.Context
import android.provider.Settings

/**
 * Whether the three things this app needs have actually been granted.
 *
 * All three are appops or secure settings that LightOS has no UI for, so the alternative to
 * reporting them on screen is adb archaeology every time the wheel goes quiet. Each one is
 * shown with the command that fixes it.
 */
object Grants {

    fun serviceEnabled(context: Context): Boolean {
        val expected = ComponentName(context, ControlService::class.java).flattenToString()
        val enabled = runCatching {
            Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            )
        }.getOrNull().orEmpty()
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    fun canWriteSettings(context: Context): Boolean =
        runCatching { Settings.System.canWrite(context) }.getOrDefault(false)

    fun canDrawOverlays(context: Context): Boolean =
        runCatching { Settings.canDrawOverlays(context) }.getOrDefault(false)
}

/**
 * Whether LightControl's own settings screen is the thing in front.
 *
 * The service learns the foreground app from window-state events, but its own readout overlay
 * raises those too — so events from this package are ignored, and the activity reports itself
 * directly instead. Service and activity share a process, so a volatile flag is the whole
 * mechanism; no IPC, and no chance of the overlay being mistaken for the app.
 */
object OwnWindow {
    @Volatile
    var resumed: Boolean = false
}
