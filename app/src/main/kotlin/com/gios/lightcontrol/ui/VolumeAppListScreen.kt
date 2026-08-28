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
import com.gios.lightcontrol.Prefs
import com.gios.lightcontrol.ui.theme.Dim

/** Named for this screen: `Candidate` and `Installed` are taken by others in this package. */
private data class VolumeApp(val label: String, val pkg: String)

/**
 * Which apps take the volume keys for themselves.
 *
 * The strip appears on a volume press because this app's key filter sees the press. It sees it
 * whether or not anything acts on it — the filter runs ahead of the app in front — so an app that
 * turns pages or flips images with the volume keys used to get a volume readout over every one.
 *
 * There is no API that answers "did the app in front swallow that key", and two releases were spent
 * trying to infer it from whether the level moved. It cannot be inferred on this phone: volume keys
 * are handled upstream of accessibility filtering, so by the time the filter is asked, the level has
 * already changed. A list is the honest answer — and unlike the inference, it cannot go wrong
 * anywhere except on the apps in it.
 *
 * BrightLibrary is on it out of the box. Nothing else needs to be, unless you have an app that does
 * the same thing, and you are the only one who can know that.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VolumeAppListScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }

    // One binder call with every launchable activity in it, as the other list screens do, so a
    // failure here is an empty list rather than a dead screen.
    val apps = remember {
        runCatching {
            val pm = context.packageManager
            val launchable = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            pm.queryIntentActivities(launchable, PackageManager.MATCH_ALL)
                .map { VolumeApp(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
                .distinctBy { it.pkg }
                .sortedBy { it.label.lowercase() }
        }.getOrDefault(emptyList())
    }

    // Mirrors the stored set so a tap redraws at once. A value read out of SharedPreferences while
    // composing is invisible to Compose, and a list that saves your tap and does not show it is
    // worse than one that refuses it — see the Wi-Fi ringer list, which shipped exactly that.
    val theirs = remember { mutableStateListOf<String>().apply { addAll(prefs.volumeKeyApps) } }

    val listState = rememberLazyListState()
    WheelScroll(listState)

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                colors = barColors(),
                title = { Text("Volume keys per app", style = MaterialTheme.typography.titleMedium) },
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
                        "Turn an app on here when its volume keys do something other than volume — " +
                            "turning a page, stepping through photos. The strip then stays down " +
                            "while that app is in front, because the press was never a volume " +
                            "change.\n\nNothing else changes: this app has never taken a volume " +
                            "key from anybody, and an app on this list keeps every key it always " +
                            "had.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Dim,
                        modifier = Modifier.padding(16.dp),
                    )
                    Rule()
                }
                items(apps, key = { it.pkg }) { app ->
                    val mine = app.pkg in theirs
                    MenuRow(
                        label = app.label,
                        detail = if (mine) "ITS OWN" else "VOLUME",
                        sub = if (mine) {
                            "no strip here — its volume keys are its own"
                        } else {
                            "the strip appears, as everywhere else"
                        },
                        dim = !mine,
                        onClick = {
                            if (mine) theirs.remove(app.pkg) else theirs.add(app.pkg)
                            prefs.toggleVolumeKeyApp(app.pkg)
                        },
                    )
                    Rule()
                }
            }
        }
    }
}
