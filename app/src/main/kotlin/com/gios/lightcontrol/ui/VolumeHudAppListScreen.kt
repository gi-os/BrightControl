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
import com.gios.lightcontrol.ui.theme.Dim

/** Named for this screen, like the other per-app lists in this package. */
private data class HudApp(val label: String, val pkg: String)

/**
 * Which apps the volume strip stays down for entirely.
 *
 * The sister of [VolumeAppListScreen], and the difference between them is worth being able to read
 * off the two screens without holding both in your head. That one is about the *keys*: an app whose
 * volume keys turn pages was never changing the volume, so the strip a press produced was answering
 * a question nobody asked. This one is about the *app*: it already shows a volume UI of its own, or
 * it is one you would simply rather this app kept out of, and it makes no difference at all what
 * moved the level — a key, the app's own slider, a headset button. Nothing is drawn there.
 *
 * Empty out of the box, because the case it was built for is one no table can enumerate. LightOS
 * and the SDK tools are refused already by the built-in list, which is why they show ALWAYS OFF
 * here rather than a switch that would do nothing; what the built-in list cannot know is that a
 * particular sideloaded audiobook player draws its own slider, or that the dialer during a call is
 * one screen you want left alone. Both were reported, against four different apps, before this
 * existed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VolumeHudAppListScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }

    // One binder call with every launchable activity in it, as the other list screens do, so a
    // failure here is an empty list rather than a dead screen.
    val apps = remember {
        runCatching {
            val pm = context.packageManager
            val launchable = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            pm.queryIntentActivities(launchable, PackageManager.MATCH_ALL)
                .map { HudApp(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
                .distinctBy { it.pkg }
                .sortedBy { it.label.lowercase() }
        }.getOrDefault(emptyList())
    }

    // Mirrors the stored set so a tap redraws at once. A value read out of SharedPreferences while
    // composing is invisible to Compose, and a list that saves your tap and does not show it is
    // worse than one that refuses it.
    val off = remember { mutableStateListOf<String>().apply { addAll(prefs.volumeHudOffApps()) } }

    val listState = rememberLazyListState()
    WheelScroll(listState)

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                colors = barColors(),
                title = { Text("Where the strip stays down", style = MaterialTheme.typography.titleMedium) },
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
                item {
                    Text(
                        "Turn an app on here and no volume strip is drawn while it is in front — " +
                            "whatever moved the level. Use it for apps that already show a volume " +
                            "control of their own, where a strip on top is the same number twice.\n\n" +
                            "This is the blunt one. If an app's volume keys do something else " +
                            "entirely, such as turning a page, the other list is the one you want: " +
                            "it leaves the strip working everywhere the level really did move.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Dim,
                        modifier = Modifier.padding(16.dp),
                    )
                    Rule()
                }
                items(apps, key = { it.pkg }) { app ->
                    // Light's own screens are refused by the built-in table whatever this list
                    // says, so they get a row that tells the truth rather than a dead switch.
                    val byTable = Policy.volumeHudRefusedByTable(app.pkg)
                    val hidden = app.pkg in off
                    MenuRow(
                        label = app.label,
                        detail = when {
                            byTable -> "ALWAYS OFF"
                            hidden -> "NO STRIP"
                            else -> "STRIP"
                        },
                        sub = when {
                            byTable ->
                                "Light's own, and it draws its own volume UI. Never given a strip, " +
                                    "except the dialer during a call — put it here to stop that too."
                            hidden -> "nothing drawn here, whatever changes the volume"
                            else -> "the strip appears, as everywhere else"
                        },
                        dim = byTable || !hidden,
                        onClick = {
                            if (hidden) off.remove(app.pkg) else off.add(app.pkg)
                            prefs.toggleVolumeHudOff(app.pkg)
                        },
                    )
                    Rule()
                }
            }
        }
    }
}
