package com.mobilegamma.cakesync.edit

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * Built-in background music for reels. The tunes are synthesised on the phone from
 * simple chord progressions, so they are free to use anywhere with no copyright claims.
 * Each is a seamless loop of 8 bars; the reel maker repeats it for longer reels.
 */
object Music {

    enum class Track(val label: String, val mood: String, val bpm: Int) {
        HAPPY("Happy", "bright pop plucks", 112),
        CALM("Calm", "soft pads and bells", 72),
        UPBEAT("Upbeat", "dance beat", 124),
        SWEET("Sweet", "music box", 90),
    }

    const val RATE = 44_100

    /** The track as a WAV file in [dir], generated the first time it is needed. */
    fun file(dir: File, track: Track): File {
        val file = File(dir, "music_${track.name.lowercase()}_v1.wav")
        if (!file.exists() || file.length() == 0L) {
            dir.mkdirs()
            val tmp = File(dir, file.name + ".tmp")
            tmp.writeBytes(wav(render(track)))
            tmp.renameTo(file)
        }
        return file
    }

    /** Length of one loop in seconds. */
    fun loopSeconds(track: Track): Double = BARS * 4 * 60.0 / track.bpm

    /** 16-bit mono samples of one loop. */
    fun render(track: Track): ShortArray {
        val beat = 60.0 / track.bpm
        val total = (loopSeconds(track) * RATE).toInt()
        val buf = FloatArray(total)
        val synth = Synth(buf)
        val chords = PROGRESSIONS.getValue(track)
        val barsPerChord = BARS / chords.size
        for ((i, chord) in chords.withIndex()) {
            val chordStart = i * barsPerChord * 4 * beat
            val chordLen = barsPerChord * 4 * beat
            when (track) {
                Track.HAPPY -> {
                    // Eighth-note arpeggio an octave up, bass on every beat, light kick and clap.
                    val steps = (chordLen / (beat / 2)).toInt()
                    for (s in 0 until steps) {
                        val note = chord[listOf(0, 1, 2, 1)[s % 4]] + 12
                        synth.tone(chordStart + s * beat / 2, beat * 1.2, note, 0.22, 0.004, 0.28, PLUCK)
                    }
                    for (b in 0 until (chordLen / beat).toInt()) {
                        val t = chordStart + b * beat
                        synth.tone(t, beat * 0.9, chord[0] - 12, 0.30, 0.01, 0.35, BASS)
                        if (b % 2 == 0) synth.kick(t, 0.55) else synth.noise(t, 0.12, 0.10, 0.05, highPass = false)
                    }
                }
                Track.CALM -> {
                    // Sustained pad under a slow bell arpeggio.
                    chord.forEach { synth.tone(chordStart, chordLen, it, 0.10, 0.6, 99.0, PAD, release = 0.5) }
                    synth.tone(chordStart, chordLen, chord[0] - 12, 0.14, 0.3, 99.0, BASS, release = 0.5)
                    val steps = (chordLen / beat).toInt()
                    for (s in 0 until steps) {
                        val note = chord[listOf(0, 2, 1, 2)[s % 4]] + 12
                        synth.tone(chordStart + s * beat, beat * 2.5, note, 0.13, 0.005, 0.9, BELL)
                    }
                }
                Track.UPBEAT -> {
                    // Sixteenth-note plucks, four-on-the-floor kick, off-beat hats and bass.
                    val steps = (chordLen / (beat / 4)).toInt()
                    for (s in 0 until steps) {
                        val note = chord[listOf(0, 1, 2, 1, 2, 0, 1, 2)[s % 8]] + 12
                        synth.tone(chordStart + s * beat / 4, beat * 0.6, note, 0.16, 0.003, 0.12, PLUCK)
                    }
                    for (b in 0 until (chordLen / beat).toInt()) {
                        val t = chordStart + b * beat
                        synth.kick(t, 0.6)
                        synth.noise(t + beat / 2, 0.06, 0.07, 0.02, highPass = true)
                        synth.tone(t + beat / 2, beat * 0.45, chord[0] - 12, 0.28, 0.005, 0.15, BASS)
                    }
                }
                Track.SWEET -> {
                    // Music box: high, quickly decaying notes going up and down the chord.
                    val steps = (chordLen / (beat / 2)).toInt()
                    for (s in 0 until steps) {
                        val note = chord[listOf(0, 1, 2, 1, 2, 1)[s % 6]] + 24
                        synth.tone(chordStart + s * beat / 2, beat * 2, note, 0.16, 0.002, 0.55, BELL)
                    }
                    synth.tone(chordStart, chordLen, chord[0] - 12, 0.16, 0.2, 2.5, BASS, release = 0.3)
                }
            }
        }
        return normalise(buf)
    }

    /** A mono 16-bit PCM WAV file. */
    fun wav(samples: ShortArray, rate: Int = RATE): ByteArray {
        val dataBytes = samples.size * 2
        val b = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + dataBytes).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
            .putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        b.put("data".toByteArray()).putInt(dataBytes)
        samples.forEach { b.putShort(it) }
        return b.array()
    }

    private fun normalise(buf: FloatArray): ShortArray {
        val peak = buf.maxOf { kotlin.math.abs(it) }.takeIf { it > 0f } ?: 1f
        val gain = 0.8f / peak
        return ShortArray(buf.size) { (buf[it] * gain * Short.MAX_VALUE).toInt().toShort() }
    }

    /** Adds notes and drums into a sample buffer; sound past the end wraps to the start. */
    private class Synth(val buf: FloatArray) {
        private val random = Random(7) // fixed seed: the same tune every time

        /** A note; [harmonics] are relative amplitudes of the 1st, 2nd, 3rd… partials. */
        fun tone(
            start: Double, length: Double, midi: Int, amp: Double, attack: Double, decay: Double,
            harmonics: DoubleArray, release: Double = 0.02,
        ) {
            val freq = 440.0 * 2.0.pow((midi - 69) / 12.0)
            val from = (start * RATE).toInt()
            val n = (length * RATE).toInt()
            for (i in 0 until n) {
                val idx = (from + i) % buf.size // tails wrap to the start, so the loop is seamless
                val t = i.toDouble() / RATE
                val env = minOf(1.0, t / attack) * exp(-t / decay) * minOf(1.0, (length - t) / release)
                var v = 0.0
                for (h in harmonics.indices) v += harmonics[h] * sin(2 * PI * freq * (h + 1) * t)
                buf[idx] += (amp * env * v).toFloat()
            }
        }

        fun kick(start: Double, amp: Double) {
            val from = (start * RATE).toInt()
            var phase = 0.0
            for (i in 0 until (0.3 * RATE).toInt()) {
                val idx = (from + i) % buf.size // tails wrap to the start, so the loop is seamless
                val t = i.toDouble() / RATE
                phase += 2 * PI * (45 + 90 * exp(-t / 0.04)) / RATE
                buf[idx] += (amp * exp(-t / 0.09) * sin(phase)).toFloat()
            }
        }

        fun noise(start: Double, length: Double, amp: Double, decay: Double, highPass: Boolean) {
            val from = (start * RATE).toInt()
            var last = 0.0
            for (i in 0 until (length * RATE).toInt()) {
                val idx = (from + i) % buf.size // tails wrap to the start, so the loop is seamless
                val t = i.toDouble() / RATE
                val white = random.nextDouble(-1.0, 1.0)
                val v = if (highPass) white - last else white
                last = white
                buf[idx] += (amp * exp(-t / decay) * v).toFloat()
            }
        }
    }

    private const val BARS = 8

    // Chords as MIDI notes (root, third, fifth).
    private val C = listOf(60, 64, 67)
    private val G = listOf(55, 59, 62)
    private val AM = listOf(57, 60, 64)
    private val F = listOf(53, 57, 60)
    private val DM = listOf(62, 65, 69)
    private val EM = listOf(64, 67, 71)
    private val PROGRESSIONS = mapOf(
        Track.HAPPY to listOf(C, G, AM, F),
        Track.CALM to listOf(F, C, DM, AM),
        Track.UPBEAT to listOf(AM, F, C, G),
        Track.SWEET to listOf(C, EM, F, G),
    )

    private val PLUCK = doubleArrayOf(1.0, 0.45, 0.2)
    private val BASS = doubleArrayOf(1.0, 0.25)
    private val PAD = doubleArrayOf(1.0, 0.3, 0.12)
    private val BELL = doubleArrayOf(1.0, 0.0, 0.35, 0.0, 0.12)
}
