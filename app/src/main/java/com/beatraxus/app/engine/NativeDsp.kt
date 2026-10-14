package com.beatraxus.app.engine

import android.util.Log
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.withLock

class NativeDsp : AutoCloseable {
    private var nativeHandle: Long = 0
    private val lock = ReentrantReadWriteLock()

    init {
        try {
            System.loadLibrary("beatraxus_dsp")
            nativeHandle = nCreate()
        } catch (e: UnsatisfiedLinkError) {
            android.util.Log.e("NativeDsp", "Failed to load library: beatraxus_dsp", e)
        }
    }

    override fun close() {
        release()
    }

    fun init(sampleRate: Float, channels: Int, outputSampleRate: Int = sampleRate.toInt()) {
        lock.writeLock().withLock {
            if (nativeHandle != 0L) {
                // ASSERTION: This is always followed by initResampler() in DspPipeline if rates differ.
                // If not followed, the DSP will silently disable SOXR because nInit passes sampleRate as both in/out.
                nInit(nativeHandle, sampleRate, channels)
                
                if (sampleRate.toInt() != outputSampleRate) {
                    android.util.Log.w("NativeDsp", "nInit called with input rate ${sampleRate.toInt()} but output rate is $outputSampleRate — call initResampler after init")
                }
            }
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

    fun setTone(bass: Float, treble: Float, air: Float) = lock.readLock().withLock {
        if (nativeHandle != 0L) nSetTone(nativeHandle, bass, treble, air)
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

    fun setHrtfMode(mode: Int) = lock.readLock().withLock {
        if (nativeHandle != 0L) nSetHrtfMode(nativeHandle, mode)
    }

    fun setSpatialUiMode(mode: Int) = lock.readLock().withLock {
        if (nativeHandle != 0L) nSetSpatialUiMode(nativeHandle, mode)
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

    fun setAudio3DStageParams(width: Float, depth: Float, height: Float, distance: Float, centerFocus: Float, roomReflections: Float) = lock.readLock().withLock {
        if (nativeHandle != 0L) nSetAudio3DStageParams(nativeHandle, width, depth, height, distance, centerFocus, roomReflections)
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

    fun setReverbMixBalance(mix: Float) = lock.readLock().withLock {
        if (nativeHandle != 0L) nSetReverbMixBalance(nativeHandle, mix)
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

    fun getLevels(): FloatArray = lock.readLock().withLock {
        return if (nativeHandle != 0L) nGetLevels(nativeHandle) else floatArrayOf(0f, 0f)
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

    fun setLimiterHardMode(enabled: Boolean) = lock.readLock().withLock {
        if (nativeHandle != 0L) nSetLimiterHardMode(nativeHandle, enabled)
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

    fun processResampled(input: FloatArray, inFrames: Int, output: FloatArray, outputCapacityFrames: Int): Int = lock.readLock().withLock {
        return if (nativeHandle != 0L) nProcessResampled(nativeHandle, input, inFrames, output, outputCapacityFrames) else 0
    }

    suspend fun extractFeatures(context: android.content.Context, uri: android.net.Uri, seconds: Int): AudioFeatures? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        if (uri == android.net.Uri.EMPTY || uri.scheme?.startsWith("http") == true) return@withContext null
        
        // A SIGSEGV inside native code cannot be caught by try/catch, so we must never hand the
        // native extractor a codec it cannot decode safely. Known offenders: ALAC, and E-AC-3/AC-3/
        // DTS/TrueHD/AC-4 (many devices ship no hardware/software decoder -> null deref in MediaCodec).
        val mimeType = context.contentResolver.getType(uri)
        val extension = uri.path?.substringAfterLast('.', "")?.lowercase()

        if (mimeType == "audio/alac" || extension == "alac" || extension in UNSAFE_EXTENSIONS) {
            Log.w("NativeDsp", "Skipping feature extraction for unsafe format (ext=$extension, mime=$mimeType): $uri")
            return@withContext null
        }
        if (!isSafeForNativeDecode(context, uri, extension)) {
            Log.w("NativeDsp", "Skipping feature extraction: codec not safe/available for native decode: $uri")
            return@withContext null
        }

        val pfd = try {
            context.contentResolver.openFileDescriptor(uri, "r")
        } catch (e: Exception) {
            null
        } ?: return@withContext null
        try {
            val fd = pfd.fd
            // Wrap native call in try-catch to prevent SIGSEGV or other native exceptions from killing the app
            val rawArr = try {
                nExtractFeatures(fd, seconds)
            } catch (t: Throwable) {
                Log.e("NativeDsp", "Native crash prevented in nExtractFeatures: ${t.message}")
                null
            } ?: return@withContext null
            
            return@withContext parseNativeFeatures(rawArr)
        } finally {
            try { pfd.close() } catch (e: Exception) {}
        }
    }

    /**
     * Same analysis as [extractFeatures], but on PCM the caller decoded itself (interleaved float,
     * [frames] x [channels]). Used for formats the native MediaCodec path refuses to touch (ALAC, Dolby, DTS, ...),
     * which the app's own decoders play fine.
     */
    suspend fun analyzePcm(pcm: FloatArray, frames: Int, sampleRate: Int, channels: Int): AudioFeatures? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            if (frames <= 0 || channels <= 0 || sampleRate <= 0 || pcm.size < frames.toLong() * channels) {
                return@withContext null
            }
            val raw = try {
                nAnalyzePcm(pcm, frames, sampleRate, channels)
            } catch (t: Throwable) {
                Log.e("NativeDsp", "Native analysis of decoded PCM failed: ${t.message}")
                null
            }
            parseNativeFeatures(raw)
        }

    /**
     * Whitelist approach: only allow the native extractor for codecs known to decode cleanly via
     * the NDK, and only when the device actually has a decoder for the track. Anything else
     * (ALAC, E-AC-3, AC-3, DTS, TrueHD, AC-4, unknown) is skipped -> caller falls back to heuristics.
     * DSF is handled natively without MediaCodec, so it is allowed through.
     */
    private fun isSafeForNativeDecode(context: android.content.Context, uri: android.net.Uri, extension: String?): Boolean {
        if (extension == "dsf") return true
        val extractor = android.media.MediaExtractor()
        return try {
            extractor.setDataSource(context, uri, null)
            var safe = false
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = (format.getString(android.media.MediaFormat.KEY_MIME) ?: "").lowercase()
                if (!mime.startsWith("audio/")) continue
                if (mime !in SAFE_MIME_TYPES) return false
                val decoder = android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS)
                    .findDecoderForFormat(format)
                safe = decoder != null
                break
            }
            safe
        } catch (e: Throwable) {
            // If the extractor cannot even open the file, native decode will not work either.
            // Do NOT use MediaMetadataRetriever here as it may crash the process.
            false
        } finally {
            try { extractor.release() } catch (e: Exception) {}
        }
    }

    private companion object {
        val UNSAFE_EXTENSIONS = setOf("alac", "caf", "ac3", "eac3", "ec3", "ec-3", "dts", "dtshd", "thd", "truehd", "ac4")
        val SAFE_MIME_TYPES = setOf(
            "audio/mpeg", "audio/mp4a-latm", "audio/flac", "audio/vorbis", "audio/opus",
            "audio/raw", "audio/x-wav", "audio/amr-wb", "audio/3gpp"
        )
    }

    private external fun nExtractFeatures(fd: Int, seconds: Int): FloatArray?
    private external fun nAnalyzePcm(pcm: FloatArray, frames: Int, sampleRate: Int, channels: Int): FloatArray?

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
    private external fun nSetTone(handle: Long, bass: Float, treble: Float, air: Float)
    private external fun nSetSpatial(handle: Long, balance: Float, widen: Float)
    private external fun nSetSpatialEnabled(handle: Long, enabled: Boolean)
    private external fun nSetSpatialIntensity(handle: Long, intensity: Float)
    private external fun nSetHrtfMode(handle: Long, mode: Int)
    private external fun nSetSpatialUiMode(handle: Long, mode: Int)
    private external fun nSetSoundStageNodePosition(handle: Long, bandIdx: Int, az: Float, el: Float, dist: Float)
    private external fun nSetSoundStageWidth(handle: Long, width: Float)
    private external fun nSetSoundStageCenterLock(handle: Long, amount: Float)
    private external fun nSetAudio3DStageParams(handle: Long, width: Float, depth: Float, height: Float, distance: Float, centerFocus: Float, roomReflections: Float)
    private external fun nSetCrossfeed(handle: Long, enabled: Boolean, level: Float)
    private external fun nSetReverb(handle: Long, amount: Float)
    private external fun nSetReverbType(handle: Long, type: Int)
    private external fun nSetReverbPredelay(handle: Long, ms: Float)
    private external fun nSetReverbWidth(handle: Long, width: Float)
    private external fun nSetReverbMixBalance(handle: Long, mix: Float)
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
    private external fun nGetLevels(handle: Long): FloatArray
    private external fun nSetHighQualityResampler(handle: Long, enabled: Boolean)
    private external fun nSetSoxrQuality(handle: Long, quality: Int)
    private external fun nSetFloat64(handle: Long, enabled: Boolean)
    private external fun nSetMono(handle: Long, enabled: Boolean)
    private external fun nSetSoftLimiter(handle: Long, enabled: Boolean)
    private external fun nSetLimiter(handle: Long, enabled: Boolean)
    private external fun nSetLimiterParams(handle: Long, thresholdDb: Float, attackMs: Float, releaseMs: Float)
    private external fun nSetLimiterHardMode(handle: Long, enabled: Boolean)
    private external fun nSetBitDepth(handle: Long, bitDepth: Int)
    private external fun nSetDither(handle: Long, enabled: Boolean, bitDepth: Int)
    private external fun nSetDitherType(handle: Long, type: Int)
    private external fun nSetCutoffRatio(handle: Long, ratio: Float)
    private external fun nPackDoP(dsd: ByteArray, pcm: IntArray, frames: Int, channels: Int, alt: Boolean)
    private external fun nDsdToPcm(dsd: ByteArray, pcm: FloatArray, frames: Int, channels: Int)
    private external fun nSetSpeed(handle: Long, speed: Float, preservePitch: Boolean)
    private external fun nProcess(handle: Long, data: FloatArray, frames: Int)
    private external fun nProcessResampled(handle: Long, input: FloatArray, inFrames: Int, output: FloatArray, outputCapacityFrames: Int): Int
}

/** Flat layout written by the native `flattenAnalysis`: 15 header floats, then spectral, then novelty. */
internal fun parseNativeFeatures(rawArr: FloatArray?): AudioFeatures? {
    if (rawArr == null || rawArr.size < 15) return null
    val spectralLen = rawArr[13].toInt()
    val noveltyLen = rawArr[14].toInt()
    if (spectralLen < 0 || noveltyLen < 0) return null
    if (rawArr.size < 15 + spectralLen + noveltyLen) return null
    return AudioFeatures(
        lufs = rawArr[0],
        rms = rawArr[1],
        peak = rawArr[2],
        dynamicRange = rawArr[3],
        bassScore = rawArr[4],
        midScore = rawArr[5],
        trebleScore = rawArr[6],
        stereoWidth = rawArr[7],
        tempoBpm = rawArr[8],
        spectralData = rawArr.copyOfRange(15, 15 + spectralLen),
        noveltyVector = if (noveltyLen > 0) rawArr.copyOfRange(15 + spectralLen, 15 + spectralLen + noveltyLen) else null,
        truePeakDb = rawArr[9],
        clippedSamplePct = rawArr[10],
        freqRangeLowHz = rawArr[11],
        freqRangeHighHz = rawArr[12]
    )
}
