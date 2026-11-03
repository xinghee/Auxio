/*
 * Copyright (c) 2025 Auxio Project
 * Id3v2Rewriter.kt is part of Auxio.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
 
package org.oxycblt.musikr.tag.write

import org.oxycblt.musikr.util.tryRepairLegacyEncoding

/** A single text value that would change if this tag were rewritten. */
internal data class TagTextFix(val frameId: String, val before: String, val after: String)

/**
 * Result of inspecting an ID3v2 tag.
 *
 * @property versionMajor 3 or 4, the version that [rewrite] preserves.
 * @property tagSize Total size of the tag including its 10 byte header.
 * @property fixes Text values that look like mislabelled GBK and can be repaired.
 */
internal class Id3TagAnalysis(
    val versionMajor: Int,
    val versionMinor: Int,
    val tagSize: Int,
    val fixes: List<TagTextFix>,
    internal val frames: List<RawFrame>,
)

/** A frame exactly as it sits in the file, plus its decoded text when we know how to read it. */
internal class RawFrame(
    val id: String,
    val header: ByteArray,
    val content: ByteArray,
    val encoding: Int,
    val texts: List<String>?,
)

/**
 * Rewrites the text encoding of an ID3v2 tag without touching anything else.
 *
 * The tag is decoded into frames, only the text frames that actually look like GBK-mislabelled-as-
 * Latin-1 get re-encoded, and every other frame (cover art, unsynchronised data, …) is copied
 * through byte for byte. The ID3 version is never changed: 2.3 keeps UTF-16, 2.4 keeps UTF-8, so
 * no frame has to be rewritten into a different frame id.
 */
internal object Id3v2Rewriter {
    private const val TAG_HEADER = 10
    private const val FRAME_HEADER = 10

    private const val ENC_LATIN1 = 0
    private const val ENC_UTF16 = 1
    private const val ENC_UTF16BE = 2
    private const val ENC_UTF8 = 3

    /**
     * Inspect [bytes] (the whole file) and report which text frames could be repaired.
     *
     * @return null when the file has no usable ID3v2 tag, or has one we refuse to touch
     *   (unsynchronised, extended header, footer).
     */
    fun analyze(bytes: ByteArray): Id3TagAnalysis? {
        if (bytes.size < TAG_HEADER) return null
        if (bytes[0] != 'I'.code.toByte() ||
            bytes[1] != 'D'.code.toByte() ||
            bytes[2] != '3'.code.toByte()) {
            return null
        }

        val major = bytes[3].toInt() and 0xFF
        val minor = bytes[4].toInt() and 0xFF
        if (major != 3 && major != 4) return null

        val flags = bytes[5].toInt() and 0xFF
        // Unsynchronisation would mean every frame's bytes are escaped, and we parse raw ones.
        if (flags and 0x80 != 0) return null
        // Extended header shifts every frame; footer shifts the end.
        if (flags and 0x40 != 0) return null
        if (major == 4 && flags and 0x10 != 0) return null

        val bodySize = synchsafe(bytes, 6)
        if (bodySize <= 0) return null
        val end = TAG_HEADER + bodySize
        if (end > bytes.size) return null

        val frames = mutableListOf<RawFrame>()
        var pos = TAG_HEADER
        while (pos + FRAME_HEADER <= end) {
            val id = frameId(bytes, pos) ?: break // Padding or garbage marks the end.
            val size = if (major == 4) synchsafe(bytes, pos + 4) else beInt(bytes, pos + 4)
            if (size <= 0 || pos + FRAME_HEADER + size > end) break

            val header = bytes.copyOfRange(pos, pos + FRAME_HEADER)
            val content = bytes.copyOfRange(pos + FRAME_HEADER, pos + FRAME_HEADER + size)
            // Status/format flags: compression, encryption, grouping and data-length indicators
            // all mean the content is not plain text. Copy those through untouched.
            val formatFlags = bytes[pos + 9].toInt() and 0xFF
            val plain = formatFlags == 0

            val parsed = if (plain) readText(content) else null
            frames +=
                RawFrame(
                    id = id,
                    header = header,
                    content = content,
                    encoding = parsed?.first ?: -1,
                    texts = parsed?.second,
                )
            pos += FRAME_HEADER + size
        }

        if (frames.isEmpty()) return null

        val fixes =
            frames.flatMap { frame ->
                frame.texts.orEmpty().mapNotNull { text ->
                    val repaired = text.tryRepairLegacyEncoding() ?: return@mapNotNull null
                    TagTextFix(frame.id, text, repaired)
                }
            }

        return Id3TagAnalysis(major, minor, end, fixes, frames)
    }

    /**
     * Rebuild the file with every repairable text frame re-encoded.
     *
     * @return The new file contents, or null when nothing needed changing.
     */
    fun rewrite(bytes: ByteArray, analysis: Id3TagAnalysis): ByteArray? {
        if (analysis.fixes.isEmpty()) return null

        val targetEncoding = if (analysis.versionMajor >= 4) ENC_UTF8 else ENC_UTF16
        val out = java.io.ByteArrayOutputStream()
        var changed = false

        for (frame in analysis.frames) {
            val texts = frame.texts
            if (texts == null) {
                out.write(frame.header)
                out.write(frame.content)
                continue
            }
            val repaired = texts.map { it.tryRepairLegacyEncoding() ?: it }
            if (repaired == texts) {
                out.write(frame.header)
                out.write(frame.content)
                continue
            }
            changed = true
            out.write(frameHeader(frame.id, repaired, targetEncoding, analysis.versionMajor))
        }

        if (!changed) return null

        val body = out.toByteArray()
        val header =
            byteArrayOf(
                'I'.code.toByte(),
                'D'.code.toByte(),
                '3'.code.toByte(),
                analysis.versionMajor.toByte(),
                analysis.versionMinor.toByte(),
                0,
            ) + synchsafeBytes(body.size)

        val audio = bytes.copyOfRange(analysis.tagSize, bytes.size)
        return header + body + audio
    }

    private fun readText(content: ByteArray): Pair<Int, List<String>>? {
        if (content.isEmpty()) return null
        val encoding = content[0].toInt() and 0xFF
        val charset =
            when (encoding) {
                ENC_LATIN1 -> Charsets.ISO_8859_1
                ENC_UTF16 -> Charsets.UTF_16 // BOM decides endianness
                ENC_UTF16BE -> Charsets.UTF_16BE
                ENC_UTF8 -> Charsets.UTF_8
                else -> return null
            }
        val raw = content.copyOfRange(1, content.size)
        val text =
            try {
                String(raw, charset)
            } catch (e: Exception) {
                return null
            }
        // Values are separated by a null terminator; TXXX/COMM additionally carry a description
        // first. Treating the whole thing as a list of values handles both.
        val parts = text.split('\u0000').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        return encoding to parts
    }

    private fun frameHeader(
        id: String,
        texts: List<String>,
        encoding: Int,
        versionMajor: Int,
    ): ByteArray {
        val joined = texts.joinToString("\u0000")
        val encoded =
            if (encoding == ENC_UTF16) {
                // UTF-16 with a little-endian BOM, which is what ID3v2.3 readers expect.
                byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + joined.toByteArray(Charsets.UTF_16LE)
            } else {
                joined.toByteArray(Charsets.UTF_8)
            }
        val content = byteArrayOf(encoding.toByte()) + encoded
        val size =
            if (versionMajor >= 4) synchsafeBytes(content.size) else beBytes(content.size)
        return id.toByteArray(Charsets.ISO_8859_1) + size + byteArrayOf(0, 0) + content
    }

    private fun frameId(bytes: ByteArray, pos: Int): String? {
        val chars = CharArray(4)
        for (i in 0 until 4) {
            val c = bytes[pos + i].toInt() and 0xFF
            val valid = (c in 0x30..0x39) || (c in 0x41..0x5A)
            if (!valid) return null
            chars[i] = c.toChar()
        }
        return String(chars)
    }

    private fun synchsafe(b: ByteArray, offset: Int): Int =
        ((b[offset].toInt() and 0x7F) shl 21) or
            ((b[offset + 1].toInt() and 0x7F) shl 14) or
            ((b[offset + 2].toInt() and 0x7F) shl 7) or
            (b[offset + 3].toInt() and 0x7F)

    private fun beInt(b: ByteArray, offset: Int): Int =
        ((b[offset].toInt() and 0xFF) shl 24) or
            ((b[offset + 1].toInt() and 0xFF) shl 16) or
            ((b[offset + 2].toInt() and 0xFF) shl 8) or
            (b[offset + 3].toInt() and 0xFF)

    private fun synchsafeBytes(n: Int): ByteArray =
        byteArrayOf(
            ((n shr 21) and 0x7F).toByte(),
            ((n shr 14) and 0x7F).toByte(),
            ((n shr 7) and 0x7F).toByte(),
            (n and 0x7F).toByte(),
        )

    private fun beBytes(n: Int): ByteArray =
        byteArrayOf(
            ((n shr 24) and 0xFF).toByte(),
            ((n shr 16) and 0xFF).toByte(),
            ((n shr 8) and 0xFF).toByte(),
            (n and 0xFF).toByte(),
        )
}
