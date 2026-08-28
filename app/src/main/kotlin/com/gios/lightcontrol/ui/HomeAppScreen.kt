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
import com.gios.lightcontrol.switcher.HomeApp
import com.gios.lightcontrol.ui.theme.Dim

private data class HomeChoice(val label: String, val pkg: String, val launcher: Boolean)

/**
 * Which app the switcher's pinned **Home** row opens.
 *
 * One list, one tap, and that is the whole rule — see [HomeApp] for the two releases spent trying
 * to deduce this instead.
 *
 * ### Launchers first, and launchers are not ordinary apps to `PackageManager`
 *
 * The list is `CATEGORY_HOME` **and** `CATEGORY_LAUNCHER`, unioned. A launcher publishes the first
 * and **need not publish the second at all**, which is the same trap `Recents.openable` and
 * `ControlService.launcherEntry` already work around — a picker built the usual way would have left
 * Luma off the list, on the one screen whose entire job is choosing a launcher. The launchers are
 * grouped at the top for the same reason: this row is almost always one of them, and everything
 * else is the long tail.
 */
@Composable
private fun homeCandidates(): List<HomeChoice> {
    val pm = LocalContext.current.packageManager
    val self = LocalContext.current.packageName
    return remember {
        runCatching {
            // Guarded, like every other whole-phone query in this app: one binder call carrying
            // every activity on the device, which on a busy boot comes back as
            // TransactionTooLargeException or DeadObjectException. An empty list loses the rows;
            // letting it throw loses the screen, which is how "it won't open" happens.
            fun query(category: String) = runCatching {
                pm.queryIntentActivities(
                    Intent(Intent.ACTION_MAIN).addCategory(category),
                    PackageManager.MATCH_ALL,
                ).map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            }.getOrDefault(emptyList())

            val homes = query(Intent.CATEGORY_HOME).map { it.first }.toSet()
            (query(Intent.CATEGORY_HOME) + query(Intent.CATEGORY_LAUNCHER))
                .filter { it.first.isNotBlank() && it.first != "android" && it.first != self }
                .distinctBy { it.first }
                .map { (pkg, label) -> HomeChoice(label, pkg, pkg in homes) }
                // Launchers first, then everything else, each run sorted by name.
                .sortedWith(compareByDescending<HomeChoice> { it.launcher }.thenBy { it.label.lowercase() })
        }.getOrDefault(emptyList())
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeAppScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    val apps = homeCandidates()
    var current by remember { mutableStateOf(prefs.switcherHomePkg) }

    val listState = rememberLazyListState()
    WheelScroll(listState)

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                colors = barColors(),
                title = {
                    Column {
                        Text("Home app", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "what the switcher's Home row opens",
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
                    label = "The system's home",
                    detail = if (current == HomeApp.SYSTEM) "•" else null,
                    // Said plainly rather than left as the neutral-sounding option. On this phone
                    // it is LightOS and it will stay LightOS whatever launcher you install, which
                    // is the fact the two rules before this setting kept running into.
                    sub = "a plain home intent — on this phone that is LightOS, which holds the " +
                        "home role whether or not you use it",
                    onClick = {
                        prefs.switcherHomePkg = HomeApp.SYSTEM
                        current = HomeApp.SYSTEM
                        onBack()
                    },
                )
                Rule()
                SectionLabel("OR AN APP")
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
                    detail = if (current == app.pkg) "•" else null,
                    // Only the launchers are marked. Naming the rest "app" would be a word on
                    // every row that says nothing, and the ones worth telling apart here are the
                    // ones that can be a home screen.
                    sub = if (app.launcher) "a launcher" else null,
                    onClick = {
                        prefs.switcherHomePkg = app.pkg
                        current = app.pkg
                        onBack()
                    },
                )
                Rule()
            }
        }
    }
}
