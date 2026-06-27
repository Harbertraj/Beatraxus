package com.beatflowy.app.engine

import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.withLock

class NativeDsp {
    private var nativeHandle: Long = 0
    private val lock = ReentrantReadWriteLock()

    init {
        System.loadLibrary("beatraxus_dsp")
        nativeHandle = nCreate()
    }

    fun init(sampleRate: Float, channels: Int) {
        lock.writeLock().withLock {
            if (nativeHandle != 0L) nInit(nativeHandle, sampleRate, channels)
        }
    }

    fun initResampler(inputRate: Float, channels: Int, targetRate: Float) {
        lock.writeLock().withLock {
            if (nativeHandle != 0L) nInitResampler(nativeHandle, inputRate, channels, targetRate)
        }
    }

    fun setPreamp(db: Float) = lock.readLock().withLock {
        if (nativeHandle != 0L) nSetPreamp(nativeHandle, db)
    }

    fun setDcBlocker(enabled: Boolean) = lock.readLock().withLock {
        if (nativeHandle != 0L) nSetDcBlocker(nativeHandle, enabled)
    }

    fun setReplayGain(db: Float) = lock.readLock().withLock {
        if (nativeHandle != 0L) nSetReplayGain(nativeHandle, db)
    }

    fun setVolume(volume: Float) = lock.readLock().withLock {
        if (nativeHandle != 0L) nSetVolume(nativeHandle, volume)
    }

    fun setDvc(enabled: Boolean) = lock.readLock().withLock {
        if (nativeHandle != 0L) nSetDvc(nativeHandle, enabled)
    }

    fun setRmsDvc(enabled: Boolean) = lock.readLock().withLock {
        if (nativeHandle != 0L) nSetRmsDvc(nativeHandle, enabled)
    }

    fun setRmsLeveler(enabled: Boolean) = lock.readLock().withLock {
        if (nativeHandle != 0L) nSetRmsLeveler(nativeHandle, enabled)
    }

    fun setDvcLevel(level: Float) = lock.readLock().withLock {
        if (nativeHandle != 0L) nSetDvcLevel(nativeHandle, level)
    }

    fun setDvcMode(mode: Int) = lock.readLock().withLock {
        if (nativeHandle != 0L) nSetDvcMode(nativeHandle, mode)
    }

    fun setTone(midBass: Float, treble: Float, air: Float) = lock.readLock().withLock {
        if (nativeHandle != 0L) nSetTone(nativeHandle, midBass, treble, air)
    }

    fun setSpatial(balance: Float, widen: Float) = lock.readLock().withLock {
        if (nativeHandle != 0L) nSetSpatial(nativeHandle, balance, widen)
    }

    fun setSpatialEnabled(enabled: Boolean) = lock.readLock().withLock {
        if (nativeHandle != 0L) nSetSpatialEnabled(nativeHandle, enabled)
    }

    fun setSpatialIntensity(intensity: Float) = lock.readLock().withLock {
        if (nativeHandle != 0L) nSetSpatialIntensity(nativeHandle, intensity)
    }

    fun setSoundStageNodePosition(bandIdx: Int, azimuth: Float, elevation: Float, distance: Float) = lock.readLock().withLock {
        if (nativeHandle != 0L) nSetSoundStageNodePosition(nativeHandle, bandIdx, azimuth, elevation, distance)
    }

    fun setSoundStageWidth(width: Float) = lock.readLock().withLock {
        if (nativeHandle != 0L) nSetSoundStageWidth(nativeHandle, width)
    }

    fun setSoundStageCenterLock(amount: Float) = lock.readLock().withLock {
        if (nativeHandle != 0L) nSetSoundStageCenterLock(nativeHandle, amount)
    }

    fun setCrossfeed(enabled: Boolean, level: Float) = lock.readLock().withLock {
        if (nativeHandle != 0L) nSetCrossfeed(nativeHandle, enabled, level)
    }

    fun setReverb(amount: Float) = lock.readLock().withLock {
        if (nativeHandle != 0L) nSetReverb(nativeHandle, amount)
    }

    fun setReverbType(type: Int) = lock.readLock().withLock {
        if (nativeHandle != 0L) nSetReverbType(nativeHandle, type)
    }

    fun setReverbPredelay(ms: Float) = lock.readLock().withLock {
        if (nativeHandle != 0L) nSetReverbPredelay(nativeHandle, ms)
    }

    fun setReverbWidth(width: Float) = lock.readLock().withLock {
        if (nativeHandle != 0L) nSetReverbWidth(nativeHandle, width)
    }

    fun setReverbParams(roomSize: Float, damping: Float) = lock.readLock().withLock {
        if (nativeHandle != 0L) nSetReverbParams(nativeHandle, roomSize, damping)
    }

    fun muteReverb() = lock.readLock().withLock {
        if (nativeHandle != 0L) nMuteReverb(nativeHandle)
    }

    fun setBand(index: Int, frequency: Float, gainDb: Float, q: Float, type: Int = 0) = lock.readLock().withLock {
        if (nativeHandle != 0L) nSetBand(nativeHandle, index, frequency, gainDb, q, type)
    }

    fun setEqPhaseMode(linearPhase: Boolean) = lock.readLock().withLock {
        if (nativeHandle != 0L) nSetEqPhaseMode(nativeHandle, linearPhase)
    }

    fun setEqEnabled(enabled: Boolean) = lock.readLock().withLock {
        if (nativeHandle != 0L) nSetEqEnabled(nativeHandle, enabled)
    }

    fun setHeadroomManagement(enabled: Boolean) = lock.readLock().withLock {
        if (nativeHandle != 0L) nSetHeadroomManagement(nativeHandle, enabled)
    }

    fun setNoHeadroomGain(enabled: Boolean) = lock.readLock().withLock {
        if (nativeHandle != 0L) nSetNoHeadroomGain(nativeHandle, enabled)
    }

    fun getHeadroomDb(): Float = lock.readLock().withLock {
        return if (nativeHandle != 0L) nGetHeadroomDb(nativeHandle) else 0.0f
    }

    fun getEqLatencyFrames(): Int = lock.readLock().withLock {
        return if (nativeHandle != 0L) nGetEqLatencyFrames(nativeHandle) else 0
    }

    fun setAiEqEnabled(enabled: Boolean) = lock.readLock().withLock {
        if (nativeHandle != 0L) nSetAiEqEnabled(nativeHandle, enabled)
    }

    fun setAiBand(index: Int, frequency: Float, gainDb: Float, q: Float, type: Int = 0) = lock.readLock().withLock {
        if (nativeHandle != 0L) nSetAiBand(nativeHandle, index, frequency, gainDb, q, type)
    }

    fun setSimBand(index: Int, frequency: Float, gainDb: Float, q: Float, type: Int = 0) = lock.readLock().withLock {
        if (nativeHandle != 0L) nSetSimBand(nativeHandle, index, frequency, gainDb, q, type)
    }

    fun setSimEqEnabled(enabled: Boolean) = lock.readLock().withLock {
        if (nativeHandle != 0L) nSetSimEqEnabled(nativeHandle, enabled)
    }

    fun setHardwareVolume(enabled: Boolean) = lock.readLock().withLock {
        if (nativeHandle != 0L) nSetHardwareVolume(nativeHandle, enabled)
    }

    fun setHighQualityResampler(enabled: Boolean) = lock.readLock().withLock {
        if (nativeHandle != 0L) nSetHighQualityResampler(nativeHandle, enabled)
    }

    fun setSoxrQuality(quality: Int) = lock.readLock().withLock {
        if (nativeHandle != 0L) nSetSoxrQuality(nativeHandle, quality)
    }

    fun setFloat64(enabled: Boolean) = lock.readLock().withLock {
        if (nativeHandle != 0L) nSetFloat64(nativeHandle, enabled)
    }

    fun setMono(enabled: Boolean) = lock.readLock().withLock {
        if (nativeHandle != 0L) nSetMono(nativeHandle, enabled)
    }

    fun setSoftLimiter(enabled: Boolean) = lock.readLock().withLock {
        if (nativeHandle != 0L) nSetSoftLimiter(nativeHandle, enabled)
    }

    fun setLimiter(enabled: Boolean) = lock.readLock().withLock {
        if (nativeHandle != 0L) nSetLimiter(nativeHandle, enabled)
    }

    fun setLimiterParams(thresholdDb: Float, attackMs: Float, releaseMs: Float) = lock.readLock().withLock {
        if (nativeHandle != 0L) nSetLimiterParams(nativeHandle, thresholdDb, attackMs, releaseMs)
    }

    fun setBitDepth(bitDepth: Int) = lock.readLock().withLock {
        if (nativeHandle != 0L) nSetBitDepth(nativeHandle, bitDepth)
    }

    fun setDither(enabled: Boolean, bitDepth: Int) = lock.readLock().withLock {
        if (nativeHandle != 0L) nSetDither(nativeHandle, enabled, bitDepth)
    }

    fun setDitherType(type: Int) = lock.readLock().withLock {
        if (nativeHandle != 0L) nSetDitherType(nativeHandle, type)
    }

    fun setCutoffRatio(ratio: Float) = lock.readLock().withLock {
        if (nativeHandle != 0L) nSetCutoffRatio(nativeHandle, ratio)
    }

    fun setSpeed(speed: Float, preservePitch: Boolean) = lock.readLock().withLock {
        if (nativeHandle != 0L) nSetSpeed(nativeHandle, speed, preservePitch)
    }

    fun process(data: FloatArray, frames: Int) = lock.readLock().withLock {
        if (nativeHandle != 0L) nProcess(nativeHandle, data, frames)
    }

    fun processResampled(input: FloatArray, inFrames: Int, output: FloatArray): Int = lock.readLock().withLock {
        return if (nativeHandle != 0L) nProcessResampled(nativeHandle, input, inFrames, output) else 0
    }

    suspend fun extractFeatures(context: android.content.Context, uri: android.net.Uri, seconds: Int): AudioFeatures? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return@withContext null
        try {
            val fd = pfd.fd
            nExtractFeatures(fd, seconds)
        } finally {
            pfd.close()
        }
    }

    private external fun nExtractFeatures(fd: Int, seconds: Int): AudioFeatures?

    fun release() {
        lock.writeLock().withLock {
            if (nativeHandle != 0L) {
                nDestroy(nativeHandle)
                nativeHandle = 0
            }
        }
    }

    protected fun finalize() {
        release()
    }

    private external fun nCreate(): Long
    private external fun nDestroy(handle: Long)
    private external fun nInit(handle: Long, sampleRate: Float, channels: Int)
    private external fun nInitResampler(handle: Long, inputSR: Float, channels: Int, targetSR: Float)
    private external fun nSetPreamp(handle: Long, db: Float)
    private external fun nSetDcBlocker(handle: Long, enabled: Boolean)
    private external fun nSetReplayGain(handle: Long, db: Float)
    private external fun nSetVolume(handle: Long, volume: Float)
    private external fun nSetDvc(handle: Long, enabled: Boolean)
    private external fun nSetRmsDvc(handle: Long, enabled: Boolean)
    private external fun nSetRmsLeveler(handle: Long, enabled: Boolean)
    private external fun nSetDvcLevel(handle: Long, level: Float)
    private external fun nSetDvcMode(handle: Long, mode: Int)
    private external fun nSetTone(handle: Long, midBass: Float, treble: Float, air: Float)
    private external fun nSetSpatial(handle: Long, balance: Float, widen: Float)
    private external fun nSetSpatialEnabled(handle: Long, enabled: Boolean)
    private external fun nSetSpatialIntensity(handle: Long, intensity: Float)
    private external fun nSetSoundStageNodePosition(handle: Long, bandIdx: Int, az: Float, el: Float, dist: Float)
    private external fun nSetSoundStageWidth(handle: Long, width: Float)
    private external fun nSetSoundStageCenterLock(handle: Long, amount: Float)
    private external fun nSetCrossfeed(handle: Long, enabled: Boolean, level: Float)
    private external fun nSetReverb(handle: Long, amount: Float)
    private external fun nSetReverbType(handle: Long, type: Int)
    private external fun nSetReverbPredelay(handle: Long, ms: Float)
    private external fun nSetReverbWidth(handle: Long, width: Float)
    private external fun nSetReverbParams(handle: Long, roomSize: Float, damping: Float)
    private external fun nMuteReverb(handle: Long)
    private external fun nSetBand(handle: Long, index: Int, frequency: Float, gainDb: Float, q: Float, type: Int)
    private external fun nSetAiBand(handle: Long, index: Int, frequency: Float, gainDb: Float, q: Float, type: Int)
    private external fun nSetSimBand(handle: Long, index: Int, frequency: Float, gainDb: Float, q: Float, type: Int)
    private external fun nSetEqPhaseMode(handle: Long, linearPhase: Boolean)
    private external fun nSetEqEnabled(handle: Long, enabled: Boolean)
    private external fun nSetAiEqEnabled(handle: Long, enabled: Boolean)
    private external fun nSetSimEqEnabled(handle: Long, enabled: Boolean)
    private external fun nSetHardwareVolume(handle: Long, enabled: Boolean)
    private external fun nSetHeadroomManagement(handle: Long, enabled: Boolean)
    private external fun nSetNoHeadroomGain(handle: Long, enabled: Boolean)
    private external fun nGetHeadroomDb(handle: Long): Float
    private external fun nGetEqLatencyFrames(handle: Long): Int
    private external fun nSetHighQualityResampler(handle: Long, enabled: Boolean)
    private external fun nSetSoxrQuality(handle: Long, quality: Int)
    private external fun nSetFloat64(handle: Long, enabled: Boolean)
    private external fun nSetMono(handle: Long, enabled: Boolean)
    private external fun nSetSoftLimiter(handle: Long, enabled: Boolean)
    private external fun nSetLimiter(handle: Long, enabled: Boolean)
    private external fun nSetLimiterParams(handle: Long, thresholdDb: Float, attackMs: Float, releaseMs: Float)
    private external fun nSetBitDepth(handle: Long, bitDepth: Int)
    private external fun nSetDither(handle: Long, enabled: Boolean, bitDepth: Int)
    private external fun nSetDitherType(handle: Long, type: Int)
    private external fun nSetCutoffRatio(handle: Long, ratio: Float)
    private external fun nPackDoP(dsd: ByteArray, pcm: IntArray, frames: Int, channels: Int, alt: Boolean)
    private external fun nDsdToPcm(dsd: ByteArray, pcm: FloatArray, frames: Int, channels: Int)
    private external fun nSetSpeed(handle: Long, speed: Float, preservePitch: Boolean)
    private external fun nProcess(handle: Long, data: FloatArray, frames: Int)
    private external fun nProcessResampled(handle: Long, input: FloatArray, inFrames: Int, output: FloatArray): Int
}
