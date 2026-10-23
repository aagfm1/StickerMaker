package com.stickermaker.app.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.stickermaker.app.R
import com.stickermaker.app.convert.WaSpec
import com.stickermaker.app.data.PackRepository
import com.stickermaker.app.data.Sticker
import com.stickermaker.app.data.StickerPack
import com.stickermaker.app.databinding.ActivityPackBinding
import com.stickermaker.app.databinding.DialogStickerBinding
import com.stickermaker.app.whatsapp.WhatsAppHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PackActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_PACK_ID = "pack_id"
        fun intent(ctx: Context, packId: String) =
            Intent(ctx, PackActivity::class.java).putExtra(EXTRA_PACK_ID, packId)
    }

    private lateinit var b: ActivityPackBinding
    private lateinit var packId: String
    private var pack: StickerPack? = null
    private val adapter by lazy { StickerAdapter(this) { showStickerDialog(it) } }

    private val pickMedia = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) startActivity(EditorActivity.intent(this, packId, uri))
    }

    private val addToWhatsApp = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_CANCELED) {
            val error = result.data?.getStringExtra(WhatsAppHelper.EXTRA_VALIDATION_ERROR)
            if (error != null) {
                MaterialAlertDialogBuilder(this)
                    .setMessage(getString(R.string.whatsapp_error, error))
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
        }
        refreshAddedState()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        packId = intent.getStringExtra(EXTRA_PACK_ID) ?: return finish()
        b = ActivityPackBinding.inflate(layoutInflater)
        setupEdgeToEdge(b.root)
        setContentView(b.root)
        setSupportActionBar(b.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = ""

        b.grid.layoutManager = GridLayoutManager(this, 4)
        b.grid.adapter = adapter

        b.addButton.setOnClickListener {
            if ((pack?.stickers?.size ?: 0) >= WaSpec.MAX_STICKERS) {
                Snackbar.make(b.root, R.string.pack_full, Snackbar.LENGTH_LONG).show()
            } else {
                pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
            }
        }
        b.whatsappButton.setOnClickListener { sendToWhatsApp() }
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun load() {
        lifecycleScope.launch {
            val p = withContext(Dispatchers.IO) { PackRepository.get(applicationContext, packId) }
            if (p == null) {
                finish()
                return@launch
            }
            pack = p
            bind(p)
            refreshAddedState()
        }
    }

    private fun bind(p: StickerPack) {
        val count = p.stickers.size
        b.name.text = p.name
        b.details.text = getString(R.string.pack_subtitle, p.publisher, count,
            getString(if (p.animated) R.string.type_animated else R.string.type_static))

        val tray = PackRepository.trayFile(this, p.id)
        val trayBitmap = if (tray.exists()) BitmapFactory.decodeFile(tray.path) else null
        if (trayBitmap != null) {
            b.tray.imageTintList = null
            b.tray.setImageBitmap(trayBitmap)
        }

        adapter.submit(p)
        b.emptyText.isVisible = count == 0
        b.hint.isVisible = count < WaSpec.MIN_STICKERS
        b.whatsappButton.isEnabled = count >= WaSpec.MIN_STICKERS
        b.addButton.isEnabled = count < WaSpec.MAX_STICKERS
    }

    private fun refreshAddedState() {
        lifecycleScope.launch {
            val added = withContext(Dispatchers.IO) { WhatsAppHelper.isPackAdded(applicationContext, packId) }
            b.addedRow.isVisible = added
        }
    }

    private fun sendToWhatsApp() {
        val p = pack ?: return
        val intent = WhatsAppHelper.addPackIntent(this, p)
        if (intent == null) {
            Snackbar.make(b.root, R.string.whatsapp_not_installed, Snackbar.LENGTH_LONG).show()
            return
        }
        try {
            addToWhatsApp.launch(
                if (intent.`package` == null) Intent.createChooser(intent, getString(R.string.whatsapp_pick)) else intent
            )
        } catch (_: ActivityNotFoundException) {
            Snackbar.make(b.root, R.string.whatsapp_not_installed, Snackbar.LENGTH_LONG).show()
        }
    }

    private fun showStickerDialog(sticker: Sticker) {
        val d = DialogStickerBinding.inflate(LayoutInflater.from(this))
        d.image.background = CheckerDrawable(getColor(R.color.checker), dp(10))
        StickerImageLoader.load(d.image, PackRepository.stickerFile(this, packId, sticker.fileName), this, 512)
        d.emojiInput.setText(sticker.emojis.joinToString(""))

        MaterialAlertDialogBuilder(this)
            .setView(d.root)
            .setPositiveButton(R.string.save) { _, _ ->
                val text = d.emojiInput.text?.toString().orEmpty()
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { PackRepository.updateEmojis(applicationContext, packId, sticker.fileName, text) }
                    load()
                }
            }
            .setNeutralButton(R.string.delete) { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { PackRepository.removeSticker(applicationContext, packId, sticker.fileName) }
                    load()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.pack, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_edit -> {
            pack?.let { p ->
                PackDialog.show(this, p) { name, publisher, _ ->
                    lifecycleScope.launch {
                        withContext(Dispatchers.IO) { PackRepository.rename(applicationContext, packId, name, publisher) }
                        load()
                    }
                }
            }
            true
        }
        R.id.action_delete -> {
            pack?.let { p ->
                MaterialAlertDialogBuilder(this)
                    .setMessage(getString(R.string.delete_pack_confirm, p.name))
                    .setPositiveButton(R.string.delete) { _, _ ->
                        lifecycleScope.launch {
                            withContext(Dispatchers.IO) { PackRepository.delete(applicationContext, packId) }
                            finish()
                        }
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
