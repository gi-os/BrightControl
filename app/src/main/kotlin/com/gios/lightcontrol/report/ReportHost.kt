package com.gios.lightcontrol.report

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import android.content.ContextWrapper
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.launch

/**
 * The whole reporting feature, as one line in `MainActivity`.
 *
 * A sibling of the app's content rather than a wrapper around it. The sheet is a
 * `ModalBottomSheet`, which renders in its own window, so it does not need to contain anything
 * to sit on top of everything — and a call that does not wrap means installing this in a new
 * app is inserting one line inside the theme, with no re-indentation and no brace to move.
 * Everything it owns — the sensor, the crash file, the queue — is tied to the composition and
 * to the lifecycle, so an app that stops calling it stops paying for it.
 *
 * Three things can raise the sheet, and they are deliberately different questions:
 *
 *  - **A shake.** You noticed something. The gesture is in [ShakeGesture] and is tuned to be
 *    hard to trigger in a pocket.
 *  - **A crash last run.** The app died and left a stack trace behind. Asked once, on the
 *    launch after, because that is the only moment the trace is still worth anything.
 *  - **A failure the app noticed itself.** See [Trouble] — the quiet ones, which are the
 *    reports that otherwise never get filed.
 *
 * The sensor is registered on RESUME and dropped on PAUSE. That is what keeps a 50Hz
 * accelerometer stream from being a battery question: it only runs while you are looking at
 * the app, and shaking a phone that is showing something else has nothing to do with this one.
 */
@Composable
fun ReportOverlay() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Walked out of the Context rather than read from `LocalLifecycleOwner`, on purpose. That
    // composition local moved from `androidx.compose.ui.platform` to `androidx.lifecycle.compose`
    // and the old one is gone in current Compose — so reading it would make this file compile in
    // some of the apps it is installed in and not others, which is the one thing this package is
    // supposed to avoid. Every Activity is a LifecycleOwner, and unwrapping ContextWrappers to
    // find it works on every version.
    val lifecycleOwner = remember(context) {
        generateSequence(context) { (it as? ContextWrapper)?.baseContext }
            .filterIsInstance<LifecycleOwner>()
            .firstOrNull()
    }

    val prefs = remember { com.gios.lightcontrol.Prefs(context) }
    var reason by remember { mutableStateOf<ReportReason?>(null) }
    val failure by Trouble.latest.collectAsState()

    // Read once. The file is deleted as soon as it has been offered, so that a crash is asked
    // about on the next launch and not on every launch after it.
    val crash = remember { CrashLog.read(context) }

    val detector = remember {
        ShakeDetector(context) {
            if (reason == null) reason = ReportReason.Shaken
        }
    }

    // Anything left in the queue from a run that could not reach the network — or from a build
    // that had no token at all — goes out now.
    LaunchedEffect(Unit) {
        runCatching { Reports.flush(context) }
        if (!crash.isNullOrBlank()) reason = ReportReason.Crashed
    }

    /** The last thing sent without asking, shown for a moment so it is not silent. */
    var sent by remember { mutableStateOf<String?>(null) }

    // **A failure the app noticed itself is sent, not offered.**
    //
    // It used to raise the sheet, and a sheet is a thing you dismiss: it appears while somebody is
    // in the middle of the very thing that failed, one tap outside it is "no thanks", and the
    // report that would have explained the failure is gone along with the only copy of what the app
    // knew. An evening of this was diagnosed by reading logs over somebody's shoulder instead.
    //
    // There is nothing to ask about anyway. A user-reported glitch needs the user — which symptom,
    // in their words, on which screen — and that is what the sheet is for, and the shake still
    // raises it. A failure the app detected has already written its own description: what it tried,
    // and what came back. Adding a dialog to that only adds a way to lose it.
    //
    // It is still visible, and it is still refusable: [Prefs.autoSendFailures] turns it off, in
    // which case this behaves exactly as before and asks.
    LaunchedEffect(failure) {
        val found = failure ?: return@LaunchedEffect
        if (!prefs.autoSendFailures) {
            // A failure only raises the sheet if nothing else already has: being asked about a
            // stale feed on top of a crash report is how people turn this off.
            if (reason == null) reason = ReportReason.Failed
            return@LaunchedEffect
        }
        val report = Reports.compose(
            context = context,
            symptom = Symptom.Other,
            note = "",
            screen = ReportContext.screen,
            crash = null,
            failure = found,
        )
        // Cleared before the send: submit() queues to disk first, so nothing here can fail in a
        // way that would want reporting — and a failure that repeats should raise a fresh one
        // rather than find this one still sitting there.
        Trouble.clear()
        runCatching { Reports.submit(context, report) }
        sent = if (Reports.canSend()) {
            "Reported: could not ${found.what}"
        } else {
            "Saved to send later: could not ${found.what}"
        }
        kotlinx.coroutines.delay(SENT_MS)
        sent = null
    }

    DisposableEffect(lifecycleOwner) {
        val lifecycle = lifecycleOwner?.lifecycle
        if (lifecycle == null) {
            // No Activity above us — nothing to hang the sensor off. The sheet still works if
            // something else raises it; only the shake is unavailable.
            onDispose { detector.stop() }
        } else {
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME -> detector.start()
                    Lifecycle.Event.ON_PAUSE -> detector.stop()
                    else -> Unit
                }
            }
            lifecycle.addObserver(observer)
            onDispose {
                lifecycle.removeObserver(observer)
                detector.stop()
            }
        }
    }

    sent?.let { line ->
        // Deliberately not a dialog. It says what left the phone and takes nothing away from what
        // the person was doing, which is the whole difference from the sheet it replaces.
        Box(
            Modifier.fillMaxSize().padding(bottom = 28.dp),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Text(
                line,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                color = Color.White,
                modifier = Modifier
                    // Only the line itself takes touches. The box around it fills the screen, and
                    // a gesture handler up there would eat every tap in the app for ten seconds.
                    .pointerInput(line) {
                        detectHorizontalDragGestures { _, _ -> sent = null }
                    }
                    .clickable { sent = null }
                    .background(Color(0xFF161616))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
    }

    reason?.let { why ->
        // The shake that opened the sheet must not open a second one behind it.
        LaunchedEffect(why) { detector.forget() }
        ReportSheet(
            reason = why,
            failure = if (why == ReportReason.Failed) failure?.what else null,
            appName = ReportApp.NAME,
            onDismiss = {
                reason = null
                Trouble.clear()
                if (why == ReportReason.Crashed) CrashLog.clear(context)
            },
            onSend = { symptom, note ->
                val report = Reports.compose(
                    context = context,
                    symptom = symptom,
                    note = note,
                    screen = ReportContext.screen,
                    crash = crash,
                    failure = if (why == ReportReason.Failed) failure else null,
                )
                // Closed before the send, not after: submit() queues to disk first, so there is
                // nothing here that can fail in a way the sheet would need to report.
                reason = null
                Trouble.clear()
                CrashLog.clear(context)
                scope.launch { runCatching { Reports.submit(context, report) } }
            },
        )
    }
}

/**
 * How long the "sent" line stays up.
 *
 * Ten seconds, not four: it names a command and a reason, which is more than four seconds of
 * reading if you were looking somewhere else when it appeared. It can also be swiped or tapped
 * away, so the length costs nothing to anybody who has already read it.
 */
private const val SENT_MS = 10_000L
