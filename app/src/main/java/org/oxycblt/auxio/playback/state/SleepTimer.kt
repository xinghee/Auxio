/*
 * Copyright (c) 2025 Auxio Project
 * SleepTimer.kt is part of Auxio.
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
 
package org.oxycblt.auxio.playback.state

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber as L

/**
 * Pauses playback after a set amount of wall-clock time.
 *
 * Wall-clock, not playback time: the intent is "I am going to sleep in 20 minutes", so manually
 * pausing and resuming in the middle should not extend the timer.
 *
 * The timer lives in a singleton so it survives the playback panel being closed, but it does not
 * survive the process being killed, which is the normal expectation for this kind of control.
 */
@Singleton
class SleepTimer @Inject constructor(private val playbackManager: PlaybackStateManager) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _remainingMs = MutableStateFlow<Long?>(null)

    /** Time left before playback is paused, or null when no timer is running. */
    val remainingMs: StateFlow<Long?> = _remainingMs.asStateFlow()

    private var job: Job? = null
    private var minutes = 0

    /** Starts (or restarts) the timer for [minutes]. */
    fun start(minutes: Int) {
        if (minutes <= 0) {
            return
        }
        cancel()
        this.minutes = minutes
        job =
            scope.launch {
                var left = minutes * 60_000L
                _remainingMs.value = left
                while (left > 0) {
                    delay(TICK_MS)
                    left -= TICK_MS
                    _remainingMs.value = left.coerceAtLeast(0)
                }
                L.d("Sleep timer fired after $minutes minutes")
                playbackManager.playing(false)
                _remainingMs.value = null
                job = null
            }
    }

    fun cancel() {
        job?.cancel()
        job = null
        minutes = 0
        _remainingMs.value = null
    }

    /** The duration this timer was started with, or 0 when idle. */
    fun activeMinutes(): Int = if (job?.isActive == true) minutes else 0

    private companion object {
        const val TICK_MS = 1_000L
    }
}
