package com.beatraxus.app.ui.components.seekbars

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.State
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.abs
import kotlin.math.sin

/** Shared drag state for the Beatraxus seekbars. */
internal class SeekDragState {
    var dragging by mutableStateOf<Float?>(null)
    var lastTarget by mutableStateOf<Float?>(null)
    fun display(progress: Float): Float = (dragging ?: lastTarget ?: progress).coerceIn(0f, 1f)
}

@Composable
internal fun rememberSeekDragState(progress: Float): SeekDragState {
    val state = remember { SeekDragState() }
    LaunchedEffect(progress) {
        val t = state.lastTarget
        if (t != null && abs(progress - t) < 0.01f) state.lastTarget = null
    }
    return state
}

/** Tap + drag to seek. Calls onChange while dragging and onFinished on release. */
@Composable
internal fun Modifier.seekDrag(
    state: SeekDragState,
    onProgressChange: (Float) -> Unit,
    onProgressFinished: (Float) -> Unit
): Modifier {
    val change by rememberUpdatedState(onProgressChange)
    val finish by rememberUpdatedState(onProgressFinished)
    return this.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown()
            val width = size.width.toFloat().coerceAtLeast(1f)
            val initial = (down.position.x / width).coerceIn(0f, 1f)
            state.dragging = initial
            change(initial)
            var lastX = down.position.x
            drag(down.id) { c ->
                lastX = c.position.x
                val p = (lastX / width).coerceIn(0f, 1f)
                state.dragging = p
                change(p)
                c.consume()
            }
            val fin = (lastX / width).coerceIn(0f, 1f)
            state.dragging = null
            state.lastTarget = fin
            finish(fin)
        }
    }
}

/** 0..1 looping value that only animates while playing (idle bars cost nothing). */
@Composable
internal fun rememberLoop(isPlaying: Boolean, durationMs: Int, label: String): State<Float> {
    val transition = rememberInfiniteTransition(label = label)
    return if (isPlaying) {
        transition.animateFloat(
            initialValue = 0f, targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(durationMs, easing = LinearEasing)),
            label = label
        )
    } else {
        remember { mutableStateOf(0f) }
    }
}

/** Stable per-song pseudo-waveform envelope, values 0.2..1.0. */
internal fun beatraxusEnvelope(seed: Int, size: Int = 128): FloatArray {
    val p1 = (seed % 97) * 0.13f
    val p2 = (seed % 61) * 0.29f
    return FloatArray(size) { i ->
        val a = sin(i * 0.23f + p1) * 0.55f
        val b = sin(i * 0.071f + p2) * 0.30f
        val c = sin(i * 0.61f + p1 * 2f) * 0.15f
        (0.2f + 0.8f * abs(a + b + c)).coerceIn(0.2f, 1f)
    }
}
