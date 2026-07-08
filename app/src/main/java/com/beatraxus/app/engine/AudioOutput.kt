package com.beatraxus.app.engine

interface AudioOutput {
    fun init(sampleRate: Int, channels: Int, bitDepth: Int, isDoP: Boolean = false): Boolean
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
}
