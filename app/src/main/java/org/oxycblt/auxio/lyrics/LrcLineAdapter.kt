/*
 * Copyright (c) 2025 Auxio Project
 * LrcLineAdapter.kt is part of Auxio.
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

import android.graphics.Typeface
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import org.oxycblt.auxio.databinding.ItemLrcLineBinding
import org.oxycblt.musikr.lyrics.LrcLine

/**
 * Draws the lyric lines with the current one highlighted.
 *
 * Tapping a line seeks there, which is the only interaction a lyric view needs.
 */
internal class LrcLineAdapter(private val onSeek: (Long) -> Unit) :
    ListAdapter<LrcLine, LrcLineAdapter.ViewHolder>(DIFF) {
    private var currentIndex = -1

    fun setCurrent(index: Int) {
        if (index == currentIndex) {
            return
        }
        val previous = currentIndex
        currentIndex = index
        // A payload keeps this a style-only update; rebinding every time made the list flicker.
        if (previous in 0 until itemCount) {
            notifyItemChanged(previous, PAYLOAD_HIGHLIGHT)
        }
        if (index in 0 until itemCount) {
            notifyItemChanged(index, PAYLOAD_HIGHLIGHT)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(ItemLrcLineBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) =
        holder.bind(getItem(position), position == currentIndex)

    override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty()) {
            super.onBindViewHolder(holder, position, payloads)
        } else {
            holder.applyHighlight(getItem(position), position == currentIndex)
        }
    }

    inner class ViewHolder(private val binding: ItemLrcLineBinding) :
        RecyclerView.ViewHolder(binding.root) {
        init {
            binding.root.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onSeek(getItem(position).timeMs)
                }
            }
        }

        fun bind(line: LrcLine, isCurrent: Boolean) {
            binding.lrcText.text = line.text
            binding.lrcTranslation.isVisible = line.translation != null
            binding.lrcTranslation.text = line.translation.orEmpty()
            applyHighlight(line, isCurrent)
        }

        /** Style-only pass, used when the highlight moves without the text changing. */
        fun applyHighlight(line: LrcLine, isCurrent: Boolean) {
            val attribute =
                if (isCurrent) {
                    androidx.appcompat.R.attr.colorPrimary
                } else {
                    com.google.android.material.R.attr.colorOnSurfaceVariant
                }
            binding.lrcText.setTextColor(MaterialColors.getColor(binding.root, attribute))
            binding.lrcText.setTypeface(null, if (isCurrent) Typeface.BOLD else Typeface.NORMAL)
            binding.lrcText.setTextSize(
                TypedValue.COMPLEX_UNIT_SP,
                if (isCurrent) HIGHLIGHT_SP else IDLE_SP
            )
        }
    }

    private companion object {
        const val PAYLOAD_HIGHLIGHT = 1
        const val HIGHLIGHT_SP = 20f
        const val IDLE_SP = 16f

        val DIFF =
            object : DiffUtil.ItemCallback<LrcLine>() {
                override fun areItemsTheSame(oldItem: LrcLine, newItem: LrcLine) =
                    oldItem.timeMs == newItem.timeMs

                override fun areContentsTheSame(oldItem: LrcLine, newItem: LrcLine) =
                    oldItem == newItem
            }
    }
}
