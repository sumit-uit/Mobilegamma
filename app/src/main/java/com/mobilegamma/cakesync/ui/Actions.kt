package com.mobilegamma.cakesync.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobilegamma.cakesync.data.Category
import com.mobilegamma.cakesync.edit.PhotoEditor
import com.mobilegamma.cakesync.share.Sharer
import com.mobilegamma.cakesync.ui.theme.CakeBrush

/** Everything that can be done with selected photos. */
enum class EditAction(
    val label: String,
    val emoji: String,
    val title: String,
    val blurb: String,
    val brush: Brush? = null,
    /** Photos needed before Continue is enabled. */
    val minPhotos: Int = 1,
) {
    STUDIO("Edit", "🪄", "Photo studio", "New background, filters, light and crop", CakeBrush.white),
    REEL("Reel", "🎬", "Reel", "Video with music and transitions", CakeBrush.reel),
    COLLAGE("Collage", "🧩", "Collage", "2 to 9 photos in one post", CakeBrush.collage, minPhotos = 2),
    FILTER("Filter", "🎨", "Filter", "Warm, Pastel, Vintage and more", CakeBrush.filter),
    BRAND("Brand", "🏷", "Brand", "Your logo, name and price", CakeBrush.brand),
    WHITE_BG("White bg", "✂", "White background", "Clean product shots", CakeBrush.white),
    CROP("Crop", "▢", "Crop", "1:1, 4:5 and 9:16 sizes", CakeBrush.crop),
    SHARE("Share", "📤", "Share", "Instagram, WhatsApp and more", CakeBrush.share),
    CATALOG("Catalog", "🛒", "Catalog", "Shop list for Facebook and Instagram", CakeBrush.catalog),
    ORDER("Order", "📦", "Order", "Group photos by customer order"),
    CATEGORY("Category", "🗂", "Category", "Move to another category"),
    INCLUDE("Include", "✅", "Include", "Upload these"),
    EXCLUDE("Exclude", "🚫", "Exclude", "Don't upload these");

    companion object {
        /** Shown as big cards on the Create screen. */
        val creative = listOf(STUDIO, REEL, COLLAGE, FILTER, BRAND, CROP, SHARE, CATALOG)

        /** Order in the selection panel: quick sorting first, then creative tools. */
        val panel = listOf(INCLUDE, EXCLUDE, ORDER, CATEGORY, STUDIO, REEL, COLLAGE, FILTER, BRAND, WHITE_BG, CROP, CATALOG, SHARE)

        /** Actions that make no sense on things the app created (they aren't uploads). */
        val notForCreations = setOf(INCLUDE, EXCLUDE, ORDER, CATEGORY, CATALOG)
    }
}

/**
 * Starts [action] on the current selection: quick ones run straight away, the rest open
 * their dialog through [openDialog].
 */
fun runAction(action: EditAction, viewModel: MainViewModel, openDialog: (EditAction) -> Unit) {
    when (action) {
        EditAction.INCLUDE -> viewModel.includeSelected(true)
        EditAction.EXCLUDE -> viewModel.includeSelected(false)
        EditAction.WHITE_BG -> viewModel.whiteBackgroundForSelected()
        EditAction.ORDER -> { viewModel.loadOrderTags(); openDialog(action) }
        else -> openDialog(action)
    }
}

/** Bottom panel shown while photos are selected. */
@Composable
fun SelectionPanel(
    count: Int,
    showCategory: Boolean,
    createdMode: Boolean,
    onAction: (EditAction) -> Unit,
    onSelectAll: () -> Unit,
    onDone: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        tonalElevation = 3.dp,
        shadowElevation = 12.dp,
    ) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("$count selected", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).padding(start = 8.dp))
                TextButton(onClick = onSelectAll) { Text("All") }
                TextButton(onClick = onDone) { Text("Done") }
            }
            val actions = EditAction.panel.filter {
                (showCategory || it != EditAction.CATEGORY) && !(createdMode && it in EditAction.notForCreations)
            }
            actions.chunked(4).forEach { row ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { action -> ActionTile(action, Modifier.weight(1f)) { onAction(action) } }
                    repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun ActionTile(action: EditAction, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(action.emoji, fontSize = 20.sp)
        Text(action.label, style = MaterialTheme.typography.labelMedium, maxLines = 1, textAlign = TextAlign.Center)
    }
}

/** The dialog for [action] on the current selection. */
@Composable
fun ActionDialog(
    action: EditAction,
    state: UiState,
    categories: List<Category>,
    viewModel: MainViewModel,
    onDismiss: () -> Unit,
) {
    val firstPhoto = state.photos.firstOrNull { it.mediaId in state.selected && !it.isVideo }?.uri
    when (action) {
        EditAction.ORDER -> OrderDialog(
            suggestions = state.orderTags,
            onDismiss = onDismiss,
            onSave = { viewModel.tagSelected(it); onDismiss() },
            onRemove = { viewModel.tagSelected(null); onDismiss() },
        )
        EditAction.CATALOG -> {
            var price by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("Export catalog") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "Creates a product list (CSV) for Meta Commerce Manager, which feeds your Facebook/Instagram " +
                                "shop and WhatsApp Business catalog. Only photos already uploaded to Drive can be included, " +
                                "and their Drive copies become viewable by link so Meta can show them.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        OutlinedTextField(
                            value = price, onValueChange = { price = it }, singleLine = true,
                            label = { Text("Price for all, e.g. 1200.00 INR (optional)") }, modifier = Modifier.fillMaxWidth(),
                        )
                    }
                },
                confirmButton = { TextButton(onClick = { viewModel.exportCatalog(price); onDismiss() }) { Text("Export") } },
                dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
            )
        }
        EditAction.REEL -> ReelDialog(
            count = state.selected.size,
            onDismiss = onDismiss,
            onMake = { music, track, options -> viewModel.makeReel(music, track, options); onDismiss() },
        )
        EditAction.FILTER -> FilterDialog(
            count = state.photos.count { it.mediaId in state.selected && !it.isVideo },
            sample = firstPhoto,
            onDismiss = onDismiss,
            onSave = { viewModel.filterSelected(it); onDismiss() },
        )
        EditAction.COLLAGE -> {
            val photos = remember { viewModel.selectedPhotos() }
            val kit = remember { viewModel.brandKit() }
            CollageDialog(
                photos = photos,
                brandKit = kit,
                onDismiss = onDismiss,
                onMake = { template, shape, backdrop, spacing, rounded, brand, price ->
                    viewModel.makeCollage(template, shape, backdrop, spacing, rounded, brand, price); onDismiss()
                },
            )
        }
        EditAction.SHARE -> {
            val kitReady = remember { !viewModel.brandKit().isEmpty }
            var caption by remember { mutableStateOf(viewModel.captionForSelection()) }
            var brand by remember { mutableStateOf(kitReady) }
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("Share ${state.selected.size} item(s)") },
                text = {
                    Column(
                        Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = caption, onValueChange = { caption = it },
                            label = { Text("Caption (copied for you)") }, modifier = Modifier.fillMaxWidth(), minLines = 3,
                        )
                        if (kitReady) SwitchRow("Add my branding to photos", brand) { brand = it }
                        Text(
                            "Opens the app with your photos attached; you press Post there. " +
                                "Instagram doesn't take captions from other apps, so paste the copied caption.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Sharer.Target.entries.forEach { target ->
                            OutlinedButton(
                                onClick = { viewModel.shareSelected(target, caption, brand); onDismiss() },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(target.label) }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
            )
        }
        EditAction.BRAND -> {
            var price by remember { mutableStateOf("") }
            val kit = remember { viewModel.brandKit() }
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("Add branding") },
                text = {
                    Column(
                        Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        EditPreview(firstPhoto, price) { kit.apply(it, price) }
                        Text(
                            "Saves copies with your logo, business name and colour filter (set up in Settings → Brand kit). " +
                                "Originals stay as they are.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        OutlinedTextField(
                            value = price, onValueChange = { price = it }, singleLine = true,
                            label = { Text("Price or text (optional), e.g. ₹1,200") }, modifier = Modifier.fillMaxWidth(),
                        )
                    }
                },
                confirmButton = { TextButton(onClick = { viewModel.brandSelected(price); onDismiss() }) { Text("Save copies") } },
                dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
            )
        }
        EditAction.CROP -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Crop for social media") },
            text = {
                Column {
                    Text(
                        "Saves cropped copies centred on the cake. Originals stay as they are.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    PhotoEditor.Shape.entries.forEach { shape ->
                        TextButton(onClick = { viewModel.cropSelected(shape); onDismiss() }) { Text(shape.label) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        )
        EditAction.CATEGORY -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Move to category") },
            text = {
                Column {
                    categories.forEach { c ->
                        TextButton(onClick = { viewModel.moveSelectedTo(c.id); onDismiss() }) { Text(c.name) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        )
        EditAction.INCLUDE, EditAction.EXCLUDE, EditAction.WHITE_BG, EditAction.STUDIO -> LaunchedEffect(Unit) { onDismiss() }
    }
}

@Composable
fun OrderDialog(
    suggestions: List<String>,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    onRemove: () -> Unit,
) {
    var tag by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Tag as order") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "These photos will upload to <category>/Orders/<name>/ in Drive. " +
                        "Photos already uploaded stay where they are.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = tag, onValueChange = { tag = it }, singleLine = true,
                    label = { Text("Order name, e.g. Order 123 - Priya") }, modifier = Modifier.fillMaxWidth(),
                )
                if (suggestions.isNotEmpty()) {
                    Text("Recent orders:", style = MaterialTheme.typography.labelSmall)
                    FlowRowCompat {
                        suggestions.take(15).forEach { s ->
                            FilterChip(selected = tag == s, onClick = { tag = s }, label = { Text(s) })
                        }
                    }
                }
                TextButton(onClick = onRemove) { Text("Remove order tag") }
            }
        },
        confirmButton = { TextButton(enabled = tag.isNotBlank(), onClick = { onSave(tag) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** FlowRow with the usual chip spacing. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FlowRowCompat(content: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) { content() }
}
