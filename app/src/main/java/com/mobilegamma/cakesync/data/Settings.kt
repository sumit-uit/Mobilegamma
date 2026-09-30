package com.mobilegamma.cakesync.data

import android.content.Context
import androidx.core.content.edit

/** User preferences, stored in SharedPreferences. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("cakesync", Context.MODE_PRIVATE)

    /** Comma-separated ML Kit labels that count as a match, e.g. "Cake, Cupcake". */
    var targetLabels: String
        get() = prefs.getString(KEY_LABELS, "Cake") ?: "Cake"
        set(value) = prefs.edit { putString(KEY_LABELS, value) }

    /** Minimum ML Kit confidence (0..1) for a label to count as a match. */
    var threshold: Float
        get() = prefs.getFloat(KEY_THRESHOLD, 0.6f)
        set(value) = prefs.edit { putFloat(KEY_THRESHOLD, value) }

    /** Name of the top-level folder the app creates in Google Drive. */
    var driveFolderName: String
        get() = prefs.getString(KEY_FOLDER, "CakeSync") ?: "CakeSync"
        set(value) = prefs.edit {
            putString(KEY_FOLDER, value)
            remove(KEY_ROOT_FOLDER_ID) // a new name means a new root folder
        }

    /** Hour of day (0-23) for the daily scan + upload. */
    var uploadHour: Int
        get() = prefs.getInt(KEY_HOUR, 20)
        set(value) = prefs.edit { putInt(KEY_HOUR, value) }

    var wifiOnly: Boolean
        get() = prefs.getBoolean(KEY_WIFI, true)
        set(value) = prefs.edit { putBoolean(KEY_WIFI, value) }

    /** The first-run introduction has been shown (or skipped). */
    var onboarded: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDED, false)
        set(value) = prefs.edit { putBoolean(KEY_ONBOARDED, value) }

    var dailySyncEnabled: Boolean
        get() = prefs.getBoolean(KEY_DAILY, false)
        set(value) = prefs.edit { putBoolean(KEY_DAILY, value) }

    /** When true, only photos the user has explicitly approved are uploaded. */
    var requireApproval: Boolean
        get() = prefs.getBoolean(KEY_APPROVAL, false)
        set(value) = prefs.edit { putBoolean(KEY_APPROVAL, value) }

    /** Don't upload matched photos in which face detection finds a person. */
    var excludePeople: Boolean
        get() = prefs.getBoolean(KEY_EXCLUDE_PEOPLE, true)
        set(value) = prefs.edit { putBoolean(KEY_EXCLUDE_PEOPLE, value) }

    /** Upload only the sharpest of several near-identical shots taken moments apart. */
    var skipDuplicates: Boolean
        get() = prefs.getBoolean(KEY_SKIP_DUPLICATES, true)
        set(value) = prefs.edit { putBoolean(KEY_SKIP_DUPLICATES, value) }

    /** Also scan and upload videos (cake detection runs on frames from each video). */
    var includeVideos: Boolean
        get() = prefs.getBoolean(KEY_VIDEOS, true)
        set(value) = prefs.edit { putBoolean(KEY_VIDEOS, value) }

    /** How far back scans look, in days; 0 = all photos. */
    var scanDays: Int
        get() = prefs.getInt(KEY_SCAN_DAYS, 7)
        set(value) = prefs.edit { putInt(KEY_SCAN_DAYS, value) }

    /**
     * MediaStore RELATIVE_PATHs to scan, e.g. "DCIM/Camera/". Empty = every folder.
     */
    var scanFolders: Set<String>
        get() = prefs.getStringSet(KEY_FOLDERS, emptySet())?.toSet() ?: emptySet()
        set(value) = prefs.edit { putStringSet(KEY_FOLDERS, value) }

    /** Cached Drive id of a category's top-level folder (reset when its folder name changes). */
    fun rootFolderId(category: Category): String? =
        prefs.getString("drive_root_${category.id}_${category.driveFolder}", null)
            ?: if (category.id == Categories.DEFAULT_ID && category.driveFolder == driveFolderName) driveRootFolderId else null

    fun setRootFolderId(category: Category, id: String) =
        prefs.edit { putString("drive_root_${category.id}_${category.driveFolder}", id) }

    var driveRootFolderId: String?
        get() = prefs.getString(KEY_ROOT_FOLDER_ID, null)
        set(value) = prefs.edit { putString(KEY_ROOT_FOLDER_ID, value) }

    /** Google account chosen for Drive (null = let Google pick the phone's default). */
    var driveAccount: String?
        get() = prefs.getString(KEY_DRIVE_ACCOUNT, null)
        set(value) = prefs.edit { putString(KEY_DRIVE_ACCOUNT, value) }

    /** Forgets the cached Drive folder ids, e.g. after switching to another Google account. */
    fun clearDriveFolders() = prefs.edit {
        prefs.all.keys.filter { it.startsWith("drive_root_") }.forEach { remove(it) }
        remove(KEY_ROOT_FOLDER_ID)
    }

    var driveConnected: Boolean
        get() = prefs.getBoolean(KEY_CONNECTED, false)
        set(value) = prefs.edit { putBoolean(KEY_CONNECTED, value) }

    var lastSyncMessage: String?
        get() = prefs.getString(KEY_LAST_MSG, null)
        set(value) = prefs.edit { putString(KEY_LAST_MSG, value) }

    fun labelSet(): Set<String> =
        targetLabels.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()

    private companion object {
        const val KEY_LABELS = "target_labels"
        const val KEY_THRESHOLD = "threshold"
        const val KEY_FOLDER = "drive_folder_name"
        const val KEY_HOUR = "upload_hour"
        const val KEY_WIFI = "wifi_only"
        const val KEY_DAILY = "daily_sync"
        const val KEY_ONBOARDED = "onboarded"
        const val KEY_APPROVAL = "require_approval"
        const val KEY_SCAN_DAYS = "scan_days"
        const val KEY_EXCLUDE_PEOPLE = "exclude_people"
        const val KEY_VIDEOS = "include_videos"
        const val KEY_SKIP_DUPLICATES = "skip_duplicates"
        const val KEY_FOLDERS = "scan_folders"
        const val KEY_ROOT_FOLDER_ID = "drive_root_folder_id"
        const val KEY_CONNECTED = "drive_connected"
        const val KEY_DRIVE_ACCOUNT = "drive_account"
        const val KEY_LAST_MSG = "last_sync_message"
    }
}
