package com.mobilegamma.cakesync.scan

import android.graphics.Bitmap
import android.graphics.Color

/**
 * Cheap on-device image quality measures used to pick the best shot from a burst of
 * near-identical photos.
 */
object Quality {

    /**
     * Sharpness as the variance of the Laplacian over a 256px greyscale copy. Higher is
     * sharper; blurry photos have few strong edges and score low.
     */
    fun sharpness(bitmap: Bitmap): Double {
        val small = scaled(bitmap, 256)
        val w = small.width
        val h = small.height
        if (w < 3 || h < 3) return 0.0
        val grey = greyscale(small)
        var sum = 0.0
        var sumSq = 0.0
        var n = 0
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val i = y * w + x
                val lap = grey[i - w] + grey[i + w] + grey[i - 1] + grey[i + 1] - 4 * grey[i]
                sum += lap
                sumSq += lap.toDouble() * lap
                n++
            }
        }
        val mean = sum / n
        return sumSq / n - mean * mean
    }

    /** 64-bit difference hash: similar-looking photos have hashes a few bits apart. */
    fun dHash(bitmap: Bitmap): Long {
        val small = Bitmap.createScaledBitmap(bitmap, 9, 8, true)
        val grey = greyscale(small)
        var hash = 0L
        var bit = 0
        for (y in 0 until 8) {
            for (x in 0 until 8) {
                if (grey[y * 9 + x] > grey[y * 9 + x + 1]) hash = hash or (1L shl bit)
                bit++
            }
        }
        return hash
    }

    fun distance(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)

    private fun scaled(bitmap: Bitmap, maxSide: Int): Bitmap {
        val scale = maxSide.toFloat() / maxOf(bitmap.width, bitmap.height)
        if (scale >= 1f) return bitmap
        return Bitmap.createScaledBitmap(
            bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true,
        )
    }

    private fun greyscale(bitmap: Bitmap): IntArray {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return IntArray(pixels.size) { i ->
            val p = pixels[i]
            (Color.red(p) * 299 + Color.green(p) * 587 + Color.blue(p) * 114) / 1000
        }
    }
}
