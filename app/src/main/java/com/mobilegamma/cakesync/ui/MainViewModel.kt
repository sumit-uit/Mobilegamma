package com.mobilegamma.cakesync.ui

import android.app.Application
import android.app.PendingIntent
import android.content.Intent
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
                    store.pendingUploads(settings.requireApproval, settings.excludePeople).size
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

    /** Cycles a photo between included and excluded (an explicit user decision). */
    fun toggle(photo: Photo) {
        viewModelScope.launch(Dispatchers.IO) {
            store.setOverride(photo.mediaId, !photo.included(settings.excludePeople))
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
        if (before.requireApproval != after.requireApproval || before.excludePeople != after.excludePeople) refresh()
    }

    private fun readSettings() = SettingsState(
        categories = categories.all(),
        uploadHour = settings.uploadHour,
        wifiOnly = settings.wifiOnly,
        dailySyncEnabled = settings.dailySyncEnabled,
        requireApproval = settings.requireApproval,
        excludePeople = settings.excludePeople,
        includeVideos = settings.includeVideos,
        scanDays = settings.scanDays,
        scanFolders = settings.scanFolders,
    )
}
