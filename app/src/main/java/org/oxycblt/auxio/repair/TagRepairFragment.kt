/*
 * Copyright (c) 2025 Auxio Project
 * TagRepairFragment.kt is part of Auxio.
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
 
package org.oxycblt.auxio.repair

import android.os.Bundle
import android.view.LayoutInflater
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.FragmentTagRepairBinding
import org.oxycblt.auxio.ui.ViewBindingFragment

/**
 * Shows which files have a repairable tag and lets the user rewrite them.
 *
 * The scan runs automatically against the folders that are already registered in Auxio, so no
 * folder picker is needed and the result can never disagree with what the library shows.
 */
@AndroidEntryPoint
class TagRepairFragment : ViewBindingFragment<FragmentTagRepairBinding>() {
    private val repairModel: RepairViewModel by viewModels()
    private val adapter = TagRepairAdapter { entry, selected -> repairModel.toggle(entry, selected) }

    override fun onCreateBinding(inflater: LayoutInflater) =
        FragmentTagRepairBinding.inflate(inflater)

    override fun onBindingCreated(binding: FragmentTagRepairBinding, savedInstanceState: Bundle?) {
        binding.repairToolbar.setNavigationOnClickListener { findNavController().navigateUp() }

        binding.repairRecycler.adapter = adapter
        binding.repairApply.setOnClickListener {
            when (repairModel.state.value?.phase) {
                RepairPhase.FINISHED,
                RepairPhase.IDLE -> repairModel.scan()
                RepairPhase.READY -> repairModel.repair()
                else -> Unit
            }
        }
        binding.repairUndo.setOnClickListener { confirmUndo() }

        repairModel.state.observe(viewLifecycleOwner) { render(it) }

        if (savedInstanceState == null) {
            repairModel.scan()
        }
    }

    private fun render(state: RepairState) {
        val binding = binding ?: return
        adapter.submitList(state.entries)

        binding.repairStatus.text =
            when (state.phase) {
                RepairPhase.IDLE -> getString(R.string.repair_idle)
                RepairPhase.SCANNING -> getString(R.string.repair_scanning, state.scanned)
                RepairPhase.READY ->
                    if (state.entries.isEmpty()) {
                        getString(R.string.repair_scanned_none, state.scanned)
                    } else {
                        getString(
                            R.string.repair_scanned_found,
                            state.scanned,
                            state.entries.size,
                        )
                    }
                RepairPhase.WORKING ->
                    getString(R.string.repair_applying, state.progress, state.progressTotal)
                RepairPhase.FINISHED -> state.notice.orEmpty()
            }

        val busy = state.phase == RepairPhase.SCANNING || state.phase == RepairPhase.WORKING
        binding.repairEmpty.isVisible =
            state.phase == RepairPhase.READY && state.entries.isEmpty()

        binding.repairProgress.isVisible = busy
        if (state.phase == RepairPhase.WORKING) {
            binding.repairProgress.isIndeterminate = false
            binding.repairProgress.max = maxOf(1, state.progressTotal)
            binding.repairProgress.setProgressCompat(state.progress, true)
        } else if (state.phase == RepairPhase.SCANNING) {
            binding.repairProgress.isIndeterminate = true
        }

        binding.repairUndo.isVisible = state.canUndo && !busy
        binding.repairApply.isEnabled =
            !busy && (state.phase != RepairPhase.READY || state.selectedCount > 0)
        binding.repairApply.text =
            when (state.phase) {
                RepairPhase.READY ->
                    if (state.selectedCount > 0) {
                        getString(R.string.repair_apply, state.selectedCount)
                    } else {
                        getString(R.string.repair_apply_none)
                    }
                RepairPhase.FINISHED,
                RepairPhase.IDLE -> getString(R.string.repair_rescan)
                else -> getString(R.string.repair_apply_none)
            }
    }

    private fun confirmUndo() {
        MaterialAlertDialogBuilder(requireContext())
            .setMessage(R.string.repair_undo_confirm)
            .setPositiveButton(R.string.repair_undo) { _, _ -> repairModel.undo() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onDestroyBinding(binding: FragmentTagRepairBinding) {
        binding.repairRecycler.adapter = null
    }
}
