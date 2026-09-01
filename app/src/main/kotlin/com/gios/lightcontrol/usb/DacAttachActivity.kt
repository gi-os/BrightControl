package com.gios.lightcontrol.usb

import android.app.Activity
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle

/**
 * The window that exists so this app is allowed to talk to the adapter, and for no other reason.
 *
 * ### Why an activity and not the receiver
 *
 * `UsbManager.requestPermission` puts up a dialog. That is the wrong shape twice over here: it
 * would arrive on top of whatever app was in front at the moment a cable went in, and it would
 * arrive **every time**, because the dialog it raises is per-connection and this feature runs on
 * every connection for the life of the phone.
 *
 * The other route is the one the platform intends. An activity with a `USB_DEVICE_ATTACHED`
 * intent filter and a device filter resource is *offered* the device on attach, and being offered
 * it comes with permission to open it — the user answers once, ticks "use by default for this
 * device", and every later attach is silent. That grant is what this activity is: a way to be
 * handed a `UsbDevice` this app is permitted to open.
 *
 * ### So it must be invisible, and it must be brief
 *
 * It has no layout, no theme worth the name, and it calls `finish()` before `onCreate` returns.
 * `noHistory`, `excludeFromRecents` and its own empty `taskAffinity` keep it out of the recents
 * list and out of the task the user was in; the transparent theme and the disabled window
 * animation keep it off the screen. The work is handed to [DacUnlock], which runs it on a worker
 * thread — a claim, a handful of control transfers and a release, all of it slower than a frame
 * and none of it anything to hold an activity open for.
 *
 * ### And it is disabled in the manifest
 *
 * `android:enabled="false"`, flipped by [DacUnlock.setEnabled] when the feature is switched on.
 * A live filter means the platform asks the user which app should handle every audio adapter they
 * plug in, whether or not this feature is on, which is a dialog earned by nothing. Off has to
 * mean off.
 */
class DacAttachActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val device = runCatching {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
        }.getOrNull()
        if (device != null) DacSignals.onAttached(DacUnlock.name(device))
        // Application context on purpose: the work outlives this activity by design, and a worker
        // holding a finished activity is a leak with nothing to gain by it.
        runCatching { DacUnlock.apply(applicationContext, device, why = "attached") }
        finish()
        // A transition on a window nobody sees is a frame of black over the app underneath.
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
