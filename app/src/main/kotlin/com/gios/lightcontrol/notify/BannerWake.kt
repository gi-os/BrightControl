package com.gios.lightcontrol.notify

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.Gravity
import android.view.View
import android.view.WindowManager

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
 * keyguard without touching it, and this lights the panel and does nothing else: no task, no
 * activity, and nothing the keyguard can observe.
 *
 * ### Two halves, and they do different jobs
 *
 * **Turning the panel on** is [poke]: a 1x1 transparent window carrying `FLAG_TURN_SCREEN_ON`,
 * added and taken down again a moment later. **Keeping it on** is the wake lock. They are not
 * redundant -- a flag that wakes the display does not hold it awake, and a screen wake lock
 * acquired against a sleeping panel is a request the display may simply not act on.
 *
 * v3.65 through v4.25 shipped the lock alone, and a Light Phone III with banners on, the lock face
 * off and `SCREEN_BRIGHT_WAKE_LOCK` doing nothing was a phone that drew every banner perfectly
 * while it was awake and never once woke up for one. Nothing said so: the whole acquire is inside
 * a `runCatching` and a lock the display ignores does not throw.
 *
 * `SCREEN_BRIGHT_WAKE_LOCK` has been deprecated since API 17 and `FLAG_TURN_SCREEN_ON` since 27.
 * Both stay. The sanctioned replacement for the first is `PowerManager.wakeUp`, which is `@hide`,
 * and for the second `Activity.setTurnScreenOn`, which is the activity this class exists to avoid.
 * Between a deprecation that works and an activity that disarms the fingerprint reader, the
 * deprecation wins.
 *
 * ### Why the flag goes on a window of its own
 *
 * Not on [NoteBanner]'s window, and emphatically not on the lock face's. `FLAG_TURN_SCREEN_ON`
 * fires from `WindowState.prepareWindowToDisplayDuringRelayout` when a window is shown, and the
 * lock face is added **as the screen goes off** -- the flag there would light the panel back up
 * every time the phone was put down. The banner's own window would do the job, but only for a
 * phone with banners on: with the lock face on the box is deliberately not drawn, because the face
 * already carries the row. One window owned by the wake means both settings wake the same way.
 *
 * Nothing here has an `ActivityRecord` attached, which is what makes it safe:
 * `prepareWindowToDisplayDuringRelayout` asks `mActivityRecord.currentLaunchCanTurnScreenOn()`
 * only when there is one, and calls `PowerManager.wakeUp(WAKE_REASON_APPLICATION)` for a plain
 * window. That is the system server's own wake, and it leaves the keyguard exactly as it was.
 */
class BannerWake(private val context: Context) {

    private val handler = Handler(Looper.getMainLooper())

    /**
     * Hold the screen on for the dwell, then let go.
     *
     * Acquired with a timeout rather than released by hand. A release that depends on the code
     * after it running is a release that a throw skips, and the cost of skipping this one is a
     * panel lit until the battery is flat.
     *
     * One lock, held for the life of the service, rather than a new one per banner. A `WakeLock`
     * built inside [wake] is a local nothing keeps a reference to, and `WakeLock.finalize`
     * releases a lock that is still held -- so a garbage collection during the few seconds a
     * banner is up would drop the panel mid-box, intermittently, which is the worst possible way
     * to find a bug. Reference counting off, so a second banner during the first re-arms the
     * timeout instead of stacking a hold that needs two releases.
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

    /** The window whose flag does the waking. Null whenever none is up. */
    private var window: View? = null

    private val drop = Runnable { release() }

    /**
     * Light the panel, and keep it lit for [dwellMs].
     *
     * Both halves, in the order that matters: the lock first, so the hold is already in place when
     * the display comes up, then the window that brings it up.
     */
    fun wake(dwellMs: Long) {
        // The margin is for the box being taken down by a tap rather than by its timer: the lock
        // outliving the box by half a second costs nothing, and the panel returning to the system
        // timeout is what should end it anyway.
        runCatching { lock?.acquire(dwellMs + MARGIN_MS) }
        // Posted rather than called, because a window may only be added from a thread with a
        // looper and this is reachable from the listener's.
        handler.post { runCatching { poke() } }
    }

    /**
     * Take the wake window down.
     *
     * Public because the service has to be able to call it on the way out: a window that outlives
     * this object has nothing left holding a handle to it. Idempotent, and safe on a wake that
     * never happened.
     */
    fun release() {
        handler.removeCallbacks(drop)
        val view = window ?: return
        window = null
        runCatching { context.getSystemService(WindowManager::class.java)?.removeView(view) }
    }

    /**
     * Add the 1x1 window, and arm its removal.
     *
     * Short-lived on purpose. The flag does its work when the window is shown, and what keeps the
     * panel on after that is the wake lock and then the phone's own screen timeout -- so there is
     * no reason for this to still be on the phone a second later, and every reason for it not to
     * be. A second banner while one is up re-arms the removal rather than adding another window.
     */
    @Suppress("DEPRECATION")
    private fun poke() {
        handler.removeCallbacks(drop)
        handler.postDelayed(drop, LIFE_MS)
        if (window != null) return
        val wm = context.getSystemService(WindowManager::class.java) ?: return
        val view = View(context)
        val params = WindowManager.LayoutParams(
            1,
            1,
            // Layer 31, like every other window this app raises. The lock face may well be up
            // underneath: this one is a single transparent pixel and takes no touches, so being
            // above it changes nothing about it.
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }
        runCatching { wm.addView(view, params) }.onSuccess { window = view }
    }

    private companion object {
        /** Shows up in `dumpsys power` under this name, which is where anyone would look. */
        const val TAG = "BrightControl:banner"
        const val MARGIN_MS = 500L

        /**
         * How long the wake window stays.
         *
         * Long enough to be shown -- the flag fires on the relayout, not on `addView` returning --
         * and short enough that it is gone well before anything the user does with the phone.
         */
        const val LIFE_MS = 1_500L
    }
}
