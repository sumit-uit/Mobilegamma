package com.mobilegamma.cakesync.edit

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class EditMathTest {

    @Test
    fun musicTracksAreLoudLoopsOfTheRightLength() {
        Music.Track.entries.forEach { track ->
            val samples = Music.render(track)
            val expected = (Music.loopSeconds(track) * Music.RATE).toInt()
            assertEquals(track.name, expected, samples.size)
            val peak = samples.maxOf { kotlin.math.abs(it.toInt()) }
            assertTrue("${track.name} peak $peak", peak in 20_000..Short.MAX_VALUE.toInt())
            // Not silent for long stretches: every second has sound.
            for (s in 0 until samples.size / Music.RATE) {
                val loudest = (s * Music.RATE until (s + 1) * Music.RATE).maxOf { kotlin.math.abs(samples[it].toInt()) }
                assertTrue("${track.name} second $s is silent", loudest > 1_000)
            }
        }
    }

    @Test
    fun musicIsTheSameEveryTime() {
        assertArrayEquals(Music.render(Music.Track.UPBEAT), Music.render(Music.Track.UPBEAT))
    }

    @Test
    fun wavHeaderDescribesMono16BitPcm() {
        val bytes = Music.wav(ShortArray(100) { it.toShort() }, 44_100)
        assertEquals(44 + 200, bytes.size)
        assertEquals("RIFF", String(bytes, 0, 4))
        assertEquals("WAVE", String(bytes, 8, 4))
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(1.toShort(), b.getShort(20)) // PCM
        assertEquals(1.toShort(), b.getShort(22)) // mono
        assertEquals(44_100, b.getInt(24))
        assertEquals(16.toShort(), b.getShort(34))
        assertEquals(200, b.getInt(40))
    }

    @Test
    fun fadeStartsAndEndsBlack() {
        val d = 2_500_000L
        assertEquals(0f, ReelMotion.brightness(0, d), 1e-6f)
        assertEquals(0.5f, ReelMotion.brightness(ReelMotion.FADE_US / 2, d), 1e-3f)
        assertEquals(1f, ReelMotion.brightness(d / 2, d), 1e-6f)
        assertEquals(0f, ReelMotion.brightness(d, d), 1e-6f)
    }

    @Test
    fun zoomAndSlideMoveSmoothly() {
        val d = 2_500_000L
        assertEquals(1f, ReelMotion.scale(0, d, zoomIn = true), 1e-6f)
        assertEquals(1f + ReelMotion.ZOOM, ReelMotion.scale(d, d, zoomIn = true), 1e-6f)
        assertEquals(1f + ReelMotion.ZOOM, ReelMotion.scale(0, d, zoomIn = false), 1e-6f)
        assertEquals(2f, ReelMotion.slideOffset(0, fromRight = true), 1e-6f)
        assertEquals(-2f, ReelMotion.slideOffset(0, fromRight = false), 1e-6f)
        assertEquals(0f, ReelMotion.slideOffset(ReelMotion.SLIDE_US, fromRight = true), 1e-6f)
    }

    @Test
    fun filterMatricesMatchBetweenPhotosAndVideo() {
        assertNull(ColorFilterPreset.NONE.glMatrix())
        ColorFilterPreset.entries.filter { it != ColorFilterPreset.NONE }.forEach { f ->
            val a = f.colorValues()!!
            val m = f.glMatrix()!!
            assertEquals(20, a.size)
            // A mid-grey pixel should come out the same through both.
            val grey = 0.5f
            for (row in 0..2) {
                val photo = (a[row * 5] + a[row * 5 + 1] + a[row * 5 + 2]) * grey * 255 + a[row * 5 + 4]
                val video = (m[row] + m[4 + row] + m[8 + row]) * grey + m[12 + row]
                assertEquals("${f.name} row $row", photo / 255f, video, 1e-4f)
            }
            assertEquals(1f, m[15], 0f)
        }
        // Black and white really is grey.
        val mono = ColorFilterPreset.MONO.colorValues()!!
        assertEquals(mono[0], mono[5], 1e-6f)
        assertEquals(mono[1], mono[6], 1e-6f)
    }

    @Test
    fun collageCellsCoverTheCanvasWithoutOverlap() {
        CollageTemplate.entries.forEach { t ->
            var area = 0f
            t.cells.forEach { c ->
                assertTrue(t.name, c.left >= 0f && c.top >= 0f && c.right <= 1f + 1e-6f && c.bottom <= 1f + 1e-6f)
                assertTrue(t.name, c.right > c.left && c.bottom > c.top)
                area += (c.right - c.left) * (c.bottom - c.top)
            }
            assertEquals(t.name, 1f, area, 1e-4f)
            for (i in t.cells.indices) for (j in i + 1 until t.cells.size) {
                val a = t.cells[i]
                val b = t.cells[j]
                val overlapW = minOf(a.right, b.right) - maxOf(a.left, b.left)
                val overlapH = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
                assertTrue("${t.name} cells $i and $j overlap", overlapW <= 1e-4f || overlapH <= 1e-4f)
            }
        }
        assertEquals(9, CollageTemplate.GRID_9.size)
    }
}
