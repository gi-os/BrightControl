package com.gios.lightcontrol.ui

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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import android.content.Intent
import android.content.pm.PackageManager
import com.gios.lightcontrol.ColorRule
import com.gios.lightcontrol.Prefs
import com.gios.lightcontrol.keys.ColorMode
import com.gios.lightcontrol.keys.Grants
import com.gios.lightcontrol.ui.theme.Dim

/**
 * The color feature's own screen: what it is, the master switch, the grant it needs, and the
 * door to the per-app list.
 */
@Composable
fun ColorScreen(onPerApp: () -> Unit, onAdb: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    val canWriteSecure = Grants.canWriteSecureSettings(context)

    var auto by remember { mutableStateOf(prefs.colorAutoSwitch) }

    SectionScaffold(
        title = "Color",
        onBack = onBack,
        guide = "LightOS keeps the whole phone in black and white. There is no per-app color on " +
            "this phone — so this adds it. Turn it on, then set a rule per app: some apps in full " +
            "color (a camera, maps), the rest left mono. The screen switches as you move between " +
            "them.",
    ) {
        MenuRow(
            label = "Per-app color",
            detail = if (auto) "ON" else "OFF",
            sub = if (auto) {
                "the screen follows each app's rule as you switch. Off leaves the phone exactly " +
                    "as you last set it."
            } else {
                "off — color rules are ignored and nothing is forced. Tap to switch on."
            },
            onClick = {
                auto = !auto
                prefs.colorAutoSwitch = auto
                // Switching off is the one moment the baseline should be put back: a rule that
                // forced color is no longer being maintained, so leaving the phone on it would
                // strand a setting nothing is watching any more. This used to happen on service
                // unbind instead, which fired on every app update and repainted color apps mono.
                if (!auto) runCatching { ColorMode(context, prefs).restoreBaseline() }
            },
        )
        GrantRow(
            label = "Color grant",
            ok = canWriteSecure,
            fix = "adb shell pm grant com.gios.lightcontrol " +
                "android.permission.WRITE_SECURE_SETTINGS",
            sub = "needed to drive the system daltonizer. LightOS has no screen for it",
        )
        if (!canWriteSecure) {
            MenuRow(
                label = "Grant it on the phone",
                detail = "›",
                sub = "the ADB screen can grant this itself, no computer — tap to go there",
                onClick = onAdb,
            )
        }
        MenuRow(
            label = "Per-app rules",
            detail = "${prefs.colorOverrides().size}",
            sub = "which apps are color, which are mono",
            onClick = onPerApp,
        )
    }
}

/**
 * Every launchable app and its color rule. Tapping a row cycles AUTO → COLOR → MONO, the same
 * one-tap idiom as the wheel per-app list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ColorAppListScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }

    val apps = remember {
        runCatching {
            val pm = context.packageManager
            val launchable = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            pm.queryIntentActivities(launchable, PackageManager.MATCH_ALL)
                .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
                .distinctBy { it.second }
                .sortedBy { it.first.lowercase() }
        }.getOrDefault(emptyList())
    }

    val rules = remember {
        mutableStateMapOf<String, ColorRule>().apply { putAll(prefs.colorOverrides()) }
    }

    val listState = rememberLazyListState()
    WheelScroll(listState)

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                colors = barColors(),
                title = { Text("Per-app color", style = MaterialTheme.typography.titleMedium) },
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
                items(apps, key = { it.second }) { (label, pkg) ->
                    val rule = rules[pkg] ?: ColorRule.Default
                    MenuRow(
                        label = label,
                        detail = colorDetail(rule),
                        sub = colorDescribe(rule),
                        onClick = {
                            val next = colorCycle(rule)
                            if (next == ColorRule.Default) rules.remove(pkg) else rules[pkg] = next
                            prefs.setColorRule(pkg, next)
                        },
                    )
                    Rule()
                }
                item {
                    Text(
                        "AUTO leaves an app at the baseline — mono, on a stock LightOS phone. " +
                            "Set the apps you want in color to COLOR and leave the rest.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Dim,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
    }
}

private fun colorCycle(rule: ColorRule): ColorRule = when (rule) {
    ColorRule.Default -> ColorRule.Color
    ColorRule.Color -> ColorRule.Mono
    ColorRule.Mono -> ColorRule.Default
}

private fun colorDetail(rule: ColorRule): String = when (rule) {
    ColorRule.Default -> "AUTO"
    ColorRule.Color -> "COLOR"
    ColorRule.Mono -> "MONO"
}

private fun colorDescribe(rule: ColorRule): String = when (rule) {
    ColorRule.Default -> "baseline — mono on a stock phone"
    ColorRule.Color -> "full color while this app is in front"
    ColorRule.Mono -> "monochrome while this app is in front"
}
