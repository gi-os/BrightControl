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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.gios.lightcontrol.Prefs
import com.gios.lightcontrol.ui.theme.Dim

private data class Candidate(val label: String, val pkg: String)

/**
 * Which apps the home button is allowed to take you back to.
 *
 * Multi-select rather than one app, because the reason this exists is not specific to any one
 * of them: a remote, a recipe, a boarding pass and a map are all things you look at for two
 * seconds, put down, and come back to — and the screen times out in between every time.
 *
 * Everything is off by default and stays off. An app on this list changes what the home button
 * does in one narrow circumstance; an app not on it changes nothing at all, which is the state
 * the whole list should be in until someone deliberately says otherwise.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResumeAppsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }

    // See PickerScreen: one binder call carrying every launchable activity on the phone, so a
    // failure here is an empty list rather than a screen that won't open.
    val apps = remember {
        runCatching {
            val pm = context.packageManager
            val launchable = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            pm.queryIntentActivities(launchable, PackageManager.MATCH_ALL)
                .map { Candidate(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
                .distinctBy { it.pkg }
                .sortedBy { it.label.lowercase() }
        }.getOrDefault(emptyList())
    }

    // Read once into state and written through to prefs on each tap. The rows have to redraw as
    // they are ticked, and SharedPreferences is not something Compose can observe.
    var chosen by remember { mutableStateOf(prefs.resumeApps()) }

    val listState = rememberLazyListState()
    WheelScroll(listState)

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                colors = barColors(),
                title = {
                    Column {
                        Text("Resume apps", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (chosen.isEmpty()) "none chosen" else "${chosen.size} chosen",
                            style = MaterialTheme.typography.labelSmall,
                            color = Dim,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
            )
        },
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), state = listState) {
            item {
                MenuRow(
                    label = "Sleep in one of these and the home button brings it back",
                    sub = "once. Press home again and you go home, and so does any press after " +
                        "you have opened something else. Needs the home tap bound to " +
                        "“Back to where you were”.",
                    dim = true,
                )
                Rule()
            }
            if (apps.isEmpty()) {
                item {
                    MenuRow(
                        label = "No apps listed",
                        sub = "the package list came back empty — leave the screen and open it " +
                            "again once the phone has settled",
                        dim = true,
                    )
                }
            }
            items(apps, key = { it.pkg }) { app ->
                MenuRow(
                    label = app.label,
                    detail = if (app.pkg in chosen) "•" else null,
                    onClick = {
                        prefs.toggleResumeApp(app.pkg)
                        chosen = prefs.resumeApps()
                    },
                )
                Rule()
            }
        }
    }
}
