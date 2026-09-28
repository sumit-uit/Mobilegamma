package com.mobilegamma.cakesync.ui

import android.Manifest
import android.os.Build
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.platform.LocalContext
import java.security.MessageDigest
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
import coil3.compose.AsyncImage
import com.mobilegamma.cakesync.data.Photo

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
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

    Scaffold(topBar = { TopAppBar(title = { Text("CakeSync") }) }) { padding ->
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
                                add(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                add(Manifest.permission.READ_EXTERNAL_STORAGE)
                            }
                            if (Build.VERSION.SDK_INT >= 34) add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
                        }
                        permissionLauncher.launch(perms.toTypedArray())
                    },
                    onConnectDrive = viewModel::connectDrive,
                    onScan = viewModel::scanNow,
                    onSync = viewModel::syncNow,
                )
            }
            state.settings?.let { s -> fullWidth { SettingsCard(s, viewModel) } }
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
                    "Tap a photo to include or exclude it. ✓ = already in Drive.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            items(state.photos, key = { it.mediaId }) { photo ->
                PhotoTile(photo, onClick = { viewModel.toggle(photo) })
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.grid.LazyGridScope.fullWidth(content: @Composable () -> Unit) =
    item(span = { GridItemSpan(maxLineSpan) }) { content() }

@Composable
private fun SetupCard(
    state: UiState,
    onGrantPhotos: () -> Unit,
    onConnectDrive: () -> Unit,
    onScan: () -> Unit,
    onSync: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusRow("Photo access", state.hasPhotoPermission, "Allow", onGrantPhotos)
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
private fun SettingsCard(s: SettingsState, viewModel: MainViewModel) {
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
                    "Looking for: ${s.targetLabels} · Drive folder: ${s.driveFolderName} · " +
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
private fun PhotoTile(photo: Photo, onClick: () -> Unit) {
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
                    .alpha(if (photo.included) 1f else 0.35f),
            )
            val badge = when {
                photo.uploaded -> "✓"
                !photo.included -> "✕"
                photo.override == true -> "＋"
                else -> null
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
            photo.labels.ifEmpty { "no labels" },
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
