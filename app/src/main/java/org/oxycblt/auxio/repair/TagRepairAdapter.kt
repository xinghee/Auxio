/*
 * Copyright (c) 2025 Auxio Project
 * TagRepairAdapter.kt is part of Auxio.
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

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.ItemTagRepairBinding

/** Lists the files that need a repair, one row per file. */
internal class TagRepairAdapter(private val onToggle: (RepairEntry, Boolean) -> Unit) :
    ListAdapter<RepairEntry, TagRepairAdapter.ViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(ItemTagRepairBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(getItem(position))

    inner class ViewHolder(private val binding: ItemTagRepairBinding) :
        RecyclerView.ViewHolder(binding.root) {
        init {
            binding.root.setOnClickListener { toggle() }
            binding.repairCheck.setOnClickListener { toggle() }
        }

        private fun toggle() {
            val position = bindingAdapterPosition
            if (position == RecyclerView.NO_POSITION) {
                return
            }
            val entry = getItem(position)
            onToggle(entry, !entry.selected)
        }

        fun bind(entry: RepairEntry) {
            binding.repairCheck.isChecked = entry.selected
            binding.repairName.text = entry.candidate.displayName
            val context = binding.root.context
            binding.repairDetail.text =
                entry.candidate.analysis.fixes.joinToString("\n") { fix ->
                    context.getString(
                        R.string.repair_entry_fix,
                        fix.frameId,
                        fix.before,
                        fix.after
                    )
                }
        }
    }

    private companion object {
        val DIFF =
            object : DiffUtil.ItemCallback<RepairEntry>() {
                override fun areItemsTheSame(oldItem: RepairEntry, newItem: RepairEntry) =
                    oldItem.id == newItem.id

                override fun areContentsTheSame(oldItem: RepairEntry, newItem: RepairEntry) =
                    oldItem == newItem
            }
    }
}
