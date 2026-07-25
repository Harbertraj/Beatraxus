package com.beatraxus.app.ui.components.seekbars

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.beatraxus.app.model.ChapterEntity
import com.beatraxus.app.model.LrcLine
import com.beatraxus.app.model.SeekbarStyle
import com.beatraxus.app.ui.components.WaveformSeekBar

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
    spectrumData: FloatArray? = null
) {
    when (style) {
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
        SeekbarStyle.SMART_CHAPTER -> SmartChapterSeekBar(
            progress = progress,
            onProgressChange = onProgressChange,
            modifier = modifier,
            onProgressFinished = onProgressFinished,
            activeColor = activeColor,
            inactiveColor = inactiveColor,
            durationMs = durationMs,
            chapters = chapters
        )
        SeekbarStyle.PARTICLE_TRAIL -> ParticleTrailSeekBar(
            progress = progress,
            onProgressChange = onProgressChange,
            modifier = modifier,
            onProgressFinished = onProgressFinished,
            activeColor = activeColor,
            inactiveColor = inactiveColor,
            seed = seed,
            dominantColor = dominantColor
        )
        SeekbarStyle.MORPHING_BLOB -> MorphingBlobSeekBar(
            progress = progress,
            onProgressChange = onProgressChange,
            modifier = modifier,
            onProgressFinished = onProgressFinished,
            activeColor = activeColor,
            inactiveColor = inactiveColor,
            seed = seed
        )
        SeekbarStyle.ALBUM_ART_GRADIENT -> AlbumArtGradientSeekBar(
            progress = progress,
            onProgressChange = onProgressChange,
            modifier = modifier,
            onProgressFinished = onProgressFinished,
            activeColor = activeColor,
            inactiveColor = inactiveColor,
            seed = seed,
            dominantColor = dominantColor
        )
        SeekbarStyle.LOUDNESS_HEATMAP -> LoudnessHeatmapSeekBar(
            progress = progress,
            onProgressChange = onProgressChange,
            modifier = modifier,
            onProgressFinished = onProgressFinished,
            activeColor = activeColor,
            inactiveColor = inactiveColor,
            seed = seed,
            loudnessData = loudnessData
        )
        SeekbarStyle.LYRICS_MARKER -> LyricsMarkerSeekBar(
            progress = progress,
            onProgressChange = onProgressChange,
            modifier = modifier,
            onProgressFinished = onProgressFinished,
            activeColor = activeColor,
            inactiveColor = inactiveColor,
            durationMs = durationMs,
            lyrics = lyrics
        )
        SeekbarStyle.MINI_SPECTRUM_THUMB -> MiniSpectrumThumbSeekBar(
            progress = progress,
            onProgressChange = onProgressChange,
            modifier = modifier,
            onProgressFinished = onProgressFinished,
            activeColor = activeColor,
            inactiveColor = inactiveColor,
            seed = seed,
            dominantColor = dominantColor
        )
    }
}
