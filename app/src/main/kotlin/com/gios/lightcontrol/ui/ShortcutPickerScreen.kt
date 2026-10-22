package com.gios.lightcontrol.ui

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.gios.lightcontrol.Action
import com.gios.lightcontrol.BindSlot
import com.gios.lightcontrol.Prefs
import com.gios.lightcontrol.shortcuts.AppShortcuts
import com.gios.lightcontrol.ui.theme.Dim
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What the screen has read so far. Null until the first read is back. */
private data class Found(
    val declared: List<AppShortcuts.Declared>,
    val makers: List<AppShortcuts.Maker>,
)

/**
 * The shortcuts inside apps, for one gesture: "Add task" rather than Todoist.
 *
 * Two lists, because Android gives an app that is not the home screen two ways in and no more. The
 * first is the shortcuts each app declares in its manifest, bound with one tap. The second is the
 * apps that build a shortcut by asking a question first, which open their own screen and hand the
 * answer back. See [AppShortcuts] for what neither route can reach, and why.
 *
 * Read off the main thread. Every app's manifest file is a separate resource load, and on a phone
 * with fifty apps that is long enough to drop frames on the way in.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShortcutPickerScreen(
    slot: BindSlot,
    /** Bound: back to the list of buttons or edges, past the picker. */
    onDone: () -> Unit,
    /** Back to the picker, with nothing changed. */
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    val current = remember { prefs.action(slot) as? Action.Shortcut }
    val pm = context.packageManager
    val self = context.packageName

    val found by produceState<Found?>(null) {
        value = withContext(Dispatchers.IO) {
            Found(AppShortcuts.declared(pm, self), AppShortcuts.makers(pm))
        }
    }

    // A sentence about the last thing that did not bind. Said on this screen, under the heading,
    // because the alternative is a tap that seems to do nothing.
    var note by remember { mutableStateOf<String?>(null) }
    var asking by remember { mutableStateOf<AppShortcuts.Maker?>(null) }

    val ask = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val maker = asking ?: return@rememberLauncherForActivityResult
        asking = null
        when (val made = AppShortcuts.made(pm, maker, r.data, self)) {
            is AppShortcuts.Made.Bound -> {
                prefs.setAction(slot, made.action)
                onDone()
            }
            AppShortcuts.Made.Nothing -> note = "${maker.app} gave no shortcut back."
            AppShortcuts.Made.HomeScreenOnly ->
                note = "${maker.app} gives this shortcut only to a home screen app."
            AppShortcuts.Made.Private ->
                note = "${maker.app} keeps the screen this shortcut opens private."
        }
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
                            "${slot.sub} · a shortcut",
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
            note?.let { line ->
                item {
                    Text(
                        line,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                    Rule()
                }
            }
            val list = found
            if (list == null) {
                item { MenuRow(label = "Reading the apps…", dim = true) }
                return@LazyColumn
            }

            item { SectionLabel("SHORTCUTS") }
            if (list.declared.isEmpty()) {
                item {
                    MenuRow(
                        label = "No app on this phone declares one",
                        sub = "the shortcuts an app makes while it runs are kept for the home " +
                            "screen, and this app cannot see them",
                        dim = true,
                    )
                }
            }
            items(list.declared) { d ->
                MenuRow(
                    label = d.label,
                    detail = if (current?.pkg == d.pkg && current?.label == d.label) "•" else null,
                    sub = if (d.startable) d.app else "${d.app} · kept private, cannot be opened from here",
                    dim = !d.startable,
                    onClick = if (d.startable) {
                        {
                            prefs.setAction(slot, d.action())
                            onDone()
                        }
                    } else {
                        null
                    },
                )
                Rule()
            }

            if (list.makers.isNotEmpty()) {
                item { SectionLabel("MAKE ONE IN THE APP") }
                items(list.makers) { m ->
                    MenuRow(
                        label = m.label,
                        sub = "${m.app} · asks you first, then binds what you chose",
                        onClick = {
                            note = null
                            asking = m
                            try {
                                ask.launch(AppShortcuts.ask(m))
                            } catch (_: ActivityNotFoundException) {
                                asking = null
                                note = "${m.app} would not open its shortcut screen."
                            } catch (_: SecurityException) {
                                asking = null
                                note = "${m.app} would not open its shortcut screen."
                            }
                        },
                    )
                    Rule()
                }
            }

            item {
                Gap(16)
                Text(
                    "Android gives the full list of an app's shortcuts only to the home screen " +
                        "app. This list has the shortcuts an app declares in advance, and the ones " +
                        "an app makes for you when you ask it. Recent chats and other shortcuts an " +
                        "app adds while it runs are not here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Dim,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                Gap(28)
            }
        }
    }
}
