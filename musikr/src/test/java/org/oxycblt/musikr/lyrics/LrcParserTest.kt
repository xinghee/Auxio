/*
 * Copyright (c) 2025 Auxio Project
 * LrcParserTest.kt is part of Auxio.
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
 
package org.oxycblt.musikr.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LrcParserTest {
    @Test
    fun parse_readsMetadataAndLines() {
        val lrc =
            LrcParser.parse(
                """
                [ti:突然的自我]
                [ar:孙露]
                [al:情调私语2]
                [by:]
                [offset:0]
                [00:00.10]突然的自我 - 孙露
                [03:42.72]如果仅有此生
                """.trimIndent()
            )

        assertEquals("突然的自我", lrc.title)
        assertEquals("孙露", lrc.artist)
        assertEquals("情调私语2", lrc.album)
        assertEquals(2, lrc.lines.size)
        assertEquals(100L, lrc.lines[0].timeMs)
        assertEquals(222_720L, lrc.lines[1].timeMs)
        assertEquals(222_720L, lrc.lastTimeMs)
    }

    @Test
    fun parse_expandsMultipleTimestampsOnOneLine() {
        val lrc = LrcParser.parse("[00:10.00][01:20.00]副歌\n")
        assertEquals(2, lrc.lines.size)
        assertEquals(10_000L, lrc.lines[0].timeMs)
        assertEquals(80_000L, lrc.lines[1].timeMs)
        assertEquals("副歌", lrc.lines[0].text)
    }

    @Test
    fun parse_acceptsColonBeforeFraction() {
        // Some taggers write [mm:ss:xx] instead of [mm:ss.xx].
        val lrc = LrcParser.parse("[01:05:50]测试\n")
        assertEquals(65_500L, lrc.lines.single().timeMs)
    }

    @Test
    fun parse_splitsBilingualLines() {
        val lrc = LrcParser.parse("[00:07.51]あたまテカテカ^头大大的\n")
        val line = lrc.lines.single()
        assertEquals("あたまテカテカ", line.text)
        assertEquals("头大大的", line.translation)
    }

    @Test
    fun parse_appliesGlobalOffset() {
        // A positive offset means "lyrics are early, show them later".
        val lrc = LrcParser.parse("[offset:500]\n[00:10.00]测试\n")
        assertEquals(9_500L, lrc.lines.single().timeMs)
    }

    @Test
    fun parse_keepsOrderAndSkipsEmptyLines() {
        val lrc = LrcParser.parse("\n[00:20.00]二\n\n[00:10.00]一\n")
        assertEquals(listOf(10_000L, 20_000L), lrc.lines.map { it.timeMs })
        assertTrue(lrc.title == null && lrc.artist == null)
    }

    @Test
    fun parse_returnsEmptyForInstrumentalNotice() {
        val lrc = LrcParser.parse("[00:00.00]此歌曲为没有填词的纯音乐，请您欣赏\n")
        assertEquals(1, lrc.lines.size)
        assertNull(lrc.title)
    }

    @Test
    fun parse_ignoresUnparseableContent() {
        val lrc = LrcParser.parse("这不是歌词\n[abc]文本\n")
        assertTrue(lrc.isEmpty)
    }
}
