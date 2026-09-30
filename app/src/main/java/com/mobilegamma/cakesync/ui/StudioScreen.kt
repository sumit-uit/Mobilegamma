package com.mobilegamma.cakesync.ui

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mobilegamma.cakesync.data.Photo
import com.mobilegamma.cakesync.edit.Backdrop
import com.mobilegamma.cakesync.edit.Backdrops
import com.mobilegamma.cakesync.edit.ColorFilterPreset
import com.mobilegamma.cakesync.edit.PhotoEditor
import com.mobilegamma.cakesync.edit.StudioCrop
import com.mobilegamma.cakesync.edit.StudioSpec
import com.mobilegamma.cakesync.share.Sharer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Tool tabs in the studio. */
enum class StudioTab(val label: String, val emoji: String) {
    BACKGROUND("Background", "🪄"), FILTERS("Filters", "🎨"), ADJUST("Adjust", "☀️"), CROP("Crop", "▢"), BRAND("Brand", "🏷"),
}

/** Full-screen single-photo editor: background, filters, light, crop and branding. */
@Composable
fun StudioScreen(
    photo: Photo,
    startTab: StudioTab,
    viewModel: MainViewModel,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val editor = remember { PhotoEditor(context) }
    var session by remember { mutableStateOf<PhotoEditor.StudioSession?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var spec by remember { mutableStateOf(StudioSpec()) }
    var tab by remember { mutableStateOf(startTab) }
    var cutoutState by remember { mutableStateOf<String?>(null) } // null idle, "working", or an error
    var cutoutVersion by remember { mutableIntStateOf(0) }
    var comparing by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf<android.net.Uri?>(null) }

    LaunchedEffect(photo.uri) {
        runCatching { editor.openStudio(photo.uri, photo.displayName) }
            .onSuccess { session = it }
            .onFailure { loadError = it.message ?: "Could not open the photo" }
    }

    fun removeBackground() {
        val s = session ?: return
        if (s.cutout != null) { spec = spec.copy(replaceBackground = true); return }
        cutoutState = "working"
        scope.launch {
            runCatching { editor.cutout(s) }
                .onSuccess { cutoutState = null; cutoutVersion++; spec = spec.copy(replaceBackground = true) }
                .onFailure { cutoutState = it.message ?: "Could not cut out the cake" }
        }
    }
    // Opened from "White background": start cutting straight away.
    LaunchedEffect(session) { if (session != null && startTab == StudioTab.BACKGROUND) removeBackground() }

    val preview by produceState<Bitmap?>(null, session, spec, cutoutVersion) {
        val s = session ?: return@produceState
        value = withContext(Dispatchers.Default) { runCatching { editor.previewStudio(s, spec) }.getOrNull() }
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(color = Color(0xFF111111), modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                // Top bar
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onClose) { Text("✕", color = Color.White, fontSize = 20.sp) }
                    Text("Photo studio", color = Color.White, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    Button(
                        enabled = session != null && !saving,
                        onClick = {
                            val s = session ?: return@Button
                            saving = true
                            scope.launch {
                                val result = runCatching { editor.saveStudio(s, spec) }
                                saving = false
                                result.onSuccess { saved = it; viewModel.studioSaved() }
                                    .onFailure { cutoutState = "Could not save: ${it.message}" }
                            }
                        },
                    ) { Text(if (saving) "Saving…" else "Save") }
                }

                // Preview (hold to compare with the original)
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(12.dp)
                        .pointerInput(Unit) {
                            detectTapGestures(onPress = { comparing = true; tryAwaitRelease(); comparing = false })
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    val shown = if (comparing) session?.preview else preview
                    when {
                        loadError != null -> Text(loadError!!, color = Color.White)
                        shown == null -> CircularProgressIndicator()
                        else -> Image(
                            shown.asImageBitmap(), contentDescription = "Preview",
                            contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize(),
                        )
                    }
                    Text(
                        if (comparing) "Original" else "Hold to compare",
                        color = Color.White, style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.align(Alignment.TopCenter).clip(CircleShape)
                            .background(Color(0x88000000)).padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                    if (cutoutState == "working") {
                        Column(
                            Modifier.align(Alignment.Center).clip(RoundedCornerShape(16.dp))
                                .background(Color(0xCC000000)).padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            CircularProgressIndicator()
                            Text("Cutting out the cake…", color = Color.White, modifier = Modifier.padding(top = 10.dp))
                        }
                    }
                }

                // Tool panel
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                ) {
                    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                        Box(Modifier.heightIn(min = 150.dp).padding(horizontal = 16.dp)) {
                            when (tab) {
                                StudioTab.BACKGROUND -> BackgroundTools(
                                    spec = spec,
                                    cutoutState = cutoutState,
                                    onRemove = ::removeBackground,
                                    onKeep = { spec = spec.copy(replaceBackground = false) },
                                    onChange = { spec = it },
                                )
                                StudioTab.FILTERS -> FilterTools(session, spec) { spec = it }
                                StudioTab.ADJUST -> AdjustTools(spec) { spec = it }
                                StudioTab.CROP -> CropTools(spec) { spec = it }
                                StudioTab.BRAND -> BrandTools(spec) { spec = it }
                            }
                        }
                        Row(
                            Modifier.fillMaxWidth().navigationBarsPadding().padding(vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                        ) {
                            StudioTab.entries.forEach { t ->
                                Column(
                                    Modifier.clip(RoundedCornerShape(14.dp))
                                        .background(if (tab == t) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                                        .clickable { tab = t }
                                        .padding(horizontal = 8.dp, vertical = 6.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    Text(t.emoji, fontSize = 18.sp)
                                    Text(t.label, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
            }
        }

        saved?.let { uri ->
            AlertDialog(
                onDismissRequest = { saved = null },
                title = { Text("Saved ✨") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Your photo is in Created. Share it now, or keep editing.", style = MaterialTheme.typography.bodyMedium)
                        Sharer.Target.entries.forEach { target ->
                            OutlinedButton(
                                onClick = { viewModel.shareUri(uri, target) },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("Share: ${target.label}") }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { saved = null; onClose() }) { Text("Done") } },
                dismissButton = { TextButton(onClick = { saved = null }) { Text("Keep editing") } },
            )
        }
    }
}

@Composable
private fun BackgroundTools(
    spec: StudioSpec,
    cutoutState: String?,
    onRemove: () -> Unit,
    onKeep: () -> Unit,
    onChange: (StudioSpec) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(selected = !spec.replaceBackground, onClick = onKeep, label = { Text("Keep background") })
            FilterChip(selected = spec.replaceBackground, onClick = onRemove, label = { Text("🪄 Remove background") })
        }
        if (cutoutState != null && cutoutState != "working") {
            Text(cutoutState, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        if (spec.replaceBackground) {
            BackdropPicker(selected = spec.backdrop, allowPhoto = true) { onChange(spec.copy(backdrop = it)) }
            SwitchRow("Soft shadow under the cake", spec.shadow) { onChange(spec.copy(shadow = it)) }
        } else {
            Text(
                "Remove the background to put your cake on a clean colour, a gradient, a blur of the original or " +
                    "your own brand background.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Round swatches for every backdrop: presets, the user's brand backgrounds and an upload
 * button (uploads are kept as brand backgrounds). [allowPhoto] adds Original and Blur.
 */
@Composable
fun BackdropPicker(selected: Backdrop, allowPhoto: Boolean, onSelect: (Backdrop) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var brand by remember { mutableStateOf(Backdrops.brand(context)) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            val added = withContext(Dispatchers.IO) { runCatching { Backdrops.addBrand(context, uri) }.getOrNull() }
            brand = Backdrops.brand(context)
            added?.let { a -> brand.firstOrNull { it.file == a.file }?.let(onSelect) }
        }
    }
    val options = buildList {
        if (allowPhoto) { add(Backdrop.Blurred); add(Backdrop.Original) }
        addAll(brand)
        addAll(Backdrops.presets)
    }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Swatch("Upload", selected = false, onClick = {
                pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
                    Text("＋", fontSize = 22.sp)
                }
            }
        }
        items(options, key = { it.toString() }) { option ->
            Swatch(option.label, selected = option == selected, onClick = { onSelect(option) }) { BackdropThumb(option) }
        }
    }
}

@Composable
private fun Swatch(label: String, selected: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(56.dp).clickable(onClick = onClick)) {
        Box(
            Modifier.size(48.dp).clip(CircleShape)
                .border(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, CircleShape)
        ) { content() }
        Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1, textAlign = TextAlign.Center)
    }
}

@Composable
private fun BackdropThumb(backdrop: Backdrop) {
    when (backdrop) {
        is Backdrop.Solid -> Box(Modifier.fillMaxSize().background(Color(backdrop.color)))
        is Backdrop.Gradient -> Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(backdrop.top), Color(backdrop.bottom)))))
        is Backdrop.Picture -> coil3.compose.AsyncImage(
            model = backdrop.file, contentDescription = backdrop.label, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
        )
        Backdrop.Original -> Box(Modifier.fillMaxSize().background(Color(0xFF8D6E63)), contentAlignment = Alignment.Center) { Text("📷") }
        Backdrop.Blurred -> Box(
            Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Color(0xFFBCAAA4), Color(0xFF6D4C41)))),
            contentAlignment = Alignment.Center,
        ) { Text("💧") }
    }
}

@Composable
private fun FilterTools(session: PhotoEditor.StudioSession?, spec: StudioSpec, onChange: (StudioSpec) -> Unit) {
    // Small copy of the photo for the filter thumbnails.
    val thumb = remember(session) {
        session?.preview?.let { p ->
            val s = 160f / maxOf(p.width, p.height)
            Bitmap.createScaledBitmap(p, (p.width * s).toInt().coerceAtLeast(1), (p.height * s).toInt().coerceAtLeast(1), true)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(ColorFilterPreset.entries) { f ->
                val img = remember(thumb, f) { thumb?.let { PhotoEditor.applyFilter(it, f) } }
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.width(68.dp).clickable { onChange(spec.copy(filter = f, filterStrength = 1f)) },
                ) {
                    Box(
                        Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)).border(
                            if (spec.filter == f) 3.dp else 0.dp,
                            if (spec.filter == f) MaterialTheme.colorScheme.primary else Color.Transparent,
                            RoundedCornerShape(12.dp),
                        )
                    ) {
                        img?.let { Image(it.asImageBitmap(), f.label, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                    }
                    Text(f.label, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        if (spec.filter != ColorFilterPreset.NONE) {
            LabelledSlider("Strength", spec.filterStrength, 0f..1f) { onChange(spec.copy(filterStrength = it)) }
        }
    }
}

@Composable
private fun AdjustTools(spec: StudioSpec, onChange: (StudioSpec) -> Unit) {
    Column(Modifier.heightIn(max = 230.dp).verticalScroll(rememberScrollState())) {
        LabelledSlider("Brightness", spec.brightness, -1f..1f) { onChange(spec.copy(brightness = it)) }
        LabelledSlider("Contrast", spec.contrast, -1f..1f) { onChange(spec.copy(contrast = it)) }
        LabelledSlider("Saturation", spec.saturation, -1f..1f) { onChange(spec.copy(saturation = it)) }
        LabelledSlider("Warmth", spec.warmth, -1f..1f) { onChange(spec.copy(warmth = it)) }
        LabelledSlider("Vignette", spec.vignette, 0f..1f) { onChange(spec.copy(vignette = it)) }
        if (spec.isAdjusted) {
            TextButton(onClick = {
                onChange(spec.copy(brightness = 0f, contrast = 0f, saturation = 0f, warmth = 0f, vignette = 0f))
            }) { Text("Reset") }
        }
    }
}

@Composable
private fun CropTools(spec: StudioSpec, onChange: (StudioSpec) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StudioCrop.entries.forEach { c ->
                FilterChip(selected = spec.crop == c, onClick = { onChange(spec.copy(crop = c)) }, label = { Text(c.label) })
            }
        }
        FilledTonalButton(onClick = { onChange(spec.copy(rotation = (spec.rotation + 1) % 4)) }) { Text("⟳  Rotate") }
        Text("Crops stay centred on the cake.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun BrandTools(spec: StudioSpec, onChange: (StudioSpec) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SwitchRow("Add my logo and business name", spec.brand) { onChange(spec.copy(brand = it)) }
        if (spec.brand) {
            OutlinedTextField(
                value = spec.price, onValueChange = { onChange(spec.copy(price = it)) }, singleLine = true,
                label = { Text("Price or text (optional), e.g. ₹1,200") }, modifier = Modifier.fillMaxWidth(),
            )
        }
        Text(
            "Set your logo, name and position in Settings → Brand kit.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LabelledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(86.dp))
        Slider(value = value, onValueChange = onChange, valueRange = range, modifier = Modifier.weight(1f))
        Text(
            "%+d".format((value * 100).toInt()).let { if (range.start >= 0f) "${(value * 100).toInt()}" else it },
            style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(40.dp), textAlign = TextAlign.End,
        )
    }
}
