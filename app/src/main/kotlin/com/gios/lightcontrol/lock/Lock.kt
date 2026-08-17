package com.gios.lightcontrol.lock

/**
 * What the lock face and the service agree on.
 *
 * Shrunk to one field in v2.7. It used to carry a callback in each direction, because the face was
 * an activity in another lifecycle that had to be told things and had to tell things back. The
 * face is now a window owned by the service itself ([LockOverlay]), so there is nothing to talk
 * to — the service simply calls methods on the object it holds.
 */
object Lock {

    /**
     * What the service will resume into, published at screen-off so the face could say so.
     *
     * A copy of the service's `slept` snapshot rather than a second source of truth: the service
     * still decides, still checks the list, still spends the snapshot on use.
     */
    @Volatile
    var pending: String? = null
}
