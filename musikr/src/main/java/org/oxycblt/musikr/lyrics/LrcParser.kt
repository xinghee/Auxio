/*
 * Copyright (c) 2025 Auxio Project
 * LrcParser.kt is part of Auxio.
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

/** One timed line of a lyric file. */
data class LrcLine(
    /** Start time in milliseconds, offset already applied. */
    val timeMs: Long,
    /** The main text of this line. */
    val text: String,
    /** A second line shown underneath, used for translations. Null when there is only one text. */
    val translation: String? = null,
)

/** A parsed lyric file. */
data class Lrc(
    val title: String?,
    val artist: String?,
    val album: String?,
    val lines: List<LrcLine>,
) {
    val isEmpty: Boolean
        get() = lines.isEmpty()

    /** Last timestamp in the file. Not the track duration: lyrics rarely run to the very end. */
    val lastTimeMs: Long
        get() = lines.lastOrNull()?.timeMs ?: 0
}

/**
 * Reads the `.lrc` format.
 *
 * Handles what actually shows up in the wild: several timestamps on one line, `[mm:ss:xx]` written
 * with a colon, a global offset, and the `original^translation` convention for bilingual files.
 * Anything it cannot make sense of is dropped rather than guessed at.
 */
object LrcParser {
    private val TIME_TAG = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    private val META_TAG = Regex("""^\[(ti|ar|al|by|offset|length|re|ve):(.*)]$""", RegexOption.IGNORE_CASE)

    fun parse(raw: String): Lrc {
        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var offsetMs = 0L
        val lines = mutableListOf<LrcLine>()

        for (line in raw.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                continue
            }

            val meta = META_TAG.matchEntire(trimmed)
            if (meta != null) {
                val key = meta.groupValues[1].lowercase()
                val value = meta.groupValues[2].trim()
                when (key) {
                    "ti" -> title = value.ifEmpty { null }
                    "ar" -> artist = value.ifEmpty { null }
                    "al" -> album = value.ifEmpty { null }
                    "offset" -> offsetMs = value.toLongOrNull() ?: 0L
                }
                continue
            }

            val stamps = TIME_TAG.findAll(trimmed).toList()
            if (stamps.isEmpty()) {
                continue
            }
            val body = trimmed.substring(stamps.last().range.last + 1).trim()
            if (body.isEmpty()) {
                continue
            }

            val text: String
            val translation: String?
            // Bilingual files join the two languages with a caret. Some use a newline-ish separator.
            val caret = body.indexOf('^')
            if (caret > 0 && caret < body.length - 1) {
                text = body.substring(0, caret).trim()
                translation = body.substring(caret + 1).trim().ifEmpty { null }
            } else {
                text = body
                translation = null
            }
            if (text.isEmpty()) {
                continue
            }

            for (stamp in stamps) {
                val timeMs = toMillis(stamp)
                lines += LrcLine(timeMs, text, translation)
            }
        }

        val sorted =
            lines.sortedBy { it.timeMs }.map { line ->
                if (offsetMs == 0L) line else line.copy(timeMs = (line.timeMs - offsetMs).coerceAtLeast(0))
            }
        return Lrc(title, artist, album, sorted)
    }

    /** `[mm:ss]`, `[mm:ss.xx]`, `[mm:ss.xxx]` and `[mm:ss:xx]` all mean the same thing. */
    private fun toMillis(match: MatchResult): Long {
        val minutes = match.groupValues[1].toLongOrNull() ?: 0L
        val seconds = match.groupValues[2].toLongOrNull() ?: 0L
        val fraction = match.groupValues[3]
        val millis =
            when (fraction.length) {
                0 -> 0L
                1 -> fraction.toLongOrNull()?.times(100) ?: 0L
                2 -> fraction.toLongOrNull()?.times(10) ?: 0L
                else -> fraction.take(3).toLongOrNull() ?: 0L
            }
        return minutes * 60_000 + seconds * 1_000 + millis
    }
}
