package com.mobilegamma.cakesync.edit

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import androidx.core.content.edit
import java.io.File

/** Where the logo sits on the photo. */
enum class LogoPosition(val label: String) {
    TOP_LEFT("↖ Top left"), TOP_RIGHT("↗ Top right"), BOTTOM_LEFT("↙ Bottom left"), BOTTOM_RIGHT("↘ Bottom right")
}

/** A consistent look applied to photos and reels. */
enum class ColorFilterPreset(val label: String) {
    NONE("None"), WARM("Warm"), BRIGHT("Bright"), COOL("Cool"), VIVID("Vivid"),
    PASTEL("Pastel"), VINTAGE("Vintage"), DRAMA("Drama"), MONO("B&W");

    /** Android 4x5 colour matrix (row-major, offsets on the 0–255 scale); null = no change. */
    fun colorValues(): FloatArray? = when (this) {
        NONE -> null
        WARM -> floatArrayOf(1.08f, 0f, 0f, 0f, 8f, 0f, 1.02f, 0f, 0f, 4f, 0f, 0f, 0.92f, 0f, -6f, 0f, 0f, 0f, 1f, 0f)
        BRIGHT -> floatArrayOf(1.1f, 0f, 0f, 0f, 14f, 0f, 1.1f, 0f, 0f, 14f, 0f, 0f, 1.1f, 0f, 14f, 0f, 0f, 0f, 1f, 0f)
        COOL -> floatArrayOf(0.94f, 0f, 0f, 0f, -4f, 0f, 1.0f, 0f, 0f, 2f, 0f, 0f, 1.1f, 0f, 10f, 0f, 0f, 0f, 1f, 0f)
        VIVID -> saturation(1.35f)
        // Softer contrast, lifted and slightly pink: suits cakes and desserts.
        PASTEL -> multiply(saturation(0.8f), floatArrayOf(0.85f, 0f, 0f, 0f, 42f, 0f, 0.85f, 0f, 0f, 34f, 0f, 0f, 0.85f, 0f, 38f, 0f, 0f, 0f, 1f, 0f))
        VINTAGE -> floatArrayOf(
            0.393f, 0.769f, 0.189f, 0f, 0f, 0.349f, 0.686f, 0.168f, 0f, 0f, 0.272f, 0.534f, 0.131f, 0f, 0f, 0f, 0f, 0f, 1f, 0f,
        ).let { sepia -> blend(sepia, 0.7f) }
        DRAMA -> multiply(saturation(1.15f), floatArrayOf(1.3f, 0f, 0f, 0f, -38f, 0f, 1.3f, 0f, 0f, -38f, 0f, 0f, 1.3f, 0f, -38f, 0f, 0f, 0f, 1f, 0f))
        MONO -> saturation(0f)
    }

    fun matrix(): ColorMatrix? = colorValues()?.let { ColorMatrix(it) }

    /**
     * The same filter as a 4x4 column-major matrix for GL video effects (colours 0–1). The
     * offsets go in the alpha column, which works because video frames are opaque.
     */
    fun glMatrix(): FloatArray? = colorValues()?.let { a ->
        FloatArray(16).also { m ->
            for (row in 0..2) {
                for (col in 0..2) m[col * 4 + row] = a[row * 5 + col]
                m[12 + row] = a[row * 5 + 4] / 255f
            }
            m[15] = 1f
        }
    }

    private companion object {
        /** Same maths as android.graphics.ColorMatrix.setSaturation. */
        fun saturation(s: Float): FloatArray {
            val inv = 1 - s
            val r = 0.213f * inv
            val g = 0.715f * inv
            val b = 0.072f * inv
            return floatArrayOf(r + s, g, b, 0f, 0f, r, g + s, b, 0f, 0f, r, g, b + s, 0f, 0f, 0f, 0f, 0f, 1f, 0f)
        }

        /** [second] applied after [first] (both 4x5, alpha untouched). */
        fun multiply(first: FloatArray, second: FloatArray): FloatArray {
            val out = FloatArray(20)
            for (row in 0..3) {
                for (col in 0..4) {
                    var v = if (col == 4) second[row * 5 + 4] else 0f
                    for (k in 0..3) v += second[row * 5 + k] * first[k * 5 + col]
                    out[row * 5 + col] = v
                }
            }
            return out
        }

        /** Mixes [matrix] with the identity: [amount] 1 = full effect. */
        fun blend(matrix: FloatArray, amount: Float): FloatArray = FloatArray(20) { i ->
            val identity = if (i % 6 == 0 && i < 20) 1f else 0f
            identity + (matrix[i] - identity) * amount
        }
    }
}

/** The user's brand settings. */
data class BrandKit(
    val logo: File?,
    val position: LogoPosition,
    /** Logo width as a fraction of the photo's shorter side. */
    val logoSize: Float,
    val logoOpacity: Float,
    /** Shown in a small label, e.g. "Soni Bakes · @sonibakes". Blank = no label. */
    val businessName: String,
    val filter: ColorFilterPreset,
    /** Also brand crops and white-background copies automatically. */
    val applyToEdits: Boolean,
) {
    val isEmpty: Boolean get() = logo == null && businessName.isBlank() && filter == ColorFilterPreset.NONE

    /**
     * Returns a branded copy of [source]: colour filter, then logo, then a label with the
     * business name and, if given, a [price] such as "₹1,200".
     */
    fun apply(source: Bitmap, price: String? = null): Bitmap {
        val out = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        filter.matrix()?.let { paint.colorFilter = ColorMatrixColorFilter(it) }
        canvas.drawBitmap(source, 0f, 0f, paint)

        val shortSide = minOf(out.width, out.height).toFloat()
        val margin = shortSide * 0.03f

        logo?.takeIf { it.exists() }?.let { file ->
            BitmapFactory.decodeFile(file.path)?.let { logoBitmap ->
                val w = shortSide * logoSize
                val h = w * logoBitmap.height / logoBitmap.width
                val left = if (position == LogoPosition.TOP_LEFT || position == LogoPosition.BOTTOM_LEFT) margin
                    else out.width - margin - w
                val top = if (position == LogoPosition.TOP_LEFT || position == LogoPosition.TOP_RIGHT) margin
                    else out.height - margin - h
                val logoPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply {
                    alpha = (logoOpacity * 255).toInt().coerceIn(0, 255)
                }
                canvas.drawBitmap(logoBitmap, null, RectF(left, top, left + w, top + h), logoPaint)
            }
        }

        val text = listOfNotNull(businessName.trim().ifBlank { null }, price?.trim()?.ifBlank { null })
            .joinToString("  ·  ")
        if (text.isNotEmpty()) {
            // Label in the bottom corner opposite a bottom logo (or bottom-left otherwise).
            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = shortSide * 0.045f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            val pad = textPaint.textSize * 0.45f
            val textW = textPaint.measureText(text)
            val boxH = textPaint.textSize + pad * 2
            val onRight = position == LogoPosition.BOTTOM_LEFT
            val left = if (onRight) out.width - margin - textW - pad * 2 else margin
            val top = out.height - margin - boxH
            canvas.drawRoundRect(
                RectF(left, top, left + textW + pad * 2, top + boxH), pad, pad,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 0, 0, 0) },
            )
            canvas.drawText(text, left + pad, top + pad - textPaint.fontMetrics.ascent * 0.95f, textPaint)
        }
        return out
    }

    companion object {
        private const val PREFS = "cakesync"

        fun load(context: Context): BrandKit {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return BrandKit(
                logo = logoFile(context).takeIf { it.exists() },
                position = runCatching { LogoPosition.valueOf(p.getString("brand_position", null)!!) }
                    .getOrDefault(LogoPosition.BOTTOM_RIGHT),
                logoSize = p.getFloat("brand_logo_size", 0.22f),
                logoOpacity = p.getFloat("brand_logo_opacity", 0.9f),
                businessName = p.getString("brand_name", "") ?: "",
                filter = runCatching { ColorFilterPreset.valueOf(p.getString("brand_filter", null)!!) }
                    .getOrDefault(ColorFilterPreset.NONE),
                applyToEdits = p.getBoolean("brand_apply_to_edits", false),
            )
        }

        fun save(context: Context, kit: BrandKit) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
                putString("brand_position", kit.position.name)
                putFloat("brand_logo_size", kit.logoSize)
                putFloat("brand_logo_opacity", kit.logoOpacity)
                putString("brand_name", kit.businessName)
                putString("brand_filter", kit.filter.name)
                putBoolean("brand_apply_to_edits", kit.applyToEdits)
            }
        }

        fun logoFile(context: Context) = File(context.filesDir, "brand_logo.png")

        /** Copies a picked image into app storage as the logo (PNG keeps transparency). */
        fun setLogo(context: Context, uri: Uri) {
            val bitmap = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                ?: throw IllegalArgumentException("Could not read that image")
            val scale = minOf(1f, 800f / maxOf(bitmap.width, bitmap.height))
            val sized = if (scale < 1f) {
                Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
            } else bitmap
            logoFile(context).outputStream().use { sized.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }

        fun removeLogo(context: Context) {
            logoFile(context).delete()
        }
    }
}
