package com.mobilegamma.cakesync.scan

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import com.google.mlkit.vision.common.InputImage
import android.net.Uri
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.label.ImageLabeler
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
 * Finds photos not scanned yet, labels them and counts faces on-device with ML Kit.
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
            // Used to skip photos with people in them (on-device, nothing is uploaded).
            val faceDetector = FaceDetection.getClient(
                FaceDetectorOptions.Builder()
                    .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                    .setMinFaceSize(0.05f)
                    .build()
            )
            // Photos scanned by older versions haven't been face-checked yet.
            val backfill = store.missingFaceCheck()
            val total = candidates.size + backfill.size
            var scanned = 0
            var matched = 0
            try {
                candidates.forEachIndexed { index, item ->
                    onProgress(index, total)
                    val result = analyze(labeler, faceDetector, item.uri) ?: return@forEachIndexed
                    val score = result.labels.filter { it.first.lowercase() in targets }
                        .maxOfOrNull { it.second } ?: 0f
                    val isMatch = score >= threshold
                    store.insert(
                        Photo(
                            mediaId = item.id,
                            uri = item.uri,
                            displayName = item.name,
                            mimeType = item.mimeType,
                            takenAtMillis = item.takenAtMillis,
                            labels = result.labels.take(5).joinToString { "${it.first} ${(it.second * 100).toInt()}%" },
                            score = score,
                            isMatch = isMatch,
                            override = null,
                            uploadedAtMillis = null,
                            faces = result.faces,
                        )
                    )
                    scanned++
                    if (isMatch) matched++
                }
                backfill.forEachIndexed { index, photo ->
                    onProgress(candidates.size + index, total)
                    val faces = countFaces(faceDetector, photo.uri) ?: return@forEachIndexed
                    store.setFaces(photo.mediaId, faces)
                }
                onProgress(total, total)
            } finally {
                labeler.close()
                faceDetector.close()
            }
            Result(scanned, matched)
        }

    private class Analysis(val labels: List<Pair<String, Float>>, val faces: Int?)

    private suspend fun analyze(labeler: ImageLabeler, faceDetector: FaceDetector, uri: Uri): Analysis? = try {
        val image = thumbnail(uri)
        val labels = labeler.process(image).await()
            .map { it.text to it.confidence }
            .sortedByDescending { it.second }
        val faces = runCatching { faceDetector.process(image).await().size }
            .onFailure { Log.w(TAG, "Face detection failed for $uri", it) }
            .getOrNull()
        Analysis(labels, faces)
    } catch (e: Exception) {
        Log.w(TAG, "Could not analyse $uri", e)
        null
    }

    private suspend fun countFaces(faceDetector: FaceDetector, uri: Uri): Int? = try {
        faceDetector.process(thumbnail(uri)).await().size
    } catch (e: Exception) {
        Log.w(TAG, "Could not face-check $uri", e)
        null
    }

    // A thumbnail is plenty for labelling and faces, and far faster than the full image.
    private fun thumbnail(uri: Uri): InputImage =
        InputImage.fromBitmap(context.contentResolver.loadThumbnail(uri, Size(768, 768), null), 0)

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
