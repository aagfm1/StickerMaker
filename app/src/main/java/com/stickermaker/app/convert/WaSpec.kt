package com.stickermaker.app.convert

/** WhatsApp's technical requirements for third-party sticker packs. */
object WaSpec {
    const val STICKER_SIZE = 512
    const val TRAY_SIZE = 96

    const val STATIC_MAX_BYTES = 100 * 1024
    const val ANIMATED_MAX_BYTES = 500 * 1024
    const val TRAY_MAX_BYTES = 50 * 1024

    const val MIN_STICKERS = 3
    const val MAX_STICKERS = 30
    const val MAX_EMOJIS = 3

    const val MAX_ANIMATION_MS = 10_000L
    const val MIN_FRAME_MS = 8
}
