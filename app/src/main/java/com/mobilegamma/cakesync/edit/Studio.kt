package com.mobilegamma.cakesync.edit

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader

/** Crop shapes offered in the studio; ratio is width / height, null keeps the photo's own. */
enum class StudioCrop(val label: String, val ratio: Float?) {
    ORIGINAL("Original", null), SQUARE("1:1", 1f), PORTRAIT("4:5", 0.8f), STORY("9:16", 9f / 16f), WIDE("16:9", 16f / 9f),
}

/** Every setting in the photo studio. Nothing is applied to the original photo. */
data class StudioSpec(
    val crop: StudioCrop = StudioCrop.ORIGINAL,
    /** Quarter turns clockwise (0-3). */
    val rotation: Int = 0,
    /** Each -1..1, 0 = unchanged. */
    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val saturation: Float = 0f,
    val warmth: Float = 0f,
    /** 0..1 darkening towards the corners. */
    val vignette: Float = 0f,
    val filter: ColorFilterPreset = ColorFilterPreset.NONE,
    /** 0..1 how strongly the filter applies. */
    val filterStrength: Float = 1f,
    /** Cut the cake out and put [backdrop] behind it. */
    val replaceBackground: Boolean = false,
    val backdrop: Backdrop = Backdrops.WHITE,
    /** Soft shadow under the cut-out cake. */
    val shadow: Boolean = true,
    val brand: Boolean = false,
    val price: String = "",
) {
    val isAdjusted get() = brightness != 0f || contrast != 0f || saturation != 0f || warmth != 0f || vignette != 0f
}

/** A rectangle in pixels (plain Kotlin so it can be unit tested). */
data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width get() = right - left
    val height get() = bottom - top
}

/** 4x5 colour-matrix maths shared by filters and adjustments (plain Kotlin, unit tested). */
object ColorMath {
    val IDENTITY = floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f)

    /** Same maths as android.graphics.ColorMatrix.setSaturation. */
    fun saturation(s: Float): FloatArray {
        val inv = 1 - s
        val r = 0.213f * inv
        val g = 0.715f * inv
        val b = 0.072f * inv
        return floatArrayOf(r + s, g, b, 0f, 0f, r, g + s, b, 0f, 0f, r, g, b + s, 0f, 0f, 0f, 0f, 0f, 1f, 0f)
    }

    /** [second] applied after [first]. */
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

    /** Mixes [matrix] with the identity: [amount] 1 = full effect, 0 = none. */
    fun blend(matrix: FloatArray, amount: Float): FloatArray = FloatArray(20) { i ->
        IDENTITY[i] + (matrix[i] - IDENTITY[i]) * amount
    }

    /** Brightness, contrast, saturation and warmth from the studio sliders, then the filter. */
    fun studio(spec: StudioSpec): FloatArray {
        val c = 1f + spec.contrast * 0.6f
        val t = 128f * (1f - c) + spec.brightness * 70f
        val w = spec.warmth * 22f
        val light = floatArrayOf(c, 0f, 0f, 0f, t + w, 0f, c, 0f, 0f, t + w * 0.3f, 0f, 0f, c, 0f, t - w, 0f, 0f, 0f, 1f, 0f)
        var m = multiply(light, saturation(1f + spec.saturation))
        spec.filter.colorValues()?.let { m = multiply(m, blend(it, spec.filterStrength)) }
        return m
    }
}

object Studio {

    /** Largest box of [ratio] (w/h) inside [width] x [height], centred on ([cx], [cy]) where possible. */
    fun cropBox(width: Int, height: Int, ratio: Float?, cx: Int = width / 2, cy: Int = height / 2): Box {
        if (ratio == null) return Box(0, 0, width, height)
        var w = width
        var h = (w / ratio).toInt()
        if (h > height) {
            h = height
            w = (h * ratio).toInt()
        }
        val left = (cx - w / 2).coerceIn(0, width - w)
        val top = (cy - h / 2).coerceIn(0, height - h)
        return Box(left, top, left + w, top + h)
    }

    /**
     * Renders [spec] from [source]. [cutout] is the cake with a transparent background (same
     * size as [source]) and is needed only when the background is replaced; [subject] is where
     * the cake is, used to centre crops.
     */
    fun render(source: Bitmap, cutout: Bitmap?, subject: Rect?, spec: StudioSpec, kit: BrandKit?): Bitmap {
        // 1. Rotate.
        val turn = Matrix().apply { postRotate(spec.rotation * 90f) }
        val rotated = if (spec.rotation % 4 == 0) source else Bitmap.createBitmap(source, 0, 0, source.width, source.height, turn, true)
        val rotatedCut = cutout?.let { if (spec.rotation % 4 == 0) it else Bitmap.createBitmap(it, 0, 0, it.width, it.height, turn, true) }
        val centre = subject?.let {
            val r = RectF(it)
            // Rotate the subject box the same way, then shift into the rotated bitmap's frame.
            val m = Matrix(turn)
            val bounds = RectF(0f, 0f, source.width.toFloat(), source.height.toFloat())
            m.mapRect(bounds)
            m.postTranslate(-bounds.left, -bounds.top)
            m.mapRect(r)
            r.centerX().toInt() to r.centerY().toInt()
        } ?: (rotated.width / 2 to rotated.height / 2)

        // 2. Crop.
        val box = cropBox(rotated.width, rotated.height, spec.crop.ratio, centre.first, centre.second)
        val src = Rect(box.left, box.top, box.right, box.bottom)
        val out = Bitmap.createBitmap(box.width, box.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val dst = Rect(0, 0, box.width, box.height)
        val colour = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(ColorMatrix(ColorMath.studio(spec)))
        }

        // 3. Background + cake.
        if (spec.replaceBackground && rotatedCut != null) {
            val cropped = Bitmap.createBitmap(rotated, box.left, box.top, box.width, box.height)
            Backdrops.draw(canvas, spec.backdrop, box.width, box.height, cropped)
            if (spec.shadow) {
                val alpha = Bitmap.createBitmap(rotatedCut, box.left, box.top, box.width, box.height).extractAlpha()
                val blur = maxOf(box.width, box.height) * 0.02f
                val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = 0x66000000
                    maskFilter = BlurMaskFilter(blur.coerceAtLeast(1f), BlurMaskFilter.Blur.NORMAL)
                }
                canvas.drawBitmap(alpha, 0f, box.height * 0.015f, shadow)
            }
            canvas.drawBitmap(rotatedCut, src, dst, colour)
        } else {
            canvas.drawBitmap(rotated, src, dst, colour)
        }

        // 4. Vignette.
        if (spec.vignette > 0f) {
            val radius = maxOf(box.width, box.height) * 0.75f
            val paint = Paint().apply {
                shader = RadialGradient(
                    box.width / 2f, box.height / 2f, radius,
                    intArrayOf(0x00000000, 0x00000000, ((spec.vignette * 200).toInt().coerceIn(0, 255) shl 24)),
                    floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP,
                )
            }
            canvas.drawRect(0f, 0f, box.width.toFloat(), box.height.toFloat(), paint)
        }

        // 5. Brand.
        return if (spec.brand && kit != null) kit.copy(filter = ColorFilterPreset.NONE).apply(out, spec.price) else out
    }
}
