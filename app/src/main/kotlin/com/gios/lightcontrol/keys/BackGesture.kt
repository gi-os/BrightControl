package com.gios.lightcontrol.keys

import kotlin.math.abs

/**
 * How far one stroke on an edge strip has got.
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

    /** Far enough. Lifting now performs the action. */
    Armed,

    /** Not an edge gesture. Nothing will fire for the rest of this stroke. */
    Cancelled,
}

/** Which edge a strip lives on, and therefore which way a stroke has to travel. */
enum class EdgeSide {
    /** Down the left edge. A stroke travels right. Goes back. */
    Left,

    /** Down the right edge. A stroke travels left. Opens the app switcher. */
    Right,
    ;

    /** The sign of a useful stroke along x, so one gesture serves both edges. */
    val dirX: Int get() = if (this == Left) 1 else -1
}

/**
 * One edge stroke, decided without a screen.
 *
 * Its own class, with no Android types in it, because the rules here are the whole feature and the
 * only other place they could be exercised is a touch listener that needs a phone and a pair of
 * hands. See `BackGestureTest`.
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
 * So a stroke that turns out to be a scroll is lost either way; what [BackStage.Cancelled] buys is
 * that it does not *also* fire when the finger happens to drift sideways at the end of a long
 * flick. One rule, checked once: if the stroke has moved further down or up than [slopPx] and
 * further that way than it has moved across, it was never an edge gesture and nothing later can
 * revive it.
 *
 * ### One class, two edges
 *
 * [side] is the only difference between going back and opening the switcher. Distance along x is
 * measured in the stroke's own direction, so every rule below reads the same for both, and a
 * left-edge stroke that travels left is exactly as meaningless as a right-edge stroke that travels
 * right — both stay at zero travel rather than arming something.
 */
class BackGesture(
    /** How far across, in pixels, arms the gesture. */
    private val triggerPx: Float,
    /** How far up or down, in pixels, gives the stroke away as a scroll. */
    private val slopPx: Float,
    /** Which edge this stroke starts from. Left by default, which is the back gesture. */
    val side: EdgeSide = EdgeSide.Left,
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
        val dx = (x - startX) * side.dirX
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

    /** True when the finger lifting means perform the action. */
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
