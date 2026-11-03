/*
 * Copyright (c) 2025 Auxio Project
 * LyricsViewModel.kt is part of Auxio.
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
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.oxycblt.auxio.R
import org.oxycblt.auxio.music.MusicRepository
import org.oxycblt.auxio.music.MusicSettings
import org.oxycblt.musikr.lyrics.Lrc
import timber.log.Timber as L

internal enum class LyricsPhase {
    IDLE,
    SCANNING,
    READY,
    FETCHING,
    DONE,
}

/** What the management screen draws. */
internal data class LyricsUiState(
    val phase: LyricsPhase = LyricsPhase.IDLE,
    val scanned: Int = 0,
    val found: Int = 0,
    val items: List<MatchCandidate> = emptyList(),
    val progress: Int = 0,
    val progressTotal: Int = 0,
    val notice: String? = null,
    val online: Boolean = false,
    val songCount: Int = 0,
) {
    val bound: Int
        get() = items.count { it.entry != null }

    val missing: Int
        get() = items.count { it.entry == null }
}

/** Raised when a row is tapped so the fragment can show the lyric text. */
internal data class LyricsPreview(val title: String, val lrc: Lrc?)

/**
 * Drives lyric scanning, matching and online completion.
 */
@HiltViewModel
internal class LyricsViewModel
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val musicSettings: MusicSettings,
    private val musicRepository: MusicRepository,
) : ViewModel() {
    private val repository = LyricsRepository(context)
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _state = MutableLiveData(LyricsUiState(online = onlineEnabled))
    val state: LiveData<LyricsUiState> = _state

    private val _preview = MutableLiveData<LyricsPreview?>()
    val preview: LiveData<LyricsPreview?> = _preview

    private var current = LyricsUiState(online = onlineEnabled)
    private var job: Job? = null

    private val onlineEnabled: Boolean
        get() = prefs.getBoolean(KEY_ONLINE, false)

    fun setOnline(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ONLINE, enabled).apply()
        mutate { it.copy(online = enabled) }
    }

    fun scan() {
        if (job?.isActive == true) return
        job =
            viewModelScope.launch {
                val songs = songKeys()
                if (songs.isEmpty()) {
                    mutate {
                        it.copy(
                            phase = LyricsPhase.DONE,
                            items = emptyList(),
                            notice = context.getString(R.string.lyrics_no_library),
                        )
                    }
                    return@launch
                }
                val roots =
                    try {
                        musicSettings.safQuery.source
                    } catch (e: Exception) {
                        L.e(e, "Cannot read the music folders")
                        emptyList()
                    }
                if (roots.isEmpty()) {
                    mutate {
                        it.copy(
                            phase = LyricsPhase.DONE,
                            items = emptyList(),
                            notice = context.getString(R.string.lyrics_no_folders),
                        )
                    }
                    return@launch
                }

                mutate {
                    it.copy(
                        phase = LyricsPhase.SCANNING,
                        scanned = 0,
                        found = 0,
                        items = emptyList(),
                        notice = null,
                        songCount = songs.size,
                    )
                }

                val report =
                    try {
                        repository.scan(roots, songs) { scanned, found ->
                            mutate { it.copy(scanned = scanned, found = found) }
                        }
                    } catch (e: Exception) {
                        L.e(e, "Lyric scan failed")
                        mutate {
                            it.copy(
                                phase = LyricsPhase.DONE,
                                notice = context.getString(R.string.lyrics_scan_failed),
                            )
                        }
                        return@launch
                    }

                mutate {
                    it.copy(
                        phase = LyricsPhase.READY,
                        items = report.candidates,
                        found = report.lrcCount,
                        notice =
                            context.getString(
                                R.string.lyrics_scanned,
                                report.lrcCount,
                                report.bound,
                                report.candidates.size,
                            ),
                    )
                }
            }
    }

    /** Pulls lyrics for every unbound song from the network. */
    fun fetchOnline() {
        if (job?.isActive == true) return
        if (!onlineEnabled) {
            mutate { it.copy(notice = context.getString(R.string.lyrics_online_off)) }
            return
        }
        job =
            viewModelScope.launch {
                val songs = songKeys()
                val pending = current.items.filter { it.entry == null }.map { it.song }
                if (pending.isEmpty()) {
                    mutate { it.copy(notice = context.getString(R.string.lyrics_nothing_missing)) }
                    return@launch
                }
                mutate {
                    it.copy(
                        phase = LyricsPhase.FETCHING,
                        progress = 0,
                        progressTotal = pending.size,
                        notice = null,
                    )
                }
                val gained =
                    try {
                        repository.fetchMissing(songs) { done, total, _ ->
                            mutate { it.copy(progress = done, progressTotal = total) }
                        }
                    } catch (e: Exception) {
                        L.e(e, "Online lyric fetch failed")
                        0
                    }
                mutate {
                    it.copy(
                        phase = LyricsPhase.DONE,
                        notice = context.getString(R.string.lyrics_fetched, gained),
                    )
                }
                // Rescan so the new bindings show up. The in-flight job has to be released first,
                // otherwise scan() sees an active job and returns immediately.
                job = null
                scan()
            }
    }

    fun openPreview(candidate: MatchCandidate) {
        job =
            viewModelScope.launch {
                val binding = repository.bindingFor(candidate.song.uri.toString())
                val lrc = binding?.let { repository.read(it) }
                _preview.value = LyricsPreview(candidate.song.title, lrc)
            }
    }

    fun consumePreview() {
        _preview.value = null
    }

    private fun songKeys(): List<SongKey> {
        val library = musicRepository.library ?: return emptyList()
        return library.songs.map { song ->
            SongKey(
                uri = song.uri,
                title = song.name.resolve(context),
                artist =
                    song.artists.joinToString("/") { artist -> artist.name.resolve(context) },
                durationMs = song.durationMs,
            )
        }
    }

    @Synchronized
    private fun mutate(block: (LyricsUiState) -> LyricsUiState) {
        val next = block(current)
        current = next
        _state.postValue(next)
    }

    private companion object {
        const val PREFS = "lyrics"
        const val KEY_ONLINE = "online_enabled"
    }
}
