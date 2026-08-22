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
import com.gios.lightcontrol.adb.GrantRequest
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
    var results by remember { mutableStateOf(listOf<String>()) }
    var done by remember { mutableStateOf(false) }

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
                                done -> "DONE"
                                else -> "RUN THESE ${parsed.steps.size}"
                            },
                            filled = true,
                            enabled = !busy && !done,
                            modifier = Modifier.fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                        ) {
                            busy = true
                            scope.launch {
                                val out = withContext(Dispatchers.IO) {
                                    val adb = AdbManager.getInstance(context)
                                    parsed.steps.map { step ->
                                        val r = runCatching { adb.runCommand(step.command) }
                                            .getOrElse { "error: ${it.message ?: it.javaClass.simpleName}" }
                                        // A grant that works prints nothing, which is the one
                                        // case worth translating into words.
                                        val note = if (r.isBlank()) "ok" else r.take(120)
                                        "${step.label} — $note"
                                    }
                                }
                                results = out
                                connected = runCatching {
                                    AdbManager.getInstance(context).connected()
                                }.getOrDefault(false)
                                busy = false
                                done = true
                            }
                        }
                    }

                    if (results.isNotEmpty()) {
                        SectionLabel("RESULT")
                        results.forEach { GuideText(it) }
                        GuideText(
                            "Some grants only take effect once the app is reopened. If $appLabel " +
                                "still says something is missing, close and reopen it.",
                        )
                    }
                }
            }
        }
    }
}
