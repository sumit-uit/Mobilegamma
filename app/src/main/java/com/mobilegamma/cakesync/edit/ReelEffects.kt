package com.mobilegamma.cakesync.edit

import android.graphics.Matrix
import androidx.media3.common.Effect
import androidx.media3.effect.MatrixTransformation
import androidx.media3.effect.RgbMatrix

/** Timing maths for reel transitions (plain Kotlin so it can be unit tested). */
object ReelMotion {
    const val FADE_US = 400_000L
    const val SLIDE_US = 450_000L
    const val ZOOM = 0.12f

    /** 0 = black, 1 = full picture: fades in at the start and out at the end of a clip. */
    fun brightness(relUs: Long, durationUs: Long): Float =
        minOf(relUs / FADE_US.toFloat(), (durationUs - relUs) / FADE_US.toFloat(), 1f).coerceIn(0f, 1f)

    /** Slow zoom over the whole clip; alternates in and out between clips. */
    fun scale(relUs: Long, durationUs: Long, zoomIn: Boolean): Float {
        val p = (relUs / durationUs.toFloat()).coerceIn(0f, 1f)
        val eased = p * p * (3 - 2 * p)
        return if (zoomIn) 1f + ZOOM * eased else 1f + ZOOM * (1 - eased)
    }

    /** Horizontal offset (2 = one screen width) as the clip slides in, easing to a stop. */
    fun slideOffset(relUs: Long, fromRight: Boolean): Float {
        val p = (relUs / SLIDE_US.toFloat()).coerceIn(0f, 1f)
        val remaining = (1 - p) * (1 - p) * (1 - p)
        return (if (fromRight) 2f else -2f) * remaining
    }
}

/** Media3 video effects for each [ReelStyle]. */
internal object ReelEffects {

    /** Effects for a clip that starts [startUs] into the reel and lasts [durationUs]. */
    fun motion(style: ReelStyle, index: Int, startUs: Long, durationUs: Long): List<Effect> {
        // Effects may be given reel time or clip time depending on the Media3 version; reel
        // time is never before the clip's start, so anything earlier is already clip time.
        fun rel(t: Long) = if (t >= startUs) t - startUs else t
        val fade = object : RgbMatrix {
            override fun getMatrix(presentationTimeUs: Long, useHdr: Boolean): FloatArray {
                val f = ReelMotion.brightness(rel(presentationTimeUs), durationUs)
                return floatArrayOf(f, 0f, 0f, 0f, 0f, f, 0f, 0f, 0f, 0f, f, 0f, 0f, 0f, 0f, 1f)
            }

            override fun isNoOp(inputWidth: Int, inputHeight: Int) = false
        }
        val zoom = object : MatrixTransformation {
            override fun getMatrix(presentationTimeUs: Long): Matrix {
                val s = ReelMotion.scale(rel(presentationTimeUs), durationUs, zoomIn = index % 2 == 0)
                return Matrix().apply { setScale(s, s) }
            }

            override fun isNoOp(inputWidth: Int, inputHeight: Int) = false
        }
        val slide = object : MatrixTransformation {
            override fun getMatrix(presentationTimeUs: Long): Matrix = Matrix().apply {
                setTranslate(ReelMotion.slideOffset(rel(presentationTimeUs), fromRight = index % 2 == 0), 0f)
            }

            override fun isNoOp(inputWidth: Int, inputHeight: Int) = false
        }
        return when (style) {
            ReelStyle.CUT, ReelStyle.MIX -> emptyList()
            ReelStyle.FADE -> listOf(fade)
            ReelStyle.ZOOM -> listOf(zoom)
            ReelStyle.SLIDE -> listOf(slide)
            ReelStyle.ZOOM_FADE -> listOf(zoom, fade)
        }
    }

    /** A constant colour filter (the matrix from [ColorFilterPreset.glMatrix]). */
    fun filter(matrix: FloatArray): Effect = RgbMatrix { _, _ -> matrix }
}
