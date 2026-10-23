package com.stickermaker.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import com.google.android.material.color.MaterialColors
import com.stickermaker.app.R
import com.stickermaker.app.convert.CropTransform

/** Square sticker preview: drag to move the image, pinch to zoom. */
class CropView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs) {

    var bitmap: Bitmap? = null
        private set
    var crop: CropTransform = CropTransform.FIT
        private set

    private val checker = CheckerDrawable(context.getColor(R.color.checker), context.dp(14))
    private val imagePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dp(2)
        color = MaterialColors.getColor(context, com.google.android.material.R.attr.colorPrimary, 0xFF0B8A6F.toInt())
    }

    private var lastX = 0f
    private var lastY = 0f
    private var activePointerId = MotionEvent.INVALID_POINTER_ID

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            zoomBy(detector.scaleFactor, detector.focusX, detector.focusY)
            return true
        }
    })

    /** Shows a new frame of the same media; the current crop is kept. */
    fun setImage(bmp: Bitmap) {
        bitmap = bmp
        crop = crop.clamped(bmp.width, bmp.height)
        invalidate()
    }

    fun setCrop(transform: CropTransform) {
        val bmp = bitmap
        crop = if (bmp != null) transform.clamped(bmp.width, bmp.height) else transform
        invalidate()
    }

    fun fill() {
        bitmap?.let { setCrop(CropTransform.fill(it.width, it.height)) }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, w)
    }

    override fun onDraw(canvas: Canvas) {
        checker.setBounds(0, 0, width, height)
        checker.draw(canvas)
        bitmap?.let { bmp ->
            canvas.save()
            canvas.clipRect(0, 0, width, height)
            canvas.drawBitmap(bmp, null, crop.destRect(bmp.width, bmp.height, width), imagePaint)
            canvas.restore()
        }
        val half = borderPaint.strokeWidth / 2
        canvas.drawRect(half, half, width - half, height - half, borderPaint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled || bitmap == null) return false
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                activePointerId = event.getPointerId(0)
                lastX = event.x
                lastY = event.y
            }
            MotionEvent.ACTION_MOVE -> {
                val index = event.findPointerIndex(activePointerId)
                if (index >= 0) {
                    val x = event.getX(index)
                    val y = event.getY(index)
                    if (!scaleDetector.isInProgress) panBy(x - lastX, y - lastY)
                    lastX = x
                    lastY = y
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val upIndex = event.actionIndex
                if (event.getPointerId(upIndex) == activePointerId) {
                    val newIndex = if (upIndex == 0) 1 else 0
                    activePointerId = event.getPointerId(newIndex)
                    lastX = event.getX(newIndex)
                    lastY = event.getY(newIndex)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activePointerId = MotionEvent.INVALID_POINTER_ID
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }

    /** Pixels on screen per source pixel. */
    private fun pixelScale(bmp: Bitmap, z: Float) =
        minOf(width.toFloat() / bmp.width, height.toFloat() / bmp.height) * z

    private fun panBy(dx: Float, dy: Float) {
        val bmp = bitmap ?: return
        val s = pixelScale(bmp, crop.zoom)
        setCrop(crop.copy(cx = crop.cx - dx / (bmp.width * s), cy = crop.cy - dy / (bmp.height * s)))
    }

    private fun zoomBy(factor: Float, focusX: Float, focusY: Float) {
        val bmp = bitmap ?: return
        val old = pixelScale(bmp, crop.zoom)
        // Source point currently under the fingers.
        val sx = crop.cx + (focusX - width / 2f) / (bmp.width * old)
        val sy = crop.cy + (focusY - height / 2f) / (bmp.height * old)
        val zoom = (crop.zoom * factor).coerceAtLeast(1f)
        val new = pixelScale(bmp, zoom)
        // Keep that point under the fingers after zooming.
        setCrop(CropTransform(
            zoom,
            sx - (focusX - width / 2f) / (bmp.width * new),
            sy - (focusY - height / 2f) / (bmp.height * new),
        ))
    }
}
