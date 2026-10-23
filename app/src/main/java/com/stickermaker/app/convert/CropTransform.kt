package com.stickermaker.app.convert

import android.graphics.RectF

/**
 * Where the source sits inside the square sticker, independent of the source's resolution,
 * so the crop chosen on a preview frame applies identically to every video/GIF frame.
 *
 * @param zoom 1 = whole image fits inside the square; [fillZoom] = image covers the square.
 * @param cx, cy point of the source (0..1) shown at the centre of the square.
 */
data class CropTransform(val zoom: Float, val cx: Float, val cy: Float) {

    /** Where to draw a [w]x[h] source on a [size]x[size] canvas. */
    fun destRect(w: Int, h: Int, size: Int): RectF {
        val scale = minOf(size.toFloat() / w, size.toFloat() / h) * zoom
        val dw = w * scale
        val dh = h * scale
        val left = size / 2f - cx * dw
        val top = size / 2f - cy * dh
        return RectF(left, top, left + dw, top + dh)
    }

    /** Keeps the zoom in range and never leaves an empty gap on a side the image overflows. */
    fun clamped(w: Int, h: Int): CropTransform {
        val z = zoom.coerceIn(1f, fillZoom(w, h) * MAX_ZOOM_OVER_FILL)
        val scale = minOf(1f / w, 1f / h) * z     // canvas size = 1
        return CropTransform(z, clampAxis(cx, w * scale), clampAxis(cy, h * scale))
    }

    private fun clampAxis(c: Float, extent: Float): Float =
        if (extent <= 1f) 0.5f else c.coerceIn(0.5f / extent, 1f - 0.5f / extent)

    companion object {
        private const val MAX_ZOOM_OVER_FILL = 6f

        val FIT = CropTransform(1f, 0.5f, 0.5f)

        fun fillZoom(w: Int, h: Int): Float = maxOf(w, h).toFloat() / minOf(w, h)

        fun fill(w: Int, h: Int) = CropTransform(fillZoom(w, h), 0.5f, 0.5f)
    }
}
