package com.beatflowy.app.engine

import android.util.Log
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.withLock

/**
 * AAudio MMAP Exclusive output.
 * Delegates all real work to the native layer via JNI.
 */
internal class MmapAudioOutput {

    private var nativeHandle: Long = 0L
    private val framesWritten = AtomicLong(0L)
    private val lock = ReentrantReadWriteLock()

    init {
        System.loadLibrary("beatraxus_dsp")
    }

    fun init(sampleRate: Int, channels: Int, requestedBufferFrames: Int, format: Int = 2): Boolean {
        lock.writeLock().withLock {
            nativeHandle = nMmapCreate(sampleRate, channels, requestedBufferFrames, format)
            return nativeHandle != 0L
        }
    }

    fun start() = lock.readLock().withLock {
        if (nativeHandle != 0L) nMmapStart(nativeHandle)
    }

    fun pause() = lock.readLock().withLock {
        if (nativeHandle != 0L) nMmapPause(nativeHandle)
    }

    fun stop() = lock.readLock().withLock {
        if (nativeHandle != 0L) nMmapStop(nativeHandle)
    }

    fun flush() = lock.readLock().withLock {
        if (nativeHandle != 0L) nMmapFlush(nativeHandle)
        framesWritten.set(0L)
    }

    fun write(data: FloatArray, offsetInSamples: Int, frameCount: Int): Int = lock.readLock().withLock {
        if (nativeHandle == 0L) return 0
        val written = nMmapWrite(nativeHandle, data, offsetInSamples, frameCount)
        if (written > 0) framesWritten.addAndGet(written.toLong())
        return written
    }

    fun writeInt(data: IntArray, offsetInSamples: Int, frameCount: Int): Int = lock.readLock().withLock {
        if (nativeHandle == 0L) return 0
        val written = nMmapWriteInt(nativeHandle, data, offsetInSamples, frameCount)
        if (written > 0) framesWritten.addAndGet(written.toLong())
        return written
    }

    fun playbackPositionFrames(): Long = lock.readLock().withLock {
        if (nativeHandle == 0L) return 0L
        return nMmapGetPlaybackPosition(nativeHandle)
    }

    fun totalFramesWritten(): Long = framesWritten.get()

    fun mmapActualBufferFrames(): Int = lock.readLock().withLock {
        if (nativeHandle == 0L) return 0
        return nMmapGetBufferFrames(nativeHandle)
    }

    fun setBufferConfig(bufferFrames: Int, bufferCount: Int, postFadeFrames: Int) = lock.readLock().withLock {
        if (nativeHandle != 0L) nMmapSetBufferConfig(nativeHandle, bufferFrames, bufferCount, postFadeFrames)
    }

    fun estimatedLatencyMs(): Int = lock.readLock().withLock {
        if (nativeHandle == 0L) return 0
        return nMmapGetLatencyMs(nativeHandle)
    }

    fun outputSampleRate(): Int = lock.readLock().withLock {
        if (nativeHandle == 0L) return 48000
        return nMmapGetSampleRate(nativeHandle)
    }

    fun release() {
        lock.writeLock().withLock {
            if (nativeHandle != 0L) {
                nMmapDestroy(nativeHandle)
                nativeHandle = 0L
            }
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
