package com.gios.lightcontrol.keys

import com.gios.lightcontrol.EdgeLength
import com.gios.lightcontrol.EdgeSide
import kotlin.math.abs

/**
 * How far one stroke on an edge strip has got.
 *
 * The two armed states are reversible on purpose: a drag that comes back under a threshold drops to
 * the stage below it, which is the only way to change your mind about a gesture already started.
 */
enum class BackStage {
    /** No finger down. */
    Idle,

    /** A finger is down and has not travelled far enough to mean anything yet. */
    Watching,

    /** Past the short threshold. Lifting now performs the short binding. */
    Armed,

    /** Past the long threshold. Lifting now performs the long binding instead. */
    ArmedLong,

    /** Not an edge gesture. Nothing will fire for the rest of this stroke. */
    Cancelled,
    ;

    /** Which binding a lift at this stage would perform, or null for none. */
    val fires: EdgeLength?
        get() = when (this) {
            Armed -> EdgeLength.Short
            ArmedLong -> EdgeLength.Long
            else -> null
        }
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
 * Crossing a threshold fires immediately in the naive version, and that makes the gesture
 * unabortable: the moment your thumb is far enough across, the app behind you has gone, whether or
 * not that is what the thumb was doing. So crossing only *arms* it, coming back under it disarms,
 * and the lift is what commits — which is also what gives the indicator something to say while the
 * finger is still down.
 *
 * With two thresholds that property is what makes a long swipe usable at all. Passing the short one
 * on the way to the long one is unavoidable, so a gesture that fired on crossing would perform the
 * short binding every single time and then perform the long one as well.
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
 * A long swipe crosses most of the screen, so it has more room to drift than a short one — which is
 * exactly why the rule compares the two distances rather than testing the vertical one alone. A
 * stroke 200px across and 60px down is still plainly horizontal.
 *
 * ### One class, two edges
 *
 * [side] is the only difference between the edges. Distance along x is measured in the stroke's own
 * direction, so every rule below reads the same for both, and a left-edge stroke that travels left
 * is exactly as meaningless as a right-edge stroke that travels right — both stay at zero travel
 * rather than arming something.
 */
class BackGesture(
    /** How far across, in pixels, arms the short binding. */
    private val triggerPx: Float,
    /** How far up or down, in pixels, gives the stroke away as a scroll. */
    private val slopPx: Float,
    /**
     * How far across arms the long binding, or 0 for an edge with no long swipe.
     *
     * Clamped up to just past [triggerPx] when a caller passes something smaller: two thresholds
     * in the wrong order would make the short binding unreachable, and a settings screen cannot be
     * trusted never to produce that.
     */
    longPx: Float = 0f,
    /** Which edge this stroke starts from. Left by default. */
    val side: EdgeSide = EdgeSide.Left,
) {

    private val longAt: Float =
        if (longPx <= 0f) 0f else longPx.coerceAtLeast(triggerPx + MIN_GAP_PX)

    /** Whether this edge has a long swipe at all. */
    val hasLong: Boolean get() = longAt > 0f

    /** The distance a full stroke covers: the furthest threshold there is. */
    private val span: Float get() = if (hasLong) longAt else triggerPx

    var stage: BackStage = BackStage.Idle
        private set

    /**
     * Progress across the whole gesture, 0 to 1, where 1 is the furthest threshold.
     *
     * Held rather than recomputed by the indicator, so the drawing has one source and cannot
     * disagree with the decision. Measured against the *long* threshold when there is one, so the
     * box on screen keeps growing after the short binding arms instead of sitting full for the
     * second half of the stroke with no indication there is anything further to reach.
     */
    var travel: Float = 0f
        private set

    /**
     * Where along that progress the short binding arms, 0 to 1.
     *
     * For the tick the indicator draws. Without it a long swipe is a guess: the box grows, the word
     * changes at some point, and there is nothing to say how much further the second stage is. With
     * it the mark you have to get past is on screen before you get there.
     */
    val armPoint: Float get() = if (span <= 0f) 1f else (triggerPx / span).coerceIn(0f, 1f)

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
        travel = (dx / span).coerceIn(0f, 1f)
        stage = when {
            hasLong && dx >= longAt -> BackStage.ArmedLong
            dx >= triggerPx -> BackStage.Armed
            else -> BackStage.Watching
        }
        // A whole pixel of movement is the smallest change worth a repaint. Every touch move
        // event on this panel would otherwise redraw a window over the app in front.
        return stage != before || abs(travel - was) * span >= 1f
    }

    /** Which binding the finger lifting means, or null for none. */
    fun up(): EdgeLength? {
        val fires = stage.fires
        reset()
        return fires
    }

    fun reset() {
        stage = BackStage.Idle
        travel = 0f
        startX = 0f
        startY = 0f
    }

    private var startX = 0f
    private var startY = 0f

    private companion object {
        /**
         * The least room there can be between the two thresholds.
         *
         * A long threshold at or below the short one makes the short binding unreachable — every
         * stroke that armed it would already have armed the long one — and there is no value of
         * either setting that should be able to do that.
         */
        const val MIN_GAP_PX = 24f
    }
}
