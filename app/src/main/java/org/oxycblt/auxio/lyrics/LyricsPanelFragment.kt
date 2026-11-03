/*
 * Copyright (c) 2025 Auxio Project
 * LyricsPanelFragment.kt is part of Auxio.
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

import android.os.Bundle
import android.view.LayoutInflater
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch
import org.oxycblt.auxio.databinding.FragmentLyricsPanelBinding
import org.oxycblt.auxio.music.resolve
import org.oxycblt.auxio.playback.state.PlaybackStateManager
import org.oxycblt.auxio.playback.state.Progression
import org.oxycblt.auxio.playback.state.QueueChange
import org.oxycblt.auxio.ui.ViewBindingFragment
import org.oxycblt.musikr.MusicParent
import org.oxycblt.musikr.Song
import org.oxycblt.musikr.lyrics.LrcLine
import timber.log.Timber as L

/**
 * Full-screen lyric view for whatever is playing.
 *
 * Lines are stacked top to bottom and the current one is scrolled a third of the way down so the
 * next lines stay readable. Tapping a line seeks there.
 */
@AndroidEntryPoint
class LyricsPanelFragment : ViewBindingFragment<FragmentLyricsPanelBinding>() {
    @Inject lateinit var playbackManager: PlaybackStateManager

    private val adapter = LrcLineAdapter { timeMs -> playbackManager.seekTo(timeMs) }
    private var repository: LyricsRepository? = null
    private var lines: List<LrcLine> = emptyList()
    private var loadedSongUri: String? = null
    private var currentIndex = -1

    private val listener =
        object : PlaybackStateManager.Listener {
            override fun onProgressionChanged(progression: Progression) {
                applyPosition(progression.calculateElapsedPositionMs())
            }

            override fun onIndexMoved(index: Int) {
                reload()
            }

            override fun onNewPlayback(
                parent: MusicParent?,
                queue: List<Song>,
                index: Int,
                isShuffled: Boolean,
            ) {
                reload()
            }

            override fun onQueueChanged(queue: List<Song>, index: Int, change: QueueChange) {
                reload()
            }
        }

    override fun onCreateBinding(inflater: LayoutInflater) =
        FragmentLyricsPanelBinding.inflate(inflater)

    override fun onBindingCreated(binding: FragmentLyricsPanelBinding, savedInstanceState: Bundle?) {
        binding.lyricPanelToolbar.setNavigationOnClickListener {
            findNavController().navigateUp()
        }
        binding.lyricPanelRecycler.adapter = adapter
        repository = LyricsRepository(requireContext())
    }

    override fun onStart() {
        super.onStart()
        playbackManager.addListener(listener)
        reload()
    }

    override fun onStop() {
        playbackManager.removeListener(listener)
        super.onStop()
    }

    private fun reload() {
        val song = playbackManager.currentSong
        val uri = song?.uri?.toString()
        if (uri == loadedSongUri) {
            return
        }
        loadedSongUri = uri
        lines = emptyList()
        currentIndex = -1
        adapter.setCurrent(-1)
        adapter.submitList(emptyList())

        val binding = binding ?: return
        if (song == null || uri == null) {
            binding.lyricPanelEmpty.isVisible = true
            return
        }
        binding.lyricPanelToolbar.title = song.name.resolve(requireContext())
        binding.lyricPanelEmpty.isVisible = false

        viewLifecycleOwner.lifecycleScope.launch {
            val repo = repository ?: return@launch
            val bindingInfo = repo.bindingFor(uri)
            if (bindingInfo == null) {
                binding.lyricPanelEmpty.isVisible = true
                return@launch
            }
            val lrc = repo.read(bindingInfo)
            if (lrc == null || lrc.isEmpty) {
                binding.lyricPanelEmpty.isVisible = true
                return@launch
            }
            binding.lyricPanelEmpty.isVisible = false
            lines = lrc.lines
            adapter.submitList(lines)
            L.d("Loaded ${lines.size} lyric lines")
        }
    }

    private fun applyPosition(positionMs: Long) {
        if (lines.isEmpty()) {
            return
        }
        var index = lines.indexOfLast { it.timeMs <= positionMs }
        if (index < 0) {
            index = 0
        }
        if (index == currentIndex) {
            return
        }
        currentIndex = index
        adapter.setCurrent(index)

        val binding = binding ?: return
        val layoutManager = binding.lyricPanelRecycler.layoutManager as? LinearLayoutManager ?: return
        layoutManager.scrollToPositionWithOffset(index, binding.lyricPanelRecycler.height / 3)
    }

    override fun onDestroyBinding(binding: FragmentLyricsPanelBinding) {
        binding.lyricPanelRecycler.adapter = null
        repository = null
    }
}
