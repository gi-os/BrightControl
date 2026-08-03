package com.gios.lightcontrol.report

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.delay

/** How long the offer stands before it takes silence for an answer. */
private const val SHOWN_MS = 4_000L

/** A crash is worth a longer look; it is also the one offer you cannot make again from nothing. */
private const val SHOWN_CRASH_MS = 8_000L

/** Long enough for the fade to finish before the caller tears the composable down. */
private const val FADE_MS = 350

/**
 * A small offer in the corner, rather than a sheet across the screen.
 *
 * The first version of this raised the full sheet the instant you shook the phone, which was the
 * wrong shape for the question. A shake is a gesture the phone can misread, so the cost of being
 * wrong is paid *every* time it is wrong — and a sheet that covers what you were reading, on a
 * 3.92" screen, to ask about something you did not ask about, is a bad trade against a report that
 * might not even exist.
 *
 * So the offer is small, it sits out of the way, and **silence is an answer**: ignore it for four
 * seconds and it fades. Nothing is lost by ignoring it — an unsent crash log stays on disk and is
 * offered again on the next launch. Only the tap costs anything, and only the tap opens the sheet.
 *
 * Drawn in a [Popup] rather than placed in the app's layout. The host calls this as a sibling of
 * the app content, and the apps this package installs into put that sibling inside a Column, a
 * Box, a Surface or nothing at all — a `fillMaxSize` overlay would mean something different in
 * each. A popup has its own window, so bottom-start is bottom-start everywhere, and the chip
 * cannot take a touch that was meant for the app underneath it.
 */
@Composable
fun ReportChip(reason: ReportReason, onOpen: () -> Unit, onExpire: () -> Unit) {
    var shown by remember { mutableStateOf(true) }
    val density = LocalDensity.current

    LaunchedEffect(reason) {
        delay(if (reason == ReportReason.Crashed) SHOWN_CRASH_MS else SHOWN_MS)
        shown = false
    }
    // Torn down only after the fade has played out, or it would vanish rather than fade —
    // which on this panel reads as a glitch, from the feature whose job is glitches.
    LaunchedEffect(shown) {
        if (shown) return@LaunchedEffect
        delay(FADE_MS.toLong())
        onExpire()
    }

    // Inset from the corner by hand: a popup sits outside the app's padding, so it would
    // otherwise touch the screen edge.
    val inset = with(density) { 16.dp.roundToPx() }

    Popup(
        alignment = Alignment.BottomStart,
        offset = IntOffset(inset, -inset),
        properties = PopupProperties(focusable = false),
    ) {
        AnimatedVisibility(
            visible = shown,
            enter = fadeIn(tween(FADE_MS)),
            exit = fadeOut(tween(FADE_MS)),
        ) {
            Box(
                Modifier
                    .background(MaterialTheme.colorScheme.background)
                    .border(1.dp, MaterialTheme.colorScheme.onBackground)
                    // No ripple: LightOS has none, and a Material ripple is the clearest tell
                    // that something was not written for this phone.
                    .clickable(interactionSource = null, indication = null, onClick = onOpen)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = when (reason) {
                        ReportReason.Crashed -> "IT CRASHED · SEND?"
                        else -> "SEND ERROR?"
                    },
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontSize = 11.sp,
                        letterSpacing = 1.5.sp,
                    ),
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                )
            }
        }
    }
}
