package com.beatraxus.app.features

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

/**
 * A-B loop. Tap once to set point A, tap again to set point B (loop starts),
 * tap a third time to clear. Works for audio (Now Playing) and video.
 */
@Stable
class AbLoopState {
    var pointA by mutableStateOf<Long?>(null)
        private set
    var pointB by mutableStateOf<Long?>(null)
        private set

    val isActive: Boolean get() = pointA != null && pointB != null

    /** Label for a button: "A-B Loop" -> "Set B" -> "Loop On" */
    val label: String
        get() = when {
            isActive -> "Loop On"
            pointA != null -> "Set B"
            else -> "A-B Loop"
        }

    fun tap(positionMs: Long) {
        val a = pointA
        val b = pointB
        when {
            a == null -> pointA = positionMs
            b == null -> {
                if (positionMs - a < 500L) {
                    // B too close to A: restart from this point
                    pointA = positionMs
                } else {
                    pointB = positionMs
                }
            }
            else -> clear()
        }
    }

    fun clear() {
        pointA = null
        pointB = null
    }
}

/** State that resets whenever [key] (for example the song id) changes. */
@Composable
fun rememberAbLoopState(key: Any?): AbLoopState = remember(key) { AbLoopState() }

/** Watches playback and jumps back to A whenever playback reaches B. */
@Composable
fun AbLoopEffect(
    state: AbLoopState,
    isPlaying: Boolean,
    positionMs: () -> Long,
    seekTo: (Long) -> Unit
) {
    val currentPosition by rememberUpdatedState(positionMs)
    val currentSeek by rememberUpdatedState(seekTo)
    LaunchedEffect(state.pointA, state.pointB, isPlaying) {
        val a = state.pointA ?: return@LaunchedEffect
        val b = state.pointB ?: return@LaunchedEffect
        if (!isPlaying) return@LaunchedEffect
        while (true) {
            val pos = currentPosition()
            if (pos >= b || pos < a - 1500L) {
                currentSeek(a)
                delay(250)
            } else {
                delay(60)
            }
        }
    }
}
