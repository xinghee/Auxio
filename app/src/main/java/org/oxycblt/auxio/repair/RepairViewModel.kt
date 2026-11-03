/*
 * Copyright (c) 2025 Auxio Project
 * RepairViewModel.kt is part of Auxio.
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
import org.oxycblt.auxio.music.MusicSettings
import timber.log.Timber as L

/** Where the repair screen currently is. */
internal enum class RepairPhase {
    /** Nothing has been done yet. */
    IDLE,
    /** Walking the music folders. */
    SCANNING,
    /** Results are on screen, waiting for the user. */
    READY,
    /** Writing files. */
    WORKING,
    /** A run finished; the button offers a rescan. */
    FINISHED,
}

/** One row of the repair list. */
internal data class RepairEntry(val candidate: RepairCandidate, val selected: Boolean = true) {
    val id: String
        get() = candidate.uri.toString()
}

/** Everything the screen needs to draw itself. */
internal data class RepairState(
    val phase: RepairPhase = RepairPhase.IDLE,
    val scanned: Int = 0,
    val entries: List<RepairEntry> = emptyList(),
    val progress: Int = 0,
    val progressTotal: Int = 0,
    val notice: String? = null,
    val canUndo: Boolean = false,
    val hasLocations: Boolean = true,
) {
    val selectedCount: Int
        get() = entries.count { it.selected }
}

/**
 * Drives scanning, repairing and undoing.
 *
 * Scanning and writing both happen off the main thread, so progress arrives through [postValue]
 * rather than [setValue].
 */
@HiltViewModel
internal class RepairViewModel
@Inject
constructor(
    @ApplicationContext context: Context,
    private val musicSettings: MusicSettings,
) : ViewModel() {
    private val appContext = context
    private val manager = TagRepairManager(context)

    private val _state = MutableLiveData(RepairState(canUndo = manager.hasBackup()))
    val state: LiveData<RepairState> = _state

    private var current = RepairState(canUndo = manager.hasBackup())
    private var job: Job? = null

    /** Scan the registered music folders. Safe to call when a run is already going. */
    fun scan() {
        if (job?.isActive == true) return
        job =
            viewModelScope.launch {
                val roots =
                    try {
                        musicSettings.safQuery.source
                    } catch (e: Exception) {
                        L.e(e, "Cannot read the configured music folders")
                        emptyList()
                    }
                if (roots.isEmpty()) {
                    mutate {
                        it.copy(
                            phase = RepairPhase.FINISHED,
                            entries = emptyList(),
                            notice = appContext.getString(R.string.repair_no_locations),
                            hasLocations = false,
                        )
                    }
                    return@launch
                }

                mutate {
                    it.copy(
                        phase = RepairPhase.SCANNING,
                        scanned = 0,
                        entries = emptyList(),
                        notice = null,
                        progress = 0,
                        progressTotal = 0,
                        hasLocations = true,
                    )
                }

                var lastScanned = 0
                var lastPost = 0
                val found =
                    try {
                        manager.scan(roots) { scanned, count ->
                            lastScanned = scanned
                            // This fires once per file; only repaint every few of them.
                            if (scanned - lastPost >= 8 || count > 0) {
                                lastPost = scanned
                                mutate { it.copy(scanned = scanned) }
                            }
                        }
                    } catch (e: Exception) {
                        L.e(e, "Scanning failed")
                        mutate {
                            it.copy(
                                phase = RepairPhase.FINISHED,
                                notice = appContext.getString(R.string.repair_failed),
                            )
                        }
                        return@launch
                    }

                mutate {
                    it.copy(
                        phase = RepairPhase.READY,
                        scanned = lastScanned,
                        entries = found.map { candidate -> RepairEntry(candidate) },
                        notice = null,
                    )
                }
            }
    }

    fun toggle(entry: RepairEntry, selected: Boolean) {
        mutate { state ->
            state.copy(
                entries =
                    state.entries.map {
                        if (it.id == entry.id) it.copy(selected = selected) else it
                    }
            )
        }
    }

    fun setAll(selected: Boolean) {
        mutate { state -> state.copy(entries = state.entries.map { it.copy(selected = selected) }) }
    }

    /** Repair every selected entry. */
    fun repair() {
        if (job?.isActive == true) return
        val targets = current.entries.filter { it.selected }.map { it.candidate }
        if (targets.isEmpty()) return
        job =
            viewModelScope.launch {
                mutate {
                    it.copy(
                        phase = RepairPhase.WORKING,
                        progress = 0,
                        progressTotal = targets.size,
                        notice = null,
                    )
                }
                val outcome = manager.repair(targets) { done, total ->
                    mutate { it.copy(progress = done, progressTotal = total) }
                }
                mutate {
                    it.copy(
                        phase = RepairPhase.FINISHED,
                        entries = emptyList(),
                        notice = describe(outcome),
                        canUndo = manager.hasBackup(),
                    )
                }
            }
    }

    /** Put back the tags of the last run. */
    fun undo() {
        if (job?.isActive == true) return
        job =
            viewModelScope.launch {
                val total = manager.lastBackupBatch().size
                mutate {
                    it.copy(
                        phase = RepairPhase.WORKING,
                        progress = 0,
                        progressTotal = total,
                        notice = null,
                    )
                }
                val outcome = manager.undo { done, count ->
                    mutate { it.copy(progress = done, progressTotal = count) }
                }
                val restored = appContext.getString(R.string.repair_undone, outcome.repaired)
                mutate {
                    it.copy(
                        phase = RepairPhase.FINISHED,
                        entries = emptyList(),
                        canUndo = manager.hasBackup(),
                        notice =
                            if (outcome.failed.isEmpty()) {
                                restored
                            } else {
                                restored +
                                    " · " +
                                    appContext.getString(
                                        R.string.repair_failed_count,
                                        outcome.failed.size,
                                    )
                            },
                    )
                }
            }
    }

    private fun describe(outcome: RepairOutcome): String {
        if (outcome.repaired == 0) {
            return appContext.getString(R.string.repair_failed)
        }
        return if (outcome.failed.isEmpty()) {
            appContext.getString(R.string.repair_done, outcome.repaired)
        } else {
            appContext.getString(
                R.string.repair_done_with_failures,
                outcome.repaired,
                outcome.failed.size,
            )
        }
    }

    @Synchronized
    private fun mutate(block: (RepairState) -> RepairState) {
        val next = block(current)
        current = next
        _state.postValue(next)
    }
}
