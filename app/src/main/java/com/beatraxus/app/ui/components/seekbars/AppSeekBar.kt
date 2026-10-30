package com.beatraxus.app.ui.components.seekbars

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.beatraxus.app.model.ChapterEntity
import com.beatraxus.app.model.LrcLine
import com.beatraxus.app.model.SeekbarStyle
import com.beatraxus.app.ui.components.WaveformSeekBar
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp

/**
 * Seekbar dispatcher. Available styles: five Beatraxus exclusives (Pulse, Thread, Prism, Matrix, Comet), Waveform,
 * Spectrum Timeline and Live Waveform. Unused parameters are kept so existing callers still compile.
 */
@Composable
fun AppSeekBar(
    style: SeekbarStyle,
    progress: Float,
    onProgressChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    onProgressFinished: (Float) -> Unit = {},
    activeColor: Color = Color.White,
    inactiveColor: Color = Color.White.copy(alpha = 0.3f),
    seed: Int = 0,
    dominantColor: Color = Color.White,
    durationMs: Long = 0L,
    chapters: List<ChapterEntity> = emptyList(),
    lyrics: List<LrcLine> = emptyList(),
    loudnessData: FloatArray? = null,
    spectrumData: FloatArray? = null,
    isPlaying: Boolean = false
) {
    when (style) {
        SeekbarStyle.WAVY_GLASS -> Box(modifier, contentAlignment = Alignment.Center) {
            // The wave canvas is 50dp tall by design; it is centred in whatever slot the caller gives.
            WavySeekBar(
                positionMs = (progress * durationMs).toLong(),
                durationMs = durationMs,
                isPlaying = isPlaying,
                onSeek = { ms -> if (durationMs > 0L) onProgressFinished(ms.toFloat() / durationMs.toFloat()) },
                modifier = Modifier.requiredHeight(50.dp),
                isTranslucent = true,
                trackKey = seed.toString(),
                showTimeLabels = false,
                primaryColor = dominantColor,
                secondaryColor = lerp(dominantColor, Color.White, 0.35f),
                tertiaryColor = lerp(dominantColor, Color.White, 0.15f),
            )
        }
        SeekbarStyle.BEATRAXUS_PULSE -> BeatraxusPulseSeekBar(
            progress = progress,
            onProgressChange = onProgressChange,
            modifier = modifier,
            onProgressFinished = onProgressFinished,
            activeColor = activeColor,
            inactiveColor = inactiveColor,
            seed = seed,
            dominantColor = dominantColor,
            isPlaying = isPlaying
        )
        SeekbarStyle.BEATRAXUS_THREAD -> BeatraxusThreadSeekBar(
            progress = progress,
            onProgressChange = onProgressChange,
            modifier = modifier,
            onProgressFinished = onProgressFinished,
            activeColor = activeColor,
            inactiveColor = inactiveColor,
            seed = seed,
            dominantColor = dominantColor,
            isPlaying = isPlaying
        )
        SeekbarStyle.BEATRAXUS_PRISM -> BeatraxusPrismSeekBar(
            progress = progress,
            onProgressChange = onProgressChange,
            modifier = modifier,
            onProgressFinished = onProgressFinished,
            activeColor = activeColor,
            inactiveColor = inactiveColor,
            seed = seed,
            dominantColor = dominantColor,
            isPlaying = isPlaying
        )
        SeekbarStyle.BEATRAXUS_MATRIX -> BeatraxusMatrixSeekBar(
            progress = progress,
            onProgressChange = onProgressChange,
            modifier = modifier,
            onProgressFinished = onProgressFinished,
            activeColor = activeColor,
            inactiveColor = inactiveColor,
            seed = seed,
            dominantColor = dominantColor,
            isPlaying = isPlaying
        )
        SeekbarStyle.BEATRAXUS_COMET -> BeatraxusCometSeekBar(
            progress = progress,
            onProgressChange = onProgressChange,
            modifier = modifier,
            onProgressFinished = onProgressFinished,
            activeColor = activeColor,
            inactiveColor = inactiveColor,
            seed = seed,
            dominantColor = dominantColor,
            isPlaying = isPlaying
        )
        SeekbarStyle.WAVEFORM -> WaveformSeekBar(
            progress = progress,
            onProgressChange = onProgressChange,
            modifier = modifier,
            onProgressFinished = onProgressFinished,
            activeColor = activeColor,
            inactiveColor = inactiveColor,
            seed = seed
        )
        SeekbarStyle.SPECTRUM_TIMELINE -> SpectrumTimelineSeekBar(
            progress = progress,
            onProgressChange = onProgressChange,
            modifier = modifier,
            onProgressFinished = onProgressFinished,
            activeColor = activeColor,
            inactiveColor = inactiveColor,
            seed = seed,
            spectrumData = spectrumData
        )
        SeekbarStyle.LIVE_WAVEFORM -> LiveWaveformSeekBar(
            progress = progress,
            onProgressChange = onProgressChange,
            modifier = modifier,
            onProgressFinished = onProgressFinished,
            activeColor = activeColor,
            inactiveColor = inactiveColor,
            seed = seed,
            isPlaying = isPlaying
        )
    }
}
