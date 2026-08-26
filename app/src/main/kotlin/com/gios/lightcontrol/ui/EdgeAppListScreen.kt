package com.gios.lightcontrol.ui

import android.content.Intent
import android.content.pm.PackageManager
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
import com.gios.lightcontrol.Policy
import com.gios.lightcontrol.Prefs
import com.gios.lightcontrol.keys.OwnWindow
import com.gios.lightcontrol.ui.theme.Dim

/**
 * Named for this screen, not for what it is. `Candidate` and `Installed` are both taken by other
 * screens in this package, and a private top-level class does not stop a redeclaration -- it only
 * stops the other file using it.
 */
private data class EdgeApp(val label: String, val pkg: String)

/**
 * Which apps both edge strips stand down for.
 *
 * A list of exclusions rather than of inclusions, because an edge gesture is only worth having if
 * it is everywhere -- an opt-in list would mean a phone where the edges sometimes work. The apps
 * worth excluding are the ones whose edges are already controls, and those are known to the person
 * holding the phone.
 *
 * One list for both edges. An app that puts its own controls at the screen edge usually does it at
 * both, and a list per edge would be twice the rows to say the same thing.
 *
 * Rows for Light's own tools say ALWAYS OFF and do nothing when tapped: they are refused by the
 * built-in table, and a switch that appears to be settable and is not is worse than no switch.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EdgeAppListScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }

    // One binder call with every launchable activity in it, as PickerScreen does, so a failure
    // here is an empty list rather than a dead screen.
    val apps = remember {
        runCatching {
            val pm = context.packageManager
            val launchable = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            pm.queryIntentActivities(launchable, PackageManager.MATCH_ALL)
                .map {
                    EdgeApp(
                        label = it.loadLabel(pm).toString(),
                        pkg = it.activityInfo.packageName,
                    )
                }
                .distinctBy { it.pkg }
                .sortedBy { it.label.lowercase() }
        }.getOrDefault(emptyList())
    }

    // Mirrors the stored set so a tap redraws at once. SharedPreferences stays the source of
    // truth; this is only the view of it.
    val off = remember { mutableStateListOf<String>().apply { addAll(prefs.edgeSwipeOffApps()) } }

    val listState = rememberLazyListState()
    WheelScroll(listState)

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                colors = barColors(),
                title = { Text("Edges per app", style = MaterialTheme.typography.titleMedium) },
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
                items(apps, key = { it.pkg }) { app ->
                    // Asked of the table rather than of the list, so this row keeps telling the
                    // truth if the table ever changes.
                    val builtIn = Policy.edgeSwipeRefusedByTable(app.pkg)
                    val excluded = app.pkg in off
                    // Hoisted and typed. A row the table refuses gets no tap at all, because a
                    // switch that appears settable and is not is worse than no switch.
                    val toggle: (() -> Unit)? = if (builtIn) {
                        null
                    } else {
                        {
                            if (excluded) off.remove(app.pkg) else off.add(app.pkg)
                            prefs.toggleEdgeSwipeOff(app.pkg)
                            OwnWindow.settingChanged()
                        }
                    }
                    MenuRow(
                        label = app.label,
                        detail = when {
                            builtIn -> "ALWAYS OFF"
                            excluded -> "OFF"
                            else -> "ON"
                        },
                        dim = builtIn,
                        sub = when {
                            builtIn -> "Light's own software — it has its own edge gestures"
                            excluded -> "no strips here. Both edges belong to the app."
                            else -> "whichever edges you have switched on"
                        },
                        onClick = toggle,
                    )
                    Rule()
                }
                item {
                    Text(
                        "Turn an app off when its edges are controls — a pager, a slider, a " +
                            "drawer. A strip receives what starts on it, so an app that wants " +
                            "those touches has to be left out of the gestures entirely.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Dim,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
    }
}
