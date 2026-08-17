package com.gios.lightcontrol.ui

import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.gios.lightcontrol.Prefs
import com.gios.lightcontrol.lock.LockBackground
import com.gios.lightcontrol.lock.LockGallery
import com.gios.lightcontrol.ui.theme.Dim
import com.gios.lightcontrol.ui.theme.Faint
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The lock screen's background: pick a photo, choose how it meets the panel, then stack filters on
 * it — dither, black & white, opacity, corner blur, corner fade — reordering and repeating them
 * freely, with a live preview at the screen's own aspect so what you see is what the lock face
 * gets.
 *
 * The same editor as BrightChat's per-chat wallpaper, and the same pipeline underneath it. The one
 * difference is where the photo comes from: BrightChat walks DCIM itself because it needs the same
 * grid it sends from, and there is nothing here for that grid to be consistent with, so this uses
 * the system picker and copies what comes back.
 *
 * Nothing is written until Save. The stack is edited against a local copy of the saved state, so
 * backing out abandons it — the same bargain every editor on this phone makes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LockBackgroundScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }

    // The image being worked on. The already-saved copy when editing; a fresh copy of whatever the
    // picker returned otherwise. Null means there is no background yet.
    var source by remember {
        mutableStateOf(LockBackground.sourceFile(context).takeIf { it.length() > 0L })
    }
    // The working stack. A plain state list — order is the whole point of a stack, and every
    // mutation below recomposes the preview through it.
    val filters = remember {
        mutableStateListOf<LockBackground.Filter>().apply { addAll(LockBackground.filters(prefs)) }
    }
    var scale by remember { mutableStateOf(LockBackground.scale(prefs)) }
    val saved = remember { LockBackground.offset(prefs) }
    var ox by remember { mutableStateOf(saved.first) }
    var oy by remember { mutableStateOf(saved.second) }
    var adding by remember { mutableStateOf(false) }
    var stamp by remember { mutableStateOf(0) }

    // Whether the photo grid is up. A flag rather than nulling [source], so backing out of a
    // re-pick returns to the editor with the stack intact instead of abandoning the whole edit.
    var picking by remember { mutableStateOf(source == null) }

    // The face draws the background edge to edge, so the preview crops to the panel's own shape —
    // corner blur in particular has to land where the real corners will be.
    val configuration = LocalConfiguration.current
    val aspect = configuration.screenWidthDp.toFloat() / configuration.screenHeightDp

    val sourceSize by produceState<Pair<Int, Int>?>(null, source, stamp) {
        value = source?.let { withContext(Dispatchers.Default) { LockBackground.sourceSize(it) } }
    }

    val preview by produceState<ImageBitmap?>(null, source, scale, ox, oy, stamp, filters.toList()) {
        val file = source
        value = if (file == null) {
            null
        } else {
            withContext(Dispatchers.Default) {
                LockBackground.preview(file, filters.toList(), scale, aspect, ox, oy)
                    ?.asImageBitmap()
            }
        }
    }

    if (picking) {
        // Straight into the grid with nothing chosen: an empty editor has nothing to edit.
        PhotoGrid(
            onPick = {
                source = it
                stamp++
                picking = false
            },
            onClose = { if (source != null) picking = false else onClose() },
        )
        return
    }

    val scroll = rememberScrollState()
    WheelScroll(scroll)

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                colors = barColors(),
                title = { Text("Background", style = MaterialTheme.typography.titleMedium) },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(scroll).padding(horizontal = 16.dp)) {

            Gap(8)
            Box(
                Modifier
                    .fillMaxWidth(0.62f)
                    .aspectRatio(aspect)
                    .align(Alignment.CenterHorizontally)
                    .background(Color.Black)
                    .border(1.dp, Faint)
                    // Dragging frames the crop. Only FILL has overflow to spend on it, and only on
                    // the axis the photo is longer in — cover-scaling leaves slack on one.
                    .pointerInput(scale, sourceSize) {
                        val src = sourceSize ?: return@pointerInput
                        if (scale != LockBackground.ScaleMode.FILL) return@pointerInput
                        detectDragGestures { change, drag ->
                            change.consume()
                            val boxW = size.width.toFloat()
                            val boxH = size.height.toFloat()
                            if (boxW <= 0f || boxH <= 0f) return@detectDragGestures
                            val srcAspect = src.first.toFloat() / src.second
                            if (srcAspect > boxW / boxH) {
                                val slack = boxH * srcAspect - boxW
                                if (slack > 1f) ox = (ox - drag.x / slack).coerceIn(0f, 1f)
                            } else {
                                val slack = boxW / srcAspect - boxH
                                if (slack > 1f) oy = (oy - drag.y / slack).coerceIn(0f, 1f)
                            }
                        }
                    },
            ) {
                preview?.let {
                    Image(
                        bitmap = it,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                // A clock over the preview, because the whole question a background has to answer
                // is whether the time survives on it.
                Column(
                    Modifier.fillMaxSize().padding(10.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("9:41", style = MaterialTheme.typography.displaySmall, color = Color.White)
                    Text(
                        "SUNDAY",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFFBBBBBB),
                    )
                }
            }

            Gap(10)
            Text(
                "Choose a different photo",
                style = MaterialTheme.typography.bodyMedium,
                color = Dim,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(interactionSource = null, indication = null) {
                        picking = true
                    }
                    .padding(vertical = 8.dp),
            )

            Gap(6)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Scale",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Faint,
                    modifier = Modifier.weight(1f),
                )
                LockBackground.ScaleMode.entries.forEach { mode ->
                    Text(
                        mode.label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (scale == mode) Color.White else Dim,
                        modifier = Modifier
                            .clickable(interactionSource = null, indication = null) { scale = mode }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    )
                }
            }
            if (scale == LockBackground.ScaleMode.FILL) {
                Text(
                    "drag the preview to frame the crop",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Faint,
                )
            }

            Gap(10)
            Rule()
            SectionLabel("FILTERS")

            if (filters.isEmpty()) {
                Text(
                    "None — the photo as it is",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Dim,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
            filters.forEachIndexed { index, filter ->
                FilterRow(
                    filter = filter,
                    // Applied top to bottom; ↑ moves a filter earlier in the pass.
                    canMoveUp = index > 0,
                    onMoveUp = {
                        filters.removeAt(index)
                        filters.add(index - 1, filter)
                    },
                    onAmount = { up ->
                        filters[index] = filter.copy(amount = filter.type.bump(filter.amount, up))
                    },
                    onRemove = { filters.removeAt(index) },
                )
            }

            Gap(4)
            if (adding) {
                LockBackground.FilterType.entries.forEach { entry ->
                    MenuRow(
                        label = entry.label,
                        onClick = {
                            filters.add(LockBackground.Filter(entry, entry.default))
                            adding = false
                        },
                    )
                }
            } else {
                MenuRow(label = "Add a filter", detail = "+", onClick = { adding = true })
            }

            Gap(18)
            Rule()
            Gap(12)
            BigButton(
                label = "Save",
                filled = true,
                enabled = source != null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                source?.let {
                    LockBackground.save(context, prefs, it, filters.toList(), scale, ox, oy)
                }
                onClose()
            }
            Gap(10)
            BigButton(label = "Remove background", modifier = Modifier.fillMaxWidth()) {
                LockBackground.remove(context, prefs)
                onClose()
            }
            Gap(24)
        }
    }
}

/**
 * One filter in the stack: its name and setting on the left, the verbs on the right — −/+ nudge the
 * amount (absent for black & white, which has none), ↑ moves it earlier in the pass, × takes it
 * out. All text, like every verb on this phone; the stack rarely runs past three, so rows over
 * menus.
 */
@Composable
private fun FilterRow(
    filter: LockBackground.Filter,
    canMoveUp: Boolean,
    onMoveUp: () -> Unit,
    onAmount: (up: Boolean) -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            listOf(filter.type.label, filter.type.display(filter.amount))
                .filter { it.isNotEmpty() }
                .joinToString(" "),
            style = MaterialTheme.typography.bodyLarge,
            color = Color.White,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        if (filter.type.hasAmount) {
            Verb("−", filter.amount > filter.type.min) { onAmount(false) }
            Verb("+", filter.amount < filter.type.max) { onAmount(true) }
        }
        Verb("↑", canMoveUp) { if (canMoveUp) onMoveUp() }
        Verb("×", true, onRemove)
    }
}

@Composable
private fun Verb(glyph: String, enabled: Boolean, onClick: () -> Unit) {
    Text(
        glyph,
        style = MaterialTheme.typography.bodyLarge,
        color = if (enabled) Dim else Faint,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .width(40.dp)
            .clickable(interactionSource = null, indication = null, onClick = onClick)
            .padding(vertical = 6.dp),
    )
}

/**
 * The single-tap photo grid, walked off the filesystem rather than asked of MediaStore.
 *
 * BrightChat's, and for BrightChat's reason: nothing on LightOS keeps MediaStore current, so the
 * system picker — which v2.11 used — does not offer a photo taken minutes ago. A directory listing
 * cannot go stale.
 *
 * One tap chooses. There is no confirm step because there is nothing to protect: the tap only moves
 * to the editor, and nothing is written until its Save.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PhotoGrid(onPick: (File) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current

    var granted by remember {
        mutableStateOf(
            context.checkSelfPermission(LockGallery.permission) == PackageManager.PERMISSION_GRANTED,
        )
    }
    var asked by remember { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted = it }
    LaunchedEffect(Unit) {
        if (!granted && !asked) {
            asked = true
            ask.launch(LockGallery.permission)
        }
    }

    val photos by produceState<List<LockGallery.Photo>?>(null, granted) {
        value = null
        value = if (granted) LockGallery.scan() else emptyList()
    }

    val grid = rememberLazyGridState()
    WheelScroll(grid)
    BackHandler { onClose() }

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                colors = barColors(),
                title = { Text("Choose a photo", style = MaterialTheme.typography.titleMedium) },
            )
        },
    ) { pad ->
        val loaded = photos
        when {
            !granted -> EmptyState(
                "Controls needs access to photos to read\nthe camera roll.",
                Modifier.padding(pad).fillMaxSize(),
            )
            loaded == null -> Box(Modifier.padding(pad).fillMaxSize())
            loaded.isEmpty() -> EmptyState(
                "No photos in DCIM or Pictures.",
                Modifier.padding(pad).fillMaxSize(),
            )
            else -> LazyVerticalGrid(
                state = grid,
                columns = GridCells.Fixed(3),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                contentPadding = PaddingValues(2.dp),
                modifier = Modifier.padding(pad).fillMaxSize(),
            ) {
                items(loaded, key = { it.key }) { photo ->
                    val thumb by produceState<ImageBitmap?>(null, photo.key) {
                        value = LockGallery.thumbnail(photo)
                    }
                    Box(
                        Modifier
                            .aspectRatio(1f)
                            .background(Faint.copy(alpha = 0.25f))
                            .clickable(interactionSource = null, indication = null) {
                                onPick(photo.file)
                            },
                    ) {
                        thumb?.let {
                            Image(
                                bitmap = it,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
            }
        }
    }
}
