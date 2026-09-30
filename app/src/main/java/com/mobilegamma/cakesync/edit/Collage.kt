package com.mobilegamma.cakesync.edit

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF

/** A cell of a collage in unit coordinates (0–1 across and down the canvas). */
data class Cell(val left: Float, val top: Float, val right: Float, val bottom: Float)

/** Ready-made collage layouts; photos fill the cells in order. */
enum class CollageTemplate(val label: String, val cells: List<Cell>) {
    TWO_SIDE("2 side by side", listOf(Cell(0f, 0f, .5f, 1f), Cell(.5f, 0f, 1f, 1f))),
    TWO_STACK("2 stacked", listOf(Cell(0f, 0f, 1f, .5f), Cell(0f, .5f, 1f, 1f))),
    BIG_LEFT_2("1 big + 2", listOf(Cell(0f, 0f, .6f, 1f), Cell(.6f, 0f, 1f, .5f), Cell(.6f, .5f, 1f, 1f))),
    BIG_TOP_2("1 top + 2", listOf(Cell(0f, 0f, 1f, .6f), Cell(0f, .6f, .5f, 1f), Cell(.5f, .6f, 1f, 1f))),
    THREE_ROWS("3 rows", listOf(Cell(0f, 0f, 1f, 1 / 3f), Cell(0f, 1 / 3f, 1f, 2 / 3f), Cell(0f, 2 / 3f, 1f, 1f))),
    GRID_4("4 grid", grid(2, 2)),
    BIG_TOP_3(
        "1 big + 3",
        listOf(Cell(0f, 0f, 1f, .64f), Cell(0f, .64f, 1 / 3f, 1f), Cell(1 / 3f, .64f, 2 / 3f, 1f), Cell(2 / 3f, .64f, 1f, 1f)),
    ),
    GRID_6("6 grid", grid(2, 3)),
    GRID_9("9 grid", grid(3, 3));

    val size: Int get() = cells.size
}

private fun grid(cols: Int, rows: Int): List<Cell> = buildList {
    for (r in 0 until rows) for (c in 0 until cols) {
        add(Cell(c / cols.toFloat(), r / rows.toFloat(), (c + 1) / cols.toFloat(), (r + 1) / rows.toFloat()))
    }
}

object CollageRenderer {
    /**
     * Draws [photos] into [template] on a [width]x[height] canvas over [backdrop]. Each photo
     * fills its cell, cropped around the centre. [spacing] is the gap between photos (and the
     * frame around them) as a fraction of the shorter side; [rounded] rounds the corners.
     */
    fun render(
        photos: List<Bitmap>,
        template: CollageTemplate,
        width: Int,
        height: Int,
        backdrop: Backdrop,
        spacing: Float = DEFAULT_SPACING,
        rounded: Boolean = true,
    ): Bitmap {
        require(photos.isNotEmpty()) { "No photos for the collage" }
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        Backdrops.draw(canvas, backdrop, width, height)
        val gap = minOf(width, height) * spacing
        val radius = if (rounded) minOf(width, height) * 0.03f else 0f
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        template.cells.forEachIndexed { i, cell ->
            val photo = photos[i % photos.size]
            val rect = cellRect(cell, width, height, gap)
            canvas.save()
            if (radius > 0f) canvas.clipPath(Path().apply { addRoundRect(rect, radius, radius, Path.Direction.CW) })
            canvas.drawBitmap(photo, centreCrop(photo.width, photo.height, rect.width(), rect.height()), rect, paint)
            canvas.restore()
        }
        return out
    }

    /** Pixel rectangle of [cell], with an outer border and inner gaps of [gap]. */
    fun cellRect(cell: Cell, width: Int, height: Int, gap: Float): RectF {
        val w = width - gap
        val h = height - gap
        return RectF(
            gap / 2 + cell.left * w + gap / 2,
            gap / 2 + cell.top * h + gap / 2,
            gap / 2 + cell.right * w - gap / 2,
            gap / 2 + cell.bottom * h - gap / 2,
        )
    }

    /** The largest centred part of a srcW x srcH image with the aspect ratio of dstW x dstH. */
    fun centreCrop(srcW: Int, srcH: Int, dstW: Float, dstH: Float): Rect {
        val scale = maxOf(dstW / srcW, dstH / srcH)
        val cropW = (dstW / scale).toInt().coerceIn(1, srcW)
        val cropH = (dstH / scale).toInt().coerceIn(1, srcH)
        val left = (srcW - cropW) / 2
        val top = (srcH - cropH) / 2
        return Rect(left, top, left + cropW, top + cropH)
    }

    /** Default gap between photos as a fraction of the canvas's shorter side. */
    const val DEFAULT_SPACING = 0.03f
    const val MAX_SPACING = 0.1f
}
