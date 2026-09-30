package com.mobilegamma.cakesync.edit

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.net.Uri
import java.io.File

/** What goes behind a cut-out cake or between collage photos. */
sealed interface Backdrop {
    val label: String

    data class Solid(override val label: String, val color: Int) : Backdrop
    data class Gradient(override val label: String, val top: Int, val bottom: Int) : Backdrop
    /** A picture file, e.g. one of the user's brand backgrounds. */
    data class Picture(override val label: String, val file: File) : Backdrop
    /** The photo's own background, unchanged (studio only). */
    data object Original : Backdrop { override val label = "Original" }
    /** The photo's own background, blurred like a portrait shot (studio only). */
    data object Blurred : Backdrop { override val label = "Blur" }
}

object Backdrops {
    val WHITE = Backdrop.Solid("White", 0xFFFFFFFF.toInt())

    /** Ready-made colours and gradients that suit cake photos. */
    val presets: List<Backdrop> = listOf(
        WHITE,
        Backdrop.Solid("Cream", 0xFFFFF4E4.toInt()),
        Backdrop.Solid("Blush", 0xFFFCE1E6.toInt()),
        Backdrop.Solid("Mint", 0xFFDDF3EA.toInt()),
        Backdrop.Solid("Sky", 0xFFDCEBFA.toInt()),
        Backdrop.Solid("Lavender", 0xFFE9E1F8.toInt()),
        Backdrop.Solid("Butter", 0xFFFFF1B8.toInt()),
        Backdrop.Solid("Charcoal", 0xFF2B2B2E.toInt()),
        Backdrop.Solid("Black", 0xFF000000.toInt()),
        Backdrop.Gradient("Peach", 0xFFFFE0CC.toInt(), 0xFFFFB199.toInt()),
        Backdrop.Gradient("Candy", 0xFFFFD1E8.toInt(), 0xFFC9B6FF.toInt()),
        Backdrop.Gradient("Sunset", 0xFFFFC371.toInt(), 0xFFFF5F6D.toInt()),
        Backdrop.Gradient("Fresh", 0xFFD4FC79.toInt(), 0xFF96E6A1.toInt()),
        Backdrop.Gradient("Ocean", 0xFFA1C4FD.toInt(), 0xFFC2E9FB.toInt()),
        Backdrop.Gradient("Studio", 0xFFFFFFFF.toInt(), 0xFFD9D9D9.toInt()),
        Backdrop.Gradient("Night", 0xFF434343.toInt(), 0xFF000000.toInt()),
    )

    // --- Brand backgrounds: pictures the user uploads once and reuses. ---

    private fun brandDir(context: Context) = File(context.filesDir, "brand_backgrounds")

    fun brand(context: Context): List<Backdrop.Picture> =
        brandDir(context).listFiles { f -> f.extension == "jpg" }.orEmpty()
            .sortedBy { it.name }
            .mapIndexed { i, f -> Backdrop.Picture("Brand ${i + 1}", f) }

    /** Copies a picked image into the brand backgrounds (max 2048px) and returns it. */
    fun addBrand(context: Context, uri: Uri): Backdrop.Picture {
        val bitmap = PhotoEditor.loadScaled(context, uri, 2048)
        val dir = brandDir(context).apply { mkdirs() }
        val file = File(dir, "bg_${System.currentTimeMillis()}.jpg")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        return Backdrop.Picture("Brand", file)
    }

    fun removeBrand(picture: Backdrop.Picture) {
        picture.file.delete()
        synchronized(cache) { cache.remove(picture.file.path) }
    }

    // --- Drawing ---

    /**
     * Fills the whole [width] x [height] canvas with [backdrop]. [original] is the photo's own
     * picture, used for [Backdrop.Original] and [Backdrop.Blurred].
     */
    fun draw(canvas: Canvas, backdrop: Backdrop, width: Int, height: Int, original: Bitmap? = null) {
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        val full = Rect(0, 0, width, height)
        when (backdrop) {
            is Backdrop.Solid -> canvas.drawColor(backdrop.color)
            is Backdrop.Gradient -> {
                paint.shader = LinearGradient(0f, 0f, width * 0.3f, height.toFloat(), backdrop.top, backdrop.bottom, Shader.TileMode.CLAMP)
                canvas.drawRect(RectF(full), paint)
            }
            is Backdrop.Picture -> {
                val bitmap = decode(backdrop.file) ?: return canvas.drawColor(WHITE.color)
                canvas.drawBitmap(bitmap, CollageRenderer.centreCrop(bitmap.width, bitmap.height, width.toFloat(), height.toFloat()), full, paint)
            }
            Backdrop.Original -> original?.let { canvas.drawBitmap(it, null, full, paint) } ?: canvas.drawColor(WHITE.color)
            Backdrop.Blurred -> original?.let { canvas.drawBitmap(blur(it), null, full, paint) } ?: canvas.drawColor(WHITE.color)
        }
    }

    /** A soft blur made by shrinking the picture a lot and scaling it back up, twice. */
    fun blur(source: Bitmap): Bitmap {
        val small = Bitmap.createScaledBitmap(source, (source.width / 24).coerceAtLeast(2), (source.height / 24).coerceAtLeast(2), true)
        val mid = Bitmap.createScaledBitmap(small, (source.width / 8).coerceAtLeast(2), (source.height / 8).coerceAtLeast(2), true)
        return Bitmap.createScaledBitmap(mid, source.width, source.height, true)
    }

    /** Small cache so dragging a slider doesn't decode the same background again and again. */
    private val cache = LinkedHashMap<String, Bitmap>()

    private fun decode(file: File): Bitmap? = synchronized(cache) {
        cache[file.path] ?: BitmapFactory.decodeFile(file.path)?.also {
            if (cache.size >= 4) cache.remove(cache.keys.first())
            cache[file.path] = it
        }
    }
}
