package com.mobilegamma.cakesync.scan

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import com.google.mlkit.vision.common.InputImage
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
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
import com.mobilegamma.cakesync.data.Categories
import com.mobilegamma.cakesync.edit.Creations
import com.mobilegamma.cakesync.data.Photo
import com.mobilegamma.cakesync.data.PhotoStore
import com.mobilegamma.cakesync.data.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Finds photos and videos not scanned yet, labels them and counts faces on-device with ML Kit.
 * Nothing leaves the phone during scanning.
 */
class PhotoScanner(private val context: Context) {

    data class Result(
        val scanned: Int,
        val matched: Int,
        /** Diagnostics: videos Android lets the app see, in total and in the chosen period/folders. */
        val videosVisible: Int = 0,
        val videosInScope: Int = 0,
        val videosNew: Int = 0,
        /** Items that could not be read or analysed (kept, visible under All scanned). */
        val failed: Int = 0,
        /** Near-identical shots set aside in favour of a sharper one. */
        val duplicates: Int = 0,
    ) {
        fun summary(): String = buildString {
            append("Scanned $scanned new item(s), $matched match(es)")
            append(" · videos visible: $videosVisible, in period/folders: $videosInScope, new: $videosNew")
            if (duplicates > 0) append(" · $duplicates near-duplicate shot(s) set aside")
            if (failed > 0) append(" · $failed could not be read (see All scanned)")
        }
    }

    /** A photo folder on the device, e.g. "DCIM/Camera/", with its image count. */
    data class Folder(val path: String, val count: Int)

    private val store = PhotoStore.get(context)
    private val settings = Settings(context)

    suspend fun scanNew(onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): Result =
        withContext(Dispatchers.IO) {
            val categoryStore = Categories(context)
            val categories = categoryStore.all()
            val days = settings.scanDays
            val since = if (days <= 0) 0L
                else System.currentTimeMillis() / 1000 - TimeUnit.DAYS.toSeconds(days.toLong())

            // Everything in the chosen folders and time window that hasn't been labelled yet.
            // Checking against known ids (not a "last scanned" marker) means newly added
            // folders or a longer window also pick up older photos.
            val known = store.knownIds()
            val videosInScope = if (settings.includeVideos) queryMedia(VIDEOS, since, settings.scanFolders) else emptyList()
            val candidates = (queryMedia(IMAGES, since, settings.scanFolders) + videosInScope)
                .filter { it.id !in known }
            val videosVisible = if (settings.includeVideos) queryMedia(VIDEOS, 0L, emptySet()).size else 0
            Log.i(TAG, "videos visible=$videosVisible inScope=${videosInScope.size} folders=${settings.scanFolders}")
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
            // Photos scanned before best-shot picking existed haven't been measured yet.
            val qualityBackfill = store.missingQuality().filter { it.mediaId !in candidates.map { c -> c.id }.toSet() }
            val total = candidates.size + backfill.size + qualityBackfill.size
            var scanned = 0
            var matched = 0
            var failed = 0
            try {
                candidates.forEachIndexed { index, item ->
                    onProgress(index, total)
                    val result = analyze(labeler, faceDetector, objectDetector, item.uri, item.isVideo)
                    if (result == null) {
                        // Keep it (not matched) so it shows under All scanned and can be included by hand.
                        failed++
                        store.insert(
                            Photo(
                                mediaId = item.id, uri = item.uri, displayName = item.name, mimeType = item.mimeType,
                                takenAtMillis = item.takenAtMillis, labels = "⚠ could not analyse", score = 0f,
                                isMatch = false, override = null, uploadedAtMillis = null, faces = 0,
                                isVideo = item.isVideo, durationMs = item.durationMs,
                            )
                        )
                        return@forEachIndexed
                    }
                    val classification = categoryStore.classify(result.labels, categories)
                    val isMatch = classification.isMatch
                    store.insert(
                        Photo(
                            mediaId = item.id,
                            uri = item.uri,
                            displayName = item.name,
                            mimeType = item.mimeType,
                            takenAtMillis = item.takenAtMillis,
                            // Top 10 labels are kept so categories can be changed later without a rescan.
                            labels = result.labels.take(10).joinToString { "${it.first} ${(it.second * 100).toInt()}%" },
                            score = classification.score,
                            isMatch = isMatch,
                            category = classification.category?.id,
                            override = null,
                            uploadedAtMillis = null,
                            faces = result.faces,
                            isVideo = item.isVideo,
                            durationMs = item.durationMs,
                        )
                    )
                    if (result.sharpness != null && result.hash != null) {
                        store.setQuality(item.id, result.sharpness, result.hash)
                    }
                    scanned++
                    if (isMatch) matched++
                }
                backfill.forEachIndexed { index, photo ->
                    onProgress(candidates.size + index, total)
                    val faces = countPeople(faceDetector, objectDetector, photo.uri, photo.isVideo)
                        ?: return@forEachIndexed
                    store.setFaces(photo.mediaId, faces)
                }
                qualityBackfill.forEachIndexed { index, photo ->
                    onProgress(candidates.size + backfill.size + index, total)
                    runCatching { frames(photo.uri, false).first() }
                        .onSuccess { store.setQuality(photo.mediaId, Quality.sharpness(it), Quality.dHash(it)) }
                        .onFailure { Log.w(TAG, "Could not measure ${photo.uri}", it) }
                }
                onProgress(total, total)
            } finally {
                labeler.close()
                faceDetector.close()
                objectDetector.close()
            }
            Result(
                scanned = scanned,
                matched = matched,
                videosVisible = videosVisible,
                videosInScope = videosInScope.size,
                videosNew = candidates.count { it.isVideo },
                failed = failed,
                duplicates = store.markDuplicates(),
            )
        }

    private class Analysis(
        val labels: List<Pair<String, Float>>,
        val faces: Int?,
        /** Photos only: sharpness and difference hash for best-shot picking. */
        val sharpness: Double? = null,
        val hash: Long? = null,
    )

    /**
     * Labels and face-checks a photo, or several frames of a video. For a video the
     * strongest label scores and the most people seen in any frame are used.
     */
    private suspend fun analyze(
        labeler: ImageLabeler,
        faceDetector: FaceDetector,
        objectDetector: ObjectDetector,
        uri: Uri,
        isVideo: Boolean,
    ): Analysis? = try {
        val frames = frames(uri, isVideo)
        if (frames.isEmpty()) throw IllegalStateException("no frames")
        val best = mutableMapOf<String, Float>()
        var people: Int? = null
        for (frame in frames) {
            labeler.process(InputImage.fromBitmap(frame, 0)).await().forEach {
                best[it.text] = maxOf(best[it.text] ?: 0f, it.confidence)
            }
            runCatching { peopleIn(frame, faceDetector, objectDetector) }
                .onSuccess { people = maxOf(people ?: 0, it) }
                .onFailure { Log.w(TAG, "Face detection failed for $uri", it) }
        }
        val photo = if (isVideo) null else frames.first()
        Analysis(
            labels = best.toList().sortedByDescending { it.second },
            faces = people,
            sharpness = photo?.let { runCatching { Quality.sharpness(it) }.getOrNull() },
            hash = photo?.let { runCatching { Quality.dHash(it) }.getOrNull() },
        )
    } catch (e: Exception) {
        Log.w(TAG, "Could not analyse $uri", e)
        null
    }

    private suspend fun countPeople(
        faceDetector: FaceDetector,
        objectDetector: ObjectDetector,
        uri: Uri,
        isVideo: Boolean,
    ): Int? = try {
        frames(uri, isVideo).maxOfOrNull { peopleIn(it, faceDetector, objectDetector) }
    } catch (e: Exception) {
        Log.w(TAG, "Could not face-check $uri", e)
        null
    }

    /**
     * Counts faces of real people. A face whose centre lies inside an object the detector
     * classifies as Food is treated as part of the cake (a topper) and ignored. This only
     * catches some toppers; others are skipped like people, and the user can tap the photo
     * in the review grid to include it.
     */
    private suspend fun peopleIn(bitmap: Bitmap, faceDetector: FaceDetector, objectDetector: ObjectDetector): Int {
        val image = InputImage.fromBitmap(bitmap, 0)
        val faces = faceDetector.process(image).await()
        if (faces.isEmpty()) return 0
        val foodAreas = runCatching { objectDetector.process(image).await() }
            .getOrDefault(emptyList())
            .filter { obj -> obj.labels.any { it.text.equals("Food", ignoreCase = true) } }
            .map { it.boundingBox }
        return faces.count { face ->
            val box = face.boundingBox
            foodAreas.none { it.contains(box.centerX(), box.centerY()) }
        }
    }

    /**
     * Images to analyse: a thumbnail for a photo (plenty for labelling and faces, and far
     * faster than the full image), or frames at 10%, 50% and 90% of a video.
     */
    private fun frames(uri: Uri, isVideo: Boolean): List<Bitmap> {
        if (!isVideo) return listOf(context.contentResolver.loadThumbnail(uri, Size(768, 768), null))
        val fromRetriever = runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                val durationUs = (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L) * 1000
                listOf(0.1, 0.5, 0.9).mapNotNull { at ->
                    runCatching {
                        retriever.getScaledFrameAtTime(
                            (durationUs * at).toLong(), MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 768, 768,
                        )
                    }.getOrNull()
                }
            } finally {
                retriever.release()
            }
        }.onFailure { Log.w(TAG, "Frame extraction failed for $uri, using thumbnail", it) }
            .getOrDefault(emptyList())
        // Some videos can't be decoded here or report no duration; the system thumbnail still works.
        return fromRetriever.ifEmpty { listOf(context.contentResolver.loadThumbnail(uri, Size(768, 768), null)) }
    }

    private data class MediaItem(
        val id: Long,
        val uri: Uri,
        val name: String,
        val mimeType: String,
        val takenAtMillis: Long,
        val isVideo: Boolean,
        val durationMs: Long?,
    )

    /** All folders that contain photos or videos, largest first. */
    suspend fun listFolders(): List<Folder> = withContext(Dispatchers.IO) {
        val counts = mutableMapOf<String, Int>()
        val excluded = settings.localOutputPrefixes()
        val collections = if (settings.includeVideos) listOf(IMAGES, VIDEOS) else listOf(IMAGES)
        for (collection in collections) {
            context.contentResolver.query(collection, arrayOf(MediaStore.MediaColumns.RELATIVE_PATH), null, null, null)
                ?.use { c ->
                    val col = c.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
                    while (c.moveToNext()) {
                        val path = c.getString(col) ?: continue
                        if (path == Creations.PHOTOS_PATH || path == Creations.VIDEOS_PATH) continue
                        // Never offer our own gallery copies as a folder to scan.
                        if (excluded.any { path.startsWith(it) }) continue
                        counts[path] = (counts[path] ?: 0) + 1
                    }
                }
        }
        counts.map { Folder(it.key, it.value) }.sortedByDescending { it.count }
    }

    /** Photos ([IMAGES]) or videos ([VIDEOS]) added after [sinceSec] in [folders] (empty = all). */
    private fun queryMedia(collection: Uri, sinceSec: Long, folders: Set<String>): List<MediaItem> {
        val isVideo = collection == VIDEOS
        val projection = buildList {
            add(MediaStore.MediaColumns._ID)
            add(MediaStore.MediaColumns.DISPLAY_NAME)
            add(MediaStore.MediaColumns.MIME_TYPE)
            add(MediaStore.MediaColumns.DATE_TAKEN)
            add(MediaStore.MediaColumns.DATE_ADDED)
            if (isVideo) add(MediaStore.MediaColumns.DURATION)
        }.toTypedArray()
        // The app's own creations (edits, reels) and the local gallery folder are never
        // re-scanned as new photos. Prefix match: copies live in dated subfolders.
        // (Folder names are sanitized to exclude LIKE wildcards, ESCAPE is belt and braces.)
        val skipped = (listOf(Creations.PHOTOS_PATH, Creations.VIDEOS_PATH) + settings.localOutputPrefixes()).distinct()
        var selection = "${MediaStore.MediaColumns.DATE_ADDED} > ? AND " +
            "(${MediaStore.MediaColumns.RELATIVE_PATH} IS NULL OR " +
            skipped.joinToString(" AND ") { "${MediaStore.MediaColumns.RELATIVE_PATH} NOT LIKE ? ESCAPE '\\'" } + ")"
        val args = mutableListOf(sinceSec.toString())
        args += skipped.map { "$it%" }
        if (folders.isNotEmpty()) {
            selection += " AND ${MediaStore.MediaColumns.RELATIVE_PATH} IN (${folders.joinToString { "?" }})"
            args += folders
        }
        val items = mutableListOf<MediaItem>()
        context.contentResolver.query(
            collection, projection, selection, args.toTypedArray(), "${MediaStore.MediaColumns.DATE_ADDED} ASC",
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val mimeCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val takenCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_TAKEN)
            val addedCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
            val durationCol = if (isVideo) c.getColumnIndexOrThrow(MediaStore.MediaColumns.DURATION) else -1
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val added = c.getLong(addedCol)
                items += MediaItem(
                    id = id,
                    uri = ContentUris.withAppendedId(collection, id),
                    name = c.getString(nameCol) ?: if (isVideo) "video_$id.mp4" else "photo_$id.jpg",
                    mimeType = c.getString(mimeCol) ?: if (isVideo) "video/mp4" else "image/jpeg",
                    takenAtMillis = if (c.isNull(takenCol) || c.getLong(takenCol) == 0L) added * 1000 else c.getLong(takenCol),
                    isVideo = isVideo,
                    durationMs = if (durationCol >= 0 && !c.isNull(durationCol)) c.getLong(durationCol) else null,
                )
            }
        }
        return items
    }

    private companion object {
        const val TAG = "PhotoScanner"
        val IMAGES: Uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val VIDEOS: Uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    }
}
