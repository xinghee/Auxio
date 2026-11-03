/*
 * Copyright (c) 2025 Auxio Project
 * LyricsTitle.kt is part of Auxio.
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

import java.text.Normalizer

/**
 * Turns noisy track titles into something a lyric library can be matched against.
 *
 * Video-site titles are the hard case: "【无损】女帝【爱江山更爱美人】-李丽芬" has to become
 * "爱江山更爱美人" before it can be compared with a `[ti:]` tag. Everything here is heuristic, so
 * [titleCandidates] returns several possibilities ordered from most to least trustworthy and lets
 * the caller decide.
 */
object LyricsTitle {
    /** Bracketed segments, in the order they usually hold the real song name. */
    private val BOOK_TITLE = Regex("""《([^》]{1,40})》""")
    private val CORNER = Regex("""【([^】]{1,40})】""")
    private val SQUARE = Regex("""\[([^]]{1,40})]""")

    /** Noise that video platforms bolt onto titles. */
    private val NOISE =
        listOf(
            "中文填词",
            "中文版",
            "原唱",
            "翻唱",
            "ai翻唱",
            "cover",
            "live",
            "mv",
            "hd",
            "4k",
            "60fps",
            "无损",
            "高清",
            "完整版",
            "官方",
            "现场版",
            "动态歌词",
            "纯享",
            "高音质",
            "hi-res",
        )

    /** Resolution and format tags that look like song names but never are. */
    private val JUNK =
        Regex("""^(?:\d{2,4}[pPiI]|\d{2,3}fps|\d{4}|mv|hd|4k|1080p|720p|2160p)$""", RegexOption.IGNORE_CASE)

    /**
     * Song name candidates taken from [raw], best first.
     *
     * Empty when the title has no bracketed part and no separator, in which case the caller should
     * fall back to the whole normalised title.
     */
    fun titleCandidates(raw: String): List<String> {
        val out = LinkedHashSet<String>()
        // 《》 is what Chinese uploaders use for a real song name, [] often holds it too, and 【】
        // is frequently just the franchise ("灌篮高手") rather than the track.
        BOOK_TITLE.findAll(raw).forEach { out += it.groupValues[1].trim() }
        SQUARE.findAll(raw).forEach { out += it.groupValues[1].trim() }
        CORNER.findAll(raw).forEach { out += it.groupValues[1].trim() }
        if (out.size <= 1) {
            // No brackets (or only one), so also try the text before the first separator.
            raw.split('-', '_', '|', '｜').forEach { piece ->
                val candidate = stripNoise(piece.trim())
                if (candidate.isNotEmpty()) {
                    out += candidate
                }
            }
        }
        return out
            .map(::stripNoise)
            .filter { it.isNotEmpty() && it.length in 1..40 && !JUNK.matches(it) }
            .distinct()
    }

    /** Drops platform noise from the tail of a candidate. */
    private fun stripNoise(value: String): String {
        var text = value
        // Trailing decorations after a separator belong to the description, not the name.
        text = text.substringBefore("：").substringBefore(":").trim()
        var changed = true
        while (changed) {
            changed = false
            for (noise in NOISE) {
                if (text.endsWith(noise, ignoreCase = true) && text.length > noise.length) {
                    text = text.dropLast(noise.length).trim().trimEnd('!', '！', '-', '_', ' ')
                    changed = true
                }
            }
        }
        return text.trim()
    }

    /**
     * Normalised form used for equality checks: full-width folded, lower case, bracketed sections
     * and punctuation removed.
     */
    fun normalize(value: String): String {
        if (value.isEmpty()) {
            return ""
        }
        var text = Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase()
        text = text.replace(Regex("""[(（\[【].*?[)）\]】]"""), "")
        text = text.replace(Regex("""\b(feat|ft|featuring)\.?\b.*"""), "")
        text =
            text.replace(
                Regex("""[\s\-_·・.,，、!！?？'"“”‘’~/\\|&+*#()（）\[\]{}<>《》【】]"""),
                ""
            )
        return text
    }

    /**
     * Dice coefficient over character bigrams, 0..1.
     *
     * Used instead of edit distance because it tolerates reordered words ("爱江山更爱美人" vs
     * "爱美人更爱江山") and is cheap enough to run against a few thousand entries.
     */
    fun similarity(left: String, right: String): Double {
        if (left.isEmpty() || right.isEmpty()) {
            return 0.0
        }
        if (left == right) {
            return 1.0
        }
        if (left.length < 2 || right.length < 2) {
            return if (left == right) 1.0 else 0.0
        }
        val a = bigrams(left)
        val b = bigrams(right)
        if (a.isEmpty() || b.isEmpty()) {
            return 0.0
        }
        // Take both totals before consuming the second map, otherwise the denominator shrinks and
        // the result can exceed 1.
        val totalA = a.values.sum()
        val totalB = b.values.sum()
        var overlap = 0
        val remaining = b.toMutableMap()
        for ((gram, count) in a) {
            val available = remaining[gram] ?: 0
            if (available > 0) {
                val used = minOf(count, available)
                overlap += used
                remaining[gram] = available - used
            }
        }
        return 2.0 * overlap / (totalA + totalB)
    }

    private fun bigrams(value: String): Map<String, Int> {
        val out = HashMap<String, Int>()
        for (i in 0 until value.length - 1) {
            val gram = value.substring(i, i + 2)
            out[gram] = (out[gram] ?: 0) + 1
        }
        return out
    }
}
