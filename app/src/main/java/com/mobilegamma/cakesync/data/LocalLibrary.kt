package com.mobilegamma.cakesync.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Copies included cakes into the gallery so users without Google Drive still get
 * one place for everything: `Pictures/<folder>/<yyyy-MM-dd>/` for photos,
 * `Movies/<folder>/<yyyy-MM-dd>/` for videos (Scoped Storage, no extra permission
 * on minSdk 29+; the app owns what it creates).
 *
 * Copies are additive only: existing files are never overwritten or deleted, and
 * the scanner excludes these output folders so copies are never re-scanned.
 */
class LocalLibrary(private val context: Context) {

    data class Result(val organized: Int, val skipped: Int)

    /** Copies every photo in [photos] that has no local copy yet. */
    suspend fun organize(photos: List<Photo>, folderName: String): Result =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val store = PhotoStore.get(context)
            var organized = 0
            var skipped = 0
            for (photo in photos) {
                // Skip if already organized (e.g. raced with another run).
                if (photo.organized) {
                    skipped++
                    continue
                }
                val localUri = runCatching { copyToGallery(photo, folderName) }
                    .onFailure { Log.w(TAG, "Could not organize ${photo.uri}", it) }
                    .getOrNull()
                if (localUri == null) {
                    skipped++
                    continue
                }
                store.markOrganized(photo.mediaId, localUri)
                organized++
            }
            Result(organized, skipped)
        }

    private fun copyToGallery(photo: Photo, folderName: String): Uri {
        val resolver = context.contentResolver
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(photo.takenAtMillis))
        val collection = if (photo.isVideo) {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val baseDir = if (photo.isVideo) {
            Environment.DIRECTORY_MOVIES
        } else {
            Environment.DIRECTORY_PICTURES
        }
        val relativePath = "$baseDir/$folderName/$day/"
        val pending = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, uniqueName(photo.displayName))
            put(MediaStore.MediaColumns.MIME_TYPE, photo.mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.DATE_TAKEN, photo.takenAtMillis)
            if (Build.VERSION.SDK_INT >= 29) put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val destUri = resolver.insert(collection, pending)
            ?: throw IllegalStateException("MediaStore refused the local copy")
        try {
            resolver.openInputStream(photo.uri)?.use { input ->
                resolver.openOutputStream(destUri)?.use { output ->
                    input.copyTo(output)
                } ?: throw IllegalStateException("Cannot open gallery destination")
            } ?: throw IllegalStateException("Cannot open ${photo.uri}")
        } catch (e: Exception) {
            runCatching { resolver.delete(destUri, null, null) }
            throw e
        }
        if (Build.VERSION.SDK_INT >= 29) {
            resolver.update(
                destUri, ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }, null, null
            )
        }
        return destUri
    }

    /**
     * MediaStore allows duplicate display names; prefixing with the taken date keeps
     * the folder readable and avoids one cake silently replacing another on MTP.
     */
    private fun uniqueName(displayName: String): String {
        val safe = displayName.ifBlank { "cake.jpg" }.take(100)
        return safe
    }

    private companion object {
        const val TAG = "LocalLibrary"
    }
}
