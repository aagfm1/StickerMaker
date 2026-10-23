package com.stickermaker.app.whatsapp

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import com.stickermaker.app.data.StickerPack
import com.stickermaker.app.provider.StickerContentProvider

object WhatsAppHelper {

    private const val CONSUMER = "com.whatsapp"
    private const val BUSINESS = "com.whatsapp.w4b"

    private const val ACTION_ENABLE_STICKER_PACK = "com.whatsapp.intent.action.ENABLE_STICKER_PACK"
    private const val EXTRA_STICKER_PACK_ID = "sticker_pack_id"
    private const val EXTRA_STICKER_PACK_AUTHORITY = "sticker_pack_authority"
    private const val EXTRA_STICKER_PACK_NAME = "sticker_pack_name"
    const val EXTRA_VALIDATION_ERROR = "validation_error"

    fun isInstalled(ctx: Context, pkg: String): Boolean = try {
        ctx.packageManager.getApplicationInfo(pkg, 0).enabled
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    fun installedApps(ctx: Context): List<String> = listOf(CONSUMER, BUSINESS).filter { isInstalled(ctx, it) }

    /** Intent that asks WhatsApp to add the pack, or null if WhatsApp isn't installed. */
    fun addPackIntent(ctx: Context, pack: StickerPack): Intent? {
        val apps = installedApps(ctx)
        if (apps.isEmpty()) return null
        val intent = Intent(ACTION_ENABLE_STICKER_PACK).apply {
            putExtra(EXTRA_STICKER_PACK_ID, pack.id)
            putExtra(EXTRA_STICKER_PACK_AUTHORITY, StickerContentProvider.AUTHORITY)
            putExtra(EXTRA_STICKER_PACK_NAME, pack.name)
        }
        // With only one WhatsApp installed, target it directly; with both, let the user choose.
        if (apps.size == 1) intent.setPackage(apps[0])
        return intent
    }

    /** True if the pack is already added in any installed WhatsApp. */
    fun isPackAdded(ctx: Context, packId: String): Boolean =
        installedApps(ctx).any { pkg -> isAddedIn(ctx, pkg, packId) }

    private fun isAddedIn(ctx: Context, pkg: String, packId: String): Boolean {
        val uri = Uri.Builder()
            .scheme("content")
            .authority("$pkg.provider.sticker_whitelist_check")
            .appendPath("is_whitelisted")
            .appendQueryParameter("authority", StickerContentProvider.AUTHORITY)
            .appendQueryParameter("identifier", packId)
            .build()
        return try {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                c.moveToFirst() && c.getInt(c.getColumnIndexOrThrow("result")) == 1
            } ?: false
        } catch (_: Exception) {
            false
        }
    }
}
