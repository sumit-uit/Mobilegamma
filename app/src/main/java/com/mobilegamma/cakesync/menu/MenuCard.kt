package com.mobilegamma.cakesync.menu

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.provider.MediaStore
import android.text.TextPaint
import android.text.TextUtils
import androidx.core.net.toUri
import com.mobilegamma.cakesync.edit.Backdrop
import com.mobilegamma.cakesync.edit.Backdrops
import com.mobilegamma.cakesync.edit.BrandKit
import com.mobilegamma.cakesync.edit.CollageRenderer
import com.mobilegamma.cakesync.edit.PhotoEditor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Menu card page sizes. */
enum class CardSize(val label: String, val width: Int, val height: Int) {
    POST("Post 4:5", 1080, 1350), STORY("Story 9:16", 1080, 1920),
}

data class CardOptions(
    val size: CardSize = CardSize.POST,
    /** Designs per page: 4 (2x2) or 6 (2x3). */
    val perPage: Int = 4,
    val backdrop: Backdrop = Backdrop.Solid("Cream", 0xFFFFF4E4.toInt()),
    val showPrices: Boolean = true,
    /** Add price-list page(s): the flavour × size tables, design levels and extras. */
    val priceList: Boolean = true,
)

/** A category's price table with its display name, for the price-list page. */
data class NamedTable(val name: String, val table: PriceTable)

/** One menu entry ready to draw. */
data class CardEntry(val title: String, val price: String?, val photo: Bitmap)

/**
 * Draws a shareable menu: header with logo and title, a grid of designs with names and
 * prices, and a footer with the business name, note and social handles.
 */
object MenuCard {

    fun pages(entries: List<CardEntry>, options: CardOptions): Int = maxOf(1, (entries.size + options.perPage - 1) / options.perPage)

    fun render(
        entries: List<CardEntry>,
        page: Int,
        options: CardOptions,
        kit: BrandKit,
        settings: MenuSettings,
        scale: Float = 1f,
    ): Bitmap {
        val w = (options.size.width * scale).toInt()
        val h = (options.size.height * scale).toInt()
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        Backdrops.draw(canvas, options.backdrop, w, h)

        val u = w / 1080f // design unit
        val margin = 48 * u
        val accent = kit.labelColor.takeIf { Color.luminance(it) < 0.6f } ?: 0xFFB83B5E.toInt()
        val font = kit.labelFont.typeface()
        val card = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(235, 255, 255, 255) }

        // Header
        val headerH = 190 * u
        canvas.drawRoundRect(RectF(margin, margin, w - margin, margin + headerH), 36 * u, 36 * u, card)
        var textLeft = margin + 40 * u
        kit.logo?.takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }?.let { logo ->
            val lh = headerH - 50 * u
            val lw = lh * logo.width / logo.height
            canvas.drawBitmap(logo, null, RectF(margin + 30 * u, margin + 25 * u, margin + 30 * u + lw, margin + 25 * u + lh), Paint(Paint.FILTER_BITMAP_FLAG))
            textLeft = margin + 60 * u + lw
        }
        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = accent; textSize = 76 * u; typeface = font }
        val subPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF5A4A4E.toInt(); textSize = 34 * u; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
        val textWidth = w - margin - 40 * u - textLeft
        canvas.drawText(ellipsize(settings.title, titlePaint, textWidth), textLeft, margin + 100 * u, titlePaint)
        val by = listOf(kit.businessName, kit.tagline).filter { it.isNotBlank() }.joinToString(" · ")
        if (by.isNotBlank()) canvas.drawText(ellipsize(by, subPaint, textWidth), textLeft, margin + 152 * u, subPaint)

        // Footer
        val footerLines = listOfNotNull(
            settings.note.trim().ifBlank { null },
            listOfNotNull(
                kit.instagram.trim().ifBlank { null }?.let { if (it.startsWith("@")) "IG $it" else "IG @$it" },
                kit.facebook.trim().ifBlank { null }?.let { "FB $it" },
                kit.website.trim().ifBlank { null },
            ).joinToString("   ").ifBlank { null },
        )
        val footPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF3E2F33.toInt(); textSize = 32 * u; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
        val footerH = (40 + 46 * footerLines.size + if (pages(entries, options) > 1) 40 else 0) * u
        val footTop = h - margin - footerH
        canvas.drawRoundRect(RectF(margin, footTop, w - margin, h - margin), 30 * u, 30 * u, card)
        var fy = footTop + 58 * u
        footerLines.forEach { line ->
            val text = ellipsize(line, footPaint, w - margin * 2 - 60 * u)
            canvas.drawText(text, (w - footPaint.measureText(text)) / 2, fy, footPaint)
            fy += 46 * u
        }
        val total = pages(entries, options)
        if (total > 1) {
            val pageText = "${page + 1} / $total"
            val small = TextPaint(footPaint).apply { textSize = 26 * u; color = 0xFF857376.toInt() }
            canvas.drawText(pageText, (w - small.measureText(pageText)) / 2, fy, small)
        }

        // Grid
        val cols = 2
        val rows = if (options.perPage <= 4) 2 else 3
        val gap = 28 * u
        val gridTop = margin + headerH + gap
        val gridBottom = footTop - gap
        val cellW = (w - margin * 2 - gap * (cols - 1)) / cols
        val cellH = (gridBottom - gridTop - gap * (rows - 1)) / rows
        val namePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF221A1C.toInt(); textSize = 36 * u; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
        val pricePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = accent; textSize = 34 * u; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
        val textArea = if (options.showPrices) 112 * u else 70 * u
        val slice = entries.drop(page * options.perPage).take(options.perPage)
        slice.forEachIndexed { i, entry ->
            val col = i % cols
            val row = i / cols
            val left = margin + col * (cellW + gap)
            val top = gridTop + row * (cellH + gap)
            val cell = RectF(left, top, left + cellW, top + cellH)
            val radius = 28 * u
            canvas.drawRoundRect(cell, radius, radius, card)
            val photoRect = RectF(left + 14 * u, top + 14 * u, left + cellW - 14 * u, top + cellH - textArea)
            canvas.save()
            canvas.clipPath(Path().apply { addRoundRect(photoRect, radius * 0.7f, radius * 0.7f, Path.Direction.CW) })
            canvas.drawBitmap(
                entry.photo,
                CollageRenderer.centreCrop(entry.photo.width, entry.photo.height, photoRect.width(), photoRect.height()),
                photoRect, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG),
            )
            canvas.restore()
            val tx = left + 24 * u
            val maxText = cellW - 48 * u
            canvas.drawText(ellipsize(entry.title, namePaint, maxText), tx, photoRect.bottom + 50 * u, namePaint)
            if (options.showPrices) {
                canvas.drawText(ellipsize(entry.price ?: "Ask for price", pricePaint, maxText), tx, photoRect.bottom + 94 * u, pricePaint)
            }
        }
        return out
    }

    /** One line of the price list: a heading, the size header, a flavour row or a note. */
    private sealed interface Line {
        data class Heading(val text: String) : Line
        data class Header(val sizes: List<SizeCol>) : Line
        data class Row(val name: String, val prices: List<String>) : Line
        data class Note(val text: String) : Line
    }

    private fun priceLines(tables: List<NamedTable>, settings: MenuSettings): List<Line> = buildList {
        tables.filter { it.table.isSet }.forEach { (name, table) ->
            add(Line.Heading(name))
            add(Line.Header(table.sizes))
            table.flavours.filter { r -> r.prices.any { it != null } }.forEach { r ->
                add(Line.Row(r.name, r.prices.map { p -> p?.let { Pricing.format(it, settings.currency) } ?: "–" }))
            }
        }
        val levels = settings.levels.filter { it.fromPrice != null }
        if (levels.isNotEmpty()) {
            add(Line.Heading("Designs"))
            levels.forEach { add(Line.Note("${it.name}: from ${Pricing.format(it.fromPrice!!, settings.currency)}")) }
        }
        if (settings.extras.isNotEmpty()) {
            add(Line.Heading("Options & extras"))
            settings.extras.forEach { add(Line.Note(if (it.price.isBlank()) it.name else "${it.name}: ${it.price}")) }
        }
    }

    private fun lineHeight(line: Line, u: Float) = when (line) {
        is Line.Heading -> 84 * u
        is Line.Header -> 64 * u
        is Line.Row -> 56 * u
        is Line.Note -> 50 * u
    }

    /** The price list split into pages that fit between the header and footer. */
    private fun priceListPages(tables: List<NamedTable>, settings: MenuSettings, options: CardOptions): List<List<Line>> {
        val lines = priceLines(tables, settings)
        if (lines.isEmpty()) return emptyList()
        val u = options.size.width / 1080f
        val available = options.size.height - (48 * 2 + 190 + 28 * 2 + 180 + 60) * u
        val pages = mutableListOf(mutableListOf<Line>())
        var used = 0f
        for (line in lines) {
            val h = lineHeight(line, u)
            if (used + h > available && pages.last().isNotEmpty()) {
                pages += mutableListOf<Line>()
                used = 0f
                // Repeat the size header on a new page.
                if (line is Line.Row) {
                    lines.subList(0, lines.indexOf(line)).lastOrNull { it is Line.Header }?.let { pages.last() += it; used += lineHeight(it, u) }
                }
            }
            pages.last() += line
            used += h
        }
        return pages
    }

    fun pricePageCount(tables: List<NamedTable>, settings: MenuSettings, options: CardOptions): Int =
        if (options.priceList) priceListPages(tables, settings, options).size else 0

    /** Draws price-list page [page] (0-based among the price-list pages). */
    fun renderPriceList(
        tables: List<NamedTable>,
        page: Int,
        options: CardOptions,
        kit: BrandKit,
        settings: MenuSettings,
        scale: Float = 1f,
    ): Bitmap {
        val w = (options.size.width * scale).toInt()
        val h = (options.size.height * scale).toInt()
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        Backdrops.draw(canvas, options.backdrop, w, h)
        val u = w / 1080f
        val margin = 48 * u
        val accent = kit.labelColor.takeIf { Color.luminance(it) < 0.6f } ?: 0xFFB83B5E.toInt()
        val card = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(240, 255, 255, 255) }
        val top = drawHeader(canvas, w, u, margin, kit, settings.title.let { if (page == 0) "Price list" else "Price list (cont.)" }, accent, card)
        val footTop = drawFooter(canvas, w, h, u, margin, kit, settings, null, card)
        val body = RectF(margin, top + 28 * u, w - margin, footTop - 28 * u)
        canvas.drawRoundRect(body, 30 * u, 30 * u, card)

        val headingPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = accent; textSize = 44 * u; typeface = kit.labelFont.typeface() }
        val headerPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF857376.toInt(); textSize = 28 * u; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
        val rowPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF221A1C.toInt(); textSize = 32 * u }
        val pricePaint = TextPaint(rowPaint).apply { typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
        val notePaint = TextPaint(rowPaint)
        val rule = Paint().apply { color = 0x22000000; strokeWidth = 1.5f * u }

        val lines = priceListPages(tables, settings, options).getOrNull(page).orEmpty()
        val left = body.left + 36 * u
        val right = body.right - 36 * u
        var y = body.top + 24 * u
        var columns: List<SizeCol> = emptyList()
        fun colX(i: Int, n: Int): Float {
            val nameWidth = (right - left) * 0.4f
            val colW = (right - left - nameWidth) / n.coerceAtLeast(1)
            return left + nameWidth + colW * (i + 1) // right edge of column i
        }
        for (line in lines) {
            val lh = lineHeight(line, u)
            when (line) {
                is Line.Heading -> canvas.drawText(ellipsize(line.text, headingPaint, right - left), left, y + 58 * u, headingPaint)
                is Line.Header -> {
                    columns = line.sizes
                    line.sizes.forEachIndexed { i, size ->
                        val label = size.label
                        canvas.drawText(label, colX(i, line.sizes.size) - headerPaint.measureText(label), y + 30 * u, headerPaint)
                        if (size.serves.isNotBlank()) {
                            val serves = "serves ${size.serves}"
                            val small = TextPaint(headerPaint).apply { textSize = 20 * u; typeface = Typeface.DEFAULT }
                            canvas.drawText(serves, colX(i, line.sizes.size) - small.measureText(serves), y + 54 * u, small)
                        }
                    }
                    canvas.drawLine(left, y + lh - 2 * u, right, y + lh - 2 * u, rule)
                }
                is Line.Row -> {
                    val baseline = y + 38 * u
                    val nameWidth = (right - left) * 0.4f - 12 * u
                    canvas.drawText(ellipsize(line.name, rowPaint, nameWidth), left, baseline, rowPaint)
                    line.prices.forEachIndexed { i, price ->
                        canvas.drawText(price, colX(i, maxOf(columns.size, line.prices.size)) - pricePaint.measureText(price), baseline, pricePaint)
                    }
                    canvas.drawLine(left, y + lh - 1 * u, right, y + lh - 1 * u, rule)
                }
                is Line.Note -> canvas.drawText(ellipsize("•  " + line.text, notePaint, right - left), left, y + 36 * u, notePaint)
            }
            y += lh
        }
        return out
    }

    /** Draws the header card and returns its bottom edge. */
    private fun drawHeader(canvas: Canvas, w: Int, u: Float, margin: Float, kit: BrandKit, title: String, accent: Int, card: Paint): Float {
        val headerH = 190 * u
        canvas.drawRoundRect(RectF(margin, margin, w - margin, margin + headerH), 36 * u, 36 * u, card)
        var textLeft = margin + 40 * u
        kit.logo?.takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }?.let { logo ->
            val lh = headerH - 50 * u
            val lw = lh * logo.width / logo.height
            canvas.drawBitmap(logo, null, RectF(margin + 30 * u, margin + 25 * u, margin + 30 * u + lw, margin + 25 * u + lh), Paint(Paint.FILTER_BITMAP_FLAG))
            textLeft = margin + 60 * u + lw
        }
        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = accent; textSize = 76 * u; typeface = kit.labelFont.typeface() }
        val subPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF5A4A4E.toInt(); textSize = 34 * u; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
        val textWidth = w - margin - 40 * u - textLeft
        canvas.drawText(ellipsize(title, titlePaint, textWidth), textLeft, margin + 100 * u, titlePaint)
        val by = listOf(kit.businessName, kit.tagline).filter { it.isNotBlank() }.joinToString(" · ")
        if (by.isNotBlank()) canvas.drawText(ellipsize(by, subPaint, textWidth), textLeft, margin + 152 * u, subPaint)
        return margin + headerH
    }

    /** Draws the footer card (note, socials, page number) and returns its top edge. */
    private fun drawFooter(
        canvas: Canvas, w: Int, h: Int, u: Float, margin: Float, kit: BrandKit, settings: MenuSettings, pageText: String?, card: Paint,
    ): Float {
        val footerLines = listOfNotNull(
            settings.note.trim().ifBlank { null },
            listOfNotNull(
                kit.instagram.trim().ifBlank { null }?.let { if (it.startsWith("@")) "IG $it" else "IG @$it" },
                kit.facebook.trim().ifBlank { null }?.let { "FB $it" },
                kit.website.trim().ifBlank { null },
            ).joinToString("   ").ifBlank { null },
        )
        val footPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF3E2F33.toInt(); textSize = 32 * u; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
        val footerH = (40 + 46 * footerLines.size + if (pageText != null) 40 else 0) * u
        val footTop = h - margin - footerH
        canvas.drawRoundRect(RectF(margin, footTop, w - margin, h - margin), 30 * u, 30 * u, card)
        var fy = footTop + 58 * u
        footerLines.forEach { line ->
            val text = ellipsize(line, footPaint, w - margin * 2 - 60 * u)
            canvas.drawText(text, (w - footPaint.measureText(text)) / 2, fy, footPaint)
            fy += 46 * u
        }
        if (pageText != null) {
            val small = TextPaint(footPaint).apply { textSize = 26 * u; color = 0xFF857376.toInt() }
            canvas.drawText(pageText, (w - small.measureText(pageText)) / 2, fy, small)
        }
        return footTop
    }

    private fun ellipsize(text: String, paint: TextPaint, width: Float): String =
        TextUtils.ellipsize(text, paint, width, TextUtils.TruncateAt.END).toString()

    /**
     * Saves every page as a JPEG in Pictures/CakeSync (so it shows under Created) and all pages
     * as one PDF in Download/CakeSync. Returns the image uris and the PDF uri.
     */
    suspend fun export(
        context: Context,
        entries: List<CardEntry>,
        options: CardOptions,
        kit: BrandKit,
        settings: MenuSettings,
        tables: List<NamedTable>,
    ): Pair<List<Uri>, Uri?> = withContext(Dispatchers.Default) {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val editor = PhotoEditor(context)
        val pdf = PdfDocument()
        val images = mutableListOf<Uri>()
        try {
            val bitmaps = (0 until if (entries.isEmpty()) 0 else pages(entries, options)).map { render(entries, it, options, kit, settings) } +
                (0 until pricePageCount(tables, settings, options)).map { renderPriceList(tables, it, options, kit, settings) }
            bitmaps.forEachIndexed { i, bitmap ->
                images += editor.saveImage(bitmap, "CakeSync_menu_${stamp}_${i + 1}.jpg")
                val pdfPage = pdf.startPage(PdfDocument.PageInfo.Builder(bitmap.width, bitmap.height, i + 1).create())
                pdfPage.canvas.drawBitmap(bitmap, 0f, 0f, null)
                pdf.finishPage(pdfPage)
            }
            val pdfUri = runCatching { savePdf(context, pdf, "CakeSync_menu_$stamp.pdf") }.getOrNull()
            images to pdfUri
        } finally {
            pdf.close()
        }
    }

    private fun savePdf(context: Context, pdf: PdfDocument, name: String): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/CakeSync")
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw IOException("Could not create $name")
        resolver.openOutputStream(uri)?.use { pdf.writeTo(it) } ?: throw IOException("Could not write $name")
        return uri
    }

    /** Loads a design photo for the card, small enough to keep memory low. */
    fun loadPhoto(context: Context, uri: String): Bitmap? = runCatching { PhotoEditor.loadScaled(context, uri.toUri(), 700) }.getOrNull()
}
