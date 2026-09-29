package com.mobilegamma.cakesync.edit

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.mobilegamma.cakesync.data.Photo

/** Things the app has made: branded, filtered and cropped copies, white backgrounds, collages and reels. */
object Creations {
    /** MediaStore RELATIVE_PATHs the editor and reel maker save into. */
    const val PHOTOS_PATH = "Pictures/CakeSync/"
    const val VIDEOS_PATH = "Movies/CakeSync/"

    /** Newest first, as [Photo]s so the grid can show them. */
    fun list(context: Context): List<Photo> =
        (query(context, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, PHOTOS_PATH, false) +
            query(context, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, VIDEOS_PATH, true))
            .sortedByDescending { it.takenAtMillis }

    /** Deletes a creation. The app owns these files, so no extra permission is needed. */
    fun delete(context: Context, uri: Uri): Boolean = context.contentResolver.delete(uri, null, null) > 0

    /** Short description of what a file is, from the name the editor gave it. */
    fun kind(fileName: String): String {
        // Android adds " (1)" when a file with the same name already exists.
        val name = fileName.replace(Regex(""" \(\d+\)(?=\.\w+$)"""), "")
        return kindOf(name)
    }

    private fun kindOf(name: String): String = when {
        name.startsWith("CakeSync_reel_") -> "🎬 Reel"
        name.startsWith("CakeSync_collage_") -> "🧩 Collage"
        name.contains("_filter_") -> "🎨 " + name.substringAfter("_filter_").substringBefore('.')
            .replaceFirstChar { it.uppercase() } + " filter"
        name.endsWith("_branded.jpg") -> "🏷 Branded"
        name.endsWith("_white.jpg") -> "✂ White background"
        name.contains("_1080x1080") -> "▢ 1:1 crop"
        name.contains("_1080x1350") -> "▢ 4:5 crop"
        name.contains("_1080x1920") -> "▢ 9:16 crop"
        else -> "Created"
    }

    private fun query(context: Context, collection: Uri, path: String, video: Boolean): List<Photo> {
        val projection = buildList {
            add(MediaStore.MediaColumns._ID)
            add(MediaStore.MediaColumns.DISPLAY_NAME)
            add(MediaStore.MediaColumns.MIME_TYPE)
            add(MediaStore.MediaColumns.DATE_ADDED)
            if (video) add(MediaStore.MediaColumns.DURATION)
        }.toTypedArray()
        val items = mutableListOf<Photo>()
        context.contentResolver.query(
            collection, projection, "${MediaStore.MediaColumns.RELATIVE_PATH} = ?", arrayOf(path),
            "${MediaStore.MediaColumns.DATE_ADDED} DESC",
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val mimeCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val addedCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
            val durCol = if (video) c.getColumnIndexOrThrow(MediaStore.MediaColumns.DURATION) else -1
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val name = c.getString(nameCol) ?: "created_$id"
                items += Photo(
                    mediaId = id,
                    uri = ContentUris.withAppendedId(collection, id),
                    displayName = name,
                    mimeType = c.getString(mimeCol) ?: if (video) "video/mp4" else "image/jpeg",
                    takenAtMillis = c.getLong(addedCol) * 1000,
                    labels = kind(name),
                    score = 0f,
                    isMatch = false,
                    override = true,
                    uploadedAtMillis = null,
                    faces = 0,
                    isVideo = video,
                    durationMs = if (durCol >= 0 && !c.isNull(durCol)) c.getLong(durCol) else null,
                )
            }
        }
        return items
    }
}
