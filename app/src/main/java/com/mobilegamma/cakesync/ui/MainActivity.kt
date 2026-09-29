package com.mobilegamma.cakesync.ui

import android.Manifest
import android.os.Build
import android.content.Context
import android.content.pm.PackageManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.platform.LocalContext
import java.security.MessageDigest
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import com.mobilegamma.cakesync.scan.PhotoScanner
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.compose.setSingletonImageLoaderFactory
import coil3.video.VideoFrameDecoder
import com.mobilegamma.cakesync.data.Photo

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            setSingletonImageLoaderFactory { ctx ->
                ImageLoader.Builder(ctx).components { add(VideoFrameDecoder.Factory()) }.build()
            }
            val colors = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
            MaterialTheme(colorScheme = colors) { MainScreen(viewModel) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Re-check permissions etc. whenever the user returns to the app.
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { viewModel.refresh() }

    val consentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result -> viewModel.onConsentResult(result.data) }

    LaunchedEffect(state.consentIntent) {
        state.consentIntent?.let {
            consentLauncher.launch(IntentSenderRequest.Builder(it.intentSender).build())
            viewModel.onConsentLaunched()
        }
    }

    // Ask for video access once per app start if videos are on and it's missing. Updating
    // from a photo-only version keeps photo access but never grants videos by itself.
    val wantVideos = state.settings?.includeVideos == true
    var askedForVideos by rememberSaveable { mutableStateOf(false) }
    val requestVideos = {
        askedForVideos = true
        permissionLauncher.launch(videoPermissions().toTypedArray())
    }
    LaunchedEffect(state.hasPhotoPermission, state.hasVideoPermission, wantVideos) {
        if (state.hasPhotoPermission && wantVideos && !state.hasVideoPermission && !askedForVideos) requestVideos()
    }

    val context = LocalContext.current
    val openAppSettings = {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
        )
    }
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
    }
    Scaffold(
        topBar = {
            TopAppBar(title = {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("CakeSync")
                    version?.let {
                        Text(
                            "  v$it",
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(bottom = 3.dp),
                        )
                    }
                }
            })
        },
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 110.dp),
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            fullWidth {
                SetupCard(
                    state = state,
                    onGrantPhotos = {
                        val perms = buildList {
                            if (Build.VERSION.SDK_INT >= 33) {
                                add(Manifest.permission.READ_MEDIA_IMAGES)
                                add(Manifest.permission.READ_MEDIA_VIDEO)
                                add(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                add(Manifest.permission.READ_EXTERNAL_STORAGE)
                            }
                            if (Build.VERSION.SDK_INT >= 34) add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
                        }
                        permissionLauncher.launch(perms.toTypedArray())
                    },
                    onGrantVideos = requestVideos,
                    onOpenSettings = openAppSettings,
                    onConnectDrive = viewModel::connectDrive,
                    onScan = viewModel::scanNow,
                    onSync = viewModel::syncNow,
                )
            }
            state.settings?.let { s -> fullWidth { SettingsCard(s, state.folders, viewModel) } }
            fullWidth {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                    FilterChip(
                        selected = !state.showAll,
                        onClick = { viewModel.setShowAll(false) },
                        label = { Text(if (state.showAll) "Matches" else "Matches (${state.photos.size})") },
                    )
                    Spacer(Modifier.width(8.dp))
                    FilterChip(
                        selected = state.showAll,
                        onClick = { viewModel.setShowAll(true) },
                        label = { Text("All scanned") },
                    )
                }
            }
            fullWidth {
                Text(
                    "Tap a photo to include or exclude it. ✓ = already in Drive, 👤 = skipped (person in photo).",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            items(state.photos, key = { it.mediaId }) { photo ->
                PhotoTile(photo, state.settings?.excludePeople ?: true, onClick = { viewModel.toggle(photo) })
            }
        }
    }
}

private fun videoPermissions(): List<String> = buildList {
    if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.READ_MEDIA_VIDEO)
    else add(Manifest.permission.READ_EXTERNAL_STORAGE)
    if (Build.VERSION.SDK_INT >= 34) add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
}

private fun androidx.compose.foundation.lazy.grid.LazyGridScope.fullWidth(content: @Composable () -> Unit) =
    item(span = { GridItemSpan(maxLineSpan) }) { content() }

@Composable
private fun SetupCard(
    state: UiState,
    onGrantPhotos: () -> Unit,
    onGrantVideos: () -> Unit,
    onOpenSettings: () -> Unit,
    onConnectDrive: () -> Unit,
    onScan: () -> Unit,
    onSync: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusRow("Photo access", state.hasPhotoPermission, "Allow", onGrantPhotos)
            if (state.settings?.includeVideos == true) {
                StatusRow("Video access", state.hasVideoPermission, "Allow", onGrantVideos)
                if (!state.hasVideoPermission) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "No prompt? Allow \"Photos and videos\" for CakeSync in Android settings.",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onOpenSettings) { Text("Open settings") }
                    }
                }
            }
            StatusRow("Google Drive", state.driveConnected, "Connect", onConnectDrive)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onScan, enabled = state.hasPhotoPermission && state.scanProgress == null) {
                    Text("Scan now")
                }
                Button(
                    onClick = onSync,
                    enabled = state.hasPhotoPermission && state.driveConnected && !state.syncRunning,
                ) {
                    Text(if (state.syncRunning) "Uploading…" else "Upload now (${state.pendingCount})")
                }
            }
            state.scanProgress?.let { (done, total) ->
                if (total > 0) LinearProgressIndicator(progress = { done / total.toFloat() }, Modifier.fillMaxWidth())
                else LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (!state.driveConnected) {
                // What Google matches against the Android OAuth client in Cloud Console.
                val context = LocalContext.current
                val identity = remember { appIdentity(context) }
                SelectionContainer {
                    Text(
                        "For Google Cloud → Android OAuth client:\n$identity",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

/** Package name, version and signing-certificate SHA-1 of the installed app. */
private fun appIdentity(context: Context): String = try {
    val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
    val signers = info.signingInfo?.apkContentsSigners.orEmpty()
    val sha1 = signers.firstOrNull()?.let { sig ->
        MessageDigest.getInstance("SHA-1").digest(sig.toByteArray())
            .joinToString(":") { "%02X".format(it) }
    } ?: "unknown"
    "Package: ${context.packageName}\nSHA-1: $sha1\nVersion: ${info.versionName}"
} catch (e: Exception) {
    "Could not read app signature: ${e.message}"
}

@Composable
private fun StatusRow(label: String, ok: Boolean, action: String, onAction: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("${if (ok) "✅" else "⚠️"}  $label", Modifier.weight(1f))
        if (!ok) TextButton(onClick = onAction) { Text(action) }
    }
}

@Composable
private fun SettingsCard(s: SettingsState, folders: List<PhotoScanner.Folder>?, viewModel: MainViewModel) {
    var expanded by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth().clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Settings", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(if (expanded) "▲" else "▼")
            }
            if (!expanded) {
                Text(
                    "Looking for: ${s.targetLabels} · Scanning: ${folderSummary(s.scanFolders)}, " +
                        "${scanWindowLabel(s.scanDays)} · Drive folder: ${s.driveFolderName} · " +
                        if (s.dailySyncEnabled) "daily at %02d:00".format(s.uploadHour) else "daily upload off",
                    style = MaterialTheme.typography.bodySmall,
                )
                return@Column
            }

            var labels by remember(s.targetLabels) { mutableStateOf(s.targetLabels) }
            OutlinedTextField(
                value = labels,
                onValueChange = { labels = it },
                label = { Text("Labels to match (comma-separated)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            var folder by remember(s.driveFolderName) { mutableStateOf(s.driveFolderName) }
            OutlinedTextField(
                value = folder,
                onValueChange = { folder = it },
                label = { Text("Drive folder") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (labels != s.targetLabels || folder != s.driveFolderName) {
                Button(
                    onClick = {
                        viewModel.updateSettings {
                            targetLabels = labels
                            if (folder.isNotBlank() && folder != driveFolderName) driveFolderName = folder.trim()
                        }
                    },
                ) { Text("Save") }
            }

            var threshold by remember(s.threshold) { mutableFloatStateOf(s.threshold) }
            Text("Minimum confidence: ${(threshold * 100).toInt()}%")
            Slider(
                value = threshold,
                onValueChange = { threshold = it },
                onValueChangeFinished = { viewModel.updateSettings { this.threshold = threshold } },
                valueRange = 0.3f..0.95f,
            )

            SwitchRow("Upload automatically every day", s.dailySyncEnabled) {
                viewModel.updateSettings { dailySyncEnabled = it }
            }
            var hour by remember(s.uploadHour) { mutableFloatStateOf(s.uploadHour.toFloat()) }
            Text("Daily upload time: %02d:00".format(hour.toInt()))
            Slider(
                value = hour,
                onValueChange = { hour = it },
                onValueChangeFinished = { viewModel.updateSettings { uploadHour = hour.toInt() } },
                valueRange = 0f..23f,
                steps = 22,
            )
            SwitchRow("Wi-Fi only", s.wifiOnly) { viewModel.updateSettings { wifiOnly = it } }
            SwitchRow("Only upload photos I've approved", s.requireApproval) {
                viewModel.updateSettings { requireApproval = it }
            }
            SwitchRow("Skip photos with people (face detection)", s.excludePeople) {
                viewModel.updateSettings { excludePeople = it }
            }
            SwitchRow("Include videos", s.includeVideos) {
                viewModel.updateSettings { includeVideos = it }
            }

            Text("Photos to scan", style = MaterialTheme.typography.titleSmall)
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SCAN_WINDOWS.forEach { days ->
                    FilterChip(
                        selected = s.scanDays == days,
                        onClick = { viewModel.updateSettings { scanDays = days } },
                        label = { Text(scanWindowLabel(days)) },
                    )
                }
            }
            var showPicker by remember { mutableStateOf(false) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Folders: ${folderSummary(s.scanFolders)}", Modifier.weight(1f))
                TextButton(onClick = { showPicker = true; viewModel.loadFolders() }) { Text("Choose") }
            }
            if (showPicker) {
                FolderPickerDialog(
                    folders = folders,
                    selected = s.scanFolders,
                    onDismiss = { showPicker = false },
                    onSave = { chosen ->
                        viewModel.updateSettings { scanFolders = chosen }
                        showPicker = false
                    },
                )
            }
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun PhotoTile(photo: Photo, excludePeople: Boolean, onClick: () -> Unit) {
    val included = photo.included(excludePeople)
    Column(Modifier.clickable(onClick = onClick)) {
        Box {
            AsyncImage(
                model = photo.uri,
                contentDescription = photo.displayName,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .alpha(if (included) 1f else 0.35f),
            )
            val badge = when {
                photo.uploaded -> "✓"
                !included && photo.override == null && photo.hasPeople -> "👤"
                !included -> "✕"
                photo.override == true -> "＋"
                else -> null
            }
            if (photo.isVideo) {
                Text(
                    "▶ " + (photo.durationMs?.let { formatDuration(it) } ?: "video"),
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(4.dp)
                        .background(Color(0xAA000000), RoundedCornerShape(4.dp))
                        .padding(horizontal = 4.dp),
                )
            }
            badge?.let {
                Text(
                    it,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .background(Color(0xAA000000), RoundedCornerShape(50))
                        .padding(horizontal = 6.dp),
                )
            }
        }
        Text(
            (if (photo.hasPeople) "👤 ${photo.faces} · " else "") + photo.labels.ifEmpty { "no labels" },
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private val SCAN_WINDOWS = listOf(7, 30, 365, 0)

private fun scanWindowLabel(days: Int) = when (days) {
    0 -> "All photos"
    365 -> "Last year"
    else -> "Last $days days"
}

private fun folderSummary(folders: Set<String>) = when {
    folders.isEmpty() -> "all folders"
    folders.size <= 2 -> folders.joinToString { folderName(it) }
    else -> "${folders.size} folders"
}

/** "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/" -> "WhatsApp Images" */
private fun folderName(path: String) = path.trimEnd('/').substringAfterLast('/').ifEmpty { path }

@Composable
private fun FolderPickerDialog(
    folders: List<PhotoScanner.Folder>?,
    selected: Set<String>,
    onDismiss: () -> Unit,
    onSave: (Set<String>) -> Unit,
) {
    var chosen by remember { mutableStateOf(selected) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Folders to scan") },
        text = {
            Column {
                Text(
                    "Nothing ticked = scan every folder.",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (folders == null) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                } else if (folders.isEmpty()) {
                    Text("No photo folders found.", Modifier.padding(top = 8.dp))
                } else {
                    LazyColumn(Modifier.heightIn(max = 400.dp).padding(top = 8.dp)) {
                        items(folders, key = { it.path }) { folder ->
                            val checked = folder.path in chosen
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { chosen = if (checked) chosen - folder.path else chosen + folder.path },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(checked = checked, onCheckedChange = null)
                                Column(Modifier.padding(start = 8.dp).weight(1f)) {
                                    Text("${folderName(folder.path)} (${folder.count})")
                                    Text(
                                        folder.path,
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(chosen) }) { Text("Save") } },
        dismissButton = {
            Row {
                TextButton(onClick = { chosen = emptySet() }) { Text("Clear") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

private fun formatDuration(ms: Long): String {
    val totalSec = ms / 1000
    return "%d:%02d".format(totalSec / 60, totalSec % 60)
}
