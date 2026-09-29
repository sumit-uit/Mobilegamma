package com.mobilegamma.cakesync.ui

import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.mobilegamma.cakesync.data.Categories
import com.mobilegamma.cakesync.data.Category
import com.mobilegamma.cakesync.data.Photo
import com.mobilegamma.cakesync.data.PhotoStore
import com.mobilegamma.cakesync.data.Settings
import com.mobilegamma.cakesync.drive.DriveAuth
import com.mobilegamma.cakesync.edit.BrandKit
import com.mobilegamma.cakesync.edit.PhotoEditor
import com.mobilegamma.cakesync.edit.ReelMaker
import com.mobilegamma.cakesync.drive.DriveClient
import com.mobilegamma.cakesync.share.Captions
import com.mobilegamma.cakesync.share.Catalog
import com.mobilegamma.cakesync.share.Sharer
import com.mobilegamma.cakesync.scan.PhotoScanner
import com.mobilegamma.cakesync.work.SyncScheduler
import com.mobilegamma.cakesync.work.SyncWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SettingsState(
    val categories: List<Category>,
    val uploadHour: Int,
    val wifiOnly: Boolean,
    val dailySyncEnabled: Boolean,
    val requireApproval: Boolean,
    val excludePeople: Boolean,
    val includeVideos: Boolean,
    val skipDuplicates: Boolean,
    val scanDays: Int,
    val scanFolders: Set<String>,
)

/** Which photos the review grid shows. */
enum class GridTab { MATCHES, VIDEOS, ALL }

data class UiState(
    val hasPhotoPermission: Boolean = false,
    val hasVideoPermission: Boolean = false,
    val driveConnected: Boolean = false,
    val tab: GridTab = GridTab.MATCHES,
    val photos: List<Photo> = emptyList(),
    val pendingCount: Int = 0,
    val scanProgress: Pair<Int, Int>? = null,
    val syncRunning: Boolean = false,
    val message: String? = null,
    val consentIntent: PendingIntent? = null,
    val settings: SettingsState? = null,
    /** Photo folders on the device, loaded when the folder picker opens. */
    val folders: List<PhotoScanner.Folder>? = null,
    /** Grid filter: a category id, or null for all categories. */
    val categoryFilter: String? = null,
    /** Labels seen in scanned photos, offered as suggestions when editing a category. */
    val seenLabels: List<String> = emptyList(),
    /** Photos picked with a long-press, for bulk actions. */
    val selected: Set<Long> = emptySet(),
    /** Order tags already used, offered as suggestions. */
    val orderTags: List<String> = emptyList(),
    /** Bumped when the brand kit changes so the preview redraws. */
    val brandVersion: Int = 0,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val settings = Settings(app)
    private val categories = Categories(app)
    private val store = PhotoStore.get(app)
    private val auth = DriveAuth(app)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        refresh()
        viewModelScope.launch {
            WorkManager.getInstance(app).getWorkInfosForUniqueWorkFlow(SyncScheduler.NOW).collect { infos ->
                val running = infos.any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
                val finished = _state.value.syncRunning && !running
                _state.update {
                    it.copy(syncRunning = running, message = if (finished) settings.lastSyncMessage else it.message)
                }
                if (!running) refresh()
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            val tab = _state.value.tab
            val filter = _state.value.categoryFilter
            val (photos, pending) = withContext(Dispatchers.IO) {
                when (tab) {
                    GridTab.MATCHES -> store.matches()
                    GridTab.VIDEOS -> store.matches().filter { it.isVideo }
                    GridTab.ALL -> store.all()
                }.filter { filter == null || it.category == filter } to
                    store.pendingUploads(settings.requireApproval, settings.excludePeople, settings.skipDuplicates).size
            }
            _state.update {
                it.copy(
                    hasPhotoPermission = SyncWorker.hasPhotoPermission(getApplication()),
                    hasVideoPermission = SyncWorker.hasVideoPermission(getApplication()),
                    driveConnected = settings.driveConnected,
                    photos = photos,
                    pendingCount = pending,
                    message = it.message ?: settings.lastSyncMessage,
                    settings = readSettings(),
                )
            }
        }
    }

    fun setTab(tab: GridTab) {
        _state.update { it.copy(tab = tab) }
        refresh()
    }

    fun setCategoryFilter(id: String?) {
        _state.update { it.copy(categoryFilter = id) }
        refresh()
    }

    fun loadSeenLabels() {
        viewModelScope.launch {
            val labels = withContext(Dispatchers.IO) { store.seenLabels() }
            _state.update { it.copy(seenLabels = labels) }
        }
    }

    /** Adds or edits a category, then re-sorts already-scanned photos with the new setup. */
    fun saveCategory(category: Category) = changeCategories { categories.upsert(category) }

    fun deleteCategory(id: String) = changeCategories {
        categories.delete(id)
        if (_state.value.categoryFilter == id) _state.update { it.copy(categoryFilter = null) }
    }

    private fun changeCategories(change: () -> Unit) {
        change()
        _state.update { it.copy(settings = readSettings()) }
        viewModelScope.launch(Dispatchers.IO) {
            val current = categories.all()
            store.reclassify { labels -> categories.classify(labels, current) }
            refresh()
        }
    }

    fun scanNow() {
        if (_state.value.scanProgress != null) return
        viewModelScope.launch {
            _state.update { it.copy(scanProgress = 0 to 0, message = "Scanning photos…") }
            val result = runCatching {
                PhotoScanner(getApplication()).scanNew { done, total ->
                    _state.update { it.copy(scanProgress = done to total) }
                }
            }
            _state.update {
                it.copy(
                    scanProgress = null,
                    message = result.fold(
                        { r -> r.summary() },
                        { e -> "Scan failed: ${e.message}" },
                    ),
                )
            }
            refresh()
        }
    }

    fun loadFolders() {
        viewModelScope.launch {
            val folders = runCatching { PhotoScanner(getApplication()).listFolders() }.getOrDefault(emptyList())
            _state.update { it.copy(folders = folders) }
        }
    }

    fun syncNow() {
        _state.update { it.copy(message = "Uploading in the background…") }
        SyncScheduler.syncNow(getApplication())
    }

    /** Long-press: start or extend a selection. */
    fun toggleSelected(photo: Photo) = _state.update {
        it.copy(selected = if (photo.mediaId in it.selected) it.selected - photo.mediaId else it.selected + photo.mediaId)
    }

    fun clearSelection() = _state.update { it.copy(selected = emptySet()) }

    fun selectAllShown() = _state.update { s -> s.copy(selected = s.photos.map { it.mediaId }.toSet()) }

    fun includeSelected(include: Boolean) = onSelected { store.setOverride(it, include) }

    /** Tags the selected photos with an order/customer name (null removes the tag). */
    fun tagSelected(tag: String?) = onSelected { store.setOrder(it, tag?.trim()?.ifBlank { null }) }

    fun moveSelectedTo(categoryId: String) = onSelected { store.setCategoryManually(it, categoryId) }

    /** Background removal: saves a white-background copy of each selected photo. */
    fun whiteBackgroundForSelected() = editSelected("White background") { editor, photo ->
        editor.removeBackground(photo.uri, photo.displayName)
    }

    /** Saves a copy of each selected photo cropped to [shape]. */
    fun cropSelected(shape: PhotoEditor.Shape) = editSelected("Crop ${shape.label}") { editor, photo ->
        editor.crop(photo.uri, photo.displayName, shape)
    }

    /** Saves branded copies (logo, name, filter) with an optional price label. */
    fun brandSelected(price: String?) = editSelected("Brand") { editor, photo ->
        editor.brand(photo.uri, photo.displayName, price)
    }

    fun brandKit(): BrandKit = BrandKit.load(getApplication())

    fun saveBrandKit(kit: BrandKit) {
        BrandKit.save(getApplication(), kit)
        _state.update { it.copy(brandVersion = it.brandVersion + 1) }
    }

    fun setBrandLogo(uri: Uri?) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching {
                if (uri == null) BrandKit.removeLogo(getApplication()) else BrandKit.setLogo(getApplication(), uri)
            }
            _state.update {
                it.copy(
                    brandVersion = it.brandVersion + 1,
                    message = result.exceptionOrNull()?.let { e -> "Logo: ${e.message}" } ?: it.message,
                )
            }
        }
    }

    /** Caption for the current selection from its category's template and hashtags. */
    fun captionForSelection(): String {
        val photos = _state.value.photos.filter { it.mediaId in _state.value.selected }
        val categoryId = photos.mapNotNull { it.category }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
        return Captions.build(photos, categories.byId(categoryId) ?: categories.all().first(), brandKit().businessName)
    }

    /**
     * Shares the selection to [target] with [caption]. With [brand], photos are shared as
     * branded copies (created first); videos are shared as they are.
     */
    fun shareSelected(target: Sharer.Target, caption: String, brand: Boolean) {
        val photos = _state.value.photos.filter { it.mediaId in _state.value.selected }
        if (photos.isEmpty()) return
        viewModelScope.launch {
            val items = mutableListOf<Pair<Uri, String>>()
            val editor = PhotoEditor(getApplication())
            for ((i, photo) in photos.withIndex()) {
                if (brand && !photo.isVideo) {
                    _state.update { it.copy(message = "Branding ${i + 1} of ${photos.size}…") }
                    val branded = runCatching { editor.brand(photo.uri, photo.displayName, null) }.getOrNull()
                    items += (branded ?: photo.uri) to "image/jpeg"
                } else {
                    items += photo.uri to photo.mimeType
                }
            }
            val result = runCatching { Sharer.share(getApplication(), items, caption, target) }
            _state.update {
                it.copy(
                    selected = emptySet(),
                    message = result.getOrElse { e -> "Could not share: ${e.message}" },
                )
            }
        }
    }

    /** Makes a 9:16 reel from the selected photos/videos (in grid order), with optional music. */
    fun makeReel(music: Uri?) {
        val items = _state.value.photos.filter { it.mediaId in _state.value.selected }.sortedBy { it.takenAtMillis }
        if (items.isEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(message = "Making a reel from ${items.size} item(s)…", selected = emptySet()) }
            val result = runCatching { ReelMaker(getApplication()).make(items, music) }
            _state.update {
                it.copy(
                    message = result.fold(
                        { "Reel saved to Movies/CakeSync (${minOf(items.size, ReelMaker.MAX_ITEMS)} item(s))" },
                        { e -> "Could not make the reel: ${e.message}" },
                    ),
                )
            }
        }
    }

    /**
     * Writes a Meta catalog CSV (Commerce Manager bulk upload) for the selected photos that
     * are already in Drive, sharing each image by link so Meta can fetch it.
     */
    fun exportCatalog(price: String) {
        val selected = _state.value.photos.filter { it.mediaId in _state.value.selected && !it.isVideo }
        val uploaded = selected.filter { it.driveFileId != null }
        if (uploaded.isEmpty()) {
            _state.update { it.copy(message = "Catalog: upload these photos to Drive first (catalog images must be online)") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(message = "Catalog: sharing ${uploaded.size} image(s)…", selected = emptySet()) }
            val result = runCatching {
                val drive = DriveClient(driveToken())
                val business = brandKit().businessName
                val items = uploaded.map { photo ->
                    drive.makePublic(photo.driveFileId!!)
                    val category = categories.byId(photo.category)?.name ?: "Cake"
                    val date = java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.getDefault())
                        .format(java.util.Date(photo.takenAtMillis))
                    Catalog.Item(
                        id = "cakesync-${photo.mediaId}",
                        title = listOfNotNull(category, photo.orderTag).joinToString(" – "),
                        description = listOfNotNull("$category made $date", business.ifBlank { null }).joinToString(" · "),
                        imageLink = Catalog.driveImageLink(photo.driveFileId),
                        price = price.trim(),
                        brand = business,
                    )
                }
                saveDownload("CakeSync_catalog_${System.currentTimeMillis() / 1000}.csv", "text/csv", Catalog.toCsv(items))
                items.size
            }
            _state.update {
                it.copy(
                    message = result.fold(
                        { n ->
                            "Catalog: $n item(s) saved to Downloads. Upload it in Meta Commerce Manager → Catalog → Data sources" +
                                (if (selected.size > n) " (${selected.size - n} not yet in Drive were skipped)" else "")
                        },
                        { e -> "Catalog failed: ${e.message}" },
                    ),
                )
            }
        }
    }

    /** Shares a category's Drive folder with "anyone with the link" and returns the link. */
    fun portfolioLink(category: Category, onLink: (String) -> Unit) {
        viewModelScope.launch {
            val result = runCatching {
                val drive = DriveClient(driveToken())
                val id = settings.rootFolderId(category)?.takeIf { drive.folderExists(it) }
                    ?: drive.findOrCreateFolder(category.driveFolder, null).also { settings.setRootFolderId(category, it) }
                drive.makePublic(id)
                Catalog.driveFolderLink(id)
            }
            result.onSuccess(onLink)
            _state.update {
                it.copy(message = result.fold({ "Portfolio link for ${category.name}: $it" }, { e -> "Portfolio link failed: ${e.message}" }))
            }
        }
    }

    private suspend fun driveToken(): String =
        when (val auth = auth.authorize()) {
            is DriveAuth.Outcome.Token -> auth.accessToken
            is DriveAuth.Outcome.NeedsConsent -> {
                _state.update { it.copy(consentIntent = auth.pendingIntent) }
                throw IllegalStateException("connect Google Drive first")
            }
        }

    private suspend fun saveDownload(name: String, mime: String, text: String) = withContext(Dispatchers.IO) {
        val resolver = getApplication<Application>().contentResolver
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name)
            put(android.provider.MediaStore.Downloads.MIME_TYPE, mime)
            put(android.provider.MediaStore.Downloads.RELATIVE_PATH, "Download/CakeSync")
        }
        val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw java.io.IOException("Could not create $name")
        resolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
    }

    private fun editSelected(what: String, edit: suspend (PhotoEditor, Photo) -> Unit) {
        val photos = _state.value.photos.filter { it.mediaId in _state.value.selected && !it.isVideo }
        if (photos.isEmpty()) {
            _state.update { it.copy(message = "$what: select photos (videos can't be edited)") }
            return
        }
        viewModelScope.launch {
            val editor = PhotoEditor(getApplication())
            var done = 0
            var error: String? = null
            for ((i, photo) in photos.withIndex()) {
                _state.update { it.copy(message = "$what: ${i + 1} of ${photos.size}…") }
                try {
                    edit(editor, photo)
                    done++
                } catch (e: Exception) {
                    error = e.message
                    if (e is PhotoEditor.ModelDownloading) break // same for every photo; try later
                }
            }
            finishEdit(what, done, error)
        }
    }

    private fun finishEdit(what: String, done: Int, error: String?) {
        val text = buildString {
            append("$what: saved $done new photo(s) to Pictures/CakeSync (originals unchanged)")
            if (error != null) append(" · problem: $error")
        }
        _state.update { it.copy(message = text, selected = emptySet()) }
    }

    fun loadOrderTags() {
        viewModelScope.launch {
            val tags = withContext(Dispatchers.IO) { store.orderTags() }
            _state.update { it.copy(orderTags = tags) }
        }
    }

    private fun onSelected(action: (Set<Long>) -> Unit) {
        val ids = _state.value.selected
        if (ids.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            action(ids)
            _state.update { it.copy(selected = emptySet()) }
            refresh()
        }
    }

    /** Cycles a photo between included and excluded (an explicit user decision). */
    fun toggle(photo: Photo) {
        viewModelScope.launch(Dispatchers.IO) {
            store.setOverride(photo.mediaId, !photo.included(settings.excludePeople, settings.skipDuplicates))
            refresh()
        }
    }

    fun connectDrive() {
        viewModelScope.launch {
            val outcome = runCatching { auth.authorize() }
            handleAuth(outcome)
        }
    }

    fun onConsentLaunched() = _state.update { it.copy(consentIntent = null) }

    fun onConsentResult(data: Intent?) = handleAuth(runCatching { auth.resultFromConsent(data) })

    private fun handleAuth(outcome: Result<DriveAuth.Outcome>) {
        outcome.onSuccess { result ->
            when (result) {
                is DriveAuth.Outcome.NeedsConsent -> _state.update { it.copy(consentIntent = result.pendingIntent) }
                is DriveAuth.Outcome.Token -> {
                    settings.driveConnected = true
                    _state.update { it.copy(driveConnected = true, message = "Google Drive connected") }
                }
            }
        }.onFailure { e ->
            _state.update { it.copy(message = "Could not connect Drive: ${e.message}") }
        }
    }

    fun updateSettings(transform: Settings.() -> Unit) {
        val before = readSettings()
        settings.transform()
        val after = readSettings()
        if (before.uploadHour != after.uploadHour || before.wifiOnly != after.wifiOnly ||
            before.dailySyncEnabled != after.dailySyncEnabled
        ) {
            SyncScheduler.apply(getApplication())
        }
        _state.update { it.copy(settings = after) }
        if (before.requireApproval != after.requireApproval || before.excludePeople != after.excludePeople ||
            before.skipDuplicates != after.skipDuplicates
        ) refresh()
    }

    private fun readSettings() = SettingsState(
        categories = categories.all(),
        uploadHour = settings.uploadHour,
        wifiOnly = settings.wifiOnly,
        dailySyncEnabled = settings.dailySyncEnabled,
        requireApproval = settings.requireApproval,
        excludePeople = settings.excludePeople,
        includeVideos = settings.includeVideos,
        skipDuplicates = settings.skipDuplicates,
        scanDays = settings.scanDays,
        scanFolders = settings.scanFolders,
    )
}
