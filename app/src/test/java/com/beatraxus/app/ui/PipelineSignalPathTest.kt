package com.beatraxus.app.ui

import android.net.Uri
import com.beatraxus.app.model.DspConfig
import com.beatraxus.app.model.DspUiState
import com.beatraxus.app.model.PlayerUiState
import com.beatraxus.app.model.Song
import com.beatraxus.app.ui.components.PipelineVerdictType
import com.beatraxus.app.ui.components.StageState
import com.beatraxus.app.ui.components.buildPipelineStages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PipelineSignalPathTest {

    @Test
    fun testBitPerfectFlacPipeline() {
        val song = Song(
            id = "1", uri = Uri.EMPTY, title = "FLAC Track", artist = "Artist", album = "Album",
            durationMs = 200000, format = "FLAC", sampleRateHz = 96000, bitDepth = 24, bitrate = 2500000
        )
        val uiState = PlayerUiState(
            format = "FLAC", inputSampleRate = 96000, outputSampleRate = 96000,
            bitDepth = 24, outputBitDepth = 24, pipelineOutputPath = "MMAP Exclusive",
            pipelineResamplerEnabled = false,
            dsp = DspUiState(config = DspConfig(bitPerfectEnabled = true))
        )

        val result = buildPipelineStages(song, uiState)

        assertEquals(PipelineVerdictType.BIT_PERFECT, result.verdict.type)
        assertEquals(7, result.stages.size)
        assertEquals(6, result.wireFormats.size)

        val dspStage = result.stages.first { it.id == "dsp" }
        assertEquals(StageState.BYPASSED, dspStage.state)

        val resamplerStage = result.stages.first { it.id == "resampler" }
        assertEquals(StageState.BYPASSED, resamplerStage.state)

        assertTrue(result.plainTextSummary.contains("BIT-PERFECT"))
    }

    @Test
    fun testProcessedMp3Pipeline() {
        val song = Song(
            id = "2", uri = Uri.EMPTY, title = "MP3 Track", artist = "Artist", album = "Album",
            durationMs = 180000, format = "MP3", sampleRateHz = 44100, bitDepth = 16, bitrate = 320000
        )
        val uiState = PlayerUiState(
            format = "MP3", inputSampleRate = 44100, outputSampleRate = 96000,
            bitDepth = 16, outputBitDepth = 24, pipelineOutputPath = "AAudio",
            pipelineResamplerEnabled = true,
            dsp = DspUiState(config = DspConfig(eqEnabled = true, limiterEnabled = true))
        )

        val result = buildPipelineStages(song, uiState)

        assertEquals(PipelineVerdictType.PROCESSED, result.verdict.type)

        val dspStage = result.stages.first { it.id == "dsp" }
        assertEquals(StageState.PROCESSED, dspStage.state)

        val resamplerStage = result.stages.first { it.id == "resampler" }
        assertEquals(StageState.PROCESSED, resamplerStage.state)

        assertTrue(result.plainTextSummary.contains("PROCESSED"))
    }

    @Test
    fun testLossyUnprocessedPipeline() {
        val song = Song(
            id = "3", uri = Uri.EMPTY, title = "AAC Track", artist = "Artist", album = "Album",
            durationMs = 150000, format = "AAC", sampleRateHz = 44100, bitDepth = 16, bitrate = 256000
        )
        val uiState = PlayerUiState(
            format = "AAC", inputSampleRate = 44100, outputSampleRate = 44100,
            bitDepth = 16, outputBitDepth = 16, pipelineOutputPath = "AudioTrack",
            pipelineResamplerEnabled = false,
            dsp = DspUiState(config = DspConfig())
        )

        val result = buildPipelineStages(song, uiState)

        assertEquals(PipelineVerdictType.LOSSY, result.verdict.type)
        assertNotNull(result.plainTextSummary)
    }
}
