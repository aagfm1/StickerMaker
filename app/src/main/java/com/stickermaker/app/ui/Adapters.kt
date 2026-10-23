package com.stickermaker.app.ui

import android.annotation.SuppressLint
import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.lifecycle.LifecycleOwner
import androidx.recyclerview.widget.RecyclerView
import com.stickermaker.app.R
import com.stickermaker.app.data.PackRepository
import com.stickermaker.app.data.Sticker
import com.stickermaker.app.data.StickerPack
import com.stickermaker.app.databinding.ItemPackBinding
import com.stickermaker.app.databinding.ItemStickerBinding

class PackAdapter(private val onClick: (StickerPack) -> Unit) : RecyclerView.Adapter<PackAdapter.Holder>() {

    class Holder(val b: ItemPackBinding) : RecyclerView.ViewHolder(b.root)

    private var items: List<StickerPack> = emptyList()

    @SuppressLint("NotifyDataSetChanged")
    fun submit(list: List<StickerPack>) {
        items = list
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemPackBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val pack = items[position]
        val ctx = holder.itemView.context
        holder.b.name.text = pack.name
        holder.b.subtitle.text = ctx.getString(
            R.string.pack_subtitle,
            pack.publisher,
            pack.stickers.size,
            ctx.getString(if (pack.animated) R.string.type_animated else R.string.type_static),
        )
        val tray = PackRepository.trayFile(ctx, pack.id)
        val bmp = if (tray.exists()) BitmapFactory.decodeFile(tray.path) else null
        if (bmp != null) {
            holder.b.tray.imageTintList = null
            holder.b.tray.setImageBitmap(bmp)
        } else {
            holder.b.tray.setImageResource(R.drawable.ic_sticker_outline)
            holder.b.tray.imageTintList = android.content.res.ColorStateList.valueOf(
                com.google.android.material.color.MaterialColors.getColor(holder.b.tray, com.google.android.material.R.attr.colorOutline)
            )
        }
        holder.itemView.setOnClickListener { onClick(pack) }
    }
}

class StickerAdapter(
    private val owner: LifecycleOwner,
    private val onClick: (Sticker) -> Unit,
) : RecyclerView.Adapter<StickerAdapter.Holder>() {

    class Holder(val b: ItemStickerBinding) : RecyclerView.ViewHolder(b.root)

    private var packId: String = ""
    private var items: List<Sticker> = emptyList()

    @SuppressLint("NotifyDataSetChanged")
    fun submit(pack: StickerPack) {
        packId = pack.id
        items = pack.stickers
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemStickerBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val sticker = items[position]
        val file = PackRepository.stickerFile(holder.itemView.context, packId, sticker.fileName)
        StickerImageLoader.load(holder.b.image, file, owner)
        holder.b.image.contentDescription = sticker.emojis.joinToString(" ")
        holder.itemView.setOnClickListener { onClick(sticker) }
    }
}
