package com.beatraxus.app.ui.components.seekbars

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.beatraxus.app.model.ChapterEntity
import com.beatraxus.app.model.LrcLine
import com.beatraxus.app.model.SeekbarStyle
import com.beatraxus.app.ui.components.WaveformSeekBar

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
