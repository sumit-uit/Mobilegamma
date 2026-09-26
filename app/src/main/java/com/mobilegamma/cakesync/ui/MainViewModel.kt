package com.mobilegamma.cakesync.ui

import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
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
    val targetLabels: String,
    val threshold: Float,
    val driveFolderName: String,
    val uploadHour: Int,
    val wifiOnly: Boolean,
    val dailySyncEnabled: Boolean,
    val requireApproval: Boolean,
)

data class UiState(
    val hasPhotoPermission: Boolean = false,
    val driveConnected: Boolean = false,
    val showAll: Boolean = false,
    val photos: List<Photo> = emptyList(),
    val pendingCount: Int = 0,
    val scanProgress: Pair<Int, Int>? = null,
    val syncRunning: Boolean = false,
    val message: String? = null,
    val consentIntent: PendingIntent? = null,
    val settings: SettingsState? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val settings = Settings(app)
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
            val showAll = _state.value.showAll
            val (photos, pending) = withContext(Dispatchers.IO) {
                (if (showAll) store.all() else store.matches()) to
                    store.pendingUploads(settings.requireApproval).size
            }
            _state.update {
                it.copy(
                    hasPhotoPermission = SyncWorker.hasPhotoPermission(getApplication()),
                    driveConnected = settings.driveConnected,
                    photos = photos,
                    pendingCount = pending,
                    message = it.message ?: settings.lastSyncMessage,
                    settings = readSettings(),
                )
            }
        }
    }

    fun setShowAll(showAll: Boolean) {
        _state.update { it.copy(showAll = showAll) }
        refresh()
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
                        { r -> "Scanned ${r.scanned} new photo(s), ${r.matched} match(es)" },
                        { e -> "Scan failed: ${e.message}" },
                    ),
                )
            }
            refresh()
        }
    }

    fun syncNow() {
        _state.update { it.copy(message = "Uploading in the background…") }
        SyncScheduler.syncNow(getApplication())
    }

    /** Cycles a photo between included and excluded (an explicit user decision). */
    fun toggle(photo: Photo) {
        viewModelScope.launch(Dispatchers.IO) {
            store.setOverride(photo.mediaId, !photo.included)
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
        if (before.targetLabels != after.targetLabels || before.threshold != after.threshold) {
            viewModelScope.launch(Dispatchers.IO) {
                store.reclassify(settings.labelSet(), settings.threshold)
                refresh()
            }
        }
        if (before.uploadHour != after.uploadHour || before.wifiOnly != after.wifiOnly ||
            before.dailySyncEnabled != after.dailySyncEnabled
        ) {
            SyncScheduler.apply(getApplication())
        }
        _state.update { it.copy(settings = after) }
        if (before.requireApproval != after.requireApproval) refresh()
    }

    private fun readSettings() = SettingsState(
        targetLabels = settings.targetLabels,
        threshold = settings.threshold,
        driveFolderName = settings.driveFolderName,
        uploadHour = settings.uploadHour,
        wifiOnly = settings.wifiOnly,
        dailySyncEnabled = settings.dailySyncEnabled,
        requireApproval = settings.requireApproval,
    )
}
