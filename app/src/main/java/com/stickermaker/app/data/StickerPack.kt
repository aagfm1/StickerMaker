package com.stickermaker.app.data

import org.json.JSONArray
import org.json.JSONObject

data class Sticker(
    val fileName: String,
    val emojis: List<String>,
)

data class StickerPack(
    /** Only letters, digits, '_', '-', '.' and spaces are allowed by WhatsApp. */
    val id: String,
    val name: String,
    val publisher: String,
    val animated: Boolean,
    /** Bumped on every change so WhatsApp refreshes its cached copy. */
    val imageDataVersion: Int,
    val createdAt: Long,
    val stickers: List<Sticker>,
) {
    val hasTray: Boolean get() = stickers.isNotEmpty()

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("publisher", publisher)
        put("animated", animated)
        put("version", imageDataVersion)
        put("createdAt", createdAt)
        put("stickers", JSONArray().apply {
            stickers.forEach { s ->
                put(JSONObject().apply {
                    put("file", s.fileName)
                    put("emojis", JSONArray(s.emojis))
                })
            }
        })
    }

    companion object {
        const val TRAY_FILE = "tray.png"

        fun fromJson(o: JSONObject): StickerPack {
            val arr = o.optJSONArray("stickers") ?: JSONArray()
            val stickers = (0 until arr.length()).map { i ->
                val s = arr.getJSONObject(i)
                val e = s.optJSONArray("emojis") ?: JSONArray()
                Sticker(s.getString("file"), (0 until e.length()).map { e.getString(it) })
            }
            return StickerPack(
                id = o.getString("id"),
                name = o.getString("name"),
                publisher = o.optString("publisher"),
                animated = o.optBoolean("animated"),
                imageDataVersion = o.optInt("version", 1),
                createdAt = o.optLong("createdAt"),
                stickers = stickers,
            )
        }
    }
}
