/*
 * Copyright (c) 2025 Auxio Project
 * LyricsMatchAdapter.kt is part of Auxio.
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

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.ItemLyricsMatchBinding

/** Lists every song with the lyric file it matched, or why it matched nothing. */
internal class LyricsMatchAdapter(private val onClick: (MatchCandidate) -> Unit) :
    ListAdapter<MatchCandidate, LyricsMatchAdapter.ViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(ItemLyricsMatchBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(getItem(position))

    inner class ViewHolder(private val binding: ItemLyricsMatchBinding) :
        RecyclerView.ViewHolder(binding.root) {
        init {
            binding.root.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onClick(getItem(position))
                }
            }
        }

        fun bind(candidate: MatchCandidate) {
            val context = binding.root.context
            binding.lyricsSong.text = candidate.song.title
            val entry = candidate.entry
            binding.lyricsMatch.text =
                when {
                    entry == null -> context.getString(R.string.lyrics_match_none)
                    candidate.kind == MatchKind.FUZZY ->
                        context.getString(
                            R.string.lyrics_match_fuzzy,
                            entry.title.orEmpty(),
                            (candidate.score * 100).toInt()
                        )
                    else ->
                        context.getString(
                            R.string.lyrics_match_ok,
                            entry.title.orEmpty(),
                            entry.artist.orEmpty()
                        )
                }
        }
    }

    private companion object {
        val DIFF =
            object : DiffUtil.ItemCallback<MatchCandidate>() {
                override fun areItemsTheSame(oldItem: MatchCandidate, newItem: MatchCandidate) =
                    oldItem.song.uri == newItem.song.uri

                override fun areContentsTheSame(oldItem: MatchCandidate, newItem: MatchCandidate) =
                    oldItem == newItem
            }
    }
}
