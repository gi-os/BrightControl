package com.gios.lightcontrol.ui

import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
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

    LaunchedEffect(flow) {
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

private const val SMOOTHING = 0.28f
private const val ARM_NOTCHES = 2
private const val IDLE_MS = 1_500L
