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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.gios.lightcontrol.Action
import com.gios.lightcontrol.Button
import com.gios.lightcontrol.Gesture
import com.gios.lightcontrol.Prefs
import com.gios.lightcontrol.ui.theme.Dim

private data class Choice(val action: Action, val label: String, val sub: String?)

/**
 * What one gesture on one button should do.
 *
 * The built-in actions and the app list are one scroll rather than two screens: on a 3.9"
 * panel a second hop to "…and now pick which app" is a hop too many, and the apps are the
 * long tail anyway — the four fixed choices sit at the top where a thumb lands.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PickerScreen(button: Button, gesture: Gesture, onDone: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    val current = remember { prefs.action(button, gesture) }

    val fixed = listOf(
        Choice(Action.PassThrough, "Pass through", "the app in front gets the key"),
        Choice(Action.None, "Nothing", "swallowed, does nothing"),
        Choice(Action.Torch, "Flashlight", "on or off"),
        Choice(Action.OpenCamera, "Camera", "opens the Light camera"),
    )

    val apps = remember {
        val pm = context.packageManager
        val launchable = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        pm.queryIntentActivities(launchable, PackageManager.MATCH_ALL)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }
    }

    val listState = rememberLazyListState()
    WheelScroll(listState)

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                colors = barColors(),
                title = {
                    Column {
                        Text(button.label, style = MaterialTheme.typography.titleMedium)
                        Text(
                            gesture.label.lowercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = Dim,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
            )
        },
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), state = listState) {
            items(fixed) { choice ->
                MenuRow(
                    label = choice.label,
                    detail = if (choice.action == current) "•" else null,
                    sub = choice.sub,
                    onClick = {
                        prefs.setAction(button, gesture, choice.action)
                        onDone()
                    },
                )
                Rule()
            }
            item { SectionLabel("OPEN AN APP") }
            items(apps, key = { it.first }) { (pkg, label) ->
                MenuRow(
                    label = label,
                    detail = if (current == Action.Launch(pkg)) "•" else null,
                    onClick = {
                        prefs.setAction(button, gesture, Action.Launch(pkg))
                        onDone()
                    },
                )
                Rule()
            }
        }
    }
}
