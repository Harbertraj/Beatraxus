package com.beatraxus.app.engine

interface AudioOutput {
    fun init(sampleRate: Int, channels: Int, bitDepth: Int, isDoP: Boolean = false, resetOffsets: Boolean = true): Boolean
    fun setTargetSampleRate(sampleRate: Int)
    fun setSampleFormat(format: com.beatraxus.app.model.SampleFormat)
    fun setDvcState(enabled: Boolean, mode: String, level: Float)
    fun setDitherState(enabled: Boolean, type: Int)
    fun setUsbExclusiveMode(enabled: Boolean)
    fun setBitPerfectMode(enabled: Boolean)
    fun setBufferConfig(bufferFrames: Int, bufferCount: Int, postFadeFrames: Int)
    fun setMmapExclusiveMode(enabled: Boolean, requestedBufferFrames: Int)
    fun isMmapActive(): Boolean
    fun mmapActualBufferFrames(): Int
    fun start()
    fun pause()
    fun stop()
    fun flush()
    fun write(data: FloatArray, offsetInSamples: Int, frameCount: Int): Int
    fun writeInt(data: IntArray, offsetInSamples: Int, frameCount: Int): Int
    fun playbackPositionFrames(): Long
    fun totalFramesWritten(): Long
    fun outputSampleRate(): Int
    fun outputBitDepth(): Int
    fun outputPathLabel(): String
    fun outputDeviceLabel(): String
    fun estimatedLatencyMs(): Int
    fun release()
    /** Android audio session id for the active AudioTrack, or -1/0 (AudioManager.ERROR /
     *  AUDIO_SESSION_ID_GENERATE) when unavailable, e.g. during MMAP-exclusive output where
     *  there is no regular mixer session to attach a Visualizer to. Kept for compatibility;
     *  the Music Detail Inspector's live meters now use [captureLiveWindow] instead, since
     *  that works in every output mode including MMAP-exclusive. */
    fun getAudioSessionId(): Int

    /** Most recent normalized (-1f..1f) PCM window handed to [write]/[writeInt], interleaved
     *  by [LiveCapture.channels]. Captured directly from the PCM pipeline rather than via
     *  android.media.audiofx.Visualizer, so — unlike Visualizer — it is available during
     *  MMAP-exclusive (bit-perfect) output too, since there is no dependency on a mixer
     *  session. Returns null before the first buffer has been written. Used by the Music
     *  Detail Inspector's Live Meters panel. */
    fun captureLiveWindow(): LiveCapture?

    data class LiveCapture(val samples: FloatArray, val channels: Int)
}
