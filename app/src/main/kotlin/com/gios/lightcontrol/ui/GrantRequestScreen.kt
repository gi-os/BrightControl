package com.gios.lightcontrol.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.gios.lightcontrol.adb.AdbManager
import com.gios.lightcontrol.adb.GrantCheckRunner
import com.gios.lightcontrol.adb.GrantRequest
import com.gios.lightcontrol.adb.Outcome
import com.gios.lightcontrol.adb.StepResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Another app's setup, shown before any of it runs.
 *
 * An app in BrightMarket says in its README which ADB grants it needs. BrightMarket sends that
 * list here rather than making the user find a computer. This screen is the consent step, and it
 * is the reason the feature is defensible: the request has already been parsed and rebuilt by
 * [GrantRequest] — nothing arbitrary can reach the shell — but the user still sees the exact
 * commands, named app first, and nothing runs until they say so.
 *
 * A refused request is shown as a refusal, with the line that caused it. That is deliberately
 * loud: a request naming a package other than the sender is the shape an attack takes, and it
 * should look like something went wrong rather than quietly running the safe half.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GrantRequestScreen(
    appLabel: String,
    pkg: String,
    lines: List<String>,
    onBack: () -> Unit,
    onAdb: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val parsed = remember(pkg, lines) { GrantRequest.parse(pkg, lines) }
    var connected by remember { mutableStateOf(AdbManager.getInstance(context).connected()) }
    var busy by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf(listOf<StepResult>()) }
    var ran by remember { mutableStateOf(false) }
    // Success is every step read back and confirmed. Nothing weaker: reaching the end of the list
    // is what the old DONE meant, and a run where the socket died on the first command reached
    // the end of the list too.
    val allHeld = ran && results.isNotEmpty() && results.all { it.outcome == Outcome.Held }

    val scroll = rememberScrollState()
    WheelScroll(scroll)

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                colors = barColors(),
                title = { Text("Set up $appLabel", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(scroll)) {
            when (parsed) {
                is GrantRequest.Parsed.Refused -> {
                    SectionLabel("REFUSED")
                    GuideText(
                        "This request was turned down and nothing has been run. An app may ask to " +
                            "set up itself and nothing else.",
                    )
                    if (parsed.line.isNotBlank()) {
                        SectionLabel("THE LINE")
                        GuideText(parsed.line)
                    }
                    SectionLabel("WHY")
                    GuideText(parsed.why)
                }

                is GrantRequest.Parsed.Ok -> {
                    SectionLabel("WHAT $appLabel IS ASKING FOR")
                    GuideText(
                        "$pkg needs these to work. They are the same lines its README would have " +
                            "you run from a computer. Each one names $pkg and nothing else — that " +
                            "is checked here, not taken on trust.",
                    )
                    parsed.steps.forEach { step ->
                        MenuRow(
                            label = step.label,
                            detail = "",
                            sub = step.command.take(160),
                        )
                        Rule()
                    }

                    if (!connected) {
                        SectionLabel("NOT CONNECTED YET")
                        GuideText(
                            "BrightControl talks to the phone's own debugging service to do this, " +
                                "and it is not connected right now. Set that up once, then come " +
                                "back — this request will still be here.",
                        )
                        BigButton(
                            label = "GO TO ADB SETUP",
                            filled = true,
                            enabled = true,
                            modifier = Modifier.fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                            onClick = onAdb,
                        )
                    } else {
                        BigButton(
                            label = when {
                                busy -> "RUNNING…"
                                allHeld -> "DONE"
                                ran -> "TRY AGAIN"
                                else -> "RUN THESE ${parsed.steps.size}"
                            },
                            filled = true,
                            // Still tappable after a failed run. The old screen disabled itself
                            // on DONE, so the one thing to do about a failure was the one thing
                            // the screen would not let you do.
                            enabled = !busy && !allHeld,
                            modifier = Modifier.fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                        ) {
                            busy = true
                            scope.launch {
                                val out = withContext(Dispatchers.IO) {
                                    val adb = AdbManager.getInstance(context)
                                    parsed.steps.map { step ->
                                        GrantCheckRunner.runAndVerify(
                                            context = context,
                                            adb = adb,
                                            label = step.label,
                                            command = step.command,
                                            check = step.check,
                                        )
                                    }
                                }
                                results = out
                                connected = runCatching {
                                    AdbManager.getInstance(context).connected()
                                }.getOrDefault(false)
                                busy = false
                                ran = true
                            }
                        }
                    }

                    if (results.isNotEmpty()) {
                        val failed = results.count { it.outcome == Outcome.Failed }
                        val unknown = results.count { it.outcome == Outcome.Unknown }
                        SectionLabel(
                            when {
                                failed > 0 -> "DID NOT WORK"
                                unknown > 0 -> "PARTLY CONFIRMED"
                                else -> "RESULT"
                            },
                        )
                        // Every line says which of the three things it is, so a run can be read
                        // at a glance instead of inferred from whether a command printed
                        // anything. The state came from the phone, not from the output.
                        results.forEach { r ->
                            MenuRow(
                                label = r.label,
                                detail = when (r.outcome) {
                                    Outcome.Held -> "OK"
                                    Outcome.Failed -> "FAILED"
                                    Outcome.Unknown -> "UNKNOWN"
                                },
                                sub = r.detail,
                            )
                            Rule()
                        }
                        if (failed > 0 && !connected) {
                            // One dead socket produces a whole list of failures, and reading that
                            // list step by step is how an evening goes missing. Say it once.
                            GuideText(
                                "The connection dropped, so these did not run. The debugging port " +
                                    "changes every time wireless debugging is switched off and " +
                                    "on — set it up again and run this once more.",
                            )
                            BigButton(
                                label = "GO TO ADB SETUP",
                                filled = true,
                                enabled = true,
                                modifier = Modifier.fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 6.dp),
                                onClick = onAdb,
                            )
                        } else if (failed > 0) {
                            GuideText(
                                "The connection is still up, so these ran and did not take. The " +
                                    "reason is on each line above.",
                            )
                        } else if (unknown > 0) {
                            GuideText(
                                "Nothing on this phone records whether the unknown ones worked, so " +
                                    "they are not being called done. Open $appLabel and see.",
                            )
                        } else {
                            GuideText(
                                "All of them read back as granted. Some are only picked up when " +
                                    "the app starts, so if $appLabel still says something is " +
                                    "missing, close and reopen it.",
                            )
                        }
                    }
                }
            }
        }
    }
}
