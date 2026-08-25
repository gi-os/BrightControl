package com.gios.lightcontrol.keys

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityManager

/**
 * Whether the three things this app needs have actually been granted.
 *
 * All three are appops or secure settings that LightOS has no UI for, so the alternative to
 * reporting them on screen is adb archaeology every time the wheel goes quiet. Each one is
 * shown with the command that fixes it.
 */
object Grants {

    /**
     * Whether [ControlService] is actually enabled.
     *
     * Asked two ways, because `enabled_accessibility_services` is not stored in one canonical
     * form. `flattenToString` produces
     * `com.gios.lightcontrol/com.gios.lightcontrol.keys.ControlService`, but a service enabled
     * with `settings put secure` is normally stored short —
     * `com.gios.lightcontrol/.keys.ControlService` — and comparing those two as text reports OFF
     * for a service that is bound and filtering keys. That is what this screen did on a phone
     * whose service was running: the readout was wrong, not the service.
     *
     * So [AccessibilityManager] is asked first. It answers with resolved [ComponentName]s and so
     * cannot disagree with itself. The settings string remains as a fallback for the moment
     * before the manager has caught up — parsed rather than string-matched, with the short forms
     * expanded.
     */
    fun serviceEnabled(context: Context): Boolean {
        val expected = ComponentName(context, ControlService::class.java)
        val fromManager = runCatching {
            context.getSystemService(AccessibilityManager::class.java)
                ?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                ?.any { info ->
                    val service = info.resolveInfo?.serviceInfo ?: return@any false
                    ComponentName(service.packageName, service.name) == expected
                }
        }.getOrNull()
        if (fromManager == true) return true

        val raw = runCatching {
            Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            )
        }.getOrNull().orEmpty()
        return raw.split(':').any { entry -> expand(entry.trim()) == expected }
    }

    /** `pkg/.Class` and `pkg/pkg.Class` name the same service. */
    private fun expand(entry: String): ComponentName? {
        val parsed = ComponentName.unflattenFromString(entry) ?: return null
        val cls = parsed.className
        return when {
            cls.startsWith(".") -> ComponentName(parsed.packageName, parsed.packageName + cls)
            !cls.contains('.') -> ComponentName(parsed.packageName, "${parsed.packageName}.$cls")
            else -> parsed
        }
    }

    /**
     * Whether `WRITE_SECURE_SETTINGS` has been granted — the permission the daltonizer writes
     * need. It is signature|privileged, so it never comes from a runtime prompt; the ADB screen
     * or a computer grants it with `pm grant`, and until then the per-app color feature is inert.
     */
    fun canWriteSecureSettings(context: Context): Boolean =
        runCatching {
            context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)

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
        set(value) {
            val was = field
            field = value
            if (value && !was) runCatching { onResumed?.invoke() }
        }

    /**
     * Called when the settings screen comes to the front, once per arrival.
     *
     * The switcher's list is built from window-state events, and events from this package are
     * dropped before they get anywhere near it — so this app could never appear in its own list
     * of recent apps, which is the second half of light-reports#47. This is the one honest signal
     * that it was in front, and [com.gios.lightcontrol.keys.ControlService] is what records it.
     * Null whenever the service is not bound; the flag itself still works.
     */
    @Volatile
    var onResumed: (() -> Unit)? = null
}
