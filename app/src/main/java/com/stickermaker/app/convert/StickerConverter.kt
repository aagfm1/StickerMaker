package com.stickermaker.app.convert

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.coroutines.coroutineContext

data class ConvertOptions(
    /** true = crop to fill the square, false = fit inside with transparent margins. */
    val fill: Boolean,
    /** Static packs: frame position. Animated packs: clip start. */
    val startMs: Long = 0,
    /** Animated packs only: clip length. */
    val lengthMs: Long = 3000,
    /** Animated packs from video only: frames per second to sample. */
    val fps: Int = 10,
    val animated: Boolean,
)

class StickerResult(val webp: ByteArray, val firstFrame: Bitmap)

class StickerTooLargeException : Exception("Sticker doesn't fit WhatsApp's size limit")

/** Turns a photo / GIF / video into a WhatsApp-ready 512x512 WebP sticker. */
class StickerConverter(private val context: Context) {

    /** [progress] receives 0..1 and is called on a background thread. */
    suspend fun convert(
        uri: Uri,
        kind: MediaKind,
        options: ConvertOptions,
        progress: (Float) -> Unit = {},
    ): StickerResult = withContext(Dispatchers.Default) {
        if (options.animated) convertAnimated(uri, kind, options, progress)
        else convertStatic(uri, kind, options, progress)
    }

    // ------------------------------------------------------------- static

    private suspend fun convertStatic(uri: Uri, kind: MediaKind, o: ConvertOptions, progress: (Float) -> Unit): StickerResult {
        progress(0.1f)
        val source = previewSource(context, uri, kind, o.startMs)
        val sticker = render(source, o.fill)
        progress(0.5f)
        for (q in intArrayOf(92, 85, 75, 65, 55, 45, 35, 25, 15)) {
            coroutineContext.ensureActive()
            val bytes = encodeWebp(sticker, q)
            if (bytes.size <= WaSpec.STATIC_MAX_BYTES) {
                progress(1f)
                return StickerResult(bytes, sticker)
            }
        }
        throw StickerTooLargeException()
    }

    // ----------------------------------------------------------- animated

    /** A rendered 512x512 frame, kept as a high-quality WebP so long clips don't exhaust memory. */
    private class CachedFrame(val webp: ByteArray, val durationMs: Int)

    private suspend fun convertAnimated(uri: Uri, kind: MediaKind, o: ConvertOptions, progress: (Float) -> Unit): StickerResult {
        val length = o.lengthMs.coerceIn(100, WaSpec.MAX_ANIMATION_MS)
        val frames = ArrayList<CachedFrame>()
        var firstFrame: Bitmap? = null

        fun keep(source: Bitmap, durationMs: Int) {
            val rendered = render(source, o.fill)
            if (firstFrame == null) firstFrame = rendered
            frames += CachedFrame(encodeWebp(rendered, CACHE_QUALITY), durationMs)
            if (rendered !== firstFrame) rendered.recycle()
        }

        when (kind) {
            MediaKind.IMAGE -> {
                // WhatsApp requires more than one frame in an animated pack.
                val image = MediaLoader.decodeImage(context, uri)
                keep(image, 1000)
                frames += CachedFrame(frames[0].webp, 1000)
            }
            MediaKind.VIDEO -> {
                val retriever = MediaLoader.openVideo(context, uri)
                try {
                    val step = (1000 / o.fps.coerceIn(1, 30)).toLong()
                    val count = maxOf(1, (length / step).toInt())
                    for (i in 0 until count) {
                        coroutineContext.ensureActive()
                        val bmp = MediaLoader.videoFrameAt(retriever, o.startMs + i * step) ?: continue
                        keep(bmp, step.toInt())
                        bmp.recycle()
                        progress(0.6f * (i + 1) / count)
                    }
                } finally {
                    retriever.release()
                }
            }
            MediaKind.GIF -> {
                val gif = MediaLoader.openGif(context, uri)
                var done = 0
                gif.forEachFrame(o.startMs, length) { bmp, duration ->
                    keep(bmp, duration)
                    done++
                    progress(0.6f * done / gif.frameCount)
                }
            }
        }
        if (frames.isEmpty()) throw IllegalStateException("No frames could be read")
        if (frames.size == 1) frames += CachedFrame(frames[0].webp, frames[0].durationMs)
        progress(0.6f)

        // Lower quality first; if even the lowest quality is too big, drop every other frame and retry.
        var current: List<CachedFrame> = frames
        val qualities = intArrayOf(80, 60, 42, 28, 15)
        val attempts = qualities.size * 4
        var attempt = 0
        while (true) {
            for (q in qualities) {
                coroutineContext.ensureActive()
                val data = muxAt(current, q)
                attempt++
                progress(0.6f + 0.4f * attempt / attempts)
                if (data.size <= WaSpec.ANIMATED_MAX_BYTES) {
                    progress(1f)
                    return StickerResult(data, firstFrame!!)
                }
                // Far too big: lower quality won't save it, thin the frames straight away.
                if (data.size > WaSpec.ANIMATED_MAX_BYTES * 3 && current.size > 4) break
            }
            if (current.size <= 2) throw StickerTooLargeException()
            current = thin(current)
        }
    }

    private fun muxAt(frames: List<CachedFrame>, quality: Int): ByteArray {
        val encoded = frames.map { f ->
            val bmp = BitmapFactory.decodeByteArray(f.webp, 0, f.webp.size)
            val bytes = encodeWebp(bmp, quality)
            bmp.recycle()
            AnimatedWebPMuxer.Frame(bytes, maxOf(WaSpec.MIN_FRAME_MS, f.durationMs))
        }
        return AnimatedWebPMuxer.mux(encoded, WaSpec.STICKER_SIZE, WaSpec.STICKER_SIZE)
    }

    /** Halves the frame rate, keeping the total duration. */
    private fun thin(frames: List<CachedFrame>): List<CachedFrame> =
        frames.chunked(2).map { pair -> CachedFrame(pair[0].webp, pair.sumOf { it.durationMs }) }

    companion object {
        private const val CACHE_QUALITY = 95

        /** Single frame used for previews and for static stickers. */
        fun previewSource(context: Context, uri: Uri, kind: MediaKind, atMs: Long): Bitmap = when (kind) {
            MediaKind.IMAGE -> MediaLoader.decodeImage(context, uri)
            MediaKind.GIF -> MediaLoader.openGif(context, uri).firstFrameAt(atMs)
                ?: MediaLoader.decodeImage(context, uri)
            MediaKind.VIDEO -> {
                val r = MediaLoader.openVideo(context, uri)
                try {
                    MediaLoader.videoFrameAt(r, atMs) ?: throw IllegalStateException("No frame at $atMs")
                } finally {
                    r.release()
                }
            }
        }

        /** Draws [src] centred on a transparent 512x512 canvas. */
        fun render(src: Bitmap, fill: Boolean, size: Int = WaSpec.STICKER_SIZE): Bitmap {
            val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val sx = size.toFloat() / src.width
            val sy = size.toFloat() / src.height
            val scale = if (fill) maxOf(sx, sy) else minOf(sx, sy)
            val w = src.width * scale
            val h = src.height * scale
            val left = (size - w) / 2f
            val top = (size - h) / 2f
            Canvas(out).drawBitmap(src, null, RectF(left, top, left + w, top + h), Paint(Paint.FILTER_BITMAP_FLAG))
            return out
        }

        fun encodeWebp(bitmap: Bitmap, quality: Int): ByteArray {
            val out = ByteArrayOutputStream()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, quality, out)
            } else {
                // Before Android 11, quality 100 means lossless.
                @Suppress("DEPRECATION")
                bitmap.compress(Bitmap.CompressFormat.WEBP, quality.coerceAtMost(99), out)
            }
            return out.toByteArray()
        }

        /** 96x96 PNG tray icon, as WhatsApp requires. */
        fun makeTray(firstFrame: Bitmap): ByteArray {
            val tray = render(firstFrame, fill = false, size = WaSpec.TRAY_SIZE)
            val out = ByteArrayOutputStream()
            tray.compress(Bitmap.CompressFormat.PNG, 100, out)
            tray.recycle()
            return out.toByteArray()
        }
    }
}
