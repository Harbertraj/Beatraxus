package com.beatflowy.app.engine

interface AudioOutput {
    fun init(sampleRate: Int, channels: Int, bitDepth: Int): Boolean
    fun setTargetSampleRate(sampleRate: Int)
    fun start()
    fun stop()
    fun flush()
    fun write(data: FloatArray, offsetInSamples: Int, frameCount: Int): Int
    fun playbackPositionFrames(): Long
    fun outputSampleRate(): Int
    fun outputPathLabel(): String
    fun outputDeviceLabel(): String
    fun estimatedLatencyMs(): Int
    fun release()
}
