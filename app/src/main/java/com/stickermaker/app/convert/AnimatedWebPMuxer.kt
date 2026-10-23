package com.stickermaker.app.convert

import java.io.ByteArrayOutputStream

/**
 * Assembles an animated WebP from still WebP frames, in pure Kotlin.
 *
 * Android can encode still WebP images (Bitmap.compress) but has no animated WebP encoder,
 * so each frame is encoded on its own and its bitstream chunks (ALPH / VP8 / VP8L) are wrapped
 * into ANMF chunks of a single animated RIFF container (VP8X + ANIM + ANMF...).
 *
 * Container layout: https://developers.google.com/speed/webp/docs/riff_container
 */
object AnimatedWebPMuxer {

    class Frame(val webp: ByteArray, val durationMs: Int)

    private class Chunk(val fourCC: String, val data: ByteArray, val start: Int, val size: Int)

    /**
     * @param frames still WebP images, each exactly [width] x [height]
     * @param loopCount 0 = loop forever
     */
    fun mux(frames: List<Frame>, width: Int, height: Int, loopCount: Int = 0): ByteArray {
        require(frames.isNotEmpty()) { "No frames" }
        var hasAlpha = false
        val anmf = ByteArrayOutputStream()

        for (frame in frames) {
            val chunks = parseChunks(frame.webp)
            val image = ByteArrayOutputStream()
            var frameW = width
            var frameH = height
            for (c in chunks) {
                when (c.fourCC) {
                    "ALPH" -> {
                        hasAlpha = true
                        writeChunk(image, c)
                    }
                    "VP8 " -> {
                        // Frame header: 3-byte frame tag, 3-byte start code, then 14-bit width/height.
                        frameW = le16(c.data, c.start + 6) and 0x3FFF
                        frameH = le16(c.data, c.start + 8) and 0x3FFF
                        writeChunk(image, c)
                    }
                    "VP8L" -> {
                        // 1-byte signature, then 14 bits width-1, 14 bits height-1, 1 bit alpha.
                        val bits = le32(c.data, c.start + 1)
                        frameW = (bits and 0x3FFF) + 1
                        frameH = ((bits ushr 14) and 0x3FFF) + 1
                        if ((bits ushr 28) and 1 == 1) hasAlpha = true
                        writeChunk(image, c)
                    }
                }
            }
            require(image.size() > 0) { "Frame is not a still WebP image" }
            require(frameW <= width && frameH <= height) { "Frame larger than canvas" }

            val payload = ByteArrayOutputStream()
            writeLe24(payload, 0)                    // X offset / 2
            writeLe24(payload, 0)                    // Y offset / 2
            writeLe24(payload, frameW - 1)
            writeLe24(payload, frameH - 1)
            writeLe24(payload, frame.durationMs.coerceIn(1, 0xFFFFFF))
            payload.write(0x02)                      // no blending, no disposal: each frame fully replaces the last
            image.writeTo(payload)
            writeChunk(anmf, "ANMF", payload.toByteArray())
        }

        val vp8x = ByteArrayOutputStream().apply {
            write(0x02 or (if (hasAlpha) 0x10 else 0))   // animation (+ alpha) flags
            write(0); write(0); write(0)                 // reserved
            writeLe24(this, width - 1)
            writeLe24(this, height - 1)
        }.toByteArray()

        val anim = ByteArrayOutputStream().apply {
            write(0); write(0); write(0); write(0)       // background colour: transparent (BGRA)
            write(loopCount and 0xFF)
            write((loopCount shr 8) and 0xFF)
        }.toByteArray()

        val body = ByteArrayOutputStream()
        body.write("WEBP".toByteArray(Charsets.US_ASCII))
        writeChunk(body, "VP8X", vp8x)
        writeChunk(body, "ANIM", anim)
        anmf.writeTo(body)

        val out = ByteArrayOutputStream(body.size() + 8)
        out.write("RIFF".toByteArray(Charsets.US_ASCII))
        writeLe32(out, body.size())
        body.writeTo(out)
        return out.toByteArray()
    }

    private fun parseChunks(webp: ByteArray): List<Chunk> {
        require(webp.size >= 12 &&
            String(webp, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(webp, 8, 4, Charsets.US_ASCII) == "WEBP") { "Not a WebP file" }
        val result = ArrayList<Chunk>()
        var pos = 12
        while (pos + 8 <= webp.size) {
            val fourCC = String(webp, pos, 4, Charsets.US_ASCII)
            val size = le32(webp, pos + 4)
            val start = pos + 8
            require(size >= 0 && start + size <= webp.size) { "Corrupt WebP chunk $fourCC" }
            result += Chunk(fourCC, webp, start, size)
            pos = start + size + (size and 1)
        }
        return result
    }

    private fun writeChunk(out: ByteArrayOutputStream, c: Chunk) {
        out.write(c.fourCC.toByteArray(Charsets.US_ASCII))
        writeLe32(out, c.size)
        out.write(c.data, c.start, c.size)
        if (c.size and 1 == 1) out.write(0)
    }

    private fun writeChunk(out: ByteArrayOutputStream, fourCC: String, payload: ByteArray) {
        out.write(fourCC.toByteArray(Charsets.US_ASCII))
        writeLe32(out, payload.size)
        out.write(payload)
        if (payload.size and 1 == 1) out.write(0)
    }

    private fun le16(b: ByteArray, i: Int) = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)

    private fun le32(b: ByteArray, i: Int) =
        (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8) or
            ((b[i + 2].toInt() and 0xFF) shl 16) or ((b[i + 3].toInt() and 0xFF) shl 24)

    private fun writeLe24(out: ByteArrayOutputStream, v: Int) {
        out.write(v and 0xFF); out.write((v shr 8) and 0xFF); out.write((v shr 16) and 0xFF)
    }

    private fun writeLe32(out: ByteArrayOutputStream, v: Int) {
        writeLe24(out, v); out.write((v ushr 24) and 0xFF)
    }
}
