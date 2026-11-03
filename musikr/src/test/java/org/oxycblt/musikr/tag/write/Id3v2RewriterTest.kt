/*
 * Copyright (c) 2025 Auxio Project
 * Id3v2RewriterTest.kt is part of Auxio.
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

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Id3v2RewriterTest {
    // "逝去日子" as GBK, which is what an old tagger wrote while declaring Latin-1.
    private val gbkTitle =
        byteArrayOf(
            0xCA.toByte(), 0xC5.toByte(), 0xC8.toByte(), 0xA5.toByte(),
            0xC8.toByte(), 0xD5.toByte(), 0xD7.toByte(), 0xD3.toByte(),
        )

    // "BEYOND - " in ASCII + "25周年" as GBK.
    private val gbkAlbum =
        "BEYOND - 25".toByteArray(Charsets.ISO_8859_1) +
            byteArrayOf(0xD6.toByte(), 0xDC.toByte(), 0xC4.toByte(), 0xEA.toByte())

    private val audio = ByteArray(64) { 0x55.toByte() }

    @Test
    fun analyze_findsGbkMislabelledFrames() {
        val file = buildTag(3, listOf(textFrame("TIT2", gbkTitle, 0), textFrame("TALB", gbkAlbum, 0)), audio)

        val analysis = Id3v2Rewriter.analyze(file)
        assertNotNull(analysis)
        assertEquals(2, analysis!!.fixes.size)
        assertEquals("TIT2", analysis.fixes[0].frameId)
        assertEquals("逝去日子", analysis.fixes[0].after)
        assertEquals("BEYOND - 25周年", analysis.fixes[1].after)
    }

    @Test
    fun rewrite_repairsTextAndKeepsAudioIntact() {
        val file = buildTag(3, listOf(textFrame("TIT2", gbkTitle, 0), textFrame("TALB", gbkAlbum, 0)), audio)
        val rewritten = Id3v2Rewriter.rewrite(file, Id3v2Rewriter.analyze(file)!!)
        assertNotNull(rewritten)

        val after = Id3v2Rewriter.analyze(rewritten!!)!!
        assertEquals(0, after.fixes.size)
        assertEquals(listOf("逝去日子"), after.frames[0].texts)
        assertEquals(listOf("BEYOND - 25周年"), after.frames[1].texts)
        assertArrayEquals(audio, rewritten.copyOfRange(after.tagSize, rewritten.size))
    }

    @Test
    fun rewrite_handlesId3v24SynchsafeSizes() {
        val file = buildTag(4, listOf(textFrame("TIT2", gbkTitle, 0, 4)), audio)
        val analysis = Id3v2Rewriter.analyze(file)!!
        assertEquals(1, analysis.fixes.size)

        val rewritten = Id3v2Rewriter.rewrite(file, analysis)!!
        val after = Id3v2Rewriter.analyze(rewritten)!!
        assertEquals(listOf("逝去日子"), after.frames[0].texts)
        assertEquals(0, after.fixes.size)
    }

    @Test
    fun rewrite_preservesNonTextFrames() {
        val picture = byteArrayOf(0) + "image/jpeg".toByteArray() + ByteArray(32) { 0x22.toByte() }
        val file = buildTag(3, listOf(textFrame("TIT2", gbkTitle, 0), frame("APIC", picture, 3)), audio)
        val rewritten = Id3v2Rewriter.rewrite(file, Id3v2Rewriter.analyze(file)!!)!!

        val haystack = String(rewritten, Charsets.ISO_8859_1)
        val needle = String(frame("APIC", picture, 3), Charsets.ISO_8859_1)
        assertTrue("APIC frame must survive untouched", haystack.contains(needle))
    }

    @Test
    fun analyze_ignoresCorrectlyEncodedText() {
        val utf16 = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "逝去日子".toByteArray(Charsets.UTF_16LE)
        val file = buildTag(3, listOf(textFrame("TIT2", utf16, 1)), audio)

        val analysis = Id3v2Rewriter.analyze(file)!!
        assertEquals(0, analysis.fixes.size)
        assertNull(Id3v2Rewriter.rewrite(file, analysis))
    }

    @Test
    fun analyze_ignoresWesternNames() {
        val latin = "Café".toByteArray(Charsets.ISO_8859_1)
        val file = buildTag(3, listOf(textFrame("TALB", latin, 0)), audio)

        val analysis = Id3v2Rewriter.analyze(file)!!
        assertEquals(0, analysis.fixes.size)
    }

    @Test
    fun analyze_returnsNullWithoutId3() {
        assertNull(Id3v2Rewriter.analyze(ByteArray(200)))
    }

    // ---- helpers ----

    private fun textFrame(id: String, body: ByteArray, encoding: Int, versionMajor: Int = 3) =
        frame(id, byteArrayOf(encoding.toByte()) + body, versionMajor)

    private fun frame(id: String, content: ByteArray, versionMajor: Int): ByteArray {
        val size = if (versionMajor >= 4) synchsafe(content.size) else be(content.size)
        return id.toByteArray(Charsets.ISO_8859_1) + size + byteArrayOf(0, 0) + content
    }

    private fun buildTag(versionMajor: Int, frames: List<ByteArray>, audio: ByteArray): ByteArray {
        val body = frames.fold(ByteArray(0)) { acc, part -> acc + part }
        val header =
            byteArrayOf(
                'I'.code.toByte(),
                'D'.code.toByte(),
                '3'.code.toByte(),
                versionMajor.toByte(),
                0,
                0,
            ) + synchsafe(body.size)
        return header + body + audio
    }

    private fun synchsafe(n: Int) =
        byteArrayOf(
            ((n shr 21) and 0x7F).toByte(),
            ((n shr 14) and 0x7F).toByte(),
            ((n shr 7) and 0x7F).toByte(),
            (n and 0x7F).toByte(),
        )

    private fun be(n: Int) =
        byteArrayOf(
            ((n shr 24) and 0xFF).toByte(),
            ((n shr 16) and 0xFF).toByte(),
            ((n shr 8) and 0xFF).toByte(),
            (n and 0xFF).toByte(),
        )
}
