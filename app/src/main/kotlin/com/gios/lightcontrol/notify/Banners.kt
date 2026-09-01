package com.gios.lightcontrol.notify

import android.app.PendingIntent
import android.os.Handler
import android.os.Looper

/**
 * Decides which notification is worth a box, and waits a moment before saying so.
 *
 * The seam between the notification listener and the window, in the shape the rest of this app
 * already uses ([com.gios.lightcontrol.lock.LockCalls], [com.gios.lightcontrol.lock.LockNotes]):
 * the listener knows what arrived, the service owns the window, and neither reaches into the
 * other. This holds the one rule that is nobody else's -- when a banner is *new*.
 *
 * ### The two seconds
 *
 * A banner is armed and drawn [GRACE_MS] later, and the wait is the whole reason this is not three
 * lines inside the listener.
 *
 * BrightChat learned it the expensive way. Its socket delivers `new-message` before the
 * `chat-read-status-changed` that says you have already read the thing on the Mac, so a text read
 * at a desk lit the phone next to it anyway; it now waits two seconds for the second event and
 * cancels. Drawing straight off `onNotificationPosted` would reintroduce that here, for every app
 * at once, and none of those apps could tell us.
 *
 * They do not have to. An app that decides an alert is stale cancels its notification, and a
 * cancelled notification is a `refresh()` with the key gone -- so waiting two seconds and checking
 * the key is still live reproduces BrightChat's fix generically, with no IPC and no knowledge of
 * any particular app. It costs two seconds of lateness on a box that then sits for four and a half.
 *
 * ### What counts as new
 *
 * `postTime` after [connected], and never a key and time already handled. Both are needed for the
 * same reason: the first `refresh()` after the listener binds hands over **everything in the
 * shade**, which at a reboot is a morning's worth of notifications. Without the baseline the phone
 * would come up and immediately draw a box about a text from yesterday.
 */
object Banners {

    /** One candidate, flattened to what a box can draw and one thing it can press. */
    data class Note(
        val key: String,
        val pkg: String,
        val app: String,
        val title: String,
        val text: String,
        val postedAt: Long,
        /** The notification's own `contentIntent`, sent on a tap. Null means the box is inert. */
        val open: PendingIntent?,
    )

    /**
     * Told when a banner should go up. Set by the service, which owns the window.
     *
     * Fires on the main thread, because [sync] is called from the listener's and adding a window
     * needs a Looper.
     */
    var onShow: ((Note, Long) -> Unit)? = null

    private val handler = Handler(Looper.getMainLooper())

    /** When the listener bound. Nothing posted before this moment is news. */
    @Volatile
    private var connected = 0L

    // Main thread only, this and [armedThrough] both. [sync] is called from the listener's
    // thread and posts; nothing else here touches them without doing the same.
    /**
     * Every banner still waiting out its grace, keyed the way the shade keys notifications.
     *
     * A map rather than one pending/pendingRun slot, because two *different* keys arriving inside
     * the same two seconds are both news and both show ([NoteBanner.show] swaps the text of a box
     * already up). One slot meant the second arrival overwrote the first's record while the
     * first's runnable stayed queued -- and when that runnable fired it nulled the slot
     * unconditionally, orphaning the second: still due to draw, but invisible to the withdrawal
     * sweep below and to the setting switching off. Per key, a runnable can only ever clear its
     * own entry.
     */
    private val queued = mutableMapOf<String, Pair<Note, Runnable>>()

    /**
     * The newest `postTime` this has ever armed for. A high-water mark, not a memory of one note.
     *
     * A single key-plus-time slot was not enough and failed in a way that reads as the feature
     * being broken. The shade shrinking makes an *older* notification the newest one again -- and
     * it does so constantly, because tapping a banner opens the app, which cancels its own
     * notification, and unlocking calls `LockNotes.clearSessionHides()`, which re-admits everything
     * swiped off the face. With only a key to compare against, that older note passed every test
     * and banged a box up about a text from five minutes ago, over the app just opened to read it.
     *
     * Against a high-water mark it cannot: becoming the newest row again is not the same event as
     * arriving, and only arriving has a `postTime` past this line.
     */
    private var armedThrough = 0L

    /**
     * Called from `onListenerConnected`. Draws the line between the backlog and the news.
     *
     * The timestamp is written straight away and the rest is posted, because the two have
     * different deadlines: [connected] must be set before the `refresh()` on the very next line of
     * the caller, and everything else here touches state that only the main thread may.
     */
    fun listenerConnected() {
        connected = System.currentTimeMillis()
        handler.post {
            cancelAll()
            armedThrough = 0L
        }
    }

    fun listenerDisconnected() {
        connected = 0L
        handler.post { cancelAll() }
    }

    /**
     * One rebuild of the shade, as the listener sees it.
     *
     * [live] is every key that survived the face's filter, [candidate] the newest of them, already
     * ruled out if it is the permanent kind. Both come from the same pass so the cancel and the
     * arm cannot disagree about what is in the shade.
     */
    fun sync(live: Set<String>, candidate: Note?, enabled: Boolean, dwellMs: Long) {
        handler.post {
            // Switched off mid-wait. Nothing half-armed should survive the setting changing.
            if (!enabled || connected == 0L) {
                cancelAll()
                return@post
            }
            // The app withdrew it inside the grace window -- read somewhere else, cancelled,
            // superseded. This is the whole point of waiting. See the header. Swept over every
            // queued key, not just the newest: a withdrawal can land for the older of two waiting.
            queued.keys.filter { it !in live }.forEach { cancelQueued(it) }

            val note = candidate ?: return@post
            if (note.postedAt <= connected) return@post
            if (note.postedAt <= armedThrough) return@post
            // A newer post *on the same key* replaces the box already waiting -- an app editing its
            // own notification is one event to be told about, not two. A different app's arriving
            // mid-wait must not cancel the first: [NoteBanner.show] swaps the text of a box already
            // up, and throwing the earlier one away here meant the first of two notifications a
            // second apart was never seen at all.
            cancelQueued(note.key)
            armedThrough = note.postedAt
            val run = Runnable {
                // Its own entry and nothing else's: another key still inside its grace stays
                // queued, cancellable, and due.
                queued.remove(note.key)
                runCatching { onShow?.invoke(note, dwellMs) }
            }
            queued[note.key] = note to run
            handler.postDelayed(run, GRACE_MS)
        }
    }

    /** One key is no longer waiting after this. Main thread only. */
    private fun cancelQueued(key: String) {
        queued.remove(key)?.let { (_, run) -> handler.removeCallbacks(run) }
    }

    /** Nothing is waiting after this. Main thread only; every caller here is already on it. */
    private fun cancelAll() {
        queued.values.forEach { (_, run) -> handler.removeCallbacks(run) }
        queued.clear()
    }

    /**
     * How long to wait before drawing. BrightChat's `READ_GRACE_MS`, and the same number for the
     * same reason -- long enough for a read on another device to come back, short enough that a
     * genuinely new message still feels immediate.
     */
    private const val GRACE_MS = 2_000L
}
