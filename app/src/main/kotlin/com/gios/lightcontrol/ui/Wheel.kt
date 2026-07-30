package com.gios.lightcontrol.ui

import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.gios.lightcontrol.LocalNotches

/**
 * Point the wheel at a scroller. About six notches to a screenful on the LPIII panel.
 *
 * Instant rather than animated: an animation per notch queues up behind a fast turn and
 * lands late, and on a greyscale LCD a hard jump reads more cleanly than a smear. Positive
 * notches move *down* the page — the wheel drags the content the way a thumb does.
 */
@Composable
fun WheelScroll(state: ScrollableState) {
    val flow = LocalNotches.current ?: return
    val step = with(LocalDensity.current) { 64.dp.toPx() }
    LaunchedEffect(flow, state) {
        flow.collect { notches -> state.scrollBy(notches * step) }
    }
}
