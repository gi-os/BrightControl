package com.gios.lightcontrol.notify

import android.content.Context
import android.os.PowerManager

/**
 * Turns the panel on for a banner, without becoming an activity.
 *
 * BrightChat wakes the screen with a `showWhenLocked` + `turnScreenOn` activity, and that route is
 * closed here. `showWhenLocked` marks the keyguard **occluded**, and AOSP's
 * `KeyguardUpdateMonitor.shouldListenForFingerprint` arms a power-button reader only while the
 * keyguard is *not* occluded -- so an activity that wakes the phone for a text is an activity that
 * stops the thumb unlocking it. v2.5 and v2.6 shipped that and it is written up at length in
 * [com.gios.lightcontrol.lock.LockOverlay]'s header.
 *
 * So the window and the wake were separated. [NoteBanner] draws at layer 31, which is above the
 * keyguard without touching it, and this holds the panel on and does nothing else: no window, no
 * task, no activity, and nothing the keyguard can observe.
 *
 * `SCREEN_BRIGHT_WAKE_LOCK` has been deprecated since API 17 and still works on 14. The sanctioned
 * replacement is `PowerManager.wakeUp`, which is `@hide` -- this app will not reach past the SDK
 * over a deprecation that functions, and if it ever stops functioning the failure is a banner
 * nobody saw rather than a phone that misbehaves.
 */
class BannerWake(private val context: Context) {

    /**
     * Hold the screen on for [dwellMs], then let go.
     *
     * Acquired with a timeout rather than released by hand. A release that depends on the code
     * after it running is a release that a throw skips, and the cost of skipping this one is a
     * panel lit until the battery is flat.
     */
    /**
     * One lock, held for the life of the service, rather than a new one per banner.
     *
     * A `WakeLock` built inside [wake] is a local nothing keeps a reference to, and
     * `WakeLock.finalize` releases a lock that is still held -- so a garbage collection during the
     * few seconds a banner is up would drop the panel mid-box, intermittently, which is the worst
     * possible way to find a bug. Reference counting off, so a second banner during the first
     * re-arms the timeout instead of stacking a hold that needs two releases.
     */
    private val lock: PowerManager.WakeLock? by lazy {
        val power = context.getSystemService(PowerManager::class.java) ?: return@lazy null
        @Suppress("DEPRECATION")
        runCatching {
            power.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                TAG,
            ).apply { setReferenceCounted(false) }
        }.getOrNull()
    }

    fun wake(dwellMs: Long) {
        // The margin is for the box being taken down by a tap rather than by its timer: the lock
        // outliving the box by half a second costs nothing, and the panel returning to the system
        // timeout is what should end it anyway.
        runCatching { lock?.acquire(dwellMs + MARGIN_MS) }
    }

    private companion object {
        /** Shows up in `dumpsys power` under this name, which is where anyone would look. */
        const val TAG = "BrightControl:banner"
        const val MARGIN_MS = 500L
    }
}
