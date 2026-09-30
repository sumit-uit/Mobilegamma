package com.mobilegamma.cakesync.edit

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.provider.MediaStore
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One-tap photo tools. Every edit is saved as a NEW image in Pictures/CakeSync/; originals
 * are never changed.
 */
class PhotoEditor(private val context: Context) {

    /** The background-removal model is being downloaded by Google Play services. */
    class ModelDownloading : IOException(
        "Downloading the background-removal model (one time). Please try again in a minute."
    )

    /** Output shapes for social media. */
    enum class Shape(val label: String, val w: Int, val h: Int) {
        SQUARE("1:1 Instagram post", 1080, 1080),
        PORTRAIT("4:5 Instagram portrait", 1080, 1350),
        STORY("9:16 Story / Reel / WhatsApp status", 1080, 1920),
    }

    /**
     * Cuts the subject (the cake) out and puts it on a plain white background. Uses ML Kit
     * subject segmentation, whose model Google Play services downloads on first use.
     */
    suspend fun removeBackground(uri: Uri, displayName: String): Uri = withContext(Dispatchers.Default) {
        val source = load(uri)
        val foreground = cutout(source)
        val out = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        Canvas(out).apply {
            drawColor(Color.WHITE)
            drawBitmap(foreground, 0f, 0f, null)
        }
        save(brandIfEnabled(out), "${baseName(displayName)}_white.jpg")
    }

    /**
     * The subject (the cake) with a transparent background, same size as [source]. Uses ML Kit
     * subject segmentation, whose model Google Play services downloads on first use.
     */
    suspend fun cutout(source: Bitmap): Bitmap = withContext(Dispatchers.Default) {
        val segmenter = SubjectSegmentation.getClient(
            SubjectSegmenterOptions.Builder().enableForegroundBitmap().build()
        )
        try {
            try {
                segmenter.process(InputImage.fromBitmap(source, 0)).await().foregroundBitmap
            } catch (e: MlKitException) {
                if (e.errorCode != MlKitException.UNAVAILABLE) throw e
                // The model isn't on the phone yet: ask Google Play services to fetch it.
                runCatching {
                    ModuleInstall.getClient(context)
                        .installModules(ModuleInstallRequest.newBuilder().addApi(segmenter).build())
                }
                throw ModelDownloading()
            } ?: throw IOException("No cake found in the photo")
        } finally {
            segmenter.close()
        }
    }

    /** A photo open in the studio: the full-size picture plus a small copy for fast previews. */
    class StudioSession(
        val name: String,
        val source: Bitmap,
        val subject: Rect?,
        val preview: Bitmap,
        val previewSubject: Rect?,
    ) {
        var cutout: Bitmap? = null
        var previewCutout: Bitmap? = null
    }

    suspend fun openStudio(uri: Uri, displayName: String): StudioSession = withContext(Dispatchers.Default) {
        val source = load(uri)
        val subject = subjectBox(source)
        val scale = minOf(1f, 900f / maxOf(source.width, source.height))
        val preview = if (scale < 1f) {
            Bitmap.createScaledBitmap(source, (source.width * scale).toInt(), (source.height * scale).toInt(), true)
        } else source
        val previewSubject = subject?.let {
            Rect((it.left * scale).toInt(), (it.top * scale).toInt(), (it.right * scale).toInt(), (it.bottom * scale).toInt())
        }
        StudioSession(displayName, source, subject, preview, previewSubject)
    }

    /** Cuts the cake out of the session's photo (once). */
    suspend fun cutout(session: StudioSession) {
        if (session.cutout != null) return
        val cut = cutout(session.source)
        session.previewCutout = Bitmap.createScaledBitmap(cut, session.preview.width, session.preview.height, true)
        session.cutout = cut
    }

    /** Small, quick render for the on-screen preview. */
    fun previewStudio(session: StudioSession, spec: StudioSpec): Bitmap =
        Studio.render(session.preview, session.previewCutout, session.previewSubject, spec, BrandKit.load(context))

    /** Full-size render saved as a new photo. */
    suspend fun saveStudio(session: StudioSession, spec: StudioSpec): Uri = withContext(Dispatchers.Default) {
        val out = Studio.render(session.source, session.cutout, session.subject, spec, BrandKit.load(context))
        save(out, "${baseName(session.name)}_studio_${System.currentTimeMillis() / 1000}.jpg")
    }

    /**
     * Crops to [shape], centred on the detected subject so the cake stays in frame. If the
     * subject is wider than the crop allows, the photo is fitted on a white background
     * instead of cutting the cake off.
     */
    suspend fun crop(uri: Uri, displayName: String, shape: Shape): Uri = withContext(Dispatchers.Default) {
        val source = load(uri)
        val subject = subjectBox(source) ?: Rect(0, 0, source.width, source.height)
        val target = shape.w.toFloat() / shape.h

        // Largest crop of the target shape that fits in the photo.
        var cw = source.width
        var ch = (cw / target).toInt()
        if (ch > source.height) {
            ch = source.height
            cw = (ch * target).toInt()
        }
        val out = Bitmap.createBitmap(shape.w, shape.h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        if (subject.width() <= cw && subject.height() <= ch) {
            // Centre the crop on the subject, kept inside the photo.
            val left = (subject.centerX() - cw / 2).coerceIn(0, source.width - cw)
            val top = (subject.centerY() - ch / 2).coerceIn(0, source.height - ch)
            canvas.drawBitmap(source, Rect(left, top, left + cw, top + ch), Rect(0, 0, shape.w, shape.h), paint)
        } else {
            // Subject doesn't fit: show the whole photo, letterboxed on white.
            val scale = minOf(shape.w.toFloat() / source.width, shape.h.toFloat() / source.height)
            val dw = (source.width * scale).toInt()
            val dh = (source.height * scale).toInt()
            val dx = (shape.w - dw) / 2
            val dy = (shape.h - dh) / 2
            canvas.drawBitmap(source, null, Rect(dx, dy, dx + dw, dy + dh), paint)
        }
        save(brandIfEnabled(out), "${baseName(displayName)}_${shape.w}x${shape.h}.jpg")
    }

    /**
     * Saves a branded copy: the brand kit's colour filter, logo and business name, plus an
     * optional [price] label such as "₹1,200".
     */
    suspend fun brand(uri: Uri, displayName: String, price: String?): Uri = withContext(Dispatchers.Default) {
        val kit = BrandKit.load(context)
        if (kit.isEmpty && price.isNullOrBlank()) throw IOException("Set up your brand kit in Settings first")
        save(kit.apply(load(uri), price), "${baseName(displayName)}_branded.jpg")
    }

    /** Saves a copy with a colour [filter] applied. */
    suspend fun filter(uri: Uri, displayName: String, filter: ColorFilterPreset): Uri = withContext(Dispatchers.Default) {
        save(brandIfEnabled(applyFilter(load(uri), filter)), "${baseName(displayName)}_filter_${filter.name.lowercase()}.jpg")
    }

    /**
     * Saves a collage of [uris] laid out as [template] in [shape], optionally with the brand
     * kit (logo, name, filter) and a [price] label.
     */
    suspend fun collage(
        uris: List<Uri>,
        template: CollageTemplate,
        shape: Shape,
        backdrop: Backdrop,
        spacing: Float,
        rounded: Boolean,
        brand: Boolean,
        price: String?,
    ): Uri = withContext(Dispatchers.Default) {
        val photos = uris.take(template.size).map { loadScaled(context, it, 1400) }
        val collage = CollageRenderer.render(photos, template, shape.w, shape.h, backdrop, spacing, rounded)
        val kit = BrandKit.load(context)
        val out = if (brand && (!kit.isEmpty || !price.isNullOrBlank())) kit.apply(collage, price) else collage
        save(out, "CakeSync_collage_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".jpg")
    }

    private fun brandIfEnabled(bitmap: Bitmap): Bitmap {
        val kit = BrandKit.load(context)
        return if (kit.applyToEdits && !kit.isEmpty) kit.apply(bitmap) else bitmap
    }

    /** Bounding box of the main food object, or the largest object found. */
    private suspend fun subjectBox(bitmap: Bitmap): Rect? {
        val detector = ObjectDetection.getClient(
            ObjectDetectorOptions.Builder()
                .setDetectorMode(ObjectDetectorOptions.SINGLE_IMAGE_MODE)
                .enableMultipleObjects()
                .enableClassification()
                .build()
        )
        return try {
            val objects = detector.process(InputImage.fromBitmap(bitmap, 0)).await()
            (objects.filter { o -> o.labels.any { it.text.equals("Food", true) } }.ifEmpty { objects })
                .maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }?.boundingBox
        } catch (e: Exception) {
            null
        } finally {
            detector.close()
        }
    }

    /** Decodes the photo, capped at 2048px on the long side, honouring EXIF rotation. */
    private fun load(uri: Uri): Bitmap = loadScaled(context, uri, 2048)

    private fun baseName(name: String) = name.substringBeforeLast('.')

    /** Saves a finished picture (e.g. a menu card page) to Pictures/CakeSync. */
    fun saveImage(bitmap: Bitmap, name: String): Uri = save(bitmap, name)

    /** Saves [bitmap] as a JPEG in Pictures/CakeSync/ and returns its MediaStore uri. */
    private fun save(bitmap: Bitmap, name: String): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CakeSync")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Could not create $name")
        try {
            resolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                ?: throw IOException("Could not write $name")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            return uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    companion object {
        /** Decodes an image no larger than [maxSide] on its long side (EXIF rotation applied). */
        fun loadScaled(context: Context, uri: Uri, maxSide: Int): Bitmap =
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                val longSide = maxOf(info.size.width, info.size.height)
                if (longSide > maxSide) {
                    val scale = maxSide.toFloat() / longSide
                    decoder.setTargetSize(
                        (info.size.width * scale).toInt().coerceAtLeast(1),
                        (info.size.height * scale).toInt().coerceAtLeast(1),
                    )
                }
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.isMutableRequired = false
            }

        /** A copy of [source] with [filter] applied (the same bitmap for None). */
        fun applyFilter(source: Bitmap, filter: ColorFilterPreset): Bitmap {
            val matrix = filter.matrix() ?: return source
            val out = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
            Canvas(out).drawBitmap(
                source, 0f, 0f,
                Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = android.graphics.ColorMatrixColorFilter(matrix) },
            )
            return out
        }
    }
}
