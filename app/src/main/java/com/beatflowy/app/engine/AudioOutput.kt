package com.beatflowy.app.engine

interface AudioOutput {
    fun init(sampleRate: Int, channels: Int, bitDepth: Int): Boolean
    fun setTargetSampleRate(sampleRate: Int)
    fun setSampleFormat(format: com.beatflowy.app.model.SampleFormat)
    fun setDvcState(enabled: Boolean, mode: String, level: Float)
    fun start()
    fun pause()
    fun stop()
    fun flush()
    fun write(data: FloatArray, offsetInSamples: Int, frameCount: Int): Int
    fun playbackPositionFrames(): Long
    fun totalFramesWritten(): Long
    fun outputSampleRate(): Int
    fun outputBitDepth(): Int
    fun outputPathLabel(): String
    fun outputDeviceLabel(): String
    fun estimatedLatencyMs(): Int
    fun release()
}
