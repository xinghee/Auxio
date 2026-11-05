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

import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import androidx.core.view.isVisible
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.FragmentLyricsPanelBinding
import org.oxycblt.auxio.music.resolve
import org.oxycblt.auxio.playback.state.PlaybackStateManager
import org.oxycblt.auxio.playback.state.Progression
import org.oxycblt.auxio.playback.state.QueueChange
import org.oxycblt.auxio.ui.ViewBindingFragment
import org.oxycblt.auxio.util.showToast
import org.oxycblt.auxio.util.systemBarInsetsCompat
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

    /**
     * Latest playback snapshot. [Progression] only records the position it was created at, so the
     * current time has to be derived from it on a timer; waiting for the next callback leaves the
     * highlight stuck where it was when the view opened.
     */
    private var progression: Progression? = null
    private var ticker: Job? = null
    private var originalStatusBarColor: Int? = null

    private val listener =
        object : PlaybackStateManager.Listener {
            override fun onProgressionChanged(progression: Progression) {
                this@LyricsPanelFragment.progression = progression
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
            parentFragmentManager.popBackStack()
        }
        binding.lyricPanelRecycler.adapter = adapter
        binding.lyricPanelToolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_refetch_lyrics) {
                refetchLyrics()
                true
            } else {
                false
            }
        }
        repository = LyricsRepository(requireContext())

        // Added directly to the activity content view, so nothing else pads the status bar.
        // The bar is transparent and the app bar's surface sits behind it, so padding the app bar
        // keeps the title clear of the camera cutout without exposing anything underneath.
        binding.root.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.systemBarInsetsCompat
            binding.lyricPanelAppbar.updatePadding(top = bars.top)
            insets
        }
    }

    override fun onStart() {
        super.onStart()
        applyTransparentStatusBar()
        playbackManager.addListener(listener)
        reload()
        ticker?.cancel()
        ticker =
            viewLifecycleOwner.lifecycleScope.launch {
                while (isActive) {
                    delay(200)
                    val snapshot = progression ?: continue
                    if (snapshot.isPlaying) {
                        applyPosition(snapshot.calculateElapsedPositionMs())
                    }
                }
            }
    }

    override fun onStop() {
        ticker?.cancel()
        ticker = null
        playbackManager.removeListener(listener)
        restoreStatusBar()
        super.onStop()
    }

    /**
     * Hides the status bar while the lyrics are visible.
     *
     * Only the status bar: hiding the navigation bar too would make the back gesture awkward on
     * devices without a hardware key. Restored in [onStop] and again in [onDestroy] so an unusual
     * teardown cannot leave the rest of the app without a status bar.
     */
    /**
     * Makes the status bar transparent instead of hiding it.
     *
     * Hiding it is not an option on EMUI/HarmonyOS: [WindowInsetsControllerCompat.hide] removes the
     * icons but keeps the space and paints it black, and nothing the app draws gets on top of that
     * layer. A transparent bar with the app bar's colour behind it looks continuous on every ROM,
     * and the clock stays readable.
     */
    @Suppress("DEPRECATION")
    private fun applyTransparentStatusBar() {
        val window = activity?.window ?: return
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (originalStatusBarColor == null) {
            originalStatusBarColor = window.statusBarColor
        }
        window.statusBarColor = Color.TRANSPARENT
        // Dark icons on a light surface, light icons on a dark one.
        val night =
            (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        controller.isAppearanceLightStatusBars = !night
    }

    @Suppress("DEPRECATION")
    private fun restoreStatusBar() {
        val window = activity?.window ?: return
        originalStatusBarColor?.let { window.statusBarColor = it }
        originalStatusBarColor = null
    }

    /**
     * Searches online and lets the user pick a replacement.
     *
     * Deliberately not automatic: when the stored lyrics are wrong, the search's top hit is
     * usually wrong in the same way, so the choice has to be human.
     */
    private fun refetchLyrics() {
        val song = playbackManager.currentSong ?: return
        val repo = repository ?: return
        val context = requireContext()
        val key =
            SongKey(
                uri = song.uri,
                title = song.name.resolve(context),
                artist = song.artists.joinToString("/") { it.name.resolve(context) },
                durationMs = song.durationMs
            )

        viewLifecycleOwner.lifecycleScope.launch {
            val candidates =
                try {
                    repo.searchOnline(key)
                } catch (e: Exception) {
                    L.w(e, "Lyric search failed")
                    emptyList()
                }
            if (candidates.isEmpty()) {
                context.showToast(R.string.lyrics_refetch_none)
                return@launch
            }
            val labels =
                candidates
                    .map {
                        "${it.title} — ${it.artist}  (${it.durationSec / 60}:" +
                            "%02d".format(it.durationSec % 60) + ")"
                    }
                    .toTypedArray()
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.lyrics_refetch)
                .setItems(labels) { _, which ->
                    viewLifecycleOwner.lifecycleScope.launch {
                        val applied = repo.applyOnline(key, candidates[which])
                        if (applied) {
                            loadedSongUri = null
                            reload()
                            context.showToast(R.string.lyrics_refetch_done)
                        } else {
                            context.showToast(R.string.lyrics_refetch_failed)
                        }
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
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
            // submitList diffs asynchronously, so scrolling before the commit lands does nothing.
            adapter.submitList(lines) {
                progression?.let { applyPosition(it.calculateElapsedPositionMs()) }
            }
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
        val recycler = binding.lyricPanelRecycler
        val layoutManager = recycler.layoutManager as? LinearLayoutManager ?: return
        recycler.post {
            val indent = recycler.height / 3
            if (indent == 0) {
                layoutManager.scrollToPositionWithOffset(index, 0)
                return@post
            }
            val target = layoutManager.findViewByPosition(index)
            if (target != null) {
                // The line is already on screen, so nudge it instead of jumping: a hard
                // scrollToPositionWithOffset on every line change reads as stuttering.
                val offset = target.top - indent
                if (abs(offset) > 1) {
                    recycler.smoothScrollBy(0, offset)
                }
            } else {
                // Seeking far away: nothing to animate from, position it directly.
                layoutManager.scrollToPositionWithOffset(index, indent)
            }
        }
    }

    override fun onDestroy() {
        restoreStatusBar()
        super.onDestroy()
    }

    override fun onDestroyBinding(binding: FragmentLyricsPanelBinding) {
        binding.lyricPanelRecycler.adapter = null
        repository = null
    }
}
