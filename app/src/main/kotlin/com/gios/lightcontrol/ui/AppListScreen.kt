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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.gios.lightcontrol.AppRule
import com.gios.lightcontrol.Policy
import com.gios.lightcontrol.Prefs
import com.gios.lightcontrol.ui.theme.Dim

private data class Installed(val label: String, val pkg: String)

/**
 * Every launchable app, and what the wheel does in it.
 *
 * Tapping a row cycles the rule rather than opening a picker: four states on a 3.9" panel is
 * a cycle's worth, and a dialog per app would be four taps to do what one does. The label
 * says which state you're in, and rows left on Default say what Default resolved to, so the
 * built-in table is visible rather than mysterious.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppListScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }

    // See PickerScreen: one binder call with every launchable activity in it, so a failure here
    // is an empty list rather than a dead screen.
    val apps = remember {
        runCatching {
            val pm = context.packageManager
            val launchable = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            pm.queryIntentActivities(launchable, PackageManager.MATCH_ALL)
                .map {
                    Installed(
                        label = it.loadLabel(pm).toString(),
                        pkg = it.activityInfo.packageName,
                    )
                }
                .distinctBy { it.pkg }
                .sortedBy { it.label.lowercase() }
        }.getOrDefault(emptyList())
    }

    // Mirrors the stored rules so a tap redraws immediately; SharedPreferences is the
    // source of truth and this is only the view of it.
    val rules = remember { mutableStateMapOf<String, AppRule>().apply { putAll(prefs.overrides()) } }

    val listState = rememberLazyListState()
    WheelScroll(listState)

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                colors = barColors(),
                title = { Text("Per-app", style = MaterialTheme.typography.titleMedium) },
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
                    val rule = rules[app.pkg] ?: AppRule.Default
                    MenuRow(
                        label = app.label,
                        detail = detail(rule),
                        sub = if (rule == AppRule.Default) {
                            "default · ${describe(Policy.ruleFor(prefs, app.pkg))}"
                        } else {
                            describe(rule)
                        },
                        onClick = {
                            val next = cycle(rule)
                            if (next == AppRule.Default) rules.remove(app.pkg) else rules[app.pkg] = next
                            prefs.setRule(app.pkg, next)
                        },
                    )
                    Rule()
                }
                item {
                    Text(
                        "Light's own tools default to hands-off — the wheel already works " +
                            "there, so anything intercepted would be a feature removed.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Dim,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
    }
}

private fun cycle(rule: AppRule): AppRule = when (rule) {
    AppRule.Default -> AppRule.BrightnessOnTurn
    AppRule.BrightnessOnTurn -> AppRule.SwipeOnTurn
    AppRule.SwipeOnTurn -> AppRule.ScrollThrough
    AppRule.ScrollThrough -> AppRule.Off
    AppRule.Off -> AppRule.Default
}

private fun detail(rule: AppRule): String = when (rule) {
    AppRule.Default -> "AUTO"
    AppRule.BrightnessOnTurn -> "BRIGHT"
    AppRule.SwipeOnTurn -> "SWIPE"
    AppRule.ScrollThrough -> "APP"
    AppRule.Off -> "OFF"
}

private fun describe(rule: AppRule): String = when (rule) {
    AppRule.Default -> "turn does whatever the global setting says"
    AppRule.BrightnessOnTurn -> "turning the wheel changes brightness"
    AppRule.SwipeOnTurn -> "turning scrolls it, by synthetic swipe"
    AppRule.ScrollThrough -> "turns go to the app, it scrolls itself"
    AppRule.Off -> "hands off — every key goes straight through"
}
