/*
 * Copyright (c) 2025 Auxio Project
 * LyricsFragment.kt is part of Auxio.
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
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.FragmentLyricsBinding
import org.oxycblt.auxio.ui.ViewBindingFragment

/**
 * Shows how many songs found lyrics, and lets the user pull the rest from the network.
 *
 * The scan is the honest part of this feature: it reports what actually matched rather than
 * silently binding whatever was closest.
 */
@AndroidEntryPoint
class LyricsFragment : ViewBindingFragment<FragmentLyricsBinding>() {
    private val lyricsModel: LyricsViewModel by viewModels()
    private val adapter = LyricsMatchAdapter { candidate -> lyricsModel.openPreview(candidate) }

    override fun onCreateBinding(inflater: LayoutInflater) = FragmentLyricsBinding.inflate(inflater)

    override fun onBindingCreated(binding: FragmentLyricsBinding, savedInstanceState: Bundle?) {
        binding.lyricsToolbar.setNavigationOnClickListener { findNavController().navigateUp() }

        binding.lyricsRecycler.adapter = adapter
        binding.lyricsRescan.setOnClickListener { lyricsModel.scan() }
        binding.lyricsFetch.setOnClickListener { lyricsModel.fetchOnline() }
        binding.lyricsOnline.setOnCheckedChangeListener { _, checked ->
            if (checked != lyricsModel.state.value?.online) {
                lyricsModel.setOnline(checked)
            }
        }

        lyricsModel.state.observe(viewLifecycleOwner) { render(it) }
        lyricsModel.preview.observe(viewLifecycleOwner) { payload ->
            if (payload != null) {
                showPreview(payload)
                lyricsModel.consumePreview()
            }
        }

        if (savedInstanceState == null) {
            lyricsModel.scan()
        }
    }

    private fun render(state: LyricsUiState) {
        val binding = binding ?: return
        adapter.submitList(state.items)

        binding.lyricsOnline.isChecked = state.online

        binding.lyricsStatus.text =
            when (state.phase) {
                LyricsPhase.IDLE -> getString(R.string.lyrics_idle)
                LyricsPhase.SCANNING ->
                    getString(R.string.lyrics_scanning, state.scanned, state.found)
                LyricsPhase.FETCHING ->
                    getString(R.string.lyrics_fetching, state.progress, state.progressTotal)
                LyricsPhase.READY,
                LyricsPhase.DONE -> state.notice ?: getString(R.string.lyrics_idle)
            }

        val busy = state.phase == LyricsPhase.SCANNING || state.phase == LyricsPhase.FETCHING
        binding.lyricsProgress.isVisible = busy
        if (state.phase == LyricsPhase.FETCHING && state.progressTotal > 0) {
            binding.lyricsProgress.isIndeterminate = false
            binding.lyricsProgress.max = state.progressTotal
            binding.lyricsProgress.setProgressCompat(state.progress, true)
        } else {
            binding.lyricsProgress.isIndeterminate = true
        }

        binding.lyricsEmpty.isVisible = !busy && state.items.isEmpty()
        binding.lyricsEmpty.text = state.notice ?: getString(R.string.lyrics_idle)
        binding.lyricsRescan.isEnabled = !busy
        binding.lyricsFetch.isEnabled = !busy && state.online
    }

    private fun showPreview(payload: LyricsPreview) {
        val lrc = payload.lrc
        val text =
            if (lrc == null || lrc.isEmpty) {
                getString(R.string.lyrics_preview_empty)
            } else {
                lrc.lines.joinToString("\n") { line ->
                    if (line.translation == null) {
                        line.text
                    } else {
                        "${line.text}\n${line.translation}"
                    }
                }
            }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(payload.title)
            .setMessage(text)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    override fun onDestroyBinding(binding: FragmentLyricsBinding) {
        binding.lyricsRecycler.adapter = null
    }
}
