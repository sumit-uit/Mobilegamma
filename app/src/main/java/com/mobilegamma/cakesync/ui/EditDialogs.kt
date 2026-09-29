package com.mobilegamma.cakesync.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.media.MediaPlayer
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.mobilegamma.cakesync.data.Photo
import com.mobilegamma.cakesync.edit.BrandKit
import com.mobilegamma.cakesync.edit.CollageBackground
import com.mobilegamma.cakesync.edit.CollageRenderer
import com.mobilegamma.cakesync.edit.CollageTemplate
import com.mobilegamma.cakesync.edit.ColorFilterPreset
import com.mobilegamma.cakesync.edit.Music
import com.mobilegamma.cakesync.edit.PhotoEditor
import com.mobilegamma.cakesync.edit.ReelMaker
import com.mobilegamma.cakesync.edit.ReelOptions
import com.mobilegamma.cakesync.edit.ReelSpeed
import com.mobilegamma.cakesync.edit.ReelStyle
import com.mobilegamma.cakesync.share.Sharer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where free, no-attribution music can be downloaded. */
private const val FREE_MUSIC_URL = "https://pixabay.com/music/"

/**
 * Shows [source] (or a sample picture when there is none) after [transform], redrawn
 * whenever one of [keys] changes. Work happens off the main thread on a small copy.
 */
@Composable
fun EditPreview(
    source: Uri?,
    vararg keys: Any?,
    maxHeight: Int = 280,
    transform: (Bitmap) -> Bitmap,
) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(null, source, *keys) {
        value = withContext(Dispatchers.Default) {
            runCatching {
                val base = source?.let { PhotoEditor.loadScaled(context, it, 720) } ?: samplePicture()
                transform(base)
            }.getOrNull()
        }
    }
    Box(Modifier.fillMaxWidth().heightIn(min = 120.dp, max = maxHeight.dp), contentAlignment = Alignment.Center) {
        val b = bitmap
        if (b == null) {
            Text("Preparing preview…", style = MaterialTheme.typography.bodySmall)
        } else {
            Image(
                b.asImageBitmap(),
                contentDescription = "Preview",
                contentScale = ContentScale.Fit,
                modifier = Modifier.heightIn(max = maxHeight.dp).clip(RoundedCornerShape(8.dp)),
            )
        }
    }
}

/** Stand-in picture for previews before any photo has been scanned. */
private fun samplePicture(): Bitmap {
    val bmp = Bitmap.createBitmap(720, 720, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    val bg = Paint().apply {
        shader = LinearGradient(0f, 0f, 0f, 720f, 0xFFF8D7DA.toInt(), 0xFFFCEFE3.toInt(), Shader.TileMode.CLAMP)
    }
    canvas.drawRect(0f, 0f, 720f, 720f, bg)
    val cake = Paint(Paint.ANTI_ALIAS_FLAG)
    cake.color = 0xFFE8A0B4.toInt(); canvas.drawRoundRect(190f, 360f, 530f, 520f, 30f, 30f, cake)
    cake.color = 0xFFF6C6D3.toInt(); canvas.drawRoundRect(240f, 250f, 480f, 370f, 26f, 26f, cake)
    cake.color = 0xFFFFE08A.toInt(); canvas.drawRect(352f, 190f, 368f, 250f, cake)
    cake.color = 0xFFFF8A3D.toInt(); canvas.drawCircle(360f, 180f, 12f, cake)
    val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF8A5A66.toInt(); textSize = 34f; textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    canvas.drawText("Sample photo", 360f, 600f, text)
    return bmp
}

/** Actions for something in the Created tab. */
@Composable
fun CreationDialog(
    item: Photo,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    onShare: (Sharer.Target) -> Unit,
    onDelete: () -> Unit,
) {
    var confirmDelete by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(item.labels) },
        text = {
            Column(
                Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                AsyncImage(
                    model = item.uri, contentDescription = item.displayName, contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp).clip(RoundedCornerShape(8.dp)),
                )
                Text(item.displayName, style = MaterialTheme.typography.labelSmall)
                Button(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
                    Text(if (item.isVideo) "▶ Play" else "Open")
                }
                Sharer.Target.entries.forEach { target ->
                    OutlinedButton(onClick = { onShare(target) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Share: ${target.label}")
                    }
                }
                if (confirmDelete) {
                    TextButton(onClick = onDelete, modifier = Modifier.fillMaxWidth()) {
                        Text("Tap again to delete for good", color = MaterialTheme.colorScheme.error)
                    }
                } else {
                    TextButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) { Text("Delete") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/** Colour filter picker with a live preview; saves filtered copies. */
@Composable
fun FilterDialog(count: Int, sample: Uri?, onDismiss: () -> Unit, onSave: (ColorFilterPreset) -> Unit) {
    var filter by remember { mutableStateOf(ColorFilterPreset.WARM) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Colour filter") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                EditPreview(sample, filter) { PhotoEditor.applyFilter(it, filter) }
                FilterChips(filter, includeNone = false) { filter = it }
                Text(
                    "Saves filtered copies of $count photo(s); originals stay as they are.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(filter) }) { Text("Save copies") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun FilterChips(selected: ColorFilterPreset, includeNone: Boolean, onSelect: (ColorFilterPreset) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ColorFilterPreset.entries.filter { includeNone || it != ColorFilterPreset.NONE }.forEach { f ->
            FilterChip(selected = selected == f, onClick = { onSelect(f) }, label = { Text(f.label) })
        }
    }
}

/** Collage templates with a live preview of the selected photos. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CollageDialog(
    photos: List<Photo>,
    brandKit: BrandKit,
    onDismiss: () -> Unit,
    onMake: (CollageTemplate, PhotoEditor.Shape, CollageBackground, Boolean, String) -> Unit,
) {
    val context = LocalContext.current
    val fits = CollageTemplate.entries.filter { it.size <= photos.size }
    var template by remember { mutableStateOf(fits.lastOrNull { it.size == photos.size } ?: fits.lastOrNull()) }
    var shape by remember { mutableStateOf(PhotoEditor.Shape.SQUARE) }
    var background by remember { mutableStateOf(CollageBackground.WHITE) }
    var brand by remember { mutableStateOf(!brandKit.isEmpty) }
    var price by remember { mutableStateOf("") }
    // Small copies of the photos, loaded once for the preview.
    val thumbs by produceState<List<Bitmap>?>(null, photos) {
        value = withContext(Dispatchers.Default) {
            photos.take(9).mapNotNull { runCatching { PhotoEditor.loadScaled(context, it.uri, 480) }.getOrNull() }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Make a collage") },
        text = {
            Column(
                Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val t = template
                if (t == null) {
                    Text("Select at least 2 photos for a collage.", style = MaterialTheme.typography.bodyMedium)
                } else {
                    val loaded = thumbs
                    if (loaded != null && loaded.isNotEmpty()) {
                        EditPreview(null, t, shape, background, brand, price, loaded.size, maxHeight = 300) {
                            val w = 540
                            val h = w * shape.h / shape.w
                            val collage = CollageRenderer.render(loaded, t, w, h, background)
                            if (brand) brandKit.apply(collage, price) else collage
                        }
                    } else {
                        Text("Preparing preview…", style = MaterialTheme.typography.bodySmall)
                    }
                    Text("Layout (${photos.size} photo(s) selected)", style = MaterialTheme.typography.labelMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        CollageTemplate.entries.forEach { option ->
                            FilterChip(
                                selected = option == t,
                                enabled = option.size <= photos.size,
                                onClick = { template = option },
                                leadingIcon = { TemplateIcon(option) },
                                label = { Text(option.label) },
                            )
                        }
                    }
                    Text("Size", style = MaterialTheme.typography.labelMedium)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        PhotoEditor.Shape.entries.forEach { s ->
                            FilterChip(selected = shape == s, onClick = { shape = s }, label = { Text(s.label.substringBefore(' ')) })
                        }
                    }
                    Text("Background", style = MaterialTheme.typography.labelMedium)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        CollageBackground.entries.forEach { b ->
                            FilterChip(selected = background == b, onClick = { background = b }, label = { Text(b.label) })
                        }
                    }
                    SwitchRow("Add my branding", brand) { brand = it }
                    if (brand) {
                        OutlinedTextField(
                            value = price, onValueChange = { price = it }, singleLine = true,
                            label = { Text("Price or text (optional)") }, modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = template != null, onClick = { onMake(template!!, shape, background, brand, price) }) {
                Text("Save collage")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Tiny drawing of a collage layout. */
@Composable
private fun TemplateIcon(template: CollageTemplate) {
    val size = 22
    Box(Modifier.size(size.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(3.dp))) {
        template.cells.forEach { c ->
            Box(
                Modifier
                    .offset((c.left * size + 1).dp, (c.top * size + 1).dp)
                    .size(((c.right - c.left) * size - 2).dp, ((c.bottom - c.top) * size - 2).dp)
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)),
            )
        }
    }
}

/** Reel style, speed, filter and music. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReelDialog(count: Int, onDismiss: () -> Unit, onMake: (Uri?, Music.Track?, ReelOptions) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var style by remember { mutableStateOf(ReelStyle.MIX) }
    var speed by remember { mutableStateOf(ReelSpeed.NORMAL) }
    var filter by remember { mutableStateOf(ColorFilterPreset.NONE) }
    var fill by remember { mutableStateOf(false) }
    var track by remember { mutableStateOf<Music.Track?>(Music.Track.HAPPY) }
    var ownMusic by remember { mutableStateOf<Uri?>(null) }
    var playing by remember { mutableStateOf<Music.Track?>(null) }
    val player = remember { arrayOfNulls<MediaPlayer>(1) }
    fun stop() {
        player[0]?.release(); player[0] = null; playing = null
    }
    DisposableEffect(Unit) { onDispose { stop() } }
    val pickMusic = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { ownMusic = uri; track = null }
    }
    AlertDialog(
        onDismissRequest = { stop(); onDismiss() },
        title = { Text("Make a reel") },
        text = {
            Column(
                Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "A 9:16 video from $count item(s) in date order (up to ${ReelMaker.MAX_ITEMS}); videos use their " +
                        "first ${ReelMaker.VIDEO_MS / 1000}s.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text("Transition", style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    ReelStyle.entries.forEach { s ->
                        FilterChip(selected = style == s, onClick = { style = s }, label = { Text(s.label) })
                    }
                }
                Text("Photo speed", style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    ReelSpeed.entries.forEach { s ->
                        val seconds = (s.photoMs / 1000.0).toString().removeSuffix(".0")
                        FilterChip(
                            selected = speed == s, onClick = { speed = s },
                            label = { Text("${s.label} ${seconds}s", maxLines = 1) },
                        )
                    }
                }
                Text("Colour filter", style = MaterialTheme.typography.labelMedium)
                FilterChips(filter, includeNone = true) { filter = it }
                SwitchRow("Fill the screen (crops edges)", fill) { fill = it }
                Text("Music", style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    FilterChip(
                        selected = track == null && ownMusic == null,
                        onClick = { track = null; ownMusic = null; stop() },
                        label = { Text("No music") },
                    )
                    Music.Track.entries.forEach { t ->
                        FilterChip(selected = track == t, onClick = { track = t; ownMusic = null }, label = { Text("🎵 ${t.label}") })
                    }
                    FilterChip(
                        selected = ownMusic != null,
                        onClick = { pickMusic.launch(arrayOf("audio/*")) },
                        label = { Text(if (ownMusic == null) "My music file…" else "🎵 My file") },
                    )
                }
                track?.let { t ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${t.label}: ${t.mood}. Free to use, made by the app.", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = {
                            if (playing == t) { stop(); return@TextButton }
                            stop()
                            scope.launch {
                                val file = withContext(Dispatchers.Default) { Music.file(context.filesDir, t) }
                                player[0] = MediaPlayer.create(context, Uri.fromFile(file))?.apply { isLooping = true; start() }
                                playing = t
                            }
                        }) { Text(if (playing == t) "■ Stop" else "▶ Listen") }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "More free music: download an MP3 from Pixabay, then choose \"My music file…\".",
                        Modifier.weight(1f), style = MaterialTheme.typography.labelSmall,
                    )
                    TextButton(onClick = { openUrl(context, FREE_MUSIC_URL) }) { Text("Open Pixabay") }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                stop()
                onMake(ownMusic, if (ownMusic == null) track else null, ReelOptions(style, speed, filter, fill))
            }) { Text("Make reel") }
        },
        dismissButton = { TextButton(onClick = { stop(); onDismiss() }) { Text("Cancel") } },
    )
}

private fun openUrl(context: Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
