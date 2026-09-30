package com.mobilegamma.cakesync.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.mobilegamma.cakesync.data.Photo
import com.mobilegamma.cakesync.ui.theme.CakeBrush
import java.util.Calendar

/** Start page: greeting, numbers, setup status, scan/upload and shortcuts. */
@Composable
fun HomeScreen(
    state: UiState,
    businessName: String,
    onGrantPhotos: () -> Unit,
    onGrantVideos: () -> Unit,
    onOpenSettings: () -> Unit,
    onConnectDrive: () -> Unit,
    onScan: () -> Unit,
    onSync: () -> Unit,
    onViewResults: () -> Unit,
    onSeeAll: () -> Unit,
    onOpenPhoto: (Photo) -> Unit,
    onCreate: (EditAction) -> Unit,
    onOpenMenu: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Hero(state, businessName)

        val videosWanted = state.settings?.includeVideos == true
        val setupDone = state.hasPhotoPermission && (!videosWanted || state.hasVideoPermission) && state.driveConnected
        if (!setupDone) {
            Section("🧁", "Get set up") {
                SetupRow("Photo access", "So CakeSync can find your cake photos", state.hasPhotoPermission, "Allow", onGrantPhotos)
                if (videosWanted) {
                    SetupRow("Video access", "To find cake videos too", state.hasVideoPermission, "Allow", onGrantVideos)
                    if (!state.hasVideoPermission) {
                        TextButton(onClick = onOpenSettings) { Text("No prompt? Open Android settings") }
                    }
                }
                SetupRow("Google Drive", "Where your photos are uploaded", state.driveConnected, "Connect", onConnectDrive)
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = onScan,
                enabled = state.hasPhotoPermission && state.scanProgress == null,
                modifier = Modifier.weight(1f).height(56.dp),
                shape = MaterialTheme.shapes.medium,
            ) { Text(if (state.scanProgress != null) "Scanning…" else "🔍  Scan now") }
            FilledTonalButton(
                onClick = onSync,
                enabled = state.hasPhotoPermission && state.driveConnected && !state.syncRunning,
                modifier = Modifier.weight(1f).height(56.dp),
                shape = MaterialTheme.shapes.medium,
            ) { Text(if (state.syncRunning) "Uploading…" else "☁️  Upload now (${state.pendingCount})") }
        }
        state.scanProgress?.let { (done, total) ->
            if (total > 0) LinearProgressIndicator(progress = { done / total.toFloat() }, Modifier.fillMaxWidth().clip(CircleShape))
            else LinearProgressIndicator(Modifier.fillMaxWidth().clip(CircleShape))
        }
        state.message?.let { message ->
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                shape = MaterialTheme.shapes.medium,
            ) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(message, style = MaterialTheme.typography.bodyMedium)
                    AnimatedVisibility(state.resultsReady) {
                        Button(onClick = onViewResults) { Text("👀 View results") }
                    }
                }
            }
        }

        if (state.menuNewCount > 0) {
            Card(
                onClick = onOpenMenu,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                shape = MaterialTheme.shapes.large,
            ) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("📋", fontSize = 28.sp)
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text("${state.menuNewCount} new cake design(s)", style = MaterialTheme.typography.titleMedium)
                        Text("Swipe to add them to your menu", style = MaterialTheme.typography.bodySmall)
                    }
                    Text("›", fontSize = 28.sp)
                }
            }
        }

        SectionHeader("Create something", null) {}
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(EditAction.creative.take(5)) { action ->
                Column(
                    Modifier
                        .width(96.dp)
                        .clip(MaterialTheme.shapes.medium)
                        .clickable { onCreate(action) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier.size(72.dp).clip(RoundedCornerShape(22.dp)).background(action.brush ?: CakeBrush.hero),
                        contentAlignment = Alignment.Center,
                    ) { Text(action.emoji, fontSize = 30.sp) }
                    Text(action.title, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }

        if (state.recent.isNotEmpty()) {
            SectionHeader("Latest cakes", "See all", onSeeAll)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(state.recent, key = { it.mediaId }) { photo ->
                    Box(
                        Modifier.size(width = 120.dp, height = 150.dp).clip(MaterialTheme.shapes.medium)
                            .clickable { onOpenPhoto(photo) },
                    ) {
                        AsyncImage(
                            model = photo.uri, contentDescription = photo.displayName,
                            contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                        )
                        if (photo.isVideo) {
                            Text(
                                "▶", color = Color.White,
                                modifier = Modifier.align(Alignment.Center).clip(CircleShape)
                                    .background(Color(0x88000000)).padding(horizontal = 12.dp, vertical = 6.dp),
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun Hero(state: UiState, businessName: String) {
    val greeting = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
        in 5..11 -> "Good morning"
        in 12..16 -> "Good afternoon"
        else -> "Good evening"
    }
    Box(
        Modifier
            .padding(top = 12.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(32.dp))
            .background(CakeBrush.hero)
            .padding(20.dp)
    ) {
        Text("🎂", fontSize = 64.sp, modifier = Modifier.align(Alignment.TopEnd))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("CakeSync", style = MaterialTheme.typography.labelLarge, color = Color(0xFF5A1A2E))
            Text(
                "$greeting,\n${businessName.ifBlank { "baker" }} 👋",
                style = MaterialTheme.typography.headlineMedium,
                color = Color(0xFF3E0F1F),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "Your cake photos, sorted and ready to post.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFF5A1A2E),
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Stat(state.matchCount, "Cake photos", Modifier.weight(1f))
                Stat(state.pendingCount, "To upload", Modifier.weight(1f))
                Stat(state.createdCount, "Created", Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun Stat(value: Int, label: String, modifier: Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(18.dp)).background(Color(0x66FFFFFF)).padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("$value", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color(0xFF3E0F1F))
        Text(label, style = MaterialTheme.typography.labelMedium, color = Color(0xFF5A1A2E))
    }
}

@Composable
private fun SetupRow(title: String, subtitle: String, done: Boolean, action: String, onAction: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(32.dp).clip(CircleShape).background(
                if (done) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.errorContainer
            ),
            contentAlignment = Alignment.Center,
        ) { Text(if (done) "✓" else "!", fontWeight = FontWeight.Bold) }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!done) Button(onClick = onAction) { Text(action) }
    }
}

@Composable
private fun SectionHeader(title: String, action: String?, onAction: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        if (action != null) TextButton(onClick = onAction) { Text(action) }
    }
}
