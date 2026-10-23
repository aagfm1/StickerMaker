package com.stickermaker.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.stickermaker.app.convert.StickerConverter
import org.json.JSONObject
import java.io.File
import android.icu.text.BreakIterator

/**
 * Stores packs under files/packs/<id>/ : pack.json, tray.png and the .webp stickers.
 * Also read by [com.stickermaker.app.provider.StickerContentProvider], so all access is synchronized.
 */
object PackRepository {

    private const val ROOT = "packs"
    private const val META = "pack.json"
    const val DEFAULT_EMOJI = "😀"

    private fun root(ctx: Context) = File(ctx.filesDir, ROOT).apply { mkdirs() }

    fun packDir(ctx: Context, id: String) = File(root(ctx), id)

    fun stickerFile(ctx: Context, packId: String, fileName: String) = File(packDir(ctx, packId), fileName)

    fun trayFile(ctx: Context, packId: String) = File(packDir(ctx, packId), StickerPack.TRAY_FILE)

    @Synchronized
    fun all(ctx: Context): List<StickerPack> =
        root(ctx).listFiles()?.filter { it.isDirectory }?.mapNotNull { read(it) }
            ?.sortedByDescending { it.createdAt } ?: emptyList()

    @Synchronized
    fun get(ctx: Context, id: String): StickerPack? {
        if (!isValidId(id)) return null
        return read(packDir(ctx, id))
    }

    @Synchronized
    fun create(ctx: Context, name: String, publisher: String, animated: Boolean): StickerPack {
        val now = System.currentTimeMillis()
        val pack = StickerPack(
            id = "pack_$now",
            name = name.trim().take(128),
            publisher = publisher.trim().take(128),
            animated = animated,
            imageDataVersion = 1,
            createdAt = now,
            stickers = emptyList(),
        )
        packDir(ctx, pack.id).mkdirs()
        write(ctx, pack)
        return pack
    }

    @Synchronized
    fun addSticker(ctx: Context, packId: String, webp: ByteArray, firstFrame: Bitmap, emojiText: String): StickerPack {
        val pack = requireNotNull(get(ctx, packId)) { "Pack not found" }
        val fileName = "s_${System.currentTimeMillis()}.webp"
        writeAtomically(stickerFile(ctx, packId, fileName), webp)
        if (pack.stickers.isEmpty()) {
            writeAtomically(trayFile(ctx, packId), StickerConverter.makeTray(firstFrame))
        }
        val updated = pack.copy(
            stickers = pack.stickers + Sticker(fileName, parseEmojis(emojiText)),
            imageDataVersion = pack.imageDataVersion + 1,
        )
        write(ctx, updated)
        return updated
    }

    @Synchronized
    fun removeSticker(ctx: Context, packId: String, fileName: String): StickerPack? {
        val pack = get(ctx, packId) ?: return null
        val wasTraySource = pack.stickers.firstOrNull()?.fileName == fileName
        val remaining = pack.stickers.filterNot { it.fileName == fileName }
        stickerFile(ctx, packId, fileName).delete()
        val updated = pack.copy(stickers = remaining, imageDataVersion = pack.imageDataVersion + 1)
        write(ctx, updated)
        if (wasTraySource) rebuildTray(ctx, updated)
        return updated
    }

    @Synchronized
    fun updateEmojis(ctx: Context, packId: String, fileName: String, emojiText: String): StickerPack? {
        val pack = get(ctx, packId) ?: return null
        val updated = pack.copy(
            stickers = pack.stickers.map { if (it.fileName == fileName) it.copy(emojis = parseEmojis(emojiText)) else it },
            imageDataVersion = pack.imageDataVersion + 1,
        )
        write(ctx, updated)
        return updated
    }

    @Synchronized
    fun rename(ctx: Context, packId: String, name: String, publisher: String): StickerPack? {
        val pack = get(ctx, packId) ?: return null
        val updated = pack.copy(
            name = name.trim().take(128),
            publisher = publisher.trim().take(128),
            imageDataVersion = pack.imageDataVersion + 1,
        )
        write(ctx, updated)
        return updated
    }

    @Synchronized
    fun delete(ctx: Context, packId: String) {
        if (isValidId(packId)) packDir(ctx, packId).deleteRecursively()
    }

    /** Splits text into emoji characters (graphemes), keeping at most three. */
    fun parseEmojis(text: String): List<String> {
        val it = BreakIterator.getCharacterInstance()
        it.setText(text)
        val result = ArrayList<String>()
        var start = it.first()
        var end = it.next()
        while (end != BreakIterator.DONE && result.size < 3) {
            val g = text.substring(start, end)
            if (g.isNotBlank() && g != ",") result += g
            start = end
            end = it.next()
        }
        return result.ifEmpty { listOf(DEFAULT_EMOJI) }
    }

    fun isValidId(id: String) = id.isNotEmpty() && id.length <= 128 && id.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in "_-. " } && !id.contains("..")

    // ------------------------------------------------------------------ io

    private fun rebuildTray(ctx: Context, pack: StickerPack) {
        val first = pack.stickers.firstOrNull() ?: run { trayFile(ctx, pack.id).delete(); return }
        val bmp = BitmapFactory.decodeFile(stickerFile(ctx, pack.id, first.fileName).path) ?: return
        writeAtomically(trayFile(ctx, pack.id), StickerConverter.makeTray(bmp))
        bmp.recycle()
    }

    private fun read(dir: File): StickerPack? = try {
        StickerPack.fromJson(JSONObject(File(dir, META).readText()))
    } catch (_: Exception) {
        null
    }

    private fun write(ctx: Context, pack: StickerPack) {
        writeAtomically(File(packDir(ctx, pack.id), META), pack.toJson().toString().toByteArray())
    }

    private fun writeAtomically(target: File, bytes: ByteArray) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(target)) {
            target.delete()
            tmp.renameTo(target)
        }
    }
}
