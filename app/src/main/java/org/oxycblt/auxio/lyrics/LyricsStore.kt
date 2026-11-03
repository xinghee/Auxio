/*
 * Copyright (c) 2025 Auxio Project
 * LyricsStore.kt is part of Auxio.
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
import java.io.File
import org.json.JSONObject
import timber.log.Timber as L

/** Where a binding came from. Manual wins over everything else. */
internal enum class LyricsSource {
    /** A `.lrc` found on the device and matched by name. */
    LOCAL,
    /** Fetched over the network and cached to internal storage. */
    ONLINE,
    /** Picked by the user. Never overwritten by a scan. */
    MANUAL,
}

/**
 * One song-to-lyric mapping.
 *
 * @property songUri The song file, which is what the library keys on.
 * @property lrcUri Where the lyrics live. A `content://` URI for local files, a `file://` URI for
 *   anything downloaded.
 * @property score Match confidence, 0..1. Always 1 for [LyricsSource.MANUAL].
 * @property label The title that was matched, so the report screen can explain itself.
 */
internal data class LyricsBinding(
    val songUri: String,
    val lrcUri: String,
    val source: LyricsSource,
    val score: Float,
    val label: String?,
)

/**
 * Persists the binding table so playback never has to touch the filesystem.
 *
 * A scan over a few thousand `.lrc` files takes seconds; looking up one song here takes
 * microseconds. Anything the user picked is kept across scans, everything else can be rebuilt.
 */
internal class LyricsStore(context: Context) {
    private val file = File(context.filesDir, FILE_NAME)

    fun load(): MutableMap<String, LyricsBinding> {
        if (!file.exists()) {
            return mutableMapOf()
        }
        return try {
            val root = JSONObject(file.readText())
            val bindings = root.optJSONObject(KEY_BINDINGS) ?: return mutableMapOf()
            val out = mutableMapOf<String, LyricsBinding>()
            bindings.keys().forEach { key ->
                val entry = bindings.optJSONObject(key) ?: return@forEach
                val lrcUri = entry.optString(KEY_LRC)
                if (lrcUri.isEmpty()) {
                    return@forEach
                }
                val source =
                    runCatching { LyricsSource.valueOf(entry.optString(KEY_SOURCE)) }
                        .getOrDefault(LyricsSource.LOCAL)
                out[key] =
                    LyricsBinding(
                        songUri = key,
                        lrcUri = lrcUri,
                        source = source,
                        score = entry.optDouble("score", 1.0).toFloat(),
                        label = entry.optString("label").ifEmpty { null },
                    )
            }
            out
        } catch (e: Exception) {
            L.e(e, "Could not read the lyric index")
            mutableMapOf()
        }
    }

    fun save(bindings: Map<String, LyricsBinding>) {
        try {
            val root = JSONObject()
            root.put("version", VERSION)
            val entries = JSONObject()
            bindings.forEach { (key, binding) ->
                entries.put(
                    key,
                    JSONObject()
                        .put(KEY_LRC, binding.lrcUri)
                        .put(KEY_SOURCE, binding.source.name)
                        .put("score", binding.score.toDouble())
                        .put("label", binding.label ?: "")
                )
            }
            root.put(KEY_BINDINGS, entries)
            file.writeText(root.toString())
        } catch (e: Exception) {
            L.e(e, "Could not write the lyric index")
        }
    }

    /** Keeps only entries the caller still wants, dropping bindings for songs that are gone. */
    fun prune(validSongs: Set<String>, bindings: Map<String, LyricsBinding>) =
        bindings.filterKeys { it in validSongs || bindings[it]?.source == LyricsSource.MANUAL }

    private companion object {
        const val FILE_NAME = "lyrics-index.json"
        const val VERSION = 1
        const val KEY_BINDINGS = "bindings"
        const val KEY_LRC = "lrc"
        const val KEY_SOURCE = "source"
    }
}
