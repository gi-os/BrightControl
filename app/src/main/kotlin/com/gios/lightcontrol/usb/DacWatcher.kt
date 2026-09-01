package com.gios.lightcontrol.usb

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build

/**
 * The second route to the same event, and the one that costs nothing.
 *
 * [DacAttachActivity] is what carries the USB permission, and it is the route that works. This is
 * a plain runtime receiver on the same broadcast, registered by the key service, and it exists for
 * two things the activity cannot do:
 *
 * - **Detach.** The volume lives in the adapter's RAM, so unplugging it is a real state change and
 *   the settings screen should say so rather than going on showing a level that no longer exists
 *   anywhere.
 * - **The attach the activity missed.** A user who answered the platform's "which app" dialog with
 *   "just once" gets no activity on the next connect. If permission happens to still be held, this
 *   raises the adapter anyway; if it is not, [DacUnlock] says which grant is missing instead of
 *   failing silently. Either way the reason lands on screen.
 *
 * Registered rather than declared. A manifest receiver for `USB_DEVICE_ATTACHED` is not on the
 * platform's list of broadcasts exempt from background restrictions, so on Android 14 it is a
 * component that may or may not be woken — and this app already has a service that is always
 * bound, which is a better place to listen from than a maybe.
 */
class DacWatcher(
    private val context: Context,
    private val log: (String) -> Unit,
) {

    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            val device = runCatching {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
            }.getOrNull()
            when (action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    if (device == null || !DacUnlock.hasAudioControl(device)) return
                    DacSignals.onAttached(DacUnlock.name(device))
                    DacUnlock.apply(this@DacWatcher.context, device, why = "broadcast", log = log)
                }

                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    if (device == null || !DacUnlock.hasAudioControl(device)) return
                    DacUnlock.onDetached()
                    log("usb dac · ${DacUnlock.name(device)} unplugged")
                }
            }
        }
    }

    fun start() {
        if (registered) return
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        // Protected system broadcasts either way, so the flag is not strictly required. Passed
        // because "not exported" is the true answer and the default for this has changed once.
        registered = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(receiver, filter)
            }
            true
        }.getOrDefault(false)
    }

    /**
     * Hand the receiver back.
     *
     * A receiver registered and never unregistered outlives the service that registered it: the
     * system keeps delivering into an unbound instance, and every rebind adds another one. The
     * same lesson the keyguard listener in the key service learned, and cheaper to apply here in
     * advance than to find later in a report about a phone that raises the adapter four times.
     */
    fun stop() {
        if (!registered) return
        registered = false
        runCatching { context.unregisterReceiver(receiver) }
    }
}
