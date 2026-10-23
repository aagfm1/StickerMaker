package com.stickermaker.app.ui

import android.content.Context
import android.content.res.Resources
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Animatable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.AttributeSet
import android.view.View
import android.widget.ImageView
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Draws edge-to-edge and pads [root] so content stays clear of the system bars and keyboard. */
fun ComponentActivity.setupEdgeToEdge(root: View) {
    enableEdgeToEdge()
    ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
        v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
        WindowInsetsCompat.CONSUMED
    }
}

fun Context.dp(value: Int): Float = value * resources.displayMetrics.density

/** An ImageView that is always as tall as it is wide. */
class SquareImageView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : AppCompatImageView(context, attrs) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, widthMeasureSpec)
        setMeasuredDimension(measuredWidth, measuredWidth)
    }
}

/** Checkerboard background that shows where a sticker is transparent. */
class CheckerDrawable(color: Int, private val cell: Float) : Drawable() {
    private val paint = Paint().apply { this.color = color }

    override fun draw(canvas: Canvas) {
        val b = bounds
        var y = b.top.toFloat()
        var row = 0
        while (y < b.bottom) {
            var x = b.left + if (row % 2 == 0) 0f else cell
            while (x < b.right) {
                canvas.drawRect(x, y, minOf(x + cell, b.right.toFloat()), minOf(y + cell, b.bottom.toFloat()), paint)
                x += cell * 2
            }
            y += cell
            row++
        }
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/** Loads sticker files off the main thread; animated WebP stickers play on Android 9+. */
object StickerImageLoader {

    fun load(view: ImageView, file: File, owner: LifecycleOwner, size: Int = 256) {
        val key = file.path + ":" + file.lastModified()
        view.tag = key
        view.setImageDrawable(null)
        owner.lifecycleScope.launch {
            val drawable = withContext(Dispatchers.IO) { decode(view.resources, file, size) }
            if (view.tag == key) {
                view.setImageDrawable(drawable)
                (drawable as? Animatable)?.start()
            }
        }
    }

    fun decode(res: Resources, file: File, size: Int): Drawable? = try {
        if (!file.exists()) null
        else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ImageDecoder.decodeDrawable(ImageDecoder.createSource(file)) { decoder, info, _ ->
                if (info.size.width > size) decoder.setTargetSize(size, size * info.size.height / info.size.width)
            }
        } else {
            BitmapFactory.decodeFile(file.path)?.let { BitmapDrawable(res, it) }
        }
    } catch (_: Exception) {
        null
    }
}
