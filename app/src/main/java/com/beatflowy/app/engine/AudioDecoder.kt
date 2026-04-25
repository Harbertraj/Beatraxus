package com.beatflowy.app.engine

import com.beatflowy.app.model.Song

internal interface AudioDecoder {
    suspend fun canDecode(song: Song): Boolean = true

    suspend fun decode(
        request: PlaybackRequest,
        sink: DecoderSink,
        control: DecoderControl
    ): DecodeResult
}

internal interface DecoderSink {
    suspend fun configure(format: PcmAudioFormat)
    suspend fun write(data: FloatArray, sampleCount: Int)
}

internal interface DecoderControl {
    fun isActive(): Boolean
    fun consumePendingSeekMs(): Long?
    fun logDebug(message: String)
    fun logWarn(message: String)
}

internal data class PlaybackRequest(
    val song: com.beatflowy.app.model.Song,
    val startPositionMs: Long
)

internal data class PcmAudioFormat(
    val sampleRate: Int,
    val channels: Int,
    val bitDepth: Int
)

internal sealed interface DecodeResult {
    data object Ended : DecodeResult
    data class Seek(val positionMs: Long) : DecodeResult
    data class Failed(val reason: String? = null) : DecodeResult
}
