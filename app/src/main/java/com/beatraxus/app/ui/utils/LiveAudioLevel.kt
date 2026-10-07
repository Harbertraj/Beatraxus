package com.beatraxus.app.ui.utils

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.beatraxus.app.viewmodel.PlayerViewModel
import kotlinx.coroutines.delay
import kotlin.math.sqrt

/**
 * Live, normalised (0..1) loudness of whatever is playing right now, or null when no provider is
 * installed (e.g. the seekbar style preview in Settings, which then keeps its synthetic animation).
 * Read it inside a draw scope so only the draw phase is invalidated, not the whole composition.
 */
val LocalLiveAudioLevel = compositionLocalOf<(() -> Float)?> { null }

/**
 * Samples the PCM pipeline (PlayerViewModel.captureLiveWindow, the same tap the DSP graph and
 * Music Detail Inspector use, so it also works in bit-perfect / exclusive output) ~30 times per
 * second and publishes a beat-friendly level: bass-weighted energy, auto-gain normalised so quiet
 * and loud tracks both use the full range, with a fast attack and a slower release.
 */
@Composable
fun ProvideLiveAudioLevel(
    viewModel: PlayerViewModel,
    isPlaying: Boolean,
    content: @Composable () -> Unit
) {
    val level = remember { mutableFloatStateOf(0f) }
    val provider = remember { { level.floatValue } }
    val playing by rememberUpdatedState(isPlaying)

    LaunchedEffect(viewModel) {
        var smooth = 0f
        var ceiling = 0.05f
        var low = 0f
        while (true) {
            if (!playing) {
                // Paused: let the level fall to rest instead of freezing on the last buffer.
                smooth *= 0.7f
                if (smooth < 0.003f) smooth = 0f
                level.floatValue = smooth
                delay(if (smooth > 0f) 33L else 150L)
                continue
            }
            val capture = viewModel.captureLiveWindow()
            if (capture != null && capture.samples.isNotEmpty()) {
                val ch = capture.channels.coerceAtLeast(1)
                val frames = capture.samples.size / ch
                if (frames > 0) {
                    var sumSq = 0.0
                    var lowSq = 0.0
                    for (f in 0 until frames) {
                        val l = capture.samples[f * ch]
                        val r = if (ch > 1) capture.samples[f * ch + 1] else l
                        val mono = (l + r) * 0.5f
                        low += 0.08f * (mono - low) // one-pole low-pass: kick / bass emphasis
                        sumSq += (mono * mono).toDouble()
                        lowSq += (low * low).toDouble()
                    }
                    val rms = sqrt(sumSq / frames).toFloat()
                    val lowRms = sqrt(lowSq / frames).toFloat()
                    val raw = 0.4f * rms + 0.6f * lowRms

                    // Slow-decaying ceiling = automatic gain control.
                    ceiling = maxOf(raw, ceiling * 0.995f, 0.02f)
                    val norm = (raw / ceiling).coerceIn(0f, 1f)
                    val shaped = norm * norm // more contrast between beats and gaps

                    smooth = if (shaped > smooth) smooth + (shaped - smooth) * 0.7f
                    else smooth * 0.82f
                    level.floatValue = smooth
                }
            }
            delay(33L)
        }
    }

    CompositionLocalProvider(LocalLiveAudioLevel provides provider, content = content)
}
