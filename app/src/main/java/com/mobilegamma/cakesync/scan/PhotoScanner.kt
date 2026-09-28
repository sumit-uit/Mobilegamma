package com.mobilegamma.cakesync.scan

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.mobilegamma.cakesync.data.Photo
import com.mobilegamma.cakesync.data.PhotoStore
import com.mobilegamma.cakesync.data.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Finds photos added since the last scan and labels them on-device with ML Kit.
 * Nothing leaves the phone during scanning.
 */
class PhotoScanner(private val context: Context) {

    data class Result(val scanned: Int, val matched: Int)

    /** A photo folder on the device, e.g. "DCIM/Camera/", with its image count. */
    data class Folder(val path: String, val count: Int)

    private val store = PhotoStore.get(context)
    private val settings = Settings(context)

    suspend fun scanNew(onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): Result =
        withContext(Dispatchers.IO) {
            val targets = settings.labelSet()
            val threshold = settings.threshold
            val days = settings.scanDays
            val since = if (days <= 0) 0L
                else System.currentTimeMillis() / 1000 - TimeUnit.DAYS.toSeconds(days.toLong())

            // Everything in the chosen folders and time window that hasn't been labelled yet.
            // Checking against known ids (not a "last scanned" marker) means newly added
            // folders or a longer window also pick up older photos.
            val known = store.knownIds()
            val candidates = queryImages(since, settings.scanFolders).filter { it.id !in known }
            val labeler = ImageLabeling.getClient(
                ImageLabelerOptions.Builder().setConfidenceThreshold(0.3f).build()
            )
            var scanned = 0
            var matched = 0
            try {
                candidates.forEachIndexed { index, item ->
                    onProgress(index, candidates.size)
                    run {
                        val labels = labelImage(labeler, item)
                        if (labels != null) {
                            val score = labels.filter { it.first.lowercase() in targets }
                                .maxOfOrNull { it.second } ?: 0f
                            val isMatch = score >= threshold
                            store.insert(
                                Photo(
                                    mediaId = item.id,
                                    uri = item.uri,
                                    displayName = item.name,
                                    mimeType = item.mimeType,
                                    takenAtMillis = item.takenAtMillis,
                                    labels = labels.take(5).joinToString { "${it.first} ${(it.second * 100).toInt()}%" },
                                    score = score,
                                    isMatch = isMatch,
                                    override = null,
                                    uploadedAtMillis = null,
                                )
                            )
                            scanned++
                            if (isMatch) matched++
                        }
                    }
                }
                onProgress(candidates.size, candidates.size)
            } finally {
                labeler.close()
            }
            Result(scanned, matched)
        }

    private suspend fun labelImage(
        labeler: com.google.mlkit.vision.label.ImageLabeler,
        item: MediaItem,
    ): List<Pair<String, Float>>? = try {
        // A small thumbnail is plenty for labelling and far faster than the full image.
        val bitmap = context.contentResolver.loadThumbnail(item.uri, Size(512, 512), null)
        val labels = labeler.process(InputImage.fromBitmap(bitmap, 0)).await()
        labels.map { it.text to it.confidence }.sortedByDescending { it.second }
    } catch (e: Exception) {
        Log.w(TAG, "Could not label ${item.uri}", e)
        null
    }

    private data class MediaItem(
        val id: Long,
        val uri: android.net.Uri,
        val name: String,
        val mimeType: String,
        val takenAtMillis: Long,
        val addedSec: Long,
    )

    /** All folders that contain photos, largest first. */
    suspend fun listFolders(): List<Folder> = withContext(Dispatchers.IO) {
        val counts = mutableMapOf<String, Int>()
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media.RELATIVE_PATH),
            null, null, null,
        )?.use { c ->
            val col = c.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)
            while (c.moveToNext()) {
                val path = c.getString(col) ?: continue
                counts[path] = (counts[path] ?: 0) + 1
            }
        }
        counts.map { Folder(it.key, it.value) }.sortedByDescending { it.count }
    }

    private fun queryImages(sinceSec: Long, folders: Set<String>): List<MediaItem> {
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_ADDED,
        )
        val items = mutableListOf<MediaItem>()
        var selection = "${MediaStore.Images.Media.DATE_ADDED} > ?"
        val args = mutableListOf(sinceSec.toString())
        if (folders.isNotEmpty()) {
            selection += " AND ${MediaStore.Images.Media.RELATIVE_PATH} IN (${folders.joinToString { "?" }})"
            args += folders
        }
        context.contentResolver.query(
            collection,
            projection,
            selection,
            args.toTypedArray(),
            "${MediaStore.Images.Media.DATE_ADDED} ASC",
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val mimeCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
            val takenCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            val addedCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val added = c.getLong(addedCol)
                items += MediaItem(
                    id = id,
                    uri = ContentUris.withAppendedId(collection, id),
                    name = c.getString(nameCol) ?: "photo_$id.jpg",
                    mimeType = c.getString(mimeCol) ?: "image/jpeg",
                    takenAtMillis = if (c.isNull(takenCol) || c.getLong(takenCol) == 0L) added * 1000 else c.getLong(takenCol),
                    addedSec = added,
                )
            }
        }
        return items
    }

    private companion object {
        const val TAG = "PhotoScanner"
    }
}
