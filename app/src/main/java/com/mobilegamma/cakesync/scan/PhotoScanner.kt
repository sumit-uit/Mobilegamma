package com.mobilegamma.cakesync.scan

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import com.google.mlkit.vision.common.InputImage
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.label.ImageLabeler
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.ObjectDetector
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
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
                    .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                    .setMinFaceSize(0.05f)
                    .build()
            )
            // Finds where food (the cake) is, so faces printed on toppers can be ignored.
            val objectDetector = ObjectDetection.getClient(
                ObjectDetectorOptions.Builder()
                    .setDetectorMode(ObjectDetectorOptions.SINGLE_IMAGE_MODE)
                    .enableMultipleObjects()
                    .enableClassification()
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
                    val result = analyze(labeler, faceDetector, objectDetector, item.uri) ?: return@forEachIndexed
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
                    val faces = countPeople(faceDetector, objectDetector, labeler, photo.uri) ?: return@forEachIndexed
                    store.setFaces(photo.mediaId, faces)
                }
                onProgress(total, total)
            } finally {
                labeler.close()
                faceDetector.close()
                objectDetector.close()
            }
            Result(scanned, matched)
        }

    private class Analysis(val labels: List<Pair<String, Float>>, val faces: Int?)

    private suspend fun analyze(
        labeler: ImageLabeler,
        faceDetector: FaceDetector,
        objectDetector: ObjectDetector,
        uri: Uri,
    ): Analysis? = try {
        val bitmap = thumbnail(uri)
        val labels = labeler.process(InputImage.fromBitmap(bitmap, 0)).await()
            .map { it.text to it.confidence }
            .sortedByDescending { it.second }
        val faces = runCatching { peopleIn(bitmap, faceDetector, objectDetector, labeler) }
            .onFailure { Log.w(TAG, "Face detection failed for $uri", it) }
            .getOrNull()
        Analysis(labels, faces)
    } catch (e: Exception) {
        Log.w(TAG, "Could not analyse $uri", e)
        null
    }

    private suspend fun countPeople(
        faceDetector: FaceDetector,
        objectDetector: ObjectDetector,
        labeler: ImageLabeler,
        uri: Uri,
    ): Int? = try {
        peopleIn(thumbnail(uri), faceDetector, objectDetector, labeler)
    } catch (e: Exception) {
        Log.w(TAG, "Could not face-check $uri", e)
        null
    }

    /**
     * Counts faces of real people. A face that is part of the cake - a printed photo
     * topper, a cartoon character or a figurine - is ignored when either:
     *  - its centre lies inside an object the detector classifies as Food, or
     *  - it is surrounded by cake: patches above, below, left and right of it (far enough
     *    out to be past a printed photo's own background) look like cake on at least three
     *    sides. A real person next to or holding a cake has cake on one side at most.
     */
    private suspend fun peopleIn(
        bitmap: Bitmap,
        faceDetector: FaceDetector,
        objectDetector: ObjectDetector,
        labeler: ImageLabeler,
    ): Int {
        val image = InputImage.fromBitmap(bitmap, 0)
        val faces = faceDetector.process(image).await()
        if (faces.isEmpty()) return 0
        val foodAreas = runCatching { objectDetector.process(image).await() }
            .getOrDefault(emptyList())
            .filter { obj -> obj.labels.any { it.text.equals("Food", ignoreCase = true) } }
            .map { it.boundingBox }
        val cakeWords = settings.labelSet() + CAKE_SURROUNDINGS
        return faces.count { face ->
            val box = face.boundingBox
            val inFood = foodAreas.any { it.contains(box.centerX(), box.centerY()) }
            val sides = if (inFood) emptyList() else cakeSides(bitmap, box, labeler, cakeWords)
            val onCake = inFood || sides.count { it.second } >= 3
            Log.i(TAG, "face $box: inFood=$inFood sides=$sides -> ${if (onCake) "on cake (ignored)" else "person"}")
            !onCake
        }
    }

    /**
     * For each direction (up, down, left, right) labels a patch 1.5 face-sizes wide whose
     * centre is 2.5 face-sizes from the face, and reports whether it looks like cake.
     * Patches mostly outside the photo count as not cake.
     */
    private suspend fun cakeSides(
        bitmap: Bitmap,
        face: Rect,
        labeler: ImageLabeler,
        cakeWords: Set<String>,
    ): List<Pair<String, Boolean>> {
        val size = maxOf(face.width(), face.height())
        val half = (size * 0.75f).toInt()
        val dist = (size * 2.5f).toInt()
        val cx = face.centerX()
        val cy = face.centerY()
        val directions = listOf("up" to (0 to -1), "down" to (0 to 1), "left" to (-1 to 0), "right" to (1 to 0))
        return directions.map { (name, d) ->
            val px = cx + d.first * dist
            val py = cy + d.second * dist
            val patch = Rect(px - half, py - half, px + half, py + half)
            val clipped = Rect(patch)
            val inside = clipped.intersect(0, 0, bitmap.width, bitmap.height) &&
                clipped.width() * clipped.height() >= patch.width() * patch.height() / 2
            val isCake = inside && run {
                val crop = Bitmap.createBitmap(bitmap, clipped.left, clipped.top, clipped.width(), clipped.height())
                val labels = labeler.process(InputImage.fromBitmap(crop, 0)).await()
                labels.any { it.text.lowercase() in cakeWords && it.confidence >= 0.5f }
            }
            name to isCake
        }
    }

    // A thumbnail is plenty for labelling and faces, and far faster than the full image.
    private fun thumbnail(uri: Uri): Bitmap =
        context.contentResolver.loadThumbnail(uri, Size(768, 768), null)

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

        /** Labels that mean "this is cake" when seen around a face. */
        val CAKE_SURROUNDINGS = setOf(
            "cake", "icing", "cupcake", "dessert", "baked goods", "cream", "sweetness", "food", "cuisine",
        )
    }
}
