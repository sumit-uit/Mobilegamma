package com.mobilegamma.cakesync.edit

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import com.mobilegamma.cakesync.data.Photo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Makes a short 9:16 reel from photos and videos on the phone (Media3 Transformer):
 * each photo shows for [PHOTO_MS], each video is trimmed to its first [VIDEO_MS], and an
 * optional music file plays underneath. The result is saved to Movies/CakeSync.
 */
class ReelMaker(private val context: Context) {

    suspend fun make(items: List<Photo>, music: Uri?): Uri {
        require(items.isNotEmpty()) { "Select some photos or videos first" }
        val portrait = Presentation.createForWidthAndHeight(WIDTH, HEIGHT, Presentation.LAYOUT_SCALE_TO_FIT)
        val clips = items.take(MAX_ITEMS).map { item ->
            val media = if (item.isVideo) {
                MediaItem.Builder().setUri(item.uri)
                    .setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder().setEndPositionMs(VIDEO_MS).build()
                    )
                    .build()
            } else {
                MediaItem.Builder().setUri(item.uri).setImageDurationMs(PHOTO_MS).build()
            }
            EditedMediaItem.Builder(media)
                .setRemoveAudio(true) // music (if any) comes from its own track
                .apply { if (!item.isVideo) setFrameRate(FPS) }
                .setEffects(Effects(listOf(), listOf(portrait)))
                .build()
        }
        val sequences = mutableListOf(EditedMediaItemSequence(clips))
        if (music != null) {
            // Looping music track underneath; the composition ends with the visuals.
            val song = EditedMediaItem.Builder(MediaItem.fromUri(music)).setRemoveVideo(true).build()
            sequences += EditedMediaItemSequence(listOf(song), /* isLooping= */ true)
        }
        val composition = Composition.Builder(sequences).build()

        val out = File(context.cacheDir, "reel_${System.currentTimeMillis()}.mp4")
        export(composition, out)
        return try {
            saveToMovies(out)
        } finally {
            out.delete()
        }
    }

    /** Transformer must be driven from the main thread. */
    private suspend fun export(composition: Composition, out: File) = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            val transformer = Transformer.Builder(context)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        if (cont.isActive) cont.resume(Unit)
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exportException: ExportException,
                    ) {
                        if (cont.isActive) cont.resumeWithException(exportException)
                    }
                })
                .build()
            cont.invokeOnCancellation { transformer.cancel() }
            transformer.start(composition, out.path)
        }
    }

    private suspend fun saveToMovies(file: File): Uri = withContext(Dispatchers.IO) {
        val name = "CakeSync_reel_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/CakeSync")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Could not create $name")
        try {
            resolver.openOutputStream(uri)?.use { output -> file.inputStream().use { it.copyTo(output) } }
                ?: throw IOException("Could not write $name")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
            uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    companion object {
        const val WIDTH = 1080
        const val HEIGHT = 1920
        const val PHOTO_MS = 2500L
        const val VIDEO_MS = 5000L
        const val FPS = 30
        const val MAX_ITEMS = 20
    }
}
