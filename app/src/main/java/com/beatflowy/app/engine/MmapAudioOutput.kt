package com.beatflowy.app.engine

import android.util.Log

/**
 * AAudio MMAP Exclusive output.
 * Delegates all real work to the native layer via JNI.
 * The native side opens an AAudio stream with AAUDIO_SHARING_MODE_EXCLUSIVE
 * and AAUDIO_PERFORMANCE_MODE_LOW_LATENCY (which triggers MMAP on supported hardware).
 */
internal class MmapAudioOutput {

    private var nativeHandle: Long = 0L
    private var framesWritten: Long = 0L

    init {
        System.loadLibrary("beatraxus_dsp")
    }

    fun init(sampleRate: Int, channels: Int, requestedBufferFrames: Int, format: Int = 2): Boolean {
        nativeHandle = nMmapCreate(sampleRate, channels, requestedBufferFrames, format)
        return nativeHandle != 0L
    }

    fun start() {
        if (nativeHandle != 0L) nMmapStart(nativeHandle)
    }

    fun pause() {
        if (nativeHandle != 0L) nMmapPause(nativeHandle)
    }

    fun stop() {
        if (nativeHandle != 0L) nMmapStop(nativeHandle)
    }

    fun flush() {
        if (nativeHandle != 0L) nMmapFlush(nativeHandle)
        framesWritten = 0L
    }

    fun write(data: FloatArray, offsetInSamples: Int, frameCount: Int): Int {
        if (nativeHandle == 0L) return 0
        val written = nMmapWrite(nativeHandle, data, offsetInSamples, frameCount)
        if (written > 0) framesWritten += written
        return written
    }

    fun writeInt(data: IntArray, offsetInSamples: Int, frameCount: Int): Int {
        if (nativeHandle == 0L) return 0
        val written = nMmapWriteInt(nativeHandle, data, offsetInSamples, frameCount)
        if (written > 0) framesWritten += written
        return written
    }

    fun playbackPositionFrames(): Long {
        if (nativeHandle == 0L) return 0L
        return nMmapGetPlaybackPosition(nativeHandle)
    }

    fun totalFramesWritten(): Long = framesWritten

    fun mmapActualBufferFrames(): Int {
        if (nativeHandle == 0L) return 0
        return nMmapGetBufferFrames(nativeHandle)
    }

    fun setBufferConfig(bufferFrames: Int, bufferCount: Int, postFadeFrames: Int) {
        if (nativeHandle != 0L) nMmapSetBufferConfig(nativeHandle, bufferFrames, bufferCount, postFadeFrames)
    }

    fun estimatedLatencyMs(): Int {
        if (nativeHandle == 0L) return 0
        return nMmapGetLatencyMs(nativeHandle)
    }

    fun outputSampleRate(): Int {
        if (nativeHandle == 0L) return 48000
        return nMmapGetSampleRate(nativeHandle)
    }

    fun release() {
        if (nativeHandle != 0L) {
            nMmapDestroy(nativeHandle)
            nativeHandle = 0L
        }
    }

    protected fun finalize() { release() }

    // JNI
    private external fun nMmapCreate(sampleRate: Int, channels: Int, bufferFrames: Int, format: Int): Long
    private external fun nMmapDestroy(handle: Long)
    private external fun nMmapStart(handle: Long)
    private external fun nMmapPause(handle: Long)
    private external fun nMmapStop(handle: Long)
    private external fun nMmapFlush(handle: Long)
    private external fun nMmapWrite(handle: Long, data: FloatArray, offsetInSamples: Int, frameCount: Int): Int
    private external fun nMmapWriteInt(handle: Long, data: IntArray, offsetInSamples: Int, frameCount: Int): Int
    private external fun nMmapGetPlaybackPosition(handle: Long): Long
    private external fun nMmapGetBufferFrames(handle: Long): Int
    private external fun nMmapGetLatencyMs(handle: Long): Int
    private external fun nMmapGetSampleRate(handle: Long): Int
    private external fun nMmapSetBufferConfig(handle: Long, bufferFrames: Int, bufferCount: Int, postFadeFrames: Int)

    companion object {
        private const val TAG = "MmapAudioOutput"
    }
}
