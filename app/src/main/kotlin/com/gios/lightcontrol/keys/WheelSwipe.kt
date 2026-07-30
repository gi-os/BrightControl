package com.gios.lightcontrol.keys

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import kotlin.math.abs

/**
 * Turning the wheel into a finger that never lifts.
 *
 * ### Why this shape
 *
 * What you actually want here is a mouse wheel: `MotionEvent.ACTION_SCROLL` with
 * `AXIS_VSCROLL`, which every scrollable on Android handles natively — Compose,
 * RecyclerView, WebView, the lot. Injecting a MotionEvent into another app needs
 * `INJECT_EVENTS`, a signature permission, and an accessibility service can only dispatch
 * *touch* strokes. There is no "send a scroll event" API out here.
 *
 * The first attempt was therefore a flick per burst of notches, and it felt like one: each
 * stroke is a separate finger that touches down, drags and lifts, so the app sees a series of
 * small flings with gaps between them, and momentum fights the next stroke.
 *
 * [GestureDescription.StrokeDescription.continueStroke] fixes the premise. A stroke marked
 * `willContinue` leaves the finger *down*, and each continuation moves it further, so the
 * whole turn is one drag that tracks the wheel. No fling, no gaps, and no queue of competing
 * gestures — which is as close to a scroll wheel as the platform allows.
 *
 * ### The two things that make it fiddly
 *
 * **A continuation must start where the last one ended**, so the current position is tracked
 * rather than recomputed. And a finger cannot leave the screen: when it reaches the edge of
 * the safe band it is lifted and the next notch starts a fresh stroke from the middle. That
 * relift is the one visible seam, and it is unavoidable — a real finger has the same limit,
 * which is why people swipe repeatedly rather than dragging one screen-length inch.
 *
 * **Only one gesture may be in flight.** Notches arrive every ~35 ms and a segment takes
 * [SEGMENT_MS] to play, so anything arriving mid-segment accumulates and the next segment
 * carries it. The continuation is dispatched from the completion callback, which is the only
 * moment the framework will accept it.
 */
class WheelSwipe(private val service: AccessibilityService) {

    private val handler = Handler(Looper.getMainLooper())

    /** Distance still to travel, in pixels of finger movement. */
    private var pending = 0f

    /** Where the finger is now, or null when it is not touching the screen. */
    private var y: Float? = null

    private var stroke: GestureDescription.StrokeDescription? = null
    private var dispatching = false

    private val lift = Runnable { runCatching { finish() } }

    /**
     * Move by [notches]. Positive notches move *down* the content, so the finger travels up.
     */
    fun turn(notches: Int, dpPerNotch: Int) {
        val metrics = service.resources.displayMetrics
        pending -= notches * dpPerNotch * metrics.density
        handler.removeCallbacks(lift)
        if (!dispatching) send(metrics)
    }

    /** Called when the service goes away, so a held finger can't outlive it. */
    fun cancel() {
        handler.removeCallbacks(lift)
        pending = 0f
        y = null
        stroke = null
        dispatching = false
    }

    private fun send(metrics: DisplayMetrics) {
        if (abs(pending) < 1f) {
            scheduleLift()
            return
        }

        val x = metrics.widthPixels / 2f
        // The band the finger stays inside. Away from the very edges, where a drag becomes a
        // system gesture — the back swipe and the home swipe both live there.
        val top = metrics.heightPixels * 0.18f
        val bottom = metrics.heightPixels * 0.82f
        val from = y ?: (metrics.heightPixels / 2f)

        val to = (from + pending).coerceIn(top, bottom)
        val travelled = to - from
        if (abs(travelled) < 1f) {
            // Already against the band edge: lift, and let the next notch start again from
            // the middle of the screen.
            finish()
            return
        }
        pending -= travelled

        val path = Path().apply {
            moveTo(x, from)
            lineTo(x, to)
        }

        val previous = stroke
        // Every one of these throws IllegalArgumentException on a path the framework doesn't
        // like — a continuation that doesn't start where the last ended, a coordinate off the
        // display, a duration it disagrees with. Uncaught, that used to take the whole key
        // filter down with it, which is a scroll bug becoming a phone you can't dismiss an
        // alarm on. Building and dispatching are one attempt, and failure means start over.
        val next = runCatching {
            if (previous == null) {
                GestureDescription.StrokeDescription(path, 0, SEGMENT_MS, true)
            } else {
                previous.continueStroke(path, 0, SEGMENT_MS, true)
            }
        }.getOrNull()
        val gesture = next?.let { runCatching { GestureDescription.Builder().addStroke(it).build() }.getOrNull() }
        if (next == null || gesture == null) {
            restart()
            return
        }

        dispatching = true
        val ok = runCatching {
            service.dispatchGesture(
                gesture,
                object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(description: GestureDescription?) = step()
                    override fun onCancelled(description: GestureDescription?) = restart()
                },
                null,
            )
        }.getOrDefault(false)
        if (!ok) {
            restart()
            return
        }
        stroke = next
        y = to
    }

    /** The previous segment landed. Carry on if the wheel is still turning. */
    private fun step() {
        dispatching = false
        if (abs(pending) >= 1f) send(service.resources.displayMetrics) else scheduleLift()
    }

    /**
     * The gesture was cancelled — a real finger touched the screen, or the window changed
     * under us. Drop everything: the touch that cancelled it is the user's intent, and
     * fighting it with a synthetic finger is how you end up with a scroll that won't stop.
     */
    private fun restart() {
        dispatching = false
        pending = 0f
        y = null
        stroke = null
    }

    private fun scheduleLift() {
        handler.removeCallbacks(lift)
        // Held down briefly after the last notch, so a pause between turns of the same
        // gesture doesn't cost a lift and a re-touch.
        handler.postDelayed(lift, HOLD_AFTER_MS)
    }

    /**
     * Lift the finger. A stroke that said `willContinue` has to be closed by a continuation
     * that says otherwise, and the path still needs a length, so it ends on a one-pixel move
     * — small enough to change nothing, long enough to be a legal path.
     */
    private fun finish() {
        val previous = stroke ?: return
        dispatching = false
        val at = y ?: return
        stroke = null
        y = null
        pending = 0f

        val x = service.resources.displayMetrics.widthPixels / 2f
        val path = Path().apply {
            moveTo(x, at)
            lineTo(x, at + 1f)
        }
        // Guarded like the rest, and for a sharper reason: this one runs from a Handler, not
        // from a key event, so nothing upstream is holding a catch for it. An uncaught throw
        // here takes the process down a beat after the wheel stopped moving.
        runCatching {
            val end = previous.continueStroke(path, 0, 1, false)
            service.dispatchGesture(GestureDescription.Builder().addStroke(end).build(), null, null)
        }
        dispatching = false
    }

    private companion object {
        /** One segment's play time. Roughly a couple of frames — long enough to be dispatched,
         *  short enough that the finger keeps up with a fast turn. */
        const val SEGMENT_MS = 32L

        /** How long the finger stays down after the last notch. */
        const val HOLD_AFTER_MS = 120L
    }
}
