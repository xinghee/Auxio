/*
 * Copyright (c) 2025 Auxio Project
 * LyricsTitleTest.kt is part of Auxio.
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
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsTitleTest {
    @Test
    fun normalize_stripsBracketsAndPunctuation() {
        // normalize() folds for comparison; it is not meant to extract the song name, so the
        // bracketed parts are removed entirely and "女帝-李丽芬" is what remains.
        assertEquals("女帝李丽芬", LyricsTitle.normalize("【无损】女帝【爱江山更爱美人】-李丽芬"))
        assertEquals("sayyoullnever", LyricsTitle.normalize("Say You'll Never (Original Version)"))
        assertEquals("", LyricsTitle.normalize(""))
    }

    @Test
    fun titleCandidates_prefersBookTitle() {
        val candidates = LyricsTitle.titleCandidates("【4K60FPS】黄霑《沧海一声笑》史诗级现场！")
        assertEquals("沧海一声笑", candidates.first())
    }

    @Test
    fun titleCandidates_prefersSquareOverCorner() {
        // 【】 usually holds the franchise, [] the actual track.
        val candidates = LyricsTitle.titleCandidates("【灌篮高手】-[直到世界尽头]中文填词 AI翻唱!!!")
        assertEquals("直到世界尽头", candidates.first())
    }

    @Test
    fun titleCandidates_dropsResolutionTags() {
        val candidates = LyricsTitle.titleCandidates("【爱江山更爱美人】[1080P]")
        assertEquals("爱江山更爱美人", candidates.first())
    }

    @Test
    fun titleCandidates_fallsBackToSeparator() {
        val candidates = LyricsTitle.titleCandidates("叶倩文歌曲《潇洒走一回》")
        assertEquals("潇洒走一回", candidates.first())
    }

    @Test
    fun titleCandidates_dropsTrailingDescription() {
        val candidates = LyricsTitle.titleCandidates("黑熊精新歌《差一点》：原来最痛的不是失败")
        assertEquals("差一点", candidates.first())
    }

    @Test
    fun similarity_handlesExtraArtistSuffix() {
        val a = LyricsTitle.normalize("爱江山更爱美人")
        val b = LyricsTitle.normalize("爱江山更爱美人 - 李丽芬")
        val score = LyricsTitle.similarity(a, b)
        assertTrue("score was $score", score > 0.6 && score <= 1.0)
    }

    @Test
    fun similarity_isOneForIdentical() {
        val value = LyricsTitle.normalize("潇洒走一回")
        assertEquals(1.0, LyricsTitle.similarity(value, value), 0.0001)
    }

    @Test
    fun similarity_rejectsDifferentSongs() {
        val a = LyricsTitle.normalize("潇洒走一回")
        val b = LyricsTitle.normalize("爱江山更爱美人")
        assertTrue(LyricsTitle.similarity(a, b) < 0.3)
    }

    @Test
    fun similarity_neverExceedsOne() {
        val a = LyricsTitle.normalize("爱江山更爱美人")
        val b = LyricsTitle.normalize("爱江山更爱美人李丽芬")
        val score = LyricsTitle.similarity(a, b)
        assertTrue("score was $score", score <= 1.0)
    }

    @Test
    fun similarity_isSymmetric() {
        val a = LyricsTitle.normalize("沧海一声笑")
        val b = LyricsTitle.normalize("沧海笑声")
        assertEquals(
            LyricsTitle.similarity(a, b),
            LyricsTitle.similarity(b, a),
            0.0001
        )
    }
}
