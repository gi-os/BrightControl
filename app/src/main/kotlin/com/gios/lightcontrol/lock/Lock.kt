package com.gios.lightcontrol.lock

/**
 * The seam between [com.gios.lightcontrol.keys.ControlService] and [LockActivity].
 *
 * Service and activity share a process, exactly like [com.gios.lightcontrol.keys.OwnWindow], so
 * two volatile fields are the whole mechanism — no IPC, no binder, nothing to leak if either end
 * dies. The service owns the decision about *where* an unlock lands, because the snapshot it is
 * made from (`slept`) is taken in the service and cannot be taken anywhere else: a backgrounded
 * app is frozen and never sees `ACTION_SCREEN_OFF`, which is the entire reason the resume feature
 * lives in an accessibility service in the first place.
 *
 * So the activity does not decide anything. It draws a face, notices the device has been unlocked,
 * and calls [onUnlock]. If the service is not running the call goes nowhere and the phone simply
 * behaves as it did before — which is the failure mode every guard in this feature is aiming for.
 */
object Lock {

    /**
     * Set by the service while it is bound. Invoked once per unlock, from the main thread.
     *
     * Nullable rather than a no-op default because "the service is gone" is a state the activity
     * has to be able to see: with nothing to hand off to, finishing is still correct, but there is
     * no point holding the screen for a handoff that will not come.
     */
    @Volatile
    var onUnlock: (() -> Unit)? = null

    /**
     * Whether a lock face is currently on screen.
     *
     * Read by the service before starting one. Not to save the start — `singleTask` would collapse
     * it anyway — but to keep the throttle in `ControlService.start` for presses that matter. A
     * screen that goes off, on and off again inside a second is ordinary; spending the app's one
     * start-per-destination on it is not.
     */
    @Volatile
    var showing: Boolean = false

    /**
     * What the service will resume into, published at screen-off so the face can say so.
     *
     * A copy of the service's `slept` snapshot rather than a second source of truth: the service still
     * decides, still checks the list, still spends the snapshot on use. This exists only so the label
     * on the lock face and the app that actually opens are read from the same value — a face that
     * promised one app and delivered another would be worse than one that promised nothing.
     */
    @Volatile
    var pending: String? = null
}
