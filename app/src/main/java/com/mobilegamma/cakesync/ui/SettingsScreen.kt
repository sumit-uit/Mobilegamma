package com.mobilegamma.cakesync.ui

import android.accounts.AccountManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.google.android.gms.common.AccountPicker
import com.mobilegamma.cakesync.data.Categories
import com.mobilegamma.cakesync.data.Category
import com.mobilegamma.cakesync.edit.Backdrops
import com.mobilegamma.cakesync.edit.ColorFilterPreset
import com.mobilegamma.cakesync.edit.LABEL_COLOURS
import com.mobilegamma.cakesync.edit.LabelFont
import com.mobilegamma.cakesync.edit.LabelStyle
import com.mobilegamma.cakesync.edit.LogoPosition
import com.mobilegamma.cakesync.scan.PhotoScanner
import com.mobilegamma.cakesync.share.Captions
import java.security.MessageDigest

/** All settings, grouped into cards. */
@Composable
fun SettingsScreen(
    state: UiState,
    viewModel: MainViewModel,
    onConnectDrive: () -> Unit,
    onShowIntro: () -> Unit,
) {
    val s = state.settings ?: return
    val context = LocalContext.current
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ScreenTitle("Settings", "Make CakeSync work your way")

        Section("🎂", "Categories") {
            var editing by remember { mutableStateOf<Category?>(null) }
            s.categories.forEach { category ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(category.name, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Labels: ${category.labels} · ${(category.threshold * 100).toInt()}% · Drive: ${category.driveFolder}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = {
                        viewModel.portfolioLink(category) { link ->
                            context.getSystemService(ClipboardManager::class.java)
                                .setPrimaryClip(ClipData.newPlainText("Portfolio", link))
                            context.startActivity(
                                Intent.createChooser(
                                    Intent(Intent.ACTION_SEND).setType("text/plain")
                                        .putExtra(Intent.EXTRA_TEXT, "${category.name} gallery: $link"),
                                    "Share portfolio link",
                                )
                            )
                        }
                    }) { Text("🔗 Link") }
                    TextButton(onClick = { editing = category; viewModel.loadSeenLabels() }) { Text("Edit") }
                }
            }
            FilledTonalButton(onClick = {
                editing = Category(Categories.newId(), "", "", 0.6f, "")
                viewModel.loadSeenLabels()
            }) { Text("+ Add category") }
            editing?.let { category ->
                CategoryDialog(
                    initial = category,
                    isNew = s.categories.none { it.id == category.id },
                    canDelete = s.categories.size > 1,
                    suggestions = state.seenLabels,
                    onDismiss = { editing = null },
                    onSave = { viewModel.saveCategory(it); editing = null },
                    onDelete = { viewModel.deleteCategory(category.id); editing = null },
                )
            }
        }

        Section("🏷", "Brand kit") {
            val sample = state.recent.firstOrNull { !it.isVideo }?.uri
            BrandKitSection(state.brandVersion, sample, viewModel)
        }

        Section("☁️", "Google Drive") {
            SwitchRow("Upload to Google Drive", s.driveUploadEnabled) {
                viewModel.updateSettings { driveUploadEnabled = it }
            }
            val pickAccount = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                result.data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)?.let(viewModel::switchDriveAccount)
            }
            val chooseAccount = {
                pickAccount.launch(
                    AccountPicker.newChooseAccountIntent(
                        AccountPicker.AccountChooserOptions.Builder()
                            .setAllowableAccountsTypes(listOf("com.google"))
                            .setAlwaysShowAccountPicker(true)
                            .build()
                    )
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (state.driveConnected) "✓ Connected" else "Not connected", style = MaterialTheme.typography.titleSmall)
                    Text(
                        state.driveAccount ?: if (state.driveConnected) "Your Google account" else "Uploads need a Google account",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!state.driveConnected) Button(onClick = onConnectDrive) { Text("Connect") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = chooseAccount) { Text(if (state.driveConnected) "Switch account" else "Choose account") }
                if (state.driveConnected) TextButton(onClick = viewModel::disconnectDrive) { Text("Disconnect") }
            }
            Text("Drive folders", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
            var renaming by remember { mutableStateOf<Category?>(null) }
            s.categories.forEach { category ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("📁 ${category.driveFolder}", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "${category.name} photos · then /<date>/ or /Orders/<order>/",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { renaming = category }) { Text("Change") }
                }
            }
            renaming?.let { category ->
                var name by remember(category) { mutableStateOf(category.driveFolder) }
                AlertDialog(
                    onDismissRequest = { renaming = null },
                    title = { Text("Drive folder for ${category.name}") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = name, onValueChange = { name = it }, singleLine = true,
                                label = { Text("Folder name") }, modifier = Modifier.fillMaxWidth(),
                            )
                            Text(
                                "New uploads go into this folder at the top of your Drive (created if it doesn't exist). " +
                                    "Photos already uploaded stay where they are.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    },
                    confirmButton = {
                        TextButton(enabled = name.isNotBlank(), onClick = { viewModel.setDriveFolder(category, name); renaming = null }) {
                            Text("Save")
                        }
                    },
                    dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
                )
            }
            // Publisher diagnostics (package/SHA-1 for the Cloud OAuth client):
            // only relevant when Drive sync is on but failing — never for gallery-only users.
            if (s.driveUploadEnabled && !state.driveConnected) {
                val identity = remember { appIdentity(context) }
                SelectionContainer {
                    Text(
                        "For Google Cloud → Android OAuth client:\n$identity",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Section("🖼", "Gallery folder") {
            SwitchRow("Save cakes to the gallery (no Drive needed)", s.localOrganizeEnabled) {
                viewModel.updateSettings { localOrganizeEnabled = it }
            }
            // User-choosable; default "CakeSync" avoids colliding with a folder the
            // user already has. Copies are additive — renaming leaves old ones behind.
            var name by remember(s.localFolderName) { mutableStateOf(s.localFolderName) }
            OutlinedTextField(
                value = name, onValueChange = { name = it }, singleLine = true,
                label = { Text("Folder name (in Pictures / Movies)") },
                supportingText = { Text("Photos → Pictures/$name/<date>/ · Videos → Movies/$name/<date>/") },
                modifier = Modifier.fillMaxWidth(),
            )
            if (name != s.localFolderName) {
                Button(onClick = { viewModel.updateSettings { localFolderName = name } }) { Text("Save") }
            }
        }

        Section("📦", "Orders") {
            Text(orderSetupSummary(state.orderSettings), style = MaterialTheme.typography.bodyMedium)
            Text(
                "Channels, advance and cancellation rules, the questions on your order form, and the Google Calendar " +
                    "orders are scheduled in.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FilledTonalButton(onClick = { viewModel.openOrdersSetup() }) {
                Text(if (state.orderSettings.configured) "Edit order setup" else "Set up orders")
            }
        }

        Section("⏰", "Daily sync") {
            SwitchRow("Sync automatically every day", s.dailySyncEnabled) {
                viewModel.updateSettings { dailySyncEnabled = it }
            }
            var hour by remember(s.uploadHour) { mutableFloatStateOf(s.uploadHour.toFloat()) }
            Text("Daily sync time: %02d:00".format(hour.toInt()))
            Slider(
                value = hour,
                onValueChange = { hour = it },
                onValueChangeFinished = { viewModel.updateSettings { uploadHour = hour.toInt() } },
                valueRange = 0f..23f,
                steps = 22,
            )
            SwitchRow("Wi-Fi only", s.wifiOnly) { viewModel.updateSettings { wifiOnly = it } }
            SwitchRow("Only sync photos I've approved", s.requireApproval) {
                viewModel.updateSettings { requireApproval = it }
            }
        }

        Section("🔍", "Detection") {
            SwitchRow("Skip photos with people (face detection)", s.excludePeople) {
                viewModel.updateSettings { excludePeople = it }
            }
            SwitchRow("Keep only the best shot (skip near-duplicates)", s.skipDuplicates) {
                viewModel.updateSettings { skipDuplicates = it }
            }
            SwitchRow("Include videos", s.includeVideos) {
                viewModel.updateSettings { includeVideos = it }
            }
        }

        Section("📁", "Photos to scan") {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                    folders = state.folders,
                    selected = s.scanFolders,
                    onDismiss = { showPicker = false },
                    onSave = { chosen ->
                        viewModel.updateSettings { scanFolders = chosen }
                        showPicker = false
                    },
                )
            }
        }

        Section("ℹ️", "About") {
            val version = remember {
                runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
            }
            Text("CakeSync ${version?.let { "v$it" } ?: ""}", style = MaterialTheme.typography.bodyMedium)
            Text(
                "Photos are checked on your phone. Only the ones you choose go to your Google Drive.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onShowIntro) { Text("Show the intro again") }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** Big serif page title with a one-line subtitle. */
@Composable
fun ScreenTitle(title: String, subtitle: String) {
    Column(Modifier.padding(top = 12.dp, bottom = 2.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A rounded card with an emoji heading. */
@Composable
fun Section(emoji: String, title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(emoji, fontSize = 20.sp)
                Spacer(Modifier.width(10.dp))
                Text(title, style = MaterialTheme.typography.titleLarge)
            }
            content()
        }
    }
}

/** Package name, version and signing-certificate SHA-1 of the installed app. */
internal fun appIdentity(context: Context): String = try {
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
internal fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    // The whole row toggles, not just the small switch.
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
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

@Composable
private fun CategoryDialog(
    initial: Category,
    isNew: Boolean,
    canDelete: Boolean,
    suggestions: List<String>,
    onDismiss: () -> Unit,
    onSave: (Category) -> Unit,
    onDelete: () -> Unit,
) {
    var name by remember { mutableStateOf(initial.name) }
    var labels by remember { mutableStateOf(initial.labels) }
    var folder by remember { mutableStateOf(initial.driveFolder) }
    var threshold by remember { mutableFloatStateOf(initial.threshold) }
    var hashtags by remember { mutableStateOf(initial.hashtags) }
    var captionTemplate by remember { mutableStateOf(initial.captionTemplate.ifBlank { Captions.DEFAULT_TEMPLATE }) }
    val chosen = labels.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "New category" else "Edit ${initial.name}") },
        text = {
            Column(
                Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it }, singleLine = true,
                    label = { Text("Name, e.g. Cupcakes") }, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = labels, onValueChange = { labels = it },
                    label = { Text("Labels to match (comma-separated)") }, modifier = Modifier.fillMaxWidth(),
                )
                if (suggestions.isNotEmpty()) {
                    Text("Labels seen in your photos (tap to add):", style = MaterialTheme.typography.labelSmall)
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        suggestions.filter { it !in chosen }.take(25).forEach { label ->
                            FilterChip(
                                selected = false,
                                onClick = { labels = (chosen + label).joinToString(", ") },
                                label = { Text(label) },
                            )
                        }
                    }
                }
                Text("Minimum confidence: ${(threshold * 100).toInt()}%")
                Slider(value = threshold, onValueChange = { threshold = it }, valueRange = 0.3f..0.95f)
                OutlinedTextField(
                    value = folder, onValueChange = { folder = it }, singleLine = true,
                    label = { Text("Drive folder") }, placeholder = { Text(name.ifBlank { "Folder name" }) },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = hashtags, onValueChange = { hashtags = it },
                    label = { Text("Hashtags for captions, e.g. homebaker custom cakes") }, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = captionTemplate, onValueChange = { captionTemplate = it }, minLines = 3,
                    label = { Text("Caption template") }, modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Placeholders: ${Captions.PLACEHOLDERS.joinToString(" ")}",
                    style = MaterialTheme.typography.labelSmall,
                )
                if (!isNew && canDelete) {
                    TextButton(onClick = onDelete) { Text("Delete this category") }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && chosen.isNotEmpty(),
                onClick = {
                    onSave(
                        initial.copy(
                            name = name.trim(),
                            labels = chosen.joinToString(", "),
                            threshold = threshold,
                            driveFolder = folder.trim().ifBlank { name.trim() },
                            hashtags = Captions.normaliseHashtags(hashtags),
                            captionTemplate = captionTemplate.trim().takeIf { it != Captions.DEFAULT_TEMPLATE }.orEmpty(),
                        )
                    )
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun BrandKitSection(brandVersion: Int, sample: Uri?, viewModel: MainViewModel) {
    val kit = remember(brandVersion) { viewModel.brandKit() }
    val pickLogo = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) viewModel.setBrandLogo(uri)
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (kit.logo != null) {
                AsyncImage(
                    model = coil3.request.ImageRequest.Builder(LocalContext.current)
                        .data(kit.logo).memoryCacheKey("logo-$brandVersion").diskCacheKey("logo-$brandVersion").build(),
                    contentDescription = "Logo",
                    modifier = Modifier.size(56.dp),
                )
            }
            OutlinedButton(onClick = {
                pickLogo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }) { Text(if (kit.logo == null) "Choose logo" else "Change logo") }
            if (kit.logo != null) TextButton(onClick = { viewModel.setBrandLogo(null) }) { Text("Remove") }
        }
        Text("Logo position", style = MaterialTheme.typography.labelMedium)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LogoPosition.entries.forEach { p ->
                FilterChip(
                    selected = kit.position == p,
                    onClick = { viewModel.saveBrandKit(kit.copy(position = p)) },
                    label = { Text(p.label) },
                )
            }
        }
        var size by remember(brandVersion) { mutableFloatStateOf(kit.logoSize) }
        Text("Logo size: ${(size * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
        Slider(
            value = size, onValueChange = { size = it }, valueRange = 0.1f..0.4f,
            onValueChangeFinished = { viewModel.saveBrandKit(kit.copy(logoSize = size)) },
        )
        var opacity by remember(brandVersion) { mutableFloatStateOf(kit.logoOpacity) }
        Text("Logo opacity: ${(opacity * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
        Slider(
            value = opacity, onValueChange = { opacity = it }, valueRange = 0.3f..1f,
            onValueChangeFinished = { viewModel.saveBrandKit(kit.copy(logoOpacity = opacity)) },
        )
        var name by remember(brandVersion) { mutableStateOf(kit.businessName) }
        OutlinedTextField(
            value = name, onValueChange = { name = it }, singleLine = true,
            label = { Text("Business name / handle, e.g. Sweet Crumbs · @sweetcrumbs") },
            modifier = Modifier.fillMaxWidth(),
        )
        if (name != kit.businessName) {
            Button(onClick = { viewModel.saveBrandKit(kit.copy(businessName = name.trim())) }) { Text("Save name") }
        }
        var tagline by remember(brandVersion) { mutableStateOf(kit.tagline) }
        var instagram by remember(brandVersion) { mutableStateOf(kit.instagram) }
        var facebook by remember(brandVersion) { mutableStateOf(kit.facebook) }
        var website by remember(brandVersion) { mutableStateOf(kit.website) }
        OutlinedTextField(
            value = tagline, onValueChange = { tagline = it }, singleLine = true,
            label = { Text("Tagline (optional), e.g. Custom cakes to order") }, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = instagram, onValueChange = { instagram = it }, singleLine = true,
            label = { Text("Instagram (optional), e.g. sweetcrumbs") }, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = facebook, onValueChange = { facebook = it }, singleLine = true,
            label = { Text("Facebook (optional), e.g. Sweet Crumbs") }, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = website, onValueChange = { website = it }, singleLine = true,
            label = { Text("Website (optional), e.g. sweetcrumbs.com") }, modifier = Modifier.fillMaxWidth(),
        )
        if (tagline != kit.tagline || instagram != kit.instagram || facebook != kit.facebook || website != kit.website) {
            Button(onClick = {
                viewModel.saveBrandKit(
                    kit.copy(
                        tagline = tagline.trim(), instagram = instagram.trim().removePrefix("@"),
                        facebook = facebook.trim(), website = website.trim(),
                    )
                )
            }) { Text("Save text") }
        }
        Text("Text font", style = MaterialTheme.typography.labelMedium)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LabelFont.entries.forEach { f ->
                val family = remember(f) { FontFamily(f.typeface()) }
                FilterChip(
                    selected = kit.labelFont == f,
                    onClick = { viewModel.saveBrandKit(kit.copy(labelFont = f)) },
                    label = { Text(f.label, fontFamily = family, fontSize = 16.sp) },
                )
            }
        }
        Text("Text colour", style = MaterialTheme.typography.labelMedium)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LABEL_COLOURS.forEach { (label, colour) ->
                val selected = kit.labelColor == colour
                Box(
                    Modifier.size(36.dp).clip(CircleShape).background(Color(colour))
                        .border(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape)
                        .clickable { viewModel.saveBrandKit(kit.copy(labelColor = colour)) },
                    contentAlignment = Alignment.Center,
                ) {
                    if (selected) Text("✓", color = if (Color(colour).luminance() > 0.5f) Color.Black else Color.White)
                }
            }
        }
        Text("Text background", style = MaterialTheme.typography.labelMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LabelStyle.entries.forEach { st ->
                FilterChip(
                    selected = kit.labelStyle == st,
                    onClick = { viewModel.saveBrandKit(kit.copy(labelStyle = st)) },
                    label = { Text(st.label) },
                )
            }
        }
        Text("Colour filter", style = MaterialTheme.typography.labelMedium)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ColorFilterPreset.entries.forEach { f ->
                FilterChip(
                    selected = kit.filter == f,
                    onClick = { viewModel.saveBrandKit(kit.copy(filter = f)) },
                    label = { Text(f.label) },
                )
            }
        }
        Text("Preview", style = MaterialTheme.typography.labelMedium)
        val preview = kit.copy(
            businessName = name.trim(), logoSize = size, logoOpacity = opacity,
            tagline = tagline.trim(), instagram = instagram.trim().removePrefix("@"),
            facebook = facebook.trim(), website = website.trim(),
        )
        EditPreview(sample, brandVersion, preview) { preview.apply(it) }
        if (kit.isEmpty && name.isBlank()) {
            Text(
                "Choose a logo, type your business name or pick a filter to see it here.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Text("Brand backgrounds", style = MaterialTheme.typography.labelMedium)
        Text(
            "Your own backdrops (e.g. your table, a pattern in your colours). Use them behind cut-out cakes " +
                "in the photo studio and in collages.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        BrandBackgrounds()
        SwitchRow("Also brand crops, filters and white-background copies", kit.applyToEdits) {
            viewModel.saveBrandKit(kit.copy(applyToEdits = it))
        }
    }
}

/** Thumbnails of the brand backgrounds with remove buttons, plus an Add button. */
@Composable
private fun BrandBackgrounds() {
    val context = LocalContext.current
    var items by remember { mutableStateOf(Backdrops.brand(context)) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            runCatching { Backdrops.addBrand(context, uri) }
            items = Backdrops.brand(context)
        }
    }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { bg ->
            Box(Modifier.size(64.dp)) {
                AsyncImage(
                    model = bg.file, contentDescription = bg.label,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)),
                )
                Text(
                    "✕", fontSize = 12.sp,
                    modifier = Modifier.align(Alignment.TopEnd).padding(2.dp)
                        .clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surface)
                        .clickable { Backdrops.removeBrand(bg); items = Backdrops.brand(context) }
                        .padding(horizontal = 5.dp),
                )
            }
        }
        OutlinedButton(
            onClick = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            modifier = Modifier.height(64.dp),
        ) { Text("＋ Add") }
    }
}
