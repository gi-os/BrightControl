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
import com.gios.lightcontrol.Action
import com.gios.lightcontrol.Prefs
import com.gios.lightcontrol.ui.theme.Dim

private data class Candidate(val label: String, val pkg: String)

/**
 * Every launchable app, label-sorted.
 *
 * See PickerScreen: one binder call carrying every launchable activity on the phone, which on a
 * busy boot can come back as `TransactionTooLargeException` or `DeadObjectException`. An empty
 * list loses the rows; letting it throw loses the screen, which is how "it won't open" happens.
 */
@Composable
private fun launchableApps(): List<Candidate> {
    val pm = LocalContext.current.packageManager
    return remember {
        runCatching {
            val launchable = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            pm.queryIntentActivities(launchable, PackageManager.MATCH_ALL)
                .map { Candidate(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
                .distinctBy { it.pkg }
                .sortedBy { it.label.lowercase() }
        }.getOrDefault(emptyList())
    }
}

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
fun ResumeAppsScreen(onBack: () -> Unit, onChooseFallback: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    val apps = launchableApps()

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
                    sub = "once. Press home again and you get the destination below, and so " +
                        "does any press after you have opened something else. Needs the home " +
                        "tap bound to “Back to where you were”.",
                    dim = true,
                )
                Rule()
                MenuRow(
                    label = "Otherwise open",
                    detail = when (val f = prefs.resumeFallback) {
                        is Action.Launch -> appLabel(context.packageManager, f.pkg).uppercase()
                        else -> "HOME"
                    },
                    sub = "every press with nothing to resume — which is most of them",
                    onClick = onChooseFallback,
                )
                Rule()
                SectionLabel("BRING THESE BACK")
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

/**
 * Where the home button goes when there is nothing to resume.
 *
 * Its own screen, and worth one, because this is the setting that keeps *Back to where you were*
 * from costing you something. Resume is bound over whatever the home tap used to be — and on
 * this phone plain "home" is LightOS, since LightOS has to hold the HOME role or it crash-loops.
 * Anyone whose tap pointed at Luma would otherwise have turned this feature on and quietly lost
 * their home screen. Point it back at Luma here and Resume becomes purely additive: the app
 * comes back when there is one, and every other press does exactly what it always did.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResumeFallbackScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    val apps = launchableApps()
    var current by remember { mutableStateOf(prefs.resumeFallback) }

    val listState = rememberLazyListState()
    WheelScroll(listState)

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                colors = barColors(),
                title = {
                    Column {
                        Text("Otherwise open", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "when there is nothing to resume",
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
                    label = "Home",
                    detail = if (current == Action.DefaultHome) "•" else null,
                    sub = "whichever launcher is default",
                    onClick = {
                        prefs.resumeFallback = Action.DefaultHome
                        current = Action.DefaultHome
                        onBack()
                    },
                )
                Rule()
                SectionLabel("OR AN APP INSTEAD OF HOME")
            }
            items(apps, key = { it.pkg }) { app ->
                MenuRow(
                    label = app.label,
                    detail = if (current == Action.Launch(app.pkg)) "•" else null,
                    onClick = {
                        prefs.resumeFallback = Action.Launch(app.pkg)
                        current = Action.Launch(app.pkg)
                        onBack()
                    },
                )
                Rule()
            }
        }
    }
}
