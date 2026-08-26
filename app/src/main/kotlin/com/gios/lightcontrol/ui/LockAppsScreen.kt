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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.gios.lightcontrol.Prefs
import com.gios.lightcontrol.lock.LockNotes
import com.gios.lightcontrol.ui.theme.Dim

private data class Source(val label: String, val pkg: String)

/**
 * Which apps the lock face never lists.
 *
 * The list is what has actually posted, not everything installed. A settings screen offering to
 * hide the notifications of 60 apps that have never sent one is a screen nobody can find anything
 * on -- and the app worth hiding is, by definition, one whose notification you have just been
 * looking at. The sources come from the raw shade, before any of the face's own rules have
 * filtered it, so an app whose notification is already being dropped still appears here to be
 * hidden for good. See [LockNotes.sources].
 *
 * Anything already hidden is listed too, whether or not it has posted since the listener bound.
 * Otherwise a rule would become unreachable the moment it started working.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LockAppsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }

    val hidden = remember { mutableStateListOf<String>().apply { addAll(prefs.lockHiddenApps()) } }

    // Read once, at composition. The shade changes while this screen is open and a list that
    // reordered itself under a thumb would be a list you cannot tap accurately.
    val sources = remember {
        val pm = context.packageManager
        (LockNotes.sources.value + prefs.lockHiddenApps())
            .map { pkg ->
                Source(
                    label = runCatching {
                        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                    }.getOrNull() ?: pkg.substringAfterLast('.'),
                    pkg = pkg,
                )
            }
            .sortedBy { it.label.lowercase() }
    }

    val listState = rememberLazyListState()
    WheelScroll(listState)

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                colors = barColors(),
                title = { Text("Apps never shown", style = MaterialTheme.typography.titleMedium) },
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
                if (sources.isEmpty()) {
                    item {
                        EmptyState(
                            "Nothing has posted a notification since the listener started, and " +
                                "nothing is hidden. Come back once there is something on the " +
                                "face you would rather not see.",
                        )
                    }
                }
                items(sources, key = { it.pkg }) { source ->
                    val off = source.pkg in hidden
                    MenuRow(
                        label = source.label,
                        detail = if (off) "HIDDEN" else "SHOWN",
                        dim = off,
                        sub = if (off) {
                            "never on the lock face. Still in the shade, still in Glance, still " +
                                "wherever else it was."
                        } else {
                            source.pkg
                        },
                        onClick = {
                            if (off) hidden.remove(source.pkg) else hidden.add(source.pkg)
                            prefs.toggleLockHidden(source.pkg)
                            // The face may be up behind these settings; the listener has to be
                            // told the rule changed.
                            LockNotes.rebuild()
                        },
                    )
                    Rule()
                }
                item {
                    Text(
                        "This hides a source from the lock face and nothing else. Nothing is " +
                            "cancelled, nothing is stored about what the notification said, and " +
                            "the app is not told.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Dim,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
    }
}
