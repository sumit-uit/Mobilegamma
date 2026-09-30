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
        fun saturation(s: Float) = ColorMath.saturation(s)
        fun multiply(first: FloatArray, second: FloatArray) = ColorMath.multiply(first, second)
        fun blend(matrix: FloatArray, amount: Float) = ColorMath.blend(matrix, amount)
    }
}

/** Font for the business name label. */
enum class LabelFont(val label: String, private val family: String, private val style: Int) {
    CLASSIC("Classic", "sans-serif", Typeface.BOLD),
    ELEGANT("Elegant", "serif", Typeface.BOLD),
    SCRIPT("Script", "cursive", Typeface.BOLD),
    PLAYFUL("Playful", "casual", Typeface.BOLD),
    MODERN("Modern", "sans-serif-condensed", Typeface.BOLD),
    TYPEWRITER("Typewriter", "monospace", Typeface.BOLD);

    fun typeface(): Typeface = Typeface.create(family, style)
}

/** How the label sits on the photo. */
enum class LabelStyle(val label: String) { DARK("Dark box"), LIGHT("Light box"), SHADOW("No box") }

/** Colours offered for the label text. */
val LABEL_COLOURS = listOf(
    "White" to 0xFFFFFFFF.toInt(), "Black" to 0xFF1A1A1A.toInt(), "Raspberry" to 0xFFB83B5E.toInt(),
    "Pink" to 0xFFFF8FAB.toInt(), "Gold" to 0xFFD4A017.toInt(), "Chocolate" to 0xFF5D3A1A.toInt(),
    "Mint" to 0xFF3F7D6E.toInt(), "Sky" to 0xFF2C73D2.toInt(),
)

/** The user's brand settings. */
data class BrandKit(
    val logo: File?,
    val position: LogoPosition,
    /** Logo width as a fraction of the photo's shorter side. */
    val logoSize: Float,
    val logoOpacity: Float,
    /** Shown in a small label, e.g. "Sweet Crumbs · @sweetcrumbs". Blank = no label. */
    val businessName: String,
    val filter: ColorFilterPreset,
    /** Also brand crops and white-background copies automatically. */
    val applyToEdits: Boolean,
    val labelFont: LabelFont = LabelFont.CLASSIC,
    val labelColor: Int = 0xFFFFFFFF.toInt(),
    val labelStyle: LabelStyle = LabelStyle.DARK,
    /** Optional smaller line under the name, e.g. "Custom cakes · Pune". */
    val tagline: String = "",
    /** Optional social lines under the name, e.g. "sweetcrumbs" → "@sweetcrumbs". */
    val instagram: String = "",
    val facebook: String = "",
    val website: String = "",
) {
    val hasSocials: Boolean get() = instagram.isNotBlank() || facebook.isNotBlank() || website.isNotBlank() || tagline.isNotBlank()

    val isEmpty: Boolean
        get() = logo == null && businessName.isBlank() && filter == ColorFilterPreset.NONE && !hasSocials

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

        drawLabel(canvas, out.width, out.height, shortSide, margin, price)
        return out
    }

    /**
     * The business name (and price) in the chosen font, colour and style, with optional
     * Instagram / Facebook / website lines, in the bottom corner away from a bottom logo.
     */
    private fun drawLabel(canvas: Canvas, width: Int, height: Int, shortSide: Float, margin: Float, price: String?) {
        val title = listOfNotNull(businessName.trim().ifBlank { null }, price?.trim()?.ifBlank { null }).joinToString("  ·  ")
        val socials = buildList {
            instagram.trim().ifBlank { null }?.let { add(Social.INSTAGRAM to if (it.startsWith("@")) it else "@$it") }
            facebook.trim().ifBlank { null }?.let { add(Social.FACEBOOK to it) }
            website.trim().ifBlank { null }?.let { add(Social.WEB to it.removePrefix("https://").removePrefix("http://")) }
        }
        val subtitle = tagline.trim()
        if (title.isEmpty() && subtitle.isEmpty() && socials.isEmpty()) return

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = labelColor
            textSize = shortSide * 0.05f
            typeface = labelFont.typeface()
        }
        val smallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = labelColor
            textSize = shortSide * 0.032f
            typeface = labelFont.typeface()
        }
        if (labelStyle == LabelStyle.SHADOW) {
            val shadow = if (Color.luminance(labelColor) > 0.5f) 0xAA000000.toInt() else 0x88FFFFFF.toInt()
            titlePaint.setShadowLayer(titlePaint.textSize * 0.12f, 0f, titlePaint.textSize * 0.05f, shadow)
            smallPaint.setShadowLayer(smallPaint.textSize * 0.15f, 0f, smallPaint.textSize * 0.05f, shadow)
        }
        val icon = smallPaint.textSize * 1.1f
        val gap = smallPaint.textSize * 0.4f
        val pad = titlePaint.textSize * 0.45f
        val maxWidth = width - margin * 2 - pad * 2

        // Socials go on one row when they fit, otherwise one per line.
        val socialWidths = socials.map { (_, text) -> icon + gap + smallPaint.measureText(text) }
        val rowWidth = socialWidths.sum() + gap * 2 * (socials.size - 1).coerceAtLeast(0)
        val socialRows: List<List<Int>> = when {
            socials.isEmpty() -> emptyList()
            rowWidth <= maxWidth -> listOf(socials.indices.toList())
            else -> socials.indices.map { listOf(it) }
        }
        val titleWidth = if (title.isEmpty()) 0f else titlePaint.measureText(title)
        val subtitleWidth = if (subtitle.isEmpty()) 0f else smallPaint.measureText(subtitle)
        val subtitleHeight = if (subtitle.isEmpty()) 0f else smallPaint.textSize * 1.35f
        val contentWidth = maxOf(titleWidth, subtitleWidth, socialRows.maxOfOrNull { row -> row.sumOf { socialWidths[it].toDouble() }.toFloat() + gap * 2 * (row.size - 1) } ?: 0f)
        val titleHeight = if (title.isEmpty()) 0f else titlePaint.textSize * 1.2f
        val rowHeight = icon * 1.35f
        val boxW = contentWidth + pad * 2
        val boxH = titleHeight + subtitleHeight + rowHeight * socialRows.size + pad * 1.4f

        val onRight = position == LogoPosition.BOTTOM_LEFT
        val left = if (onRight) width - margin - boxW else margin
        val top = height - margin - boxH
        when (labelStyle) {
            LabelStyle.DARK -> canvas.drawRoundRect(RectF(left, top, left + boxW, top + boxH), pad, pad, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 0, 0, 0) })
            LabelStyle.LIGHT -> canvas.drawRoundRect(RectF(left, top, left + boxW, top + boxH), pad, pad, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(210, 255, 255, 255) })
            LabelStyle.SHADOW -> Unit
        }
        var y = top + pad * 0.7f
        if (title.isNotEmpty()) {
            canvas.drawText(title, left + pad, y - titlePaint.fontMetrics.ascent * 0.95f, titlePaint)
            y += titleHeight
        }
        if (subtitle.isNotEmpty()) {
            canvas.drawText(subtitle, left + pad, y - smallPaint.fontMetrics.ascent * 0.95f, smallPaint)
            y += subtitleHeight
        }
        socialRows.forEach { row ->
            var x = left + pad
            row.forEach { i ->
                val (kind, text) = socials[i]
                drawSocialIcon(canvas, kind, x, y + (rowHeight - icon) / 2, icon, labelColor)
                canvas.drawText(text, x + icon + gap, y + rowHeight / 2 - (smallPaint.fontMetrics.ascent + smallPaint.fontMetrics.descent) / 2, smallPaint)
                x += socialWidths[i] + gap * 2
            }
            y += rowHeight
        }
    }

    private enum class Social { INSTAGRAM, FACEBOOK, WEB }

    /** Simple line icons drawn in the label colour, so they match any style. */
    private fun drawSocialIcon(canvas: Canvas, kind: Social, x: Float, y: Float, size: Float, color: Int) {
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeWidth = size * 0.1f
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        val cx = x + size / 2
        val cy = y + size / 2
        when (kind) {
            Social.INSTAGRAM -> {
                val inset = stroke.strokeWidth / 2
                canvas.drawRoundRect(RectF(x + inset, y + inset, x + size - inset, y + size - inset), size * 0.28f, size * 0.28f, stroke)
                canvas.drawCircle(cx, cy, size * 0.22f, stroke)
                canvas.drawCircle(x + size * 0.76f, y + size * 0.24f, size * 0.06f, fill)
            }
            Social.FACEBOOK -> {
                canvas.drawCircle(cx, cy, size / 2 - stroke.strokeWidth / 2, stroke)
                val f = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    this.color = color
                    textSize = size * 0.8f
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    textAlign = Paint.Align.CENTER
                }
                canvas.drawText("f", cx + size * 0.03f, cy - (f.fontMetrics.ascent + f.fontMetrics.descent) / 2, f)
            }
            Social.WEB -> {
                val r = size / 2 - stroke.strokeWidth / 2
                canvas.drawCircle(cx, cy, r, stroke)
                canvas.drawOval(RectF(cx - r * 0.45f, cy - r, cx + r * 0.45f, cy + r), stroke)
                canvas.drawLine(cx - r, cy, cx + r, cy, stroke)
            }
        }
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
                tagline = p.getString("brand_tagline", "") ?: "",
                labelFont = runCatching { LabelFont.valueOf(p.getString("brand_font", null)!!) }.getOrDefault(LabelFont.CLASSIC),
                labelColor = p.getInt("brand_label_color", 0xFFFFFFFF.toInt()),
                labelStyle = runCatching { LabelStyle.valueOf(p.getString("brand_label_style", null)!!) }.getOrDefault(LabelStyle.DARK),
                instagram = p.getString("brand_instagram", "") ?: "",
                facebook = p.getString("brand_facebook", "") ?: "",
                website = p.getString("brand_website", "") ?: "",
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
                putString("brand_font", kit.labelFont.name)
                putInt("brand_label_color", kit.labelColor)
                putString("brand_label_style", kit.labelStyle.name)
                putString("brand_tagline", kit.tagline)
                putString("brand_instagram", kit.instagram)
                putString("brand_facebook", kit.facebook)
                putString("brand_website", kit.website)
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
