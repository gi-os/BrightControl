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
     * Takes the face down. Set by the activity while it exists, called by the service.
     *
     * The direction reversed in v2.6, and the reversal is the whole fix. It used to be the
     * activity that noticed the unlock and told the service; but showing the bouncer over an
     * occluding activity *stops* it, so the activity had unregistered its receiver by the time
     * `ACTION_USER_PRESENT` arrived and the face simply stayed up over an open phone. The service
     * is bound by the system, never stopped and never frozen, so it is the half that can be
     * relied on to notice. It notices, then reaches in here.
     */
    @Volatile
    var dismiss: (() -> Unit)? = null

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
