/*
 * Copyright (c) 2025 Auxio Project
 * EncodingFixTest.kt is part of Auxio.
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

import org.junit.Assert.assertEquals
import org.junit.Test

class EncodingFixTest {
    @Test
    fun fixLegacyEncoding_repairsGbkMislabelledAsLatin1() {
        // ID3v2 encoding byte 0 declares ISO-8859-1 while the bytes are GBK for "逝去日子".
        assertEquals("逝去日子", "ÊÅÈ¥ÈÕ×Ó".fixLegacyEncoding())
    }

    @Test
    fun fixLegacyEncoding_repairsGbkAlbumWithAsciiPrefix() {
        // The high bytes here are only 4 out of 21, which is normal for an album name.
        assertEquals("BEYOND - 25周年", "BEYOND - 25ÖÜÄê".fixLegacyEncoding())
        assertEquals("周年", "ÖÜÄê".fixLegacyEncoding())
    }

    @Test
    fun fixLegacyEncoding_repairsUtf8MislabelledAsLatin1() {
        val mojibake = String("逝去日子".toByteArray(Charsets.UTF_8), Charsets.ISO_8859_1)
        assertEquals("逝去日子", mojibake.fixLegacyEncoding())
    }

    @Test
    fun fixLegacyEncoding_leavesWesternNamesAlone() {
        assertEquals("Café", "Café".fixLegacyEncoding())
        assertEquals("Müller", "Müller".fixLegacyEncoding())
        assertEquals("Björk", "Björk".fixLegacyEncoding())
        // Two isolated high bytes must not be paired into a CJK glyph.
        assertEquals("Björk - Homogénic", "Björk - Homogénic".fixLegacyEncoding())
    }

    @Test
    fun fixLegacyEncoding_leavesAsciiAlone() {
        assertEquals("Hello World", "Hello World".fixLegacyEncoding())
        assertEquals("Track 01", "Track 01".fixLegacyEncoding())
    }

    @Test
    fun fixLegacyEncoding_leavesProperUnicodeAlone() {
        assertEquals("逝去日子", "逝去日子".fixLegacyEncoding())
        assertEquals("日本語のタイトル", "日本語のタイトル".fixLegacyEncoding())
    }

    @Test
    fun fixLegacyEncoding_handlesEmptyString() {
        assertEquals("", "".fixLegacyEncoding())
    }
}
