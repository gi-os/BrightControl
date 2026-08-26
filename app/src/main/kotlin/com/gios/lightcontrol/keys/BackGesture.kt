package com.gios.lightcontrol.keys

import kotlin.math.abs

/**
 * How far one stroke on the edge strip has got.
 *
 * [Armed] is the state the indicator draws differently, and it is reversible on purpose: a drag
 * that comes back under the trigger disarms, which is the only way to change your mind about a
 * gesture that has already been started.
 */
enum class BackStage {
    /** No finger down. */
    Idle,

    /** A finger is down and has not travelled far enough to mean anything yet. */
    Watching,

    /** Far enough. Lifting now goes back. */
    Armed,

    /** Not a back gesture. Nothing will fire for the rest of this stroke. */
    Cancelled,
}

/**
 * One edge stroke, decided without a screen.
 *
 * Its own class, with no Android types in it, because the rules here are the whole feature and the
 * only other place they could live is a touch listener that needs a phone and a pair of hands to
 * exercise. See `BackGestureTest`.
 *
 * ### Why the lift decides, and not the threshold
 *
 * Crossing the trigger fires immediately in the naive version, and that makes the gesture
 * unabortable: the moment your thumb is far enough across, the app behind you has gone, whether or
 * not that is what the thumb was doing. So crossing only *arms* it, coming back under the trigger
 * disarms it, and the lift is what commits — which is also what gives the indicator something to
 * say while the finger is still down.
 *
 * ### Why a vertical stroke has to be cancelled rather than ignored
 *
 * The strip consumes every touch that starts on it, and a swallowed touch cannot be handed back.
 * So a stroke that turns out to be a scroll is lost either way; what [Cancelled] buys is that it
 * does not *also* go back when the finger happens to drift sideways at the end of a long flick.
 * One rule, checked once: if the stroke has moved further down or up than [slopPx] and further
 * that way than it has moved across, it was never a back gesture and nothing later can revive it.
 */
class BackGesture(
    /** How far across, in pixels, arms the gesture. */
    private val triggerPx: Float,
    /** How far up or down, in pixels, gives the stroke away as a scroll. */
    private val slopPx: Float,
) {

    var stage: BackStage = BackStage.Idle
        private set

    /**
     * Progress toward the trigger, 0 to 1.
     *
     * Held rather than recomputed by the indicator, so the drawing has one source and cannot
     * disagree with the decision.
     */
    var travel: Float = 0f
        private set

    /** Where the indicator sits, in pixels down the screen: where the finger went down. */
    var anchorY: Float = 0f
        private set

    fun down(x: Float, y: Float) {
        startX = x
        startY = y
        anchorY = y
        travel = 0f
        stage = BackStage.Watching
    }

    /** True when something the indicator draws has changed. */
    fun move(x: Float, y: Float): Boolean {
        if (stage == BackStage.Idle || stage == BackStage.Cancelled) return false
        val dx = x - startX
        val dy = y - startY
        if (abs(dy) > slopPx && abs(dy) > abs(dx)) {
            stage = BackStage.Cancelled
            travel = 0f
            return true
        }
        val before = stage
        val was = travel
        travel = (dx / triggerPx).coerceIn(0f, 1f)
        stage = if (dx >= triggerPx) BackStage.Armed else BackStage.Watching
        // A whole pixel of movement is the smallest change worth a repaint. Every touch move
        // event on this panel would otherwise redraw a window over the app in front.
        return stage != before || abs(travel - was) * triggerPx >= 1f
    }

    /** True when the finger lifting means go back. */
    fun up(): Boolean {
        val fire = stage == BackStage.Armed
        reset()
        return fire
    }

    fun reset() {
        stage = BackStage.Idle
        travel = 0f
        startX = 0f
        startY = 0f
    }

    private var startX = 0f
    private var startY = 0f
}
