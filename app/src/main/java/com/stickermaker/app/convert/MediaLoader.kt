package com.stickermaker.app.convert

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import com.bumptech.glide.gifdecoder.GifDecoder
import com.bumptech.glide.gifdecoder.StandardGifDecoder
import java.io.IOException

enum class MediaKind { IMAGE, GIF, VIDEO }

/** Reads photos, GIFs and videos into Bitmaps. */
object MediaLoader {

    fun kindOf(context: Context, uri: Uri): MediaKind {
        val type = context.contentResolver.getType(uri).orEmpty().lowercase()
        return when {
            type == "image/gif" -> MediaKind.GIF
            type.startsWith("video/") -> MediaKind.VIDEO
            type.isEmpty() && uri.toString().lowercase().endsWith(".gif") -> MediaKind.GIF
            else -> MediaKind.IMAGE
        }
    }

    // ---------------------------------------------------------------- images

    /** Decodes an image, honouring EXIF rotation, down-sampled so its short side stays >= ~[minSide]. */
    fun decodeImage(context: Context, uri: Uri, minSide: Int = 1024): Bitmap {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("Unreadable image")

        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= minSide) sample *= 2

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: throw IOException("Unreadable image")

        val orientation = try {
            resolver.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } ?: ExifInterface.ORIENTATION_NORMAL
        } catch (_: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
        return applyExif(bitmap, orientation)
    }

    private fun applyExif(src: Bitmap, orientation: Int): Bitmap {
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
            else -> return src
        }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    /** Hardware bitmaps can't be drawn on a software canvas; return a drawable copy if needed. */
    fun software(bmp: Bitmap): Bitmap =
        if (bmp.config == Bitmap.Config.HARDWARE) bmp.copy(Bitmap.Config.ARGB_8888, false) else bmp

    // ---------------------------------------------------------------- video

    fun openVideo(context: Context, uri: Uri): MediaMetadataRetriever =
        MediaMetadataRetriever().apply { setDataSource(context, uri) }

    fun videoDurationMs(retriever: MediaMetadataRetriever): Long =
        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L

    /** Frame nearest to [timeMs], already rotated, scaled to roughly 1024px on its long side. */
    fun videoFrameAt(retriever: MediaMetadataRetriever, timeMs: Long): Bitmap? {
        val us = timeMs * 1000
        return try {
            val scaled = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> retriever.getScaledFrameAtTime(
                    us, MediaMetadataRetriever.OPTION_CLOSEST, 1024, 1024,
                    MediaMetadataRetriever.BitmapParams().apply { preferredConfig = Bitmap.Config.ARGB_8888 },
                )
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1 ->
                    retriever.getScaledFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST, 1024, 1024)
                else -> null
            }
            scaled ?: retriever.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST)
        } catch (_: Throwable) {
            null
        }
    }

    // ---------------------------------------------------------------- GIF

    class Gif(private val decoder: StandardGifDecoder) {
        val frameCount: Int get() = decoder.frameCount

        /** Display time of each frame; tiny delays are treated as 100 ms, as browsers do. */
        fun delayOf(index: Int): Int = decoder.getDelay(index).let { if (it < 20) 100 else it }

        val durationMs: Long get() = (0 until frameCount).sumOf { delayOf(it).toLong() }

        /**
         * Calls [onFrame] for every frame overlapping [startMs, startMs + lengthMs), with the
         * part of its display time that falls inside the window. The bitmap is only valid
         * during the callback.
         */
        fun forEachFrame(startMs: Long, lengthMs: Long, onFrame: (Bitmap, Int) -> Unit) {
            val end = startMs + lengthMs
            var t = 0L
            decoder.resetFrameIndex()
            for (i in 0 until frameCount) {
                decoder.advance()
                val delay = delayOf(i)
                val frameStart = t
                val frameEnd = t + delay
                t = frameEnd
                // Every frame must be decoded in order (GIF frames build on each other),
                // even the ones before the window.
                val bitmap = decoder.nextFrame ?: continue
                if (frameEnd <= startMs) continue
                if (frameStart >= end) break
                val visible = (minOf(frameEnd, end) - maxOf(frameStart, startMs)).toInt()
                if (visible > 0) onFrame(bitmap, visible)
            }
        }

        fun firstFrameAt(timeMs: Long): Bitmap? {
            var result: Bitmap? = null
            forEachFrame(timeMs, 1) { bmp, _ -> if (result == null) result = bmp.copy(Bitmap.Config.ARGB_8888, false) }
            return result
        }
    }

    fun openGif(context: Context, uri: Uri): Gif {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IOException("Unreadable GIF")
        val decoder = StandardGifDecoder(SimpleBitmapProvider)
        decoder.setDefaultBitmapConfig(Bitmap.Config.ARGB_8888)
        val status = decoder.read(bytes)
        if ((status != GifDecoder.STATUS_OK && status != GifDecoder.STATUS_PARTIAL_DECODE) || decoder.frameCount == 0) {
            throw IOException("Unreadable GIF")
        }
        return Gif(decoder)
    }

    private object SimpleBitmapProvider : GifDecoder.BitmapProvider {
        override fun obtain(width: Int, height: Int, config: Bitmap.Config): Bitmap =
            Bitmap.createBitmap(width, height, config)
        override fun release(bitmap: Bitmap) = Unit
        override fun obtainByteArray(size: Int) = ByteArray(size)
        override fun release(bytes: ByteArray) = Unit
        override fun obtainIntArray(size: Int) = IntArray(size)
        override fun release(array: IntArray) = Unit
    }
}
