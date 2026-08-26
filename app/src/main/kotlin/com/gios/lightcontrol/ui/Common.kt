package com.gios.lightcontrol.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.runtime.DisposableEffect
import com.gios.lightcontrol.ui.theme.Dim
import com.gios.lightcontrol.ui.theme.Faint
import com.gios.lightcontrol.ui.theme.RuleGray

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun barColors() = TopAppBarDefaults.topAppBarColors(
    containerColor = Color.Black,
    titleContentColor = Color.White,
    navigationIconContentColor = Color.White,
    actionIconContentColor = Color.White,
)

@Composable
fun Rule(modifier: Modifier = Modifier) =
    HorizontalDivider(modifier = modifier, color = RuleGray, thickness = 1.dp)

@Composable
fun Gap(height: Int) = Box(Modifier.height(height.dp))

/**
 * A target big enough to hit without looking. Filled means destructive-or-primary;
 * on a grayscale matte panel inversion is the only emphasis that reads at arm's
 * length, so there is no third style.
 */
@Composable
fun BigButton(
    label: String,
    modifier: Modifier = Modifier,
    filled: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val fg = when {
        !enabled -> Faint
        filled -> Color.Black
        else -> Color.White
    }
    Box(
        modifier
            .height(58.dp)
            .background(if (filled && enabled) Color.White else Color.Black)
            .border(BorderStroke(1.dp, if (enabled) Color.White else RuleGray))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = fg, maxLines = 1)
    }
}

@Composable
fun MenuRow(
    label: String,
    detail: String? = null,
    sub: String? = null,
    dim: Boolean = false,
    onClick: (() -> Unit)? = null,
    subMaxLines: Int = 2,
) {
    // The wheel's highlight, when the wheel is driving this screen. Drawn as a bar and a shade
    // rather than as a border or a padding change: anything that alters the row's size makes the
    // whole list twitch as the selection passes down it, which is the one thing a cursor must
    // never do.
    val stop = cursorStop(onClick)
    val barPx = with(LocalDensity.current) { 3.dp.toPx() }
    Row(
        Modifier
            .fillMaxWidth()
            .then(stop.modifier)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .drawBehind {
                if (!stop.selected) return@drawBehind
                drawRect(SelectedGround)
                drawRect(Color.White, size = Size(barPx, size.height))
            }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                color = if (dim) Dim else Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (sub != null) {
                Text(
                    sub,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Dim,
                    maxLines = subMaxLines,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (detail != null) {
            Text(detail, style = MaterialTheme.typography.bodyLarge, color = Color.White)
        }
    }
}

@Composable
fun EmptyState(message: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(24.dp), Alignment.Center) {
        Text(
            message,
            style = MaterialTheme.typography.bodyLarge,
            color = Dim,
            textAlign = TextAlign.Center,
        )
    }
}

/** Bottom tab bar in the LightOS action-bar idiom; the active tab is bracketed as
 *  well as brightened, because a tint would not read on this panel. */
@Composable
fun TabBar(selected: Int, labels: List<String>, onSelect: (Int) -> Unit) {
    Column {
        Rule()
        Row(
            Modifier.fillMaxWidth().height(60.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            labels.forEachIndexed { i, label ->
                val active = i == selected
                Box(
                    Modifier.weight(1f).fillMaxHeight().clickable { onSelect(i) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (active) "[ $label ]" else label,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (active) Color.White else Faint,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** A small caps section header, with room above it. */
@Composable
fun SectionLabel(text: String) {
    Gap(18)
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = Dim,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
    Gap(6)
}

/**
 * A paragraph of explanation at the top of a section screen. Every section has one, because the
 * whole point of splitting the settings up was to be able to say, on each screen, what the thing
 * on it actually does — a single scroll had no room for it.
 */
@Composable
fun GuideText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = Dim,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

/**
 * A grant, shown with its state and — on tap, or always when missing — the adb line that sets
 * it. LightOS has no Settings screen for any of these, so the command *is* the documentation.
 */
@Composable
fun GrantRow(label: String, ok: Boolean, fix: String, sub: String? = null) {
    var showing by remember { mutableStateOf(false) }
    MenuRow(
        label = label,
        detail = if (ok) "ON" else "OFF",
        sub = when {
            showing -> fix
            ok -> sub
            else -> sub ?: "tap for the adb line"
        },
        dim = !ok,
        onClick = { showing = !showing },
        subMaxLines = if (showing) 6 else 2,
    )
}

/**
 * The frame every section screen shares: black ground, a titled bar with a back arrow, and a
 * scroll wired to the wheel. [guide] is the one-paragraph explanation shown first.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SectionScaffold(
    title: String,
    onBack: () -> Unit,
    guide: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scroll = rememberScrollState()
    WheelScroll(scroll)
    Scaffold(
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                colors = barColors(),
                title = { Text(title, style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(scroll)) {
            if (guide != null) {
                GuideText(guide)
                Rule()
            }
            content()
            Gap(28)
        }
    }
}

/** The ground under the highlighted row. Barely lighter than black, which is all it takes. */
private val SelectedGround = Color(0xFF161616)


/**
 * Hold the screen on while something is running that would be ruined by it going off.
 *
 * ### Why this is not a nicety
 *
 * The adb work here is slow on purpose — a reconnect looks for a daemon for twelve seconds, a
 * pairing confirmation sits waiting for a request for the better part of a minute, and a batch of
 * nine grants is all of that in a row. Long enough, in other words, for a phone with a short screen
 * timeout to go dark in the middle. What happens then is not merely that nobody sees the result:
 *
 *  - The pairing dialog this app reads its code from is a **Settings** dialog, and Settings pausing
 *    is what tears the pairing session down. A screen that sleeps mid-pair kills the pairing.
 *  - Answering a Bluetooth pairing request depends on which branch the platform takes, and that is
 *    decided by whether the phone is interactive. A screen that changes state mid-attempt changes
 *    the answer.
 *  - And a run whose result nobody saw gets pressed again, which for a bond means starting over
 *    with an address that has since rotated.
 *
 * The window flag is not used: this is a per-view attribute, so it lifts itself when the composable
 * leaves and cannot be left switched on by a screen that navigated away mid-run.
 *
 * Callers pass a condition rather than calling this only while something runs, because a screen that
 * has to remember to turn the flag back off is a screen that will one day forget.
 */
@Composable
fun KeepAwake(active: Boolean) {
    val view = LocalView.current
    DisposableEffect(active) {
        view.keepScreenOn = active
        onDispose { view.keepScreenOn = false }
    }
}
