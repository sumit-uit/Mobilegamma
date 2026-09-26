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

    var dailySyncEnabled: Boolean
        get() = prefs.getBoolean(KEY_DAILY, false)
        set(value) = prefs.edit { putBoolean(KEY_DAILY, value) }

    /** When true, only photos the user has explicitly approved are uploaded. */
    var requireApproval: Boolean
        get() = prefs.getBoolean(KEY_APPROVAL, false)
        set(value) = prefs.edit { putBoolean(KEY_APPROVAL, value) }

    /** How far back the very first scan looks, in days. */
    var firstScanDays: Int
        get() = prefs.getInt(KEY_FIRST_DAYS, 7)
        set(value) = prefs.edit { putInt(KEY_FIRST_DAYS, value) }

    /** MediaStore DATE_ADDED (seconds) of the newest photo already scanned. */
    var lastScannedAddedSec: Long
        get() = prefs.getLong(KEY_LAST_SCAN, 0L)
        set(value) = prefs.edit { putLong(KEY_LAST_SCAN, value) }

    var driveRootFolderId: String?
        get() = prefs.getString(KEY_ROOT_FOLDER_ID, null)
        set(value) = prefs.edit { putString(KEY_ROOT_FOLDER_ID, value) }

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
        const val KEY_APPROVAL = "require_approval"
        const val KEY_FIRST_DAYS = "first_scan_days"
        const val KEY_LAST_SCAN = "last_scanned_added_sec"
        const val KEY_ROOT_FOLDER_ID = "drive_root_folder_id"
        const val KEY_CONNECTED = "drive_connected"
        const val KEY_LAST_MSG = "last_sync_message"
    }
}
