package com.stickermaker.app.ui

import android.app.Activity
import android.content.Context
import android.view.LayoutInflater
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.stickermaker.app.R
import com.stickermaker.app.data.StickerPack
import com.stickermaker.app.databinding.DialogPackBinding

/** Dialog for creating a pack, or renaming one when [existing] is given. */
object PackDialog {

    private const val PREFS = "settings"
    private const val KEY_PUBLISHER = "publisher"

    fun show(
        activity: Activity,
        existing: StickerPack? = null,
        onDone: (name: String, publisher: String, animated: Boolean) -> Unit,
    ) {
        val d = DialogPackBinding.inflate(LayoutInflater.from(activity))
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        d.nameInput.setText(existing?.name.orEmpty())
        d.publisherInput.setText(existing?.publisher ?: prefs.getString(KEY_PUBLISHER, ""))
        d.typeSection.isVisible = existing == null

        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(if (existing == null) R.string.new_pack else R.string.edit_pack)
            .setView(d.root)
            .setPositiveButton(if (existing == null) R.string.create else R.string.save, null)
            .setNegativeButton(R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = d.nameInput.text?.toString()?.trim().orEmpty()
                val publisher = d.publisherInput.text?.toString()?.trim().orEmpty()
                d.nameLayout.error = if (name.isEmpty()) activity.getString(R.string.required_field) else null
                d.publisherLayout.error = if (publisher.isEmpty()) activity.getString(R.string.required_field) else null
                if (name.isEmpty() || publisher.isEmpty()) return@setOnClickListener

                prefs.edit().putString(KEY_PUBLISHER, publisher).apply()
                val animated = existing?.animated ?: (d.typeGroup.checkedButtonId == R.id.typeAnimated)
                dialog.dismiss()
                onDone(name, publisher, animated)
            }
        }
        dialog.show()
        if (existing == null) d.nameInput.requestFocus()
    }
}
