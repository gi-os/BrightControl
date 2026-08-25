package com.gios.lightcontrol.ui

import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.gios.lightcontrol.LocalNotches
import kotlinx.coroutines.channels.Channel
import kotlin.math.abs

/**
 * Point the wheel at a scroller. Six-ish notches to a screenful on the LPIII panel.
 *
 * Two things make this feel like scrolling rather than like a slide projector:
 *
 *  - **A debt paid off per frame.** The sensor fires a notch every ~35 ms, faster than a
 *    frame, so applying each on arrival stacks up instant jumps with nothing to follow. Here
 *    each notch adds distance owed and every frame pays [SMOOTHING] of it, so one notch
 *    glides and a fast spin becomes one continuous sweep that coasts a little past your thumb.
 *  - **A bump guard.** The wheel sits under a thumb. The first notch after a pause is held
 *    back and only released when a second one confirms it was deliberate.
 *
 * Positive notches move *down* the page — the wheel drags the content the way a thumb does.
 */
@Composable
fun WheelScroll(state: ScrollableState) {
    val step = with(LocalDensity.current) { 64.dp.toPx() }
    val debt = remember { Debt() }
    val wake = remember { Channel<Unit>(Channel.CONFLATED) }
    val flow = LocalNotches.current
    val cursor = LocalCursor.current
    val scope = rememberCoroutineScope()

    // Where the list is, near enough for deciding when the selection has run off the end of it.
    // Measured from the window rather than from this composable, because the rows report window
    // coordinates too and the two have to be in the same space. The top bar is 64dp on every
    // screen in this app; a row of slack at the bottom keeps the last stop clear of the edge.
    val density = LocalDensity.current
    val screenHeight = LocalConfiguration.current.screenHeightDp
    LaunchedEffect(cursor, state, screenHeight) {
        val top = with(density) { TOP_BAR_DP.dp.toPx() }
        val bottom = with(density) { screenHeight.dp.toPx() }
        cursor?.attach(state, scope, top, bottom)
    }

    LaunchedEffect(flow, cursor) {
        val notches = flow ?: return@LaunchedEffect
        var armed = false
        var held = 0
        var count = 0
        var last = 0L
        notches.collect { n ->
            val now = System.nanoTime() / 1_000_000
            if (now - last > IDLE_MS) {
                armed = false
                held = 0
                count = 0
            }
            last = now
            // Selecting rather than scrolling, whenever this screen has rows to select and the
            // feature is on. One notch is one row, immediately — no arming, because a notch that
            // moves a highlight one row is not the accident the scroll's bump guard exists for,
            // and the switcher this borrows from has never needed one.
            //
            // A screen with no selectable rows — the ADB log, the guide — falls through to the
            // scroll below, so the wheel is never dead.
            //
            // Turning towards the top of the phone moves *up* the list, which is the switcher's
            // convention and the opposite of the scroll below it: dragging a page down with a
            // thumb and moving a cursor up are the same motion with opposite results. Selection
            // wins, because a highlight that goes down when the wheel goes up is the version
            // people get wrong every time.
            if (cursor != null && cursor.enabled && cursor.hasItems) {
                cursor.move(if (n > 0) -1 else 1)
                return@collect
            }
            if (armed) {
                debt.px += n * step
                wake.trySend(Unit)
                return@collect
            }
            held += n
            count++
            if (count >= ARM_NOTCHES) {
                armed = true
                debt.px += held * step
                held = 0
                wake.trySend(Unit)
            }
        }
    }

    LaunchedEffect(state, wake) {
        while (true) {
            // Suspends while the wheel is still, so an idle screen costs nothing.
            wake.receive()
            state.scroll {
                while (abs(debt.px) > 0.5f) {
                    withFrameNanos { }
                    val wanted = (debt.px * SMOOTHING).let {
                        if (abs(it) < 1f) debt.px else it
                    }
                    debt.px -= wanted
                    val consumed = scrollBy(wanted)
                    // At an edge the rest is unpayable, and keeping it would mean the next
                    // turn back spends its first notches on nothing.
                    if (abs(consumed) < abs(wanted) - 0.5f) debt.px = 0f
                }
            }
        }
    }
}

/** Not Compose state: nothing in composition reads it, and observing it would restart glides. */
private class Debt {
    @Volatile
    var px: Float = 0f
}

/** The top bar's height, which every screen in this app shares. */
private const val TOP_BAR_DP = 64

private const val SMOOTHING = 0.28f
private const val ARM_NOTCHES = 2
private const val IDLE_MS = 1_500L
