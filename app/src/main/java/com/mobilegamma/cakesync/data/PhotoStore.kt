package com.mobilegamma.cakesync.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri

/** A photo the app has scanned, with its classification and upload state. */
data class Photo(
    val mediaId: Long,
    val uri: Uri,
    val displayName: String,
    val mimeType: String,
    val takenAtMillis: Long,
    val labels: String,
    val score: Float,
    val isMatch: Boolean,
    /** User decision: null = follow the classifier, true = include, false = exclude. */
    val override: Boolean?,
    val uploadedAtMillis: Long?,
    /** Faces found by on-device face detection; null = not checked yet. */
    val faces: Int? = null,
) {
    val hasPeople: Boolean get() = (faces ?: 0) > 0
    val uploaded: Boolean get() = uploadedAtMillis != null

    /** Whether this photo will be uploaded; the user's choice always wins. */
    fun included(excludePeople: Boolean): Boolean =
        override ?: (isMatch && !(excludePeople && hasPeople))
}

/** Local record of scanned photos, so each photo is classified and uploaded only once. */
class PhotoStore private constructor(context: Context) :
    SQLiteOpenHelper(context, "photos.db", null, 2) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE photos (
                media_id INTEGER PRIMARY KEY,
                uri TEXT NOT NULL,
                display_name TEXT NOT NULL,
                mime_type TEXT NOT NULL,
                taken_at INTEGER NOT NULL,
                labels TEXT NOT NULL,
                score REAL NOT NULL,
                is_match INTEGER NOT NULL,
                override INTEGER,
                uploaded_at INTEGER,
                drive_file_id TEXT,
                faces INTEGER
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) db.execSQL("ALTER TABLE photos ADD COLUMN faces INTEGER")
    }

    fun contains(mediaId: Long): Boolean =
        readableDatabase.rawQuery("SELECT 1 FROM photos WHERE media_id = ?", arrayOf(mediaId.toString()))
            .use { it.moveToFirst() }

    fun knownIds(): Set<Long> =
        readableDatabase.rawQuery("SELECT media_id FROM photos", null).use { c ->
            buildSet { while (c.moveToNext()) add(c.getLong(0)) }
        }

    fun insert(photo: Photo) {
        val values = ContentValues().apply {
            put("media_id", photo.mediaId)
            put("uri", photo.uri.toString())
            put("display_name", photo.displayName)
            put("mime_type", photo.mimeType)
            put("taken_at", photo.takenAtMillis)
            put("labels", photo.labels)
            put("score", photo.score)
            put("is_match", if (photo.isMatch) 1 else 0)
            photo.faces?.let { put("faces", it) }
        }
        writableDatabase.insertWithOnConflict("photos", null, values, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun setFaces(mediaId: Long, faces: Int) {
        val values = ContentValues().apply { put("faces", faces) }
        writableDatabase.update("photos", values, "media_id = ?", arrayOf(mediaId.toString()))
    }

    /** Photos scanned before face detection existed. */
    fun missingFaceCheck(): List<Photo> = query("WHERE faces IS NULL")

    fun setOverride(mediaId: Long, include: Boolean?) {
        val values = ContentValues().apply {
            if (include == null) putNull("override") else put("override", if (include) 1 else 0)
        }
        writableDatabase.update("photos", values, "media_id = ?", arrayOf(mediaId.toString()))
    }

    /**
     * Re-applies new label/threshold settings to already-scanned photos using their stored
     * labels (the top 5 labels ≥30% confidence), so settings changes don't need a rescan.
     */
    fun reclassify(targets: Set<String>, threshold: Float) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (photo in all(limit = Int.MAX_VALUE)) {
                val score = photo.labels.split(", ")
                    .mapNotNull { entry ->
                        val cut = entry.lastIndexOf(' ')
                        if (cut <= 0) return@mapNotNull null
                        val name = entry.substring(0, cut).lowercase()
                        val pct = entry.substring(cut + 1).removeSuffix("%").toIntOrNull()
                        if (name in targets && pct != null) pct / 100f else null
                    }
                    .maxOrNull() ?: 0f
                val values = ContentValues().apply {
                    put("score", score)
                    put("is_match", if (score >= threshold) 1 else 0)
                }
                db.update("photos", values, "media_id = ?", arrayOf(photo.mediaId.toString()))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun markUploaded(mediaId: Long, driveFileId: String) {
        val values = ContentValues().apply {
            put("uploaded_at", System.currentTimeMillis())
            put("drive_file_id", driveFileId)
        }
        writableDatabase.update("photos", values, "media_id = ?", arrayOf(mediaId.toString()))
    }

    /** Photos the classifier matched or the user included, newest first. */
    fun matches(): List<Photo> =
        query("WHERE (override = 1) OR (override IS NULL AND is_match = 1) ORDER BY taken_at DESC")

    /** Every scanned photo, newest first (for fixing missed detections). */
    fun all(limit: Int = 500): List<Photo> = query("ORDER BY taken_at DESC LIMIT $limit")

    /**
     * Photos waiting to be uploaded. With [excludePeople], automatic matches must have been
     * face-checked and contain no faces; photos the user explicitly included always go.
     */
    fun pendingUploads(requireApproval: Boolean, excludePeople: Boolean): List<Photo> {
        val auto = if (excludePeople) "is_match = 1 AND faces = 0" else "is_match = 1"
        val include = if (requireApproval) "override = 1" else
            "(override = 1 OR (override IS NULL AND $auto))"
        return query("WHERE uploaded_at IS NULL AND $include ORDER BY taken_at ASC")
    }

    private fun query(clause: String): List<Photo> =
        readableDatabase.rawQuery("SELECT * FROM photos $clause", null).use { c ->
            buildList { while (c.moveToNext()) add(c.toPhoto()) }
        }

    private fun Cursor.toPhoto(): Photo {
        val overrideIdx = getColumnIndexOrThrow("override")
        val uploadedIdx = getColumnIndexOrThrow("uploaded_at")
        val facesIdx = getColumnIndexOrThrow("faces")
        return Photo(
            mediaId = getLong(getColumnIndexOrThrow("media_id")),
            uri = Uri.parse(getString(getColumnIndexOrThrow("uri"))),
            displayName = getString(getColumnIndexOrThrow("display_name")),
            mimeType = getString(getColumnIndexOrThrow("mime_type")),
            takenAtMillis = getLong(getColumnIndexOrThrow("taken_at")),
            labels = getString(getColumnIndexOrThrow("labels")),
            score = getFloat(getColumnIndexOrThrow("score")),
            isMatch = getInt(getColumnIndexOrThrow("is_match")) == 1,
            override = if (isNull(overrideIdx)) null else getInt(overrideIdx) == 1,
            uploadedAtMillis = if (isNull(uploadedIdx)) null else getLong(uploadedIdx),
            faces = if (isNull(facesIdx)) null else getInt(facesIdx),
        )
    }

    companion object {
        @Volatile private var instance: PhotoStore? = null

        fun get(context: Context): PhotoStore =
            instance ?: synchronized(this) {
                instance ?: PhotoStore(context.applicationContext).also { instance = it }
            }
    }
}
