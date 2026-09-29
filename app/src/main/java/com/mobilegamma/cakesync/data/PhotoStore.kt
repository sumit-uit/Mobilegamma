package com.mobilegamma.cakesync.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri

/** A photo or video the app has scanned, with its classification and upload state. */
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
    val isVideo: Boolean = false,
    val durationMs: Long? = null,
    /** Id of the matched [Category]; null when nothing matched. */
    val category: String? = null,
    /** Customer/order tag, e.g. "Order #123 / Priya"; uploads then go to that order's folder. */
    val orderTag: String? = null,
    /** Sharpness (variance of Laplacian); null = not measured (e.g. videos). */
    val sharpness: Double? = null,
    /** A near-identical shot of a sharper photo taken moments apart. */
    val duplicate: Boolean = false,
) {
    val hasPeople: Boolean get() = (faces ?: 0) > 0
    val uploaded: Boolean get() = uploadedAtMillis != null

    /** Whether this photo will be uploaded; the user's choice always wins. */
    fun included(excludePeople: Boolean, skipDuplicates: Boolean = true): Boolean =
        override ?: (isMatch && !(excludePeople && hasPeople) && !(skipDuplicates && duplicate))
}

/** Local record of scanned photos, so each photo is classified and uploaded only once. */
class PhotoStore private constructor(context: Context) :
    SQLiteOpenHelper(context, "photos.db", null, 9) {

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
                faces INTEGER,
                is_video INTEGER NOT NULL DEFAULT 0,
                duration_ms INTEGER,
                category TEXT,
                category_manual INTEGER NOT NULL DEFAULT 0,
                order_tag TEXT,
                sharpness REAL,
                dhash INTEGER,
                duplicate INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) db.execSQL("ALTER TABLE photos ADD COLUMN faces INTEGER")
        // v3/v4: people and cake-topper checks improved; re-check every photo on the next scan.
        if (oldVersion < 5) db.execSQL("UPDATE photos SET faces = NULL")
        if (oldVersion < 6) {
            db.execSQL("ALTER TABLE photos ADD COLUMN is_video INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE photos ADD COLUMN duration_ms INTEGER")
        }
        if (oldVersion < 7) {
            // Categories: everything matched so far belongs to the first (Cake) category.
            db.execSQL("ALTER TABLE photos ADD COLUMN category TEXT")
            db.execSQL("UPDATE photos SET category = '${Categories.DEFAULT_ID}' WHERE is_match = 1")
        }
        if (oldVersion < 8) {
            db.execSQL("ALTER TABLE photos ADD COLUMN category_manual INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE photos ADD COLUMN order_tag TEXT")
        }
        if (oldVersion < 9) {
            db.execSQL("ALTER TABLE photos ADD COLUMN sharpness REAL")
            db.execSQL("ALTER TABLE photos ADD COLUMN dhash INTEGER")
            db.execSQL("ALTER TABLE photos ADD COLUMN duplicate INTEGER NOT NULL DEFAULT 0")
        }
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
            put("is_video", if (photo.isVideo) 1 else 0)
            photo.durationMs?.let { put("duration_ms", it) }
            photo.category?.let { put("category", it) }
        }
        writableDatabase.insertWithOnConflict("photos", null, values, SQLiteDatabase.CONFLICT_IGNORE)
    }

    /** Include (true), exclude (false) or reset (null) several photos at once. */
    fun setOverride(mediaIds: Collection<Long>, include: Boolean?) = mediaIds.forEach { setOverride(it, include) }

    /** Tags photos with an order/customer name, or clears the tag with null. */
    fun setOrder(mediaIds: Collection<Long>, tag: String?) = updateEach(mediaIds) {
        if (tag == null) putNull("order_tag") else put("order_tag", tag)
    }

    /** Moves photos to a category by hand and includes them; category edits won't undo this. */
    fun setCategoryManually(mediaIds: Collection<Long>, categoryId: String) = updateEach(mediaIds) {
        put("category", categoryId)
        put("category_manual", 1)
        put("override", 1)
    }

    /** Order tags in use, most recent first. */
    fun orderTags(): List<String> =
        readableDatabase.rawQuery(
            "SELECT order_tag, MAX(taken_at) t FROM photos WHERE order_tag IS NOT NULL GROUP BY order_tag ORDER BY t DESC",
            null,
        ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }

    private fun updateEach(mediaIds: Collection<Long>, fill: ContentValues.() -> Unit) {
        val db = writableDatabase
        val values = ContentValues().apply(fill)
        db.beginTransaction()
        try {
            mediaIds.forEach { db.update("photos", values, "media_id = ?", arrayOf(it.toString())) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun setQuality(mediaId: Long, sharpness: Double, dHash: Long) = updateEach(listOf(mediaId)) {
        put("sharpness", sharpness)
        put("dhash", dHash)
    }

    /** Photos (not videos) whose sharpness hasn't been measured yet. */
    fun missingQuality(): List<Photo> = query("WHERE is_video = 0 AND sharpness IS NULL")

    /**
     * Groups matched photos taken within [windowMs] of each other that look nearly the same
     * (difference hash within [maxDistance] bits) and marks all but the sharpest in each
     * group as duplicates.
     */
    fun markDuplicates(windowMs: Long = 90_000, maxDistance: Int = 12): Int {
        data class Shot(val id: Long, val takenAt: Long, val sharpness: Double, val hash: Long)
        val shots = readableDatabase.rawQuery(
            "SELECT media_id, taken_at, sharpness, dhash FROM photos " +
                "WHERE is_video = 0 AND sharpness IS NOT NULL AND dhash IS NOT NULL " +
                "AND (is_match = 1 OR override = 1) ORDER BY taken_at",
            null,
        ).use { c -> buildList { while (c.moveToNext()) add(Shot(c.getLong(0), c.getLong(1), c.getDouble(2), c.getLong(3))) } }

        val duplicates = mutableSetOf<Long>()
        var group = mutableListOf<Shot>()
        fun closeGroup() {
            if (group.size > 1) {
                val best = group.maxBy { it.sharpness }
                group.filter { it.id != best.id }.forEach { duplicates += it.id }
            }
            group = mutableListOf()
        }
        for (shot in shots) {
            val last = group.lastOrNull()
            val sameBurst = last != null && shot.takenAt - last.takenAt <= windowMs &&
                group.any { com.mobilegamma.cakesync.scan.Quality.distance(it.hash, shot.hash) <= maxDistance }
            if (!sameBurst) closeGroup()
            group += shot
        }
        closeGroup()

        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("UPDATE photos SET duplicate = 0")
            duplicates.forEach { db.execSQL("UPDATE photos SET duplicate = 1 WHERE media_id = $it") }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return duplicates.size
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
     * Re-applies category settings to already-scanned photos using their stored labels, so
     * changing categories doesn't need a rescan.
     */
    fun reclassify(classify: (List<Pair<String, Float>>) -> Classification) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (photo in query("WHERE category_manual = 0")) {
                val result = classify(parseLabels(photo.labels))
                val values = ContentValues().apply {
                    put("score", result.score)
                    put("is_match", if (result.isMatch) 1 else 0)
                    if (result.category == null) putNull("category") else put("category", result.category.id)
                }
                db.update("photos", values, "media_id = ?", arrayOf(photo.mediaId.toString()))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Label names seen across scanned photos, most frequent first (for category suggestions). */
    fun seenLabels(limit: Int = 40): List<String> =
        all(limit = 2000).flatMap { parseLabels(it.labels).map { l -> l.first } }
            .groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.value }.take(limit).map { it.key }

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
    fun pendingUploads(requireApproval: Boolean, excludePeople: Boolean, skipDuplicates: Boolean = true): List<Photo> {
        var auto = if (excludePeople) "is_match = 1 AND faces = 0" else "is_match = 1"
        if (skipDuplicates) auto += " AND duplicate = 0"
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
            isVideo = getInt(getColumnIndexOrThrow("is_video")) == 1,
            durationMs = getColumnIndexOrThrow("duration_ms").let { if (isNull(it)) null else getLong(it) },
            category = getColumnIndexOrThrow("category").let { if (isNull(it)) null else getString(it) },
            orderTag = getColumnIndexOrThrow("order_tag").let { if (isNull(it)) null else getString(it) },
            sharpness = getColumnIndexOrThrow("sharpness").let { if (isNull(it)) null else getDouble(it) },
            duplicate = getInt(getColumnIndexOrThrow("duplicate")) == 1,
        )
    }

    companion object {
        @Volatile private var instance: PhotoStore? = null

        /** Parses the stored "Cake 93%, Food 88%" label list back into (name, confidence). */
        fun parseLabels(stored: String): List<Pair<String, Float>> =
            stored.split(", ").mapNotNull { entry ->
                val cut = entry.lastIndexOf(' ')
                if (cut <= 0) return@mapNotNull null
                val pct = entry.substring(cut + 1).removeSuffix("%").toIntOrNull() ?: return@mapNotNull null
                entry.substring(0, cut) to pct / 100f
            }

        fun get(context: Context): PhotoStore =
            instance ?: synchronized(this) {
                instance ?: PhotoStore(context.applicationContext).also { instance = it }
            }
    }
}
