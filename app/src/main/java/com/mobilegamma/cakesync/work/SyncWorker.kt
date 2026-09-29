package com.mobilegamma.cakesync.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import android.content.pm.ServiceInfo
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.mobilegamma.cakesync.R
import com.mobilegamma.cakesync.data.PhotoStore
import com.mobilegamma.cakesync.data.Settings
import com.mobilegamma.cakesync.drive.DriveAuth
import com.mobilegamma.cakesync.drive.DriveClient
import com.mobilegamma.cakesync.drive.DriveException
import com.mobilegamma.cakesync.scan.PhotoScanner
import com.mobilegamma.cakesync.ui.MainActivity
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Daily job: scan new photos and videos, then upload every match that is not yet in Drive
 * into `<root folder>/<yyyy-MM-dd>/` (the day it was taken).
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val settings = Settings(context)
    private val store = PhotoStore.get(context)

    override suspend fun doWork(): Result {
        if (!hasPhotoPermission(applicationContext)) {
            return finish("Photo access not granted", success = false)
        }

        PhotoScanner(applicationContext).scanNew()

        val pending = store.pendingUploads(settings.requireApproval, settings.excludePeople)
        if (pending.isEmpty()) return finish("Nothing new to upload", success = true, notify = false)

        val token = when (val auth = runCatching { DriveAuth(applicationContext).authorize() }.getOrNull()) {
            is DriveAuth.Outcome.Token -> auth.accessToken
            else -> {
                settings.driveConnected = false
                return finish("Reconnect Google Drive to upload ${pending.size} file(s)", success = false)
            }
        }

        // Videos can take longer than a normal background job may run, so show an ongoing
        // notification and run as a foreground job. Android can refuse this while the app is
        // in the background; the upload then still runs, just without that protection.
        runCatching { setForeground(progressInfo("Uploading ${pending.size} file(s) to Drive…")) }
            .onFailure { Log.w(TAG, "Could not run as foreground work", it) }

        val drive = DriveClient(token)
        val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val resolver = applicationContext.contentResolver
        var uploaded = 0
        try {
            val rootId = ensureRootFolder(drive)
            val dayFolders = mutableMapOf<String, String>()
            for ((index, photo) in pending.withIndex()) {
                val size = runCatching { resolver.openAssetFileDescriptor(photo.uri, "r")?.use { it.length } }
                    .getOrNull()
                if (size == null) {
                    Log.w(TAG, "No longer readable: ${photo.uri}")
                    continue
                }
                runCatching { setForeground(progressInfo("Uploading ${index + 1} of ${pending.size}: ${photo.displayName}")) }
                val day = dayFormat.format(Date(photo.takenAtMillis))
                val folderId = dayFolders.getOrPut(day) { drive.findOrCreateFolder(day, rootId) }
                val fileId = drive.uploadFile(photo.displayName, photo.mimeType, folderId, size) {
                    resolver.openInputStream(photo.uri) ?: throw IOException("Cannot open ${photo.uri}")
                }
                store.markUploaded(photo.mediaId, fileId)
                uploaded++
            }
        } catch (e: DriveException) {
            Log.e(TAG, "Drive upload failed", e)
            if (e.code == 401 || e.code == 403) settings.driveConnected = false
            return if (e.code >= 500 || e.code == 429) retryWith("Drive busy, will retry ($uploaded uploaded)")
            else finish("Upload failed after $uploaded file(s): HTTP ${e.code}", success = false)
        } catch (e: IOException) {
            Log.e(TAG, "Network error", e)
            return retryWith("Network error, will retry ($uploaded uploaded)")
        }

        return finish("Uploaded $uploaded file(s) to Drive", success = true)
    }

    private suspend fun ensureRootFolder(drive: DriveClient): String {
        settings.driveRootFolderId?.let { id -> if (drive.folderExists(id)) return id }
        return drive.findOrCreateFolder(settings.driveFolderName, parentId = null)
            .also { settings.driveRootFolderId = it }
    }

    private fun retryWith(message: String): Result {
        settings.lastSyncMessage = "${timestamp()} · $message"
        return Result.retry()
    }

    private fun finish(message: String, success: Boolean, notify: Boolean = true): Result {
        settings.lastSyncMessage = "${timestamp()} · $message"
        if (notify) notify(message)
        return if (success) Result.success() else Result.failure()
    }

    private fun timestamp() = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date())

    private fun notify(message: String) {
        val ctx = applicationContext
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        val manager = ctx.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Photo sync", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("CakeSync")
            .setContentText(message)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun progressInfo(text: String): ForegroundInfo {
        val ctx = applicationContext
        val manager = ctx.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(PROGRESS_CHANNEL_ID, "Upload progress", NotificationManager.IMPORTANCE_LOW)
        )
        val notification = NotificationCompat.Builder(ctx, PROGRESS_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("CakeSync")
            .setContentText(text)
            .setOngoing(true)
            .setProgress(0, 0, true)
            .build()
        return if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(PROGRESS_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(PROGRESS_NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val TAG = "SyncWorker"
        private const val PROGRESS_CHANNEL_ID = "upload_progress"
        private const val PROGRESS_NOTIFICATION_ID = 2
        private const val CHANNEL_ID = "sync"
        private const val NOTIFICATION_ID = 1

        /** Full access to videos; without it Android shows the app no (or only picked) videos. */
        fun hasVideoPermission(context: Context): Boolean {
            val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO
                else Manifest.permission.READ_EXTERNAL_STORAGE
            return ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
        }

        fun hasPhotoPermission(context: Context): Boolean {
            val perms = if (Build.VERSION.SDK_INT >= 33) {
                listOf(
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO,
                    Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
                )
            } else {
                listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            return perms.any {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }
        }
    }
}
