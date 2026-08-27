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
import com.gios.lightcontrol.BindSlot
import com.gios.lightcontrol.Button
import com.gios.lightcontrol.Prefs
import com.gios.lightcontrol.ui.theme.Dim

private data class Choice(val action: Action, val label: String, val sub: String?)

/** A run of choices under one heading, so a list this long can be read rather than scanned. */
private data class Group(val title: String?, val choices: List<Choice>)

/**
 * What one gesture should do — a press of a button, or a swipe from an edge.
 *
 * The built-in actions and the app list are one scroll rather than two screens: on a 3.9"
 * panel a second hop to "…and now pick which app" is a hop too many, and the apps are the
 * long tail anyway — the fixed choices sit at the top where a thumb lands.
 *
 * One screen for both kinds of binding, addressed by [BindSlot]. A second picker for the edges
 * would have been a second list of actions to keep in step with this one, which is how a phone ends
 * up able to bind an app to a button and not to a gesture for no reason anybody chose.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PickerScreen(
    slot: BindSlot,
    onDone: () -> Unit,
    /** Straight on to choosing which apps, because the action does nothing until some are. */
    onChooseResumeApps: () -> Unit,
) {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    val current = remember { prefs.action(slot) }

    // One list, every slot. Grouped only to be readable — nothing here is filtered by which
    // button or edge you arrived from, which is the whole point: a phone that can bind an app to
    // one gesture and not to another does it for no reason anybody chose.
    val groups = listOf(
        Group(
            null,
            listOf(
                Choice(Action.PassThrough, "Pass through", "the app in front gets the key"),
                Choice(Action.None, "Nothing", "swallowed, does nothing"),
            ),
        ),
        Group(
            "GETTING AROUND",
            listOf(
                Choice(Action.DefaultHome, "Home", "whichever launcher is default"),
                Choice(Action.LightOsHome, "LightOS home", "Light's dashboard, by name"),
                Choice(Action.Back, "Go back", "the back this phone has no button for"),
                Choice(Action.Switcher, "App switcher", "the list of apps you have been in"),
                Choice(
                    Action.Resume,
                    "Back to where you were",
                    "a chosen app if the screen slept in it",
                ),
            ),
        ),
        Group(
            "THE PHONE",
            listOf(
                Choice(Action.Torch, "Flashlight", "on or off"),
                Choice(Action.OpenCamera, "Camera", "opens the Light camera"),
                Choice(
                    Action.OpenSettings,
                    "System settings",
                    "the settings LightOS ships no way into",
                ),
                Choice(Action.Shade, "Notification shade", "pulls the shade down"),
                Choice(Action.QuickSettings, "Quick settings", "the panel behind the shade"),
                Choice(Action.Screenshot, "Screenshot", "saved wherever the system saves them"),
                Choice(Action.LockNow, "Lock the phone", "as the power button would"),
                Choice(Action.PowerMenu, "Power menu", "restart, power off"),
            ),
        ),
        Group(
            "VOLUME AND BRIGHTNESS",
            listOf(
                Choice(
                    Action.VolumeUp,
                    "Volume up",
                    "one step, on whatever the volume keys would move",
                ),
                Choice(Action.VolumeDown, "Volume down", "one step down, the same way"),
                Choice(Action.BrightnessUp, "Brighter", "one notch, as a wheel turn would"),
                Choice(Action.BrightnessDown, "Dimmer", "one notch the other way"),
            ),
        ),
        Group(
            "THIS APP",
            listOf(
                Choice(
                    Action.ColorFlip,
                    "Colour or mono",
                    "flips the app in front, and remembers which",
                ),
                Choice(
                    Action.SwitchTurn,
                    "Switch what a turn does",
                    "between brightness and scrolling, and says which",
                ),
                Choice(Action.ShowLock, "Lock face", "put it up over whatever is on screen"),
                Choice(Action.Hotspot, "Hotspot", "up or down, with the name already saved"),
            ),
        ),
    )

    // Guarded: this is one binder call carrying every launchable activity on the phone, and on a
    // busy boot it can come back as TransactionTooLargeException or DeadObjectException. An empty
    // list loses the app rows; letting it throw loses the whole screen, which is how "it won't
    // open" happens.
    val apps = remember {
        runCatching {
            val pm = context.packageManager
            val launchable = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            pm.queryIntentActivities(launchable, PackageManager.MATCH_ALL)
                .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
                .distinctBy { it.first }
                .sortedBy { it.second.lowercase() }
        }.getOrDefault(emptyList())
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
                        Text(slot.title, style = MaterialTheme.typography.titleMedium)
                        Text(
                            slot.sub,
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
            groups.forEach { group ->
                group.title?.let { item { SectionLabel(it) } }
                items(group.choices, key = { it.action.store() }) { choice ->
                    MenuRow(
                        label = choice.label,
                        detail = if (choice.action == current) "•" else null,
                        sub = choice.sub,
                        onClick = {
                            prefs.setAction(slot, choice.action)
                            // Picking Resume and landing back on a screen that says RESUME, with
                            // nothing chosen to resume to, is a setting that reads as finished and
                            // isn't. The list is the second half of this choice, not a follow-up.
                            if (choice.action == Action.Resume) onChooseResumeApps() else onDone()
                        },
                    )
                    Rule()
                }
            }
            item {
                SectionLabel(
                    if (slot is BindSlot.Key && slot.button == Button.Home) {
                        "OPEN AN APP INSTEAD OF HOME"
                    } else {
                        "OPEN AN APP"
                    },
                )
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
            items(apps, key = { it.first }) { (pkg, label) ->
                MenuRow(
                    label = label,
                    detail = if (current == Action.Launch(pkg)) "•" else null,
                    onClick = {
                        prefs.setAction(slot, Action.Launch(pkg))
                        onDone()
                    },
                )
                Rule()
            }
        }
    }
}
