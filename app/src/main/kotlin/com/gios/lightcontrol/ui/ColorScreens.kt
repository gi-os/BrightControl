package com.gios.lightcontrol.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.gios.lightcontrol.ColorRule
import com.gios.lightcontrol.Policy
import com.gios.lightcontrol.Prefs
import com.gios.lightcontrol.color.ColorRequests
import com.gios.lightcontrol.keys.ColorMode
import com.gios.lightcontrol.keys.ColorOutcome
import com.gios.lightcontrol.keys.Grants
import com.gios.lightcontrol.report.CrashLog
import com.gios.lightcontrol.report.Failure
import com.gios.lightcontrol.report.Reports
import com.gios.lightcontrol.report.Symptom
import kotlinx.coroutines.launch
import com.gios.lightcontrol.ui.theme.Dim

/**
 * The color feature's own screen: what it is, the master switch, the grant it needs, and the
 * door to the per-app list.
 */
@Composable
fun ColorScreen(onPerApp: () -> Unit, onAdb: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    val canWriteSecure = Grants.canWriteSecureSettings(context)

    var auto by remember { mutableStateOf(prefs.colorAutoSwitch) }
    var live by remember { mutableStateOf(ColorMode(context, prefs).live()) }
    var log by remember { mutableStateOf(prefs.colorLog()) }
    var asking by remember { mutableStateOf(ColorRequests.asking()) }
    var sent by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Both of these are snapshots of state that lives outside the composition, and both of them
    // only move while this screen is *not* being looked at: the log is written by the key service
    // as you switch apps, and the live pair by whoever wrote the daltonizer last. Read once at
    // composition, they were correct exactly until you left the screen — and leaving the screen
    // is the entire way this feature is exercised. The composition survives the trip, so coming
    // back showed the screen as it was before, and "Nothing applied yet" outlived six applied
    // rules. Re-read on every resume, which is cheap: two secure-setting reads and one preference.
    //
    // Lifecycle is walked out of the Context rather than read from `LocalLifecycleOwner`, for the
    // reason spelled out in ReportOverlay.
    val lifecycleOwner = remember(context) {
        generateSequence(context) { (it as? ContextWrapper)?.baseContext }
            .filterIsInstance<LifecycleOwner>()
            .firstOrNull()
    }
    DisposableEffect(lifecycleOwner) {
        val lifecycle = lifecycleOwner?.lifecycle
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                live = ColorMode(context, prefs).live()
                log = prefs.colorLog()
                asking = ColorRequests.asking()
            }
        }
        lifecycle?.addObserver(observer)
        onDispose { lifecycle?.removeObserver(observer) }
    }

    SectionScaffold(
        title = "Color",
        onBack = onBack,
        guide = "LightOS keeps the whole phone in black and white. There is no per-app color on " +
            "this phone — so this adds it. Turn it on, then set a rule per app: some apps in full " +
            "color (a camera, maps), the rest left mono. The screen switches as you move between " +
            "them.",
    ) {
        MenuRow(
            label = "Per-app color",
            detail = if (auto) "ON" else "OFF",
            sub = if (auto) {
                "the screen follows each app's rule as you switch. Off leaves the phone exactly " +
                    "as you last set it."
            } else {
                "off — color rules are ignored and nothing is forced. Tap to switch on."
            },
            onClick = {
                auto = !auto
                prefs.colorAutoSwitch = auto
                // Switching off is the one moment the baseline should be put back: a rule that
                // forced color is no longer being maintained, so leaving the phone on it would
                // strand a setting nothing is watching any more. This used to happen on service
                // unbind instead, which fired on every app update and repainted color apps mono.
                if (!auto) runCatching { ColorMode(context, prefs).restoreBaseline() }
            },
        )
        GrantRow(
            label = "Color grant",
            ok = canWriteSecure,
            fix = "adb shell pm grant com.gios.lightcontrol " +
                "android.permission.WRITE_SECURE_SETTINGS",
            sub = "needed to drive the system daltonizer. LightOS has no screen for it",
        )
        if (!canWriteSecure) {
            MenuRow(
                label = "Grant it on the phone",
                detail = "›",
                sub = "the ADB screen can grant this itself, no computer — tap to go there",
                onClick = onAdb,
            )
        }
        // The state the feature actually runs on, in plain sight. This bug was diagnosed three
        // times from the outside — grant? write? LightOS fighting back? — and every guess would
        // have been settled in a second by being able to read the two ints.
        val shown = live
        MenuRow(
            label = "Live filter",
            detail = when {
                shown == null -> "?"
                shown.first == 0 || shown.second < 0 -> "COLOR"
                shown.second == 0 -> "MONO"
                else -> "FILTER ${shown.second}"
            },
            sub = if (shown == null) {
                "the daltonizer settings cannot be read"
            } else {
                "enabled=${shown.first}, mode=${shown.second} — mode 0 is monochrome even with " +
                    "enabled 0, which is why off is written as -1"
            },
            dim = true,
            onClick = {
                live = ColorMode(context, prefs).live()
                log = prefs.colorLog()
                asking = ColorRequests.asking()
            },
        )
        // Which apps have asked for themselves, over color/ColorService. Worth a row of its own
        // rather than folding into the per-app list, because it answers a question the list cannot:
        // an app with no rule set and no preset can still be in colour, and until this was on
        // screen there was no way to tell that from the feature being broken. An empty list on a
        // phone with a migrated app is the finding — either it never bound, or its request was
        // dropped when its process died.
        MenuRow(
            label = "Apps asking now",
            detail = "${asking.size}",
            sub = if (asking.isEmpty()) {
                "no app is asking. An app built against light-common 1.7.0 or later asks for " +
                    "itself; anything older needs a rule below."
            } else {
                asking.entries.joinToString(", ") { (pkg, rule) ->
                    "${pkg.substringAfterLast('.')} ${rule.name.uppercase()}"
                }
            },
            dim = true,
            onClick = { asking = ColorRequests.asking() },
        )
        MenuRow(
            label = "Per-app rules",
            detail = "${prefs.colorOverrides().size}",
            sub = "which apps are color, which are mono",
            onClick = onPerApp,
        )
        // Every rule this app applied, and what the phone read back a second later. `ok` means
        // the write landed and stayed — so a screen that is still the wrong color is a phone
        // ignoring a setting it agrees with. `LOST` names the values something else preferred.
        // An app that produces no line here never had its rule applied at all.
        SectionLabel("WHAT HAPPENED")
        if (log.isEmpty()) {
            // Not an empty state — a finding. The log is written when a rule is applied, so
            // nothing here means no rule was ever applied, which is a different bug entirely
            // from one that gets applied and does not hold.
            MenuRow(
                label = "Nothing applied yet",
                sub = "open an app with a Color or Mono rule and come back. Still empty means " +
                    "the rule never ran, not that it ran and failed.",
                dim = true,
            )
        } else {
            log.forEach { line -> MenuRow(label = line, dim = true) }
        }
        // Reading a dozen lines of `want 0/-1 got 1/0` off a 3.92" screen and retyping them into
        // a message is the step where a diagnostic log stops being worth having. This sends the
        // whole thing — log, live pair, every rule set, both grants — as an issue on the same
        // queue shake-to-report uses, so it survives having no signal and needs no computer.
        MenuRow(
            label = if (sent) "Log sent" else "Send log",
            detail = when {
                sent -> "✓"
                Reports.canSend() -> "›"
                else -> "QUEUED"
            },
            sub = when {
                sent -> "filed on light-reports, and queued if there was no signal"
                Reports.canSend() -> "file this log as an issue — no typing, no computer"
                else -> "this build has no report token, so it will wait on disk"
            },
            dim = true,
            onClick = {
                // Read again here rather than trusting the snapshot. The evidence block always
                // read the log fresh out of preferences while the title was written from the
                // snapshot, so a stale screen filed an issue whose title said nothing had ever
                // been applied above a body listing six applications — which is the report that
                // brought this on. The two halves of a report have to describe the same phone.
                live = ColorMode(context, prefs).live()
                log = prefs.colorLog()
                asking = ColorRequests.asking()
                val report = Reports.compose(
                    context = context,
                    symptom = Symptom.Wrong,
                    note = colorHeadline(log),
                    screen = "color",
                    crash = CrashLog.read(context),
                    failure = Failure(
                        what = "hold per-app color",
                        detail = colorEvidence(prefs, live, auto, canWriteSecure),
                    ),
                )
                // Marked sent before the send, as the sheet does: submit() queues to disk first,
                // so there is nothing here left to fail in a way this row could report.
                sent = true
                scope.launch { runCatching { Reports.submit(context, report) } }
            },
        )
        MenuRow(
            label = "Clear",
            sub = "start the log again from the next app you open",
            dim = true,
            onClick = {
                prefs.clearColorLog()
                log = emptyList()
                sent = false
            },
        )
    }
}

/**
 * Every launchable app and its color rule. Tapping a row cycles AUTO → COLOR → MONO, the same
 * one-tap idiom as the wheel per-app list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ColorAppListScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }

    val apps = remember {
        runCatching {
            val pm = context.packageManager
            val launchable = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            // Launchers as well as launchable apps, and LightOS is the reason. A home app
            // publishes `CATEGORY_HOME` and need not publish `CATEGORY_LAUNCHER` at all — LightOS
            // does not — so the one package that draws the dashboard, the lock screen, the camera
            // and the album was the one package this list could not offer a row for. Reported as
            // "album has no colour filter option to toggle", which was exactly true.
            val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            val found = pm.queryIntentActivities(launchable, PackageManager.MATCH_ALL) +
                pm.queryIntentActivities(home, PackageManager.MATCH_ALL)
            found
                .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
                .distinctBy { it.second }
                .sortedBy { it.first.lowercase() }
        }.getOrDefault(emptyList())
    }

    val rules = remember {
        mutableStateMapOf<String, ColorRule>().apply { putAll(prefs.colorOverrides()) }
    }

    val listState = rememberLazyListState()
    WheelScroll(listState)

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                colors = barColors(),
                title = { Text("Per-app color", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            LazyColumn(Modifier.fillMaxSize(), state = listState) {
                items(apps, key = { it.second }) { (label, pkg) ->
                    // Explicit and resolved are separate on purpose. The row has to show what the
                    // app will actually do — which for a preset is COLOR with nothing stored —
                    // while the tap has to know whether anything is stored, so that cycling back
                    // to the preset clears the override instead of pinning the same value by hand.
                    val explicit = rules[pkg]
                    val builtIn = Policy.builtInColorRuleFor(pkg)
                    val resolved = explicit ?: builtIn
                    // LightOS is one package for its whole layer, so its row is one decision for
                    // the dashboard, the lock screen, the camera and the album together. Worth
                    // saying on the row: somebody looking for "Album" will never find it, and the
                    // reason is not obvious from outside.
                    val wholeLayer = pkg.startsWith("com.lightos")
                    MenuRow(
                        label = label,
                        detail = colorDetail(resolved),
                        sub = when {
                            wholeLayer ->
                                "the whole Light layer — dashboard, lock screen, camera, album " +
                                    "and the tools are one package, so this is one decision for " +
                                    "all of them. " + colorDescribe(resolved)
                            explicit == null && builtIn != ColorRule.Default ->
                                "built in — ${colorDescribe(builtIn)}. Tap to override"
                            else -> colorDescribe(resolved)
                        },
                        onClick = {
                            // Landing back on the preset stores nothing, so a later change to the
                            // table still reaches this app. Storing Color over a Color preset
                            // would freeze it at today's answer forever. Which step that is —
                            // and which step has to be skipped because it would redraw the row
                            // unchanged — is [Policy.nextColorRule], where it can be tested.
                            val store = Policy.nextColorRule(resolved, builtIn)
                            if (store == ColorRule.Default) rules.remove(pkg) else rules[pkg] = store
                            prefs.setColorRule(pkg, store)
                        },
                    )
                    Rule()
                }
                item {
                    Text(
                        "AUTO leaves an app at the baseline — mono, on a stock LightOS phone. " +
                            "Set the apps you want in color to COLOR and leave the rest. PASS " +
                            "means this app writes nothing at all, for apps that drive the " +
                            "filter themselves — Roll and BrightChat both do, and two writers " +
                            "fighting is what a flickering screen looks like. The stock camera " +
                            "cannot ask, so it ships on COLOR. Tapping any of them overrides it.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Dim,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
    }
}

private fun colorDetail(rule: ColorRule): String = when (rule) {
    ColorRule.Default -> "AUTO"
    ColorRule.Color -> "COLOR"
    ColorRule.Mono -> "MONO"
    ColorRule.Passthrough -> "PASS"
}

private fun colorDescribe(rule: ColorRule): String = when (rule) {
    ColorRule.Default -> "baseline — mono on a stock phone"
    ColorRule.Color -> "full color while this app is in front"
    ColorRule.Mono -> "monochrome while this app is in front"
    ColorRule.Passthrough -> "left alone — this app sets its own"
}

/**
 * The issue title, from the log itself.
 *
 * A title reading "Something looks wrong" is worth nothing in a list of them weeks later, and the
 * one fact that decides which bug this is — whether the writes are being lost or ignored — is
 * already in the log. Put it in the title.
 *
 * A repaint leads, because it is the only one of these counts that is about the screen rather
 * than about a write. Four reports — light-reports#37, #38, #44 and #45 — arrived titled "N held,
 * 0 overwritten" while the phone was being repainted grey between the lines, because every write
 * really had landed and the count really was zero. See [ColorOutcome.repaints].
 */
private fun colorHeadline(log: List<String>): String {
    if (log.isEmpty()) return "per-app color: nothing was ever applied"
    val lost = log.count { it.endsWith("LOST") }
    val ok = log.count { it.endsWith("ok") }
    // A line the next app's rule replaced is neither: counting it as overwritten made an ordinary
    // walk through three apps read as a fault, and counting it as held would hide a real one.
    val superseded = log.size - ok - lost
    val repainted = ColorOutcome.repaints(log)
    if (repainted > 0) {
        return "per-app color: $repainted repainted by another app, $ok held"
    }
    return "per-app color: $ok held, $lost overwritten" +
        if (superseded > 0) ", $superseded superseded" else ""
}

/** Everything about the feature's state worth having in the issue, as one block. */
private fun colorEvidence(
    prefs: Prefs,
    live: Pair<Int, Int>?,
    auto: Boolean,
    canWriteSecure: Boolean,
): String = buildString {
    appendLine("master switch: ${if (auto) "on" else "OFF"}")
    appendLine("WRITE_SECURE_SETTINGS: ${if (canWriteSecure) "granted" else "NOT GRANTED"}")
    appendLine("live: " + (live?.let { "enabled=${it.first} mode=${it.second}" } ?: "unreadable"))
    appendLine(
        "baseline: enabled=${prefs.colorBaselineEnabled} mode=${prefs.colorBaselineMode}",
    )
    appendLine()
    appendLine("rules:")
    val rules = prefs.colorOverrides()
    if (rules.isEmpty()) appendLine("  none") else rules.forEach { (pkg, rule) ->
        appendLine("  $pkg = ${rule.name}")
    }
    appendLine()
    appendLine("log (newest first):")
    val lines = prefs.colorLog()
    if (lines.isEmpty()) appendLine("  empty") else lines.forEach { appendLine("  $it") }
}
