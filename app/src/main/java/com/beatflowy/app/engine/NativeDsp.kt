package com.beatflowy.app.engine

class NativeDsp {
    private var nativeHandle: Long = 0

    init {
        System.loadLibrary("beatraxus_dsp")
        nativeHandle = nCreate()
    }

    fun init(sampleRate: Float, channels: Int) {
        if (nativeHandle != 0L) nInit(nativeHandle, sampleRate, channels)
    }

    fun initResampler(inputRate: Float, channels: Int, targetRate: Float) {
        if (nativeHandle != 0L) nInitResampler(nativeHandle, inputRate, channels, targetRate)
    }

    fun setPreamp(db: Float) {
        if (nativeHandle != 0L) nSetPreamp(nativeHandle, db)
    }

    fun setDcBlocker(enabled: Boolean) {
        if (nativeHandle != 0L) nSetDcBlocker(nativeHandle, enabled)
    }

    fun setReplayGain(db: Float) {
        if (nativeHandle != 0L) nSetReplayGain(nativeHandle, db)
    }

    fun setVolume(volume: Float) {
        if (nativeHandle != 0L) nSetVolume(nativeHandle, volume)
    }

    fun setDvc(enabled: Boolean) {
        if (nativeHandle != 0L) nSetDvc(nativeHandle, enabled)
    }

    fun setDvcLevel(level: Float) {
        if (nativeHandle != 0L) nSetDvcLevel(nativeHandle, level)
    }

    fun setDvcMode(mode: Int) {
        if (nativeHandle != 0L) nSetDvcMode(nativeHandle, mode)
    }

    fun setTone(midBass: Float, treble: Float, air: Float) {
        if (nativeHandle != 0L) nSetTone(nativeHandle, midBass, treble, air)
    }

    fun setSpatial(balance: Float, widen: Float) {
        if (nativeHandle != 0L) nSetSpatial(nativeHandle, balance, widen)
    }

    fun setCrossfeed(enabled: Boolean, level: Float) {
        if (nativeHandle != 0L) nSetCrossfeed(nativeHandle, enabled, level)
    }

    fun setReverb(amount: Float) {
        if (nativeHandle != 0L) nSetReverb(nativeHandle, amount)
    }

    fun setReverbType(type: Int) {
        if (nativeHandle != 0L) nSetReverbType(nativeHandle, type)
    }

    fun setReverbPredelay(ms: Float) {
        if (nativeHandle != 0L) nSetReverbPredelay(nativeHandle, ms)
    }

    fun setReverbWidth(width: Float) {
        if (nativeHandle != 0L) nSetReverbWidth(nativeHandle, width)
    }

    fun setReverbParams(roomSize: Float, damping: Float) {
        if (nativeHandle != 0L) nSetReverbParams(nativeHandle, roomSize, damping)
    }

    fun muteReverb() {
        if (nativeHandle != 0L) nMuteReverb(nativeHandle)
    }

    fun setBand(index: Int, frequency: Float, gainDb: Float, q: Float, type: Int = 0) {
        if (nativeHandle != 0L) nSetBand(nativeHandle, index, frequency, gainDb, q, type)
    }

    fun setEqPhaseMode(linearPhase: Boolean) {
        if (nativeHandle != 0L) nSetEqPhaseMode(nativeHandle, linearPhase)
    }

    fun setEqEnabled(enabled: Boolean) {
        if (nativeHandle != 0L) nSetEqEnabled(nativeHandle, enabled)
    }

    fun setHighQualityResampler(enabled: Boolean) {
        if (nativeHandle != 0L) nSetHighQualityResampler(nativeHandle, enabled)
    }

    fun setSoxrQuality(quality: Int) {
        if (nativeHandle != 0L) nSetSoxrQuality(nativeHandle, quality)
    }

    fun setFloat64(enabled: Boolean) {
        if (nativeHandle != 0L) nSetFloat64(nativeHandle, enabled)
    }

    fun setLimiter(enabled: Boolean) {
        if (nativeHandle != 0L) nSetLimiter(nativeHandle, enabled)
    }

    fun setLimiterParams(thresholdDb: Float, attackMs: Float, releaseMs: Float) {
        if (nativeHandle != 0L) nSetLimiterParams(nativeHandle, thresholdDb, attackMs, releaseMs)
    }

    fun setBitDepth(bitDepth: Int) {
        if (nativeHandle != 0L) nSetBitDepth(nativeHandle, bitDepth)
    }

    fun setDither(enabled: Boolean, bitDepth: Int) {
        if (nativeHandle != 0L) nSetDither(nativeHandle, enabled, bitDepth)
    }

    fun setDitherType(type: Int) {
        if (nativeHandle != 0L) nSetDitherType(nativeHandle, type)
    }

    fun setCutoffRatio(ratio: Float) {
        if (nativeHandle != 0L) nSetCutoffRatio(nativeHandle, ratio)
    }

    fun process(data: FloatArray, frames: Int) {
        if (nativeHandle != 0L) nProcess(nativeHandle, data, frames)
    }

    fun processResampled(input: FloatArray, inFrames: Int, output: FloatArray): Int {
        return if (nativeHandle != 0L) {
            nProcessResampled(nativeHandle, input, inFrames, output)
        } else 0
    }

    fun release() {
        if (nativeHandle != 0L) {
            nDestroy(nativeHandle)
            nativeHandle = 0
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
    private external fun nSetDvcLevel(handle: Long, level: Float)
    private external fun nSetDvcMode(handle: Long, mode: Int)
    private external fun nSetTone(handle: Long, midBass: Float, treble: Float, air: Float)
    private external fun nSetSpatial(handle: Long, balance: Float, widen: Float)
    private external fun nSetCrossfeed(handle: Long, enabled: Boolean, level: Float)
    private external fun nSetReverb(handle: Long, amount: Float)
    private external fun nSetReverbType(handle: Long, type: Int)
    private external fun nSetReverbPredelay(handle: Long, ms: Float)
    private external fun nSetReverbWidth(handle: Long, width: Float)
    private external fun nSetReverbParams(handle: Long, roomSize: Float, damping: Float)
    private external fun nMuteReverb(handle: Long)
    private external fun nSetBand(handle: Long, index: Int, frequency: Float, gainDb: Float, q: Float, type: Int)
    private external fun nSetEqPhaseMode(handle: Long, linearPhase: Boolean)
    private external fun nSetEqEnabled(handle: Long, enabled: Boolean)
    private external fun nSetHighQualityResampler(handle: Long, enabled: Boolean)
    private external fun nSetSoxrQuality(handle: Long, quality: Int)
    private external fun nSetFloat64(handle: Long, enabled: Boolean)
    private external fun nSetLimiter(handle: Long, enabled: Boolean)
    private external fun nSetLimiterParams(handle: Long, thresholdDb: Float, attackMs: Float, releaseMs: Float)
    private external fun nSetBitDepth(handle: Long, bitDepth: Int)
    private external fun nSetDither(handle: Long, enabled: Boolean, bitDepth: Int)
    private external fun nSetDitherType(handle: Long, type: Int)
    private external fun nSetCutoffRatio(handle: Long, ratio: Float)
    private external fun nProcess(handle: Long, data: FloatArray, frames: Int)
    private external fun nProcessResampled(handle: Long, input: FloatArray, inFrames: Int, output: FloatArray): Int
}
