/*
 * Copyright (c) 2025 Auxio Project
 * LyricsMatcher.kt is part of Auxio.
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
 
package org.oxycblt.auxio.lyrics

import android.net.Uri
import kotlin.math.abs
import org.oxycblt.musikr.lyrics.LyricsTitle

/** The minimum a song has to expose to be matched. */
internal data class SongKey(
    val uri: Uri,
    val title: String,
    val artist: String,
    val durationMs: Long,
)

/** How confident a match is, without exposing raw scores to the UI. */
internal enum class MatchKind {
    /** The whole title matched a `[ti:]` tag exactly. */
    TITLE,
    /** A bracket-extracted candidate matched exactly. */
    CANDIDATE,
    /** Similarity above the review threshold but not exact. */
    FUZZY,
    /** Nothing usable. */
    NONE,
}

/**
 * @property auto True when the match is good enough to apply without asking.
 */
internal data class MatchCandidate(
    val song: SongKey,
    val entry: LrcEntry?,
    val kind: MatchKind,
    val score: Float,
) {
    val auto: Boolean
        get() = entry != null && score >= AUTO_THRESHOLD

    private companion object {
        const val AUTO_THRESHOLD = 0.82f
    }
}

/**
 * Compares songs against the lyric files found on the device.
 *
 * Titles from video sites are noisy, so each song is tried against several extracted candidates
 * before falling back to a similarity sweep. The sweep is length-filtered first: comparing 1500
 * entries against a 6-character title is wasted work when most can be discarded on length alone.
 */
internal class LyricsMatcher {
    private class IndexedEntry(val entry: LrcEntry, val normTitle: String, val normArtist: String)

    fun match(songs: List<SongKey>, entries: List<LrcEntry>): List<MatchCandidate> {
        val indexed =
            entries.mapNotNull { entry ->
                val title = entry.title ?: return@mapNotNull null
                val norm = LyricsTitle.normalize(title)
                if (norm.isEmpty()) {
                    null
                } else {
                    IndexedEntry(entry, norm, LyricsTitle.normalize(entry.artist.orEmpty()))
                }
            }
        val exact = HashMap<String, MutableList<IndexedEntry>>()
        indexed.forEach { item -> exact.getOrPut(item.normTitle) { mutableListOf() } += item }

        return songs.map { song -> matchOne(song, indexed, exact) }
    }

    private fun matchOne(
        song: SongKey,
        indexed: List<IndexedEntry>,
        exact: Map<String, List<IndexedEntry>>,
    ): MatchCandidate {
        val candidates = LyricsTitle.titleCandidates(song.title) + song.title
        val songArtist = LyricsTitle.normalize(song.artist)

        for ((position, candidate) in candidates.withIndex()) {
            val norm = LyricsTitle.normalize(candidate)
            if (norm.isEmpty()) {
                continue
            }
            val hits = exact[norm] ?: continue
            val best = pickBest(hits, song)
            val kind = if (position == candidates.lastIndex) MatchKind.TITLE else MatchKind.CANDIDATE
            return MatchCandidate(song, best.entry, kind, 1.0f)
        }

        // Nothing exact; sweep by similarity with a length window to keep it cheap.
        var bestEntry: IndexedEntry? = null
        var bestScore = 0.0
        for (candidate in candidates) {
            val norm = LyricsTitle.normalize(candidate)
            if (norm.length < 2) {
                continue
            }
            for (item in indexed) {
                val lengthGap = abs(item.normTitle.length - norm.length)
                if (lengthGap > maxOf(2, norm.length / 2)) {
                    continue
                }
                val score = LyricsTitle.similarity(norm, item.normTitle)
                if (score <= bestScore) {
                    continue
                }
                // An artist match is a useful tie-breaker, never a substitute for a title match.
                val artistBonus =
                    if (songArtist.isNotEmpty() &&
                        item.normArtist.isNotEmpty() &&
                        LyricsTitle.similarity(songArtist, item.normArtist) > 0.6) {
                        0.05
                    } else {
                        0.0
                    }
                bestScore = minOf(1.0, score + artistBonus)
                bestEntry = item
            }
        }

        return if (bestEntry != null && bestScore >= REVIEW_THRESHOLD) {
            MatchCandidate(song, bestEntry.entry, MatchKind.FUZZY, bestScore.toFloat())
        } else {
            MatchCandidate(song, null, MatchKind.NONE, 0f)
        }
    }

    /**
     * Same title, several files. Prefer the one whose length is closest to the song, because the
     * last timestamp tracks how long the singer actually sings rather than the file duration.
     */
    private fun pickBest(hits: List<IndexedEntry>, song: SongKey): IndexedEntry {
        if (hits.size == 1) {
            return hits.first()
        }
        val songSec = song.durationMs / 1000.0
        return hits.minByOrNull { hit ->
            val lrcSec = hit.entry.lastMs / 1000.0
            // A lyric file usually ends 5-30s before the track does; treat anything in that band
            // as equally good and prefer the longer one.
            if (lrcSec in (songSec - 40)..songSec) {
                abs(songSec - 40 - lrcSec)
            } else {
                abs(songSec - lrcSec)
            }
        } ?: hits.first()
    }

    private companion object {
        /** Below this a fuzzy match is noise rather than a candidate. */
        const val REVIEW_THRESHOLD = 0.60
    }
}
