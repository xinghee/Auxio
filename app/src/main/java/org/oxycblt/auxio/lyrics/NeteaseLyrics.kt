/*
 * Copyright (c) 2025 Auxio Project
 * NeteaseLyrics.kt is part of Auxio.
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

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.oxycblt.musikr.lyrics.LrcParser
import timber.log.Timber as L

/** A lyric file fetched from the network. */
internal data class OnlineLyrics(
    val title: String,
    val artist: String,
    val lrc: String,
    val durationSec: Int,
)

/**
 * Looks lyrics up online when nothing on the device matches.
 *
 * Only one source is supported on purpose: a chain of fallbacks multiplies the failure modes
 * without adding much coverage. The lyrics are returned as plain LRC and cached by the caller, so
 * this is only ever hit once per song.
 */
internal class NeteaseLyrics {
    suspend fun fetch(keyword: String, durationMs: Long): OnlineLyrics? =
        withContext(Dispatchers.IO) {
            val songs = search(keyword) ?: return@withContext null
            if (songs.isEmpty()) {
                return@withContext null
            }
            // Prefer a candidate whose length is close to the file; fall back to the top hit.
            val target = durationMs / 1000
            val picked =
                songs.filter { target > 0 && abs(it.durationSec - target) <= 8L }
                    .minByOrNull { abs(it.durationSec - target) }
                    ?: songs.first()
            val lrc = lyric(picked.id) ?: return@withContext null
            if (lrc.isBlank()) {
                return@withContext null
            }
            OnlineLyrics(picked.title, picked.artist, lrc, picked.durationSec)
        }

    private data class Hit(
        val id: Long,
        val title: String,
        val artist: String,
        val durationSec: Int,
    )

    private fun search(keyword: String): List<Hit>? =
        try {
            val query = URLEncoder.encode(keyword, "UTF-8")
            val body = request("https://music.163.com/api/search/get/web?s=$query&type=1&limit=10")
            val result = JSONObject(body).optJSONObject("result") ?: return null
            val songs = result.optJSONArray("songs") ?: return emptyList()
            (0 until songs.length()).mapNotNull { index ->
                val song = songs.optJSONObject(index) ?: return@mapNotNull null
                val id = song.optLong("id")
                if (id <= 0) {
                    return@mapNotNull null
                }
                val artists = song.optJSONArray("artists")
                val artistNames =
                    buildString {
                        if (artists != null) {
                            for (i in 0 until artists.length()) {
                                val name = artists.optJSONObject(i)?.optString("name").orEmpty()
                                if (name.isNotEmpty()) {
                                    if (isNotEmpty()) append("/")
                                    append(name)
                                }
                            }
                        }
                    }
                Hit(
                    id = id,
                    title = song.optString("name"),
                    artist = artistNames,
                    durationSec = (song.optLong("duration") / 1000).toInt(),
                )
            }
        } catch (e: Exception) {
            L.w(e, "Lyric search failed")
            null
        }

    /** Fetches the original lyrics and merges the translation into the same lines. */
    private fun lyric(id: Long): String? =
        try {
            val body =
                request("https://music.163.com/api/song/lyric?id=$id&lv=1&kv=1&tv=-1")
            val root = JSONObject(body)
            val original = root.optJSONObject("lrc")?.optString("lyric").orEmpty()
            if (original.isBlank()) {
                return null
            }
            val translation = root.optJSONObject("tlyric")?.optString("lyric").orEmpty()
            if (translation.isBlank()) {
                original
            } else {
                merge(original, translation)
            }
        } catch (e: Exception) {
            L.w(e, "Lyric fetch failed")
            null
        }

    /**
     * Appends the translation to each original line as `text^translation`, which is the same
     * convention the parser already understands.
     */
    private fun merge(original: String, translation: String): String {
        val parsedTranslation = LrcParser.parse(translation)
        if (parsedTranslation.isEmpty) {
            return original
        }
        val byTime = HashMap<Long, String>()
        parsedTranslation.lines.forEach { line -> byTime.putIfAbsent(line.timeMs, line.text) }
        val out = StringBuilder()
        for (line in original.lineSequence()) {
            val trimmed = line.trim()
            val parsed = LrcParser.parse(trimmed)
            val first = parsed.lines.firstOrNull()
            if (first == null) {
                out.append(line).append('\n')
                continue
            }
            val translated =
                byTime[first.timeMs] ?: byTime.entries.firstOrNull { abs(it.key - first.timeMs) < 20L }?.value
            if (translated.isNullOrEmpty() || translated == first.text) {
                out.append(line).append('\n')
            } else {
                val stamp = trimmed.substringBefore(']') + "]"
                out.append(stamp).append(first.text).append('^').append(translated).append('\n')
            }
        }
        return out.toString()
    }

    private fun request(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 8_000
            connection.readTimeout = 8_000
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Referer", "https://music.163.com/")
            val code = connection.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("HTTP $code for $url")
            }
            connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
    }
}
