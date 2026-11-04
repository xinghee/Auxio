/*
 * Copyright (c) 2025 Auxio Project
 * LyricsRepository.kt is part of Auxio.
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

import android.content.Context
import android.net.Uri
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.oxycblt.musikr.fs.Location
import org.oxycblt.musikr.lyrics.Lrc
import org.oxycblt.musikr.lyrics.LrcParser
import org.oxycblt.musikr.lyrics.LyricsTitle
import timber.log.Timber as L

/** The result of one scan pass. */
internal data class LyricsReport(
    val candidates: List<MatchCandidate>,
    val lrcCount: Int,
) {
    val bound: Int
        get() = candidates.count { it.entry != null }

    val autoBound: Int
        get() = candidates.count { it.auto }

    val missing: Int
        get() = candidates.count { it.entry == null }
}

/** Where a lyric file lives and how it got there. */
internal class LyricsCache(context: Context) {
    private val dir = File(context.filesDir, DIR_NAME)

    fun write(key: String, content: String): Uri? =
        try {
            dir.mkdirs()
            val file = File(dir, "${hash(key)}.lrc")
            file.writeText(content)
            Uri.fromFile(file)
        } catch (e: Exception) {
            L.w(e, "Could not cache lyrics")
            null
        }

    private fun hash(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val DIR_NAME = "lyrics"
    }
}

/**
 * Ties scanning, matching, caching and online lookup together.
 *
 * The binding table is the single source of truth at playback time: it is written once by a scan
 * (or by an online fetch) and read on every song change, so playback never touches the filesystem
 * to find lyrics.
 */
internal class LyricsRepository(context: Context) {
    private val appContext = context.applicationContext
    private val store = LyricsStore(appContext)
    private val scanner = LyricsScanner(appContext)
    private val matcher = LyricsMatcher()
    private val netease = NeteaseLyrics()
    private val cache = LyricsCache(appContext)

    fun bindings(): Map<String, LyricsBinding> = store.load()

    fun bindingFor(songUri: String): LyricsBinding? = store.load()[songUri]

    /** Walks the lyric folders, matches against [songs] and rewrites the binding table. */
    suspend fun scan(
        roots: List<Location.Opened>,
        songs: List<SongKey>,
        onProgress: (scanned: Int, found: Int) -> Unit = { _, _ -> },
    ): LyricsReport =
        withContext(Dispatchers.IO) {
            val entries = scanner.scan(roots, onProgress)
            val candidates = matcher.match(songs, entries)

            // Manual picks survive a rescan; everything else is recomputed.
            val existing = store.load()
            val merged = LinkedHashMap<String, LyricsBinding>()
            existing.forEach { (key, binding) ->
                if (binding.source == LyricsSource.MANUAL) {
                    merged[key] = binding
                }
            }
            candidates.forEach { candidate ->
                val entry = candidate.entry ?: return@forEach
                val key = candidate.song.uri.toString()
                if (merged.containsKey(key)) {
                    return@forEach
                }
                merged[key] =
                    LyricsBinding(
                        songUri = key,
                        lrcUri = entry.uri.toString(),
                        source = LyricsSource.LOCAL,
                        score = candidate.score,
                        label = entry.title,
                    )
            }
            // Keep online results for songs this scan could not match.
            existing.forEach { (key, binding) ->
                if (binding.source == LyricsSource.ONLINE && !merged.containsKey(key)) {
                    merged[key] = binding
                }
            }
            store.save(merged)
            LyricsReport(candidates, entries.size)
        }

    /**
     * Fetches lyrics for every song that has no binding yet.
     *
     * @return How many songs gained lyrics.
     */
    suspend fun fetchMissing(
        songs: List<SongKey>,
        onProgress: (done: Int, total: Int, title: String) -> Unit = { _, _, _ -> },
    ): Int =
        withContext(Dispatchers.IO) {
            val existing = store.load().toMutableMap()
            val pending = songs.filter { existing[it.uri.toString()] == null }
            var gained = 0
            pending.forEachIndexed { index, song ->
                currentCoroutineContext().ensureActive()
                onProgress(index + 1, pending.size, song.title)
                val keyword =
                    LyricsTitle.titleCandidates(song.title).firstOrNull() ?: song.title
                if (keyword.isBlank()) {
                    return@forEachIndexed
                }
                val found = netease.fetch(keyword, song.durationMs) ?: return@forEachIndexed
                val uri = cache.write(song.uri.toString(), found.lrc) ?: return@forEachIndexed
                existing[song.uri.toString()] =
                    LyricsBinding(
                        songUri = song.uri.toString(),
                        lrcUri = uri.toString(),
                        source = LyricsSource.ONLINE,
                        score = 0.9f,
                        label = "${found.title} - ${found.artist}",
                    )
                gained++
                store.save(existing)
            }
            gained
        }

    /** Searches online for candidates, so the user can replace a wrong match by hand. */
    suspend fun searchOnline(song: SongKey): List<OnlineCandidate> =
        withContext(Dispatchers.IO) {
            val keyword = LyricsTitle.titleCandidates(song.title).firstOrNull() ?: song.title
            if (keyword.isBlank()) {
                emptyList()
            } else {
                netease.search(keyword)
            }
        }

    /**
     * Downloads [candidate] and binds it to [song].
     *
     * Marked [LyricsSource.MANUAL] so a later scan cannot silently replace a choice the user made.
     */
    suspend fun applyOnline(song: SongKey, candidate: OnlineCandidate): Boolean =
        withContext(Dispatchers.IO) {
            val lrc = netease.download(candidate.id) ?: return@withContext false
            val uri = cache.write(song.uri.toString(), lrc) ?: return@withContext false
            val map = store.load().toMutableMap()
            map[song.uri.toString()] =
                LyricsBinding(
                    songUri = song.uri.toString(),
                    lrcUri = uri.toString(),
                    source = LyricsSource.MANUAL,
                    score = 1f,
                    label = "${candidate.title} - ${candidate.artist}",
                )
            store.save(map)
            true
        }

    /** Reads and parses the lyrics behind [binding]. */
    suspend fun read(binding: LyricsBinding): Lrc? =
        withContext(Dispatchers.IO) {
            val text =
                try {
                    val uri = Uri.parse(binding.lrcUri)
                    if (uri.scheme == "file") {
                        val file = File(uri.path ?: return@withContext null)
                        if (file.exists()) file.readText() else null
                    } else {
                        appContext.contentResolver.openInputStream(uri)?.use { input ->
                            val bytes = input.readBytes()
                            if (bytes.size >= 3 &&
                                bytes[0] == 0xEF.toByte() &&
                                bytes[1] == 0xBB.toByte() &&
                                bytes[2] == 0xBF.toByte()) {
                                String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
                            } else {
                                String(bytes, Charsets.UTF_8)
                            }
                        }
                    }
                } catch (e: Exception) {
                    L.w(e, "Could not read lyrics")
                    null
                } ?: return@withContext null
            LrcParser.parse(text)
        }

    fun unbind(songUri: String) {
        val map = store.load().toMutableMap()
        map.remove(songUri)
        store.save(map)
    }
}
