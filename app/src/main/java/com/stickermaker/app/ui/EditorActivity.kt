package com.stickermaker.app.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.LabelFormatter
import com.google.android.material.slider.RangeSlider
import com.google.android.material.slider.Slider
import com.stickermaker.app.R
import com.stickermaker.app.convert.ConvertOptions
import com.stickermaker.app.convert.CropTransform
import com.stickermaker.app.convert.MediaKind
import com.stickermaker.app.convert.MediaLoader
import com.stickermaker.app.convert.StickerConverter
import com.stickermaker.app.convert.StickerTooLargeException
import com.stickermaker.app.convert.WaSpec
import com.stickermaker.app.data.PackRepository
import com.stickermaker.app.data.StickerPack
import com.stickermaker.app.databinding.ActivityEditorBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class EditorActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_PACK_ID = "pack_id"
        fun intent(ctx: Context, packId: String, uri: Uri) =
            Intent(ctx, EditorActivity::class.java)
                .putExtra(EXTRA_PACK_ID, packId)
                .setData(uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    private lateinit var b: ActivityEditorBinding
    private lateinit var pack: StickerPack
    private lateinit var uri: Uri
    private lateinit var kind: MediaKind
    private var durationMs = 0L
    private var previewJob: Job? = null
    private var previewShown = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val packId = intent.getStringExtra(EXTRA_PACK_ID)
        val data = intent.data
        if (packId == null || data == null) return finish()
        uri = data

        b = ActivityEditorBinding.inflate(layoutInflater)
        setupEdgeToEdge(b.root)
        setContentView(b.root)
        setSupportActionBar(b.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        b.makeButton.isEnabled = false

        lifecycleScope.launch {
            val p = withContext(Dispatchers.IO) { PackRepository.get(applicationContext, packId) }
            if (p == null) return@launch finish()
            pack = p
            kind = MediaLoader.kindOf(applicationContext, uri)
            durationMs = withContext(Dispatchers.IO) { readDuration() }
            if (durationMs < 0) {
                Toast.makeText(this@EditorActivity, R.string.error_open, Toast.LENGTH_LONG).show()
                return@launch finish()
            }
            try {
                setupControls()
            } catch (_: Exception) {
                // A bad duration from the file must not take the whole app down.
                b.timeSection.isVisible = false
            }
            refreshPreview()
            b.makeButton.isEnabled = true
        }

        b.framingFit.setOnClickListener { b.preview.setCrop(CropTransform.FIT) }
        b.framingFill.setOnClickListener { b.preview.fill() }
        b.makeButton.setOnClickListener { makeSticker() }
    }

    private fun readDuration(): Long = try {
        when (kind) {
            MediaKind.IMAGE -> 0L
            MediaKind.GIF -> MediaLoader.openGif(applicationContext, uri).durationMs
            MediaKind.VIDEO -> {
                val r = MediaLoader.openVideo(applicationContext, uri)
                try { MediaLoader.videoDurationMs(r) } finally { r.release() }
            }
        }
    } catch (_: Exception) {
        -1L
    }

    // ------------------------------------------------------------ controls

    private fun setupControls() {
        val hasTime = kind != MediaKind.IMAGE && durationMs > 100
        b.staticNote.isVisible = !pack.animated && kind != MediaKind.IMAGE
        b.fpsSection.isVisible = pack.animated && kind == MediaKind.VIDEO
        b.timeSection.isVisible = hasTime
        if (!hasTime) return

        val totalSec = durationMs / 1000f
        val formatter = LabelFormatter { v -> String.format(Locale.getDefault(), "%.1f", v) }

        if (pack.animated) {
            b.timeTitle.setText(R.string.clip)
            val defaultLen = if (kind == MediaKind.GIF) 10f else 3f
            b.rangeSlider.apply {
                isVisible = true
                valueFrom = 0f
                valueTo = totalSec
                setMinSeparationValue(minOf(0.2f, totalSec / 2))
                setValues(0f, minOf(totalSec, defaultLen))
                setLabelFormatter(formatter)
                addOnChangeListener { _, _, _ -> updateTimeLabel() }
                addOnSliderTouchListener(object : RangeSlider.OnSliderTouchListener {
                    override fun onStartTrackingTouch(slider: RangeSlider) = Unit
                    override fun onStopTrackingTouch(slider: RangeSlider) = refreshPreview()
                })
            }
        } else {
            b.timeTitle.setText(R.string.frame)
            b.frameSlider.apply {
                isVisible = true
                valueFrom = 0f
                valueTo = totalSec
                value = 0f
                setLabelFormatter(formatter)
                addOnChangeListener { _, _, _ -> updateTimeLabel() }
                addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
                    override fun onStartTrackingTouch(slider: Slider) = Unit
                    override fun onStopTrackingTouch(slider: Slider) = refreshPreview()
                })
            }
        }
        updateTimeLabel()
    }

    /** Selected clip in ms; anything past 10 s is cut, as WhatsApp requires. */
    private fun clip(): Pair<Long, Long> {
        if (!b.timeSection.isVisible) return 0L to minOf(durationMs.takeIf { it > 0 } ?: 1000L, WaSpec.MAX_ANIMATION_MS)
        if (!pack.animated) return (b.frameSlider.value * 1000).toLong() to 0L
        val v = b.rangeSlider.values
        val start = (v[0] * 1000).toLong()
        val end = minOf((v[1] * 1000).toLong(), start + WaSpec.MAX_ANIMATION_MS)
        return start to maxOf(end, start + 100)
    }

    private fun updateTimeLabel() {
        val (start, end) = clip()
        b.timeLabel.text = if (pack.animated) {
            getString(R.string.clip_label, start / 1000f, end / 1000f, (end - start) / 1000f)
        } else {
            getString(R.string.frame_label, start / 1000f)
        }
    }

    private fun fps() = when (b.fpsGroup.checkedButtonId) {
        R.id.fps8 -> 8
        R.id.fps15 -> 15
        else -> 10
    }

    // ------------------------------------------------------------- preview

    private fun refreshPreview() {
        previewJob?.cancel()
        b.previewLoading.isVisible = true
        val at = clip().first
        previewJob = lifecycleScope.launch {
            val bitmap: Bitmap? = withContext(Dispatchers.IO) {
                try {
                    MediaLoader.software(StickerConverter.previewSource(applicationContext, uri, kind, at))
                } catch (_: Throwable) {
                    null
                }
            }
            b.previewLoading.isVisible = false
            if (bitmap != null) {
                b.preview.setImage(bitmap)
                previewShown = true
            } else if (!previewShown) {
                Toast.makeText(this@EditorActivity, R.string.error_open, Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    // ------------------------------------------------------------ convert

    private fun makeSticker() {
        val (start, end) = clip()
        val options = ConvertOptions(
            crop = b.preview.crop,
            startMs = start,
            lengthMs = end - start,
            fps = fps(),
            animated = pack.animated,
        )
        val emojiText = b.emojiInput.text?.toString().orEmpty()
        val showFramesPhase = pack.animated && kind != MediaKind.IMAGE
        setWorking(true)

        lifecycleScope.launch {
            try {
                val result = StickerConverter(applicationContext).convert(uri, kind, options) { p ->
                    runOnUiThread {
                        b.progress.setProgressCompat((p * 100).toInt(), true)
                        b.status.setText(if (showFramesPhase && p < 0.6f) R.string.working_frames else R.string.working_compress)
                    }
                }
                val saved = withContext(Dispatchers.IO) {
                    val current = PackRepository.get(applicationContext, pack.id)
                    if (current == null || current.stickers.size >= WaSpec.MAX_STICKERS) false
                    else {
                        PackRepository.addSticker(applicationContext, pack.id, result.webp, result.firstFrame, emojiText)
                        true
                    }
                }
                Toast.makeText(
                    this@EditorActivity,
                    if (saved) R.string.sticker_saved else R.string.pack_full,
                    Toast.LENGTH_SHORT,
                ).show()
                finish()
            } catch (e: CancellationException) {
                throw e
            } catch (_: StickerTooLargeException) {
                setWorking(false)
                showError(R.string.error_too_large)
            } catch (_: Throwable) {
                setWorking(false)
                showError(R.string.error_convert)
            }
        }
    }

    private fun setWorking(working: Boolean) {
        b.progress.isVisible = working
        b.status.isVisible = working
        if (working) {
            b.progress.setProgressCompat(0, false)
            b.status.setText(R.string.working_frames)
        }
        b.makeButton.isEnabled = !working
        b.framingFit.isEnabled = !working
        b.framingFill.isEnabled = !working
        b.preview.isEnabled = !working
        b.fpsGroup.isEnabled = !working
        b.rangeSlider.isEnabled = !working
        b.frameSlider.isEnabled = !working
        b.emojiInput.isEnabled = !working
    }

    private fun showError(message: Int) {
        MaterialAlertDialogBuilder(this)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
