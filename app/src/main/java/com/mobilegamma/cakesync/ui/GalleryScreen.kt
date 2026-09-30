package com.mobilegamma.cakesync.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import com.mobilegamma.cakesync.data.Photo

/** The photo grid: matches, videos, everything scanned and what the app has made. */
@Composable
fun GalleryScreen(
    state: UiState,
    viewModel: MainViewModel,
    pendingAction: EditAction?,
    onCancelPending: () -> Unit,
    onOpenCreation: (Photo) -> Unit,
) {
    val categories = state.settings?.categories.orEmpty()
    val created = state.tab == GridTab.CREATED
    val selecting = state.selected.isNotEmpty() || pendingAction != null
    val haptics = LocalHapticFeedback.current
    var viewing by remember { mutableStateOf<Long?>(null) }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 108.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        span { ScreenTitle("Gallery", "Tap a photo to see it · long-press to select") }
        if (pendingAction != null) span { PickBanner(pendingAction, onCancelPending) }
        span {
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(
                    GridTab.MATCHES to "Matches",
                    GridTab.VIDEOS to "Videos",
                    GridTab.ALL to "All scanned",
                    GridTab.CREATED to "✨ Created",
                ).forEach { (tab, name) ->
                    val selected = state.tab == tab
                    FilterChip(
                        selected = selected,
                        onClick = { viewModel.setTab(tab) },
                        label = { Text(if (selected && tab != GridTab.ALL) "$name (${state.photos.size})" else name) },
                        shape = CircleShape,
                    )
                }
            }
        }
        if (categories.size > 1 && !created) span {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AssistChip(
                    onClick = { viewModel.setCategoryFilter(null) },
                    label = { Text("All categories") },
                    colors = chipColors(state.categoryFilter == null),
                )
                categories.forEach { category ->
                    AssistChip(
                        onClick = { viewModel.setCategoryFilter(category.id) },
                        label = { Text(category.name) },
                        colors = chipColors(state.categoryFilter == category.id),
                    )
                }
            }
        }
        if (state.photos.isEmpty()) span { EmptyGallery(state.tab) }
        else if (!created && !selecting) span {
            Text(
                "✓ in Drive · 👤 person, skipped · ≈ near-duplicate · ✕ excluded",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items(state.photos, key = { (if (it.isVideo) "v" else "p") + it.mediaId }) { photo ->
            if (created) {
                PhotoTile(
                    photo = photo, excludePeople = false, skipDuplicates = false, categoryName = null,
                    selected = false, created = true,
                    onClick = { onOpenCreation(photo) }, onLongClick = { onOpenCreation(photo) },
                )
            } else {
                val categoryName = if (categories.size > 1) categories.firstOrNull { it.id == photo.category }?.name else null
                PhotoTile(
                    photo = photo,
                    excludePeople = state.settings?.excludePeople ?: true,
                    skipDuplicates = state.settings?.skipDuplicates ?: true,
                    categoryName = categoryName,
                    selected = photo.mediaId in state.selected,
                    onClick = { if (selecting) viewModel.toggleSelected(photo) else viewing = photo.mediaId },
                    onLongClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        viewModel.toggleSelected(photo)
                    },
                )
            }
        }
    }

    val current = viewing?.let { id -> state.photos.firstOrNull { it.mediaId == id } }
    if (current != null) {
        PhotoViewer(
            photo = current,
            state = state,
            onClose = { viewing = null },
            onToggle = { viewModel.toggle(current) },
            onSelect = { viewModel.selectOnly(current); viewing = null },
            onOpen = { viewModel.openCreated(current) },
        )
    }
}

private fun LazyGridScope.span(content: @Composable () -> Unit) =
    item(span = { GridItemSpan(maxLineSpan) }) { content() }

@Composable
private fun chipColors(selected: Boolean) = AssistChipDefaults.assistChipColors(
    containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
)

@Composable
private fun PickBanner(action: EditAction, onCancel: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        shape = MaterialTheme.shapes.large,
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(action.emoji, fontSize = 28.sp)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text("Pick photos for: ${action.title}", style = MaterialTheme.typography.titleMedium)
                Text("Tap photos to select them, then press Continue.", style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
    }
}

@Composable
private fun EmptyGallery(tab: GridTab) {
    val (emoji, title, text) = when (tab) {
        GridTab.CREATED -> Triple(
            "✨", "Nothing made yet",
            "Go to Create to make a reel, collage or branded post. What you make shows up here, and in your " +
                "Gallery app under Pictures/CakeSync and Movies/CakeSync.",
        )
        GridTab.VIDEOS -> Triple(
            "🎥", "No cake videos yet",
            "Check Video access on Home, then look under All scanned: videos show ▶ and can be included from there.",
        )
        else -> Triple("🎂", "No cake photos yet", "Press Scan now on Home to look through your photos.")
    }
    Column(
        Modifier.fillMaxWidth().padding(vertical = 40.dp, horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier.size(88.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) { Text(emoji, fontSize = 40.sp) }
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(
            text, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

private val GREYSCALE = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })

@Composable
@OptIn(ExperimentalFoundationApi::class)
fun PhotoTile(
    photo: Photo,
    excludePeople: Boolean,
    skipDuplicates: Boolean,
    categoryName: String?,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    /** Something the app made (Created tab): no include/exclude badges. */
    created: Boolean = false,
) {
    val included = created || photo.included(excludePeople, skipDuplicates)
    val scale by animateFloatAsState(if (selected) 0.9f else 1f, label = "select")
    Column(Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .scale(scale)
                .clip(RoundedCornerShape(16.dp))
                .then(
                    if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(16.dp))
                    else Modifier
                )
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
        ) {
            AsyncImage(
                model = photo.uri,
                contentDescription = photo.displayName,
                contentScale = ContentScale.Crop,
                colorFilter = if (included) null else GREYSCALE,
                modifier = Modifier.fillMaxSize().alpha(if (included) 1f else 0.5f),
            )
            val badge = when {
                created -> null
                photo.uploaded -> "✓"
                !included && photo.override == null && photo.hasPeople -> "👤"
                !included && photo.override == null && photo.duplicate -> "≈"
                !included -> "✕"
                photo.override == true -> "＋"
                else -> null
            }
            if (selected) {
                Box(
                    Modifier.align(Alignment.TopStart).padding(6.dp).size(24.dp).clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) { Text("✓", color = MaterialTheme.colorScheme.onPrimary, fontSize = 14.sp) }
            }
            badge?.let {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(6.dp).size(24.dp).clip(CircleShape)
                        .background(Color(0xAA000000)),
                    contentAlignment = Alignment.Center,
                ) { Text(it, color = Color.White, fontSize = 12.sp) }
            }
            if (photo.isVideo) {
                Text(
                    "▶ " + (photo.durationMs?.let { formatDuration(it) } ?: "video"),
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(6.dp)
                        .background(Color(0xAA000000), RoundedCornerShape(6.dp))
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
        }
        Text(
            (photo.orderTag?.let { "📦 $it · " } ?: "") + (categoryName?.let { "$it · " } ?: "") +
                (if (photo.hasPeople) "👤 ${photo.faces} · " else "") + photo.labels.ifEmpty { "no labels" },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 4.dp, top = 3.dp, end = 4.dp),
        )
    }
}

/** Full-screen view of one photo with its status and quick actions. */
@Composable
private fun PhotoViewer(
    photo: Photo,
    state: UiState,
    onClose: () -> Unit,
    onToggle: () -> Unit,
    onSelect: () -> Unit,
    onOpen: () -> Unit,
) {
    val excludePeople = state.settings?.excludePeople ?: true
    val skipDuplicates = state.settings?.skipDuplicates ?: true
    val included = photo.included(excludePeople, skipDuplicates)
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AsyncImage(
                model = photo.uri,
                contentDescription = photo.displayName,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(bottom = 200.dp),
            )
            IconButton(
                onClick = onClose,
                modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(8.dp),
            ) { Text("✕", color = Color.White, fontSize = 22.sp) }
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(listOf(Color.Transparent, Color(0xEE000000), Color.Black)),
                    )
                    .navigationBarsPadding()
                    .padding(20.dp)
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val status = when {
                    photo.uploaded -> "✓ Already in your Drive"
                    included -> "☁️ Will be uploaded"
                    photo.override == null && photo.hasPeople -> "👤 Skipped: a person is in this photo"
                    photo.override == null && photo.duplicate -> "≈ Skipped: a sharper copy is kept"
                    else -> "✕ Excluded from uploads"
                }
                Text(status, color = Color.White, style = MaterialTheme.typography.titleMedium)
                Text(
                    listOfNotNull(photo.orderTag?.let { "📦 $it" }, photo.labels.ifBlank { null }).joinToString(" · "),
                    color = Color(0xCCFFFFFF),
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!photo.uploaded) {
                        Button(onClick = onToggle) { Text(if (included) "Exclude" else "Include") }
                    }
                    FilledTonalButton(onClick = onSelect) { Text("Select") }
                    OutlinedButton(onClick = onOpen) {
                        Text(if (photo.isVideo) "▶ Play" else "Open", color = Color.White)
                    }
                }
            }
        }
    }
}

internal fun formatDuration(ms: Long): String {
    val totalSec = ms / 1000
    return "%d:%02d".format(totalSec / 60, totalSec % 60)
}
