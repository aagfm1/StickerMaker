package com.stickermaker.app.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.stickermaker.app.BuildConfig
import com.stickermaker.app.convert.WaSpec
import com.stickermaker.app.data.PackRepository
import com.stickermaker.app.data.StickerPack

/**
 * Exposes the packs to WhatsApp, following the contract of WhatsApp's official
 * sticker sample app (github.com/WhatsApp/stickers).
 *
 *   content://<authority>/metadata                     all packs
 *   content://<authority>/metadata/<packId>            one pack
 *   content://<authority>/stickers/<packId>            stickers of a pack
 *   content://<authority>/stickers_asset/<packId>/<f>  a sticker or tray image
 */
class StickerContentProvider : ContentProvider() {

    companion object {
        val AUTHORITY: String = BuildConfig.CONTENT_PROVIDER_AUTHORITY

        private const val METADATA = "metadata"
        private const val STICKERS = "stickers"
        private const val STICKERS_ASSET = "stickers_asset"

        private const val ALL_PACKS = 1
        private const val ONE_PACK = 2
        private const val PACK_STICKERS = 3
        private const val ASSET = 4

        // Pack columns
        const val STICKER_PACK_IDENTIFIER = "sticker_pack_identifier"
        const val STICKER_PACK_NAME = "sticker_pack_name"
        const val STICKER_PACK_PUBLISHER = "sticker_pack_publisher"
        const val STICKER_PACK_ICON = "sticker_pack_icon"
        const val ANDROID_APP_DOWNLOAD_LINK = "android_play_store_link"
        const val IOS_APP_DOWNLOAD_LINK = "ios_app_download_link"
        const val PUBLISHER_EMAIL = "sticker_pack_publisher_email"
        const val PUBLISHER_WEBSITE = "sticker_pack_publisher_website"
        const val PRIVACY_POLICY_WEBSITE = "sticker_pack_privacy_policy_website"
        const val LICENSE_AGREEMENT_WEBSITE = "sticker_pack_license_agreement_website"
        const val IMAGE_DATA_VERSION = "image_data_version"
        const val AVOID_CACHE = "whatsapp_will_not_cache_stickers"
        const val ANIMATED_STICKER_PACK = "animated_sticker_pack"

        // Sticker columns
        const val STICKER_FILE_NAME = "sticker_file_name"
        const val STICKER_FILE_EMOJI = "sticker_emoji"
        const val STICKER_ACCESSIBILITY_TEXT = "sticker_accessibility_text"
    }

    private val matcher = UriMatcher(UriMatcher.NO_MATCH).apply {
        addURI(AUTHORITY, METADATA, ALL_PACKS)
        addURI(AUTHORITY, "$METADATA/*", ONE_PACK)
        addURI(AUTHORITY, "$STICKERS/*", PACK_STICKERS)
        addURI(AUTHORITY, "$STICKERS_ASSET/*/*", ASSET)
    }

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?,
    ): Cursor {
        val ctx = requireNotNull(context)
        val cursor = when (matcher.match(uri)) {
            ALL_PACKS -> packsCursor(PackRepository.all(ctx).filter { it.stickers.size >= WaSpec.MIN_STICKERS })
            ONE_PACK -> packsCursor(listOfNotNull(PackRepository.get(ctx, uri.lastPathSegment.orEmpty())))
            PACK_STICKERS -> stickersCursor(PackRepository.get(ctx, uri.lastPathSegment.orEmpty()))
            else -> throw IllegalArgumentException("Unknown URI: $uri")
        }
        cursor.setNotificationUri(ctx.contentResolver, uri)
        return cursor
    }

    private fun packsCursor(packs: List<StickerPack>): Cursor {
        val c = MatrixCursor(arrayOf(
            STICKER_PACK_IDENTIFIER, STICKER_PACK_NAME, STICKER_PACK_PUBLISHER, STICKER_PACK_ICON,
            ANDROID_APP_DOWNLOAD_LINK, IOS_APP_DOWNLOAD_LINK, PUBLISHER_EMAIL, PUBLISHER_WEBSITE,
            PRIVACY_POLICY_WEBSITE, LICENSE_AGREEMENT_WEBSITE, IMAGE_DATA_VERSION, AVOID_CACHE,
            ANIMATED_STICKER_PACK,
        ))
        for (p in packs) {
            c.newRow()
                .add(STICKER_PACK_IDENTIFIER, p.id)
                .add(STICKER_PACK_NAME, p.name)
                .add(STICKER_PACK_PUBLISHER, p.publisher)
                .add(STICKER_PACK_ICON, StickerPack.TRAY_FILE)
                .add(ANDROID_APP_DOWNLOAD_LINK, "")
                .add(IOS_APP_DOWNLOAD_LINK, "")
                .add(PUBLISHER_EMAIL, "")
                .add(PUBLISHER_WEBSITE, "")
                .add(PRIVACY_POLICY_WEBSITE, "")
                .add(LICENSE_AGREEMENT_WEBSITE, "")
                .add(IMAGE_DATA_VERSION, p.imageDataVersion.toString())
                .add(AVOID_CACHE, 0)
                .add(ANIMATED_STICKER_PACK, if (p.animated) 1 else 0)
        }
        return c
    }

    private fun stickersCursor(pack: StickerPack?): Cursor {
        val c = MatrixCursor(arrayOf(STICKER_FILE_NAME, STICKER_FILE_EMOJI, STICKER_ACCESSIBILITY_TEXT))
        pack?.stickers?.forEach { s ->
            c.addRow(arrayOf<Any>(s.fileName, s.emojis.joinToString(","), ""))
        }
        return c
    }

    override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor? {
        if (matcher.match(uri) != ASSET) throw IllegalArgumentException("Unknown URI: $uri")
        val segments = uri.pathSegments
        if (segments.size != 3) throw IllegalArgumentException("Bad asset URI: $uri")
        val ctx = requireNotNull(context)
        val packId = segments[1]
        val fileName = segments[2]

        // Only serve files that belong to the pack — never arbitrary paths.
        val pack = PackRepository.get(ctx, packId) ?: return null
        val allowed = fileName == StickerPack.TRAY_FILE || pack.stickers.any { it.fileName == fileName }
        if (!allowed) return null
        val file = PackRepository.stickerFile(ctx, packId, fileName)
        if (!file.exists()) return null

        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        return AssetFileDescriptor(pfd, 0, AssetFileDescriptor.UNKNOWN_LENGTH)
    }

    override fun getType(uri: Uri): String = when (matcher.match(uri)) {
        ALL_PACKS -> "vnd.android.cursor.dir/vnd.$AUTHORITY.$METADATA"
        ONE_PACK -> "vnd.android.cursor.item/vnd.$AUTHORITY.$METADATA"
        PACK_STICKERS -> "vnd.android.cursor.dir/vnd.$AUTHORITY.$STICKERS"
        ASSET -> if (uri.lastPathSegment.orEmpty().endsWith(".png")) "image/png" else "image/webp"
        else -> throw IllegalArgumentException("Unknown URI: $uri")
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()
}
