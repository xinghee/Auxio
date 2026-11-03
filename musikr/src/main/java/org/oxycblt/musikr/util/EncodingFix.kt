/*
 * Copyright (c) 2025 Auxio Project
 * EncodingFix.kt is part of Auxio.
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
 
package org.oxycblt.musikr.util

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

private val GB18030 = Charset.forName("GB18030")
private const val CJK_FIRST = 0x4E00
private const val CJK_LAST = 0x9FFF

/**
 * Repair tag text that was decoded with the wrong single-byte charset.
 *
 * Older Chinese taggers write ID3v2 text with encoding byte 0 (declaring ISO-8859-1) while
 * actually storing GBK bytes. TagLib decodes that one-to-one into U+0080..U+00FF, so a title like
 * "逝去日子" surfaces as "ÊÅÈ¥ÈÕ×Ó". Because Latin-1 is injective, the original bytes can be
 * recovered without loss and decoded again, which is exactly what this does.
 *
 * Strings that already contain real Unicode are returned untouched, so correctly tagged files
 * are never affected.
 *
 * @return The repaired [String], or this [String] when no repair was applicable.
 */
internal fun String.fixLegacyEncoding(): String = tryRepairLegacyEncoding() ?: this

/**
 * Same detection as [fixLegacyEncoding], but tells the caller whether a repair actually applies.
 *
 * Used by the tag rewriter, which needs to know which frames are worth rewriting rather than just
 * getting a corrected string back.
 *
 * @return The repaired text, or null when this [String] does not look like mislabelled double-byte
 *   text.
 */
internal fun String.tryRepairLegacyEncoding(): String? {
    // Anything above U+00FF means TagLib already produced real Unicode (UTF-8/UTF-16 tags).
    if (any { it.code > 0xFF }) {
        return null
    }
    if (isEmpty()) {
        return null
    }

    val raw = ByteArray(length) { index -> get(index).code.toByte() }
    // Pure ASCII, or a Western name with a single accented letter ("Café", "Müller").
    if (raw.count { (it.toInt() and 0xFF) >= 0x80 } < 2) {
        return null
    }

    // UTF-8 stored under a Latin-1 declaration is also common in the wild.
    decodeStrict(raw, Charsets.UTF_8)?.takeIf { decoded -> decoded.any { it.code > 0xFF } }?.let {
        return it
    }
    // GBK encodes every CJK glyph as exactly two bytes, so its high bytes always come in
    // even-length runs. An isolated high byte ("Björk - Homogénic") is therefore not GBK.
    // Note this must not require high bytes to dominate: album names like
    // "BEYOND - 25◌◌◌◌" are mostly ASCII and would otherwise be skipped.
    if (!hasEvenHighRuns(raw)) {
        return null
    }
    // GB18030 is a superset of GBK, which is what Chinese taggers actually wrote.
    decodeStrict(raw, GB18030)
        ?.takeIf { decoded ->
            decoded.any { it.code in CJK_FIRST..CJK_LAST } && decoded.none { it.isISOControl() }
        }
        ?.let {
            return it
        }

    return null
}

/**
 * Whether every run of non-ASCII bytes has an even length, which is what a double-byte GBK string
 * looks like once read back through Latin-1.
 */
private fun hasEvenHighRuns(bytes: ByteArray): Boolean {
    var i = 0
    while (i < bytes.size) {
        if ((bytes[i].toInt() and 0xFF) < 0x80) {
            i++
            continue
        }
        var end = i
        while (end < bytes.size && (bytes[end].toInt() and 0xFF) >= 0x80) {
            end++
        }
        if ((end - i) % 2 != 0) {
            return false
        }
        i = end
    }
    return true
}

private fun decodeStrict(bytes: ByteArray, charset: Charset): String? =
    try {
        charset
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (e: CharacterCodingException) {
        null
    }
