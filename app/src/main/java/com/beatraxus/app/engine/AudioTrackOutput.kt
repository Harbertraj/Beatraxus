package com.beatraxus.app.engine

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Process
import android.util.Log
import com.beatraxus.app.model.DvcMode
import com.beatraxus.app.model.OutputMode
import com.beatraxus.app.model.SampleFormat
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.withLock
import kotlin.math.roundToInt

class AudioTrackOutput(
    context: Context
) : AudioOutput {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val lifecycleLock = ReentrantReadWriteLock()
    private val stateLock = Any() 

    @Volatile
    private var audioTrack: AudioTrack? = null

    private var sampleRate = 44_100
    private var targetSampleRate = 44_100
    private var sampleFormat = SampleFormat.AUTO
    private var channels = 2
    private var totalFramesWritten = 0L
    @Volatile private var playbackHeadWraps = 0L
    @Volatile private var lastPlaybackHeadPosition = 0
    private var playbackHeadOffset = 0L
    private var selectedMode = OutputMode.HI_RES
    private var activeMode = OutputMode.HI_RES
    private var outputDeviceName = OutputDeviceType.SPEAKER.displayName
    private var preferredDevice: AudioDeviceInfo? = null

    // Live-meter tap: a cheap copy of the most recent PCM window, stashed on every write()/
    // writeInt() call before it goes to the mixer/MMAP path. This is what lets the Inspector's
    // Live Meters work during MMAP-exclusive output, where android.media.audiofx.Visualizer
    // has nothing to attach to.
    @Volatile private var liveCaptureSamples: FloatArray? = null
    @Volatile private var liveCaptureChannels: Int = 2
    private var currentEncoding = AudioFormat.ENCODING_PCM_FLOAT
    private var currentPerformanceMode = AudioTrack.PERFORMANCE_MODE_NONE
    private var currentBytesPerSample = 4
    private var supportedDirectRates: List<Int> = emptyList()
    private var dvcEnabled = true
    private var dvcMode = DvcMode.DAC
    private var dvcLevel = 1f
    @Volatile private var ditherEnabled = true
    @Volatile private var ditherType = 2
    private var ditherLastErr = 0f
    private var ditherLastNoise = 0f
    private var ditherState0 = 0x1234ABCD   
    private var ditherState1 = 0xDEADBEEF.toInt()  
    private var lastThreadId = -1L

    // MMAP Exclusive
    private var mmapExclusiveRequested: Boolean = false
    private var mmapRequestedBufferFrames: Int = 96
    private var mmapOutput: MmapAudioOutput? = null
    private var usingMmap: Boolean = false

    private var bufferFrames: Int = 0
    private var bufferCount: Int = 2
    private var postFadeFrames: Int = 0

    // ── USB Direct Mode ────────────────────────────────────────────────────────
    @Volatile private var usbExclusiveEnabled = false
    @Volatile private var bitPerfectEnabled = false
    private var detectedUsbDevice: AudioDeviceInfo? = null
    private var usbSupportedRates: List<Int> = emptyList()
    private var usbSupportedBitDepths: List<Int> = emptyList()

    // ── Pre-allocated PCM conversion buffers (fixes GC bottleneck) ────────────
    private var pcm16Buffer = ByteArray(32_768 * 2)
    private var pcm24Buffer = ByteArray(32_768 * 3)
    private var pcm32Buffer = ByteArray(32_768 * 4)

    private val isMtkDevice = Build.HARDWARE.lowercase().contains("mt") ||
                             Build.BOARD.lowercase().contains("mt") ||
                             Build.MANUFACTURER.lowercase().contains("mediatek")

    override fun setUsbExclusiveMode(enabled: Boolean) {
        lifecycleLock.writeLock().withLock {
            if (usbExclusiveEnabled == enabled) return
            usbExclusiveEnabled = enabled
            Log.d(TAG, "USB Exclusive Mode: $enabled")
        }
    }

    override fun setBitPerfectMode(enabled: Boolean) {
        lifecycleLock.writeLock().withLock {
            bitPerfectEnabled = enabled
        }
    }

    private fun detectUsbDevice(): AudioDeviceInfo? {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        return devices.firstOrNull { d ->
            (d.type == AudioDeviceInfo.TYPE_USB_DEVICE || d.type == AudioDeviceInfo.TYPE_USB_HEADSET) &&
            d.sampleRates.isNotEmpty() &&
            d.channelCounts.isNotEmpty()
        }
    }

    private fun detectUsbCapabilities(device: AudioDeviceInfo): Pair<List<Int>, List<Int>> {
        val rates = device.sampleRates.toList().ifEmpty {
            listOf(44_100, 48_000, 96_000, 192_000)
        }
        val depths = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.encodings.toList().mapNotNull { enc ->
                when (enc) {
                    AudioFormat.ENCODING_PCM_16BIT -> 16
                    AudioFormat.ENCODING_PCM_24BIT_PACKED -> 24
                    AudioFormat.ENCODING_PCM_32BIT -> 32
                    AudioFormat.ENCODING_PCM_FLOAT -> 32
                    else -> null
                }
            }.distinct().sorted()
        } else {
            listOf(16, 24)
        }
        return rates.sorted() to depths
    }

    fun setOutputMode(mode: OutputMode) {
        lifecycleLock.writeLock().withLock {
            selectedMode = mode
        }
    }

    fun selectedOutputMode(): OutputMode = lifecycleLock.readLock().withLock { selectedMode }

    fun refreshRouteState(): OutputRouteState = lifecycleLock.writeLock().withLock {
        val usbDev = if (usbExclusiveEnabled) detectUsbDevice() else null
        detectedUsbDevice = usbDev

        val device = resolvePreferredOutputDevice()
        preferredDevice = device
        outputDeviceName = deviceTypeLabel(device)
        supportedDirectRates = detectDirectRates()
        val maxDirectRate = supportedDirectRates.maxOrNull() ?: 48_000
        val hiResSupported = supportedDirectRates.any { it > 48_000 }

        val usbActive: Boolean
        val usbName: String
        val usbRates: List<Int>
        val usbDepths: List<Int>
        if (usbDev != null) {
            val (rates, depths) = detectUsbCapabilities(usbDev)
            usbSupportedRates = rates
            usbSupportedBitDepths = depths
            usbActive = true
            usbName = usbDev.productName?.toString() ?: "USB DAC"
            usbRates = rates
            usbDepths = depths
        } else {
            usbSupportedRates = emptyList()
            usbSupportedBitDepths = emptyList()
            usbActive = false
            usbName = ""
            usbRates = emptyList()
            usbDepths = emptyList()
        }

        val summary = when {
            usbActive -> "USB Direct: $usbName — ${usbRates.joinToString("/") { "${it / 1000}k" }} Hz"
            hiResSupported -> "MTK HiFi / Direct PCM available on $outputDeviceName"
            else -> "Direct hi-res not available on $outputDeviceName"
        }

        return OutputRouteState(
            selectedMode = selectedMode,
            activeMode = when {
                selectedMode == OutputMode.MMAP_EXCLUSIVE && usingMmap -> OutputMode.MMAP_EXCLUSIVE
                selectedMode == OutputMode.HI_RES && hiResSupported    -> OutputMode.HI_RES
                else                                                   -> OutputMode.AAUDIO
            },
            outputDevice = outputDeviceName,
            hiResDirectSupported = hiResSupported,
            capabilitySummary = summary,
            maxSupportedSampleRate = maxDirectRate,
            usbExclusiveActive = usbActive,
            usbDeviceName = usbName,
            usbSupportedRates = usbRates,
            usbSupportedBitDepths = usbDepths,
            mmapSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1,
            mmapExclusiveActive = usingMmap,
            mmapActualBufferFrames = mmapOutput?.mmapActualBufferFrames() ?: 0,
            mmapActualLatencyMs = (mmapOutput?.estimatedLatencyMs() ?: 0).toFloat()
        )
    }

    override fun init(sampleRate: Int, channels: Int, bitDepth: Int, isDoP: Boolean, resetOffsets: Boolean): Boolean = lifecycleLock.writeLock().withLock {
        val lastPosForRecovery = if (!resetOffsets) playbackPositionFrames() else 0L
        refreshRouteState()

        val channelConfig = when (channels) {
            1 -> AudioFormat.CHANNEL_OUT_MONO
            2 -> AudioFormat.CHANNEL_OUT_STEREO
            4 -> AudioFormat.CHANNEL_OUT_QUAD
            6 -> AudioFormat.CHANNEL_OUT_5POINT1
            8 -> AudioFormat.CHANNEL_OUT_7POINT1
            else -> if (channels > 2) AudioFormat.CHANNEL_OUT_5POINT1 else AudioFormat.CHANNEL_OUT_STEREO
        }

        val usbDev = detectedUsbDevice
        val isUsbExclusiveActive = usbExclusiveEnabled && usbDev != null

        val resolvedActiveMode = if (isUsbExclusiveActive) {
            OutputMode.AAUDIO
        } else if (selectedMode == OutputMode.HI_RES) {
            val supportedEncodings = listOf(
                AudioFormat.ENCODING_PCM_FLOAT,
                AudioFormat.ENCODING_PCM_32BIT,
                AudioFormat.ENCODING_PCM_24BIT_PACKED,
                AudioFormat.ENCODING_PCM_16BIT
            ).filter { enc ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    isDirectPlaybackSupported(sampleRate, channelConfig, enc)
                } else true
            }
            if (supportedEncodings.isNotEmpty() || sampleRate > 48000 || isMtkDevice) {
                OutputMode.HI_RES
            } else {
                OutputMode.AAUDIO
            }
        } else {
            OutputMode.AAUDIO
        }

        val resolvedSampleRate = when {
            isUsbExclusiveActive -> resolveUsbSampleRate(
                requested = if (targetSampleRate > 0) targetSampleRate else sampleRate,
                device = usbDev
            )
            resolvedActiveMode == OutputMode.AAUDIO -> getHardwareSampleRate()
            else -> resolveSupportedSampleRate(if (targetSampleRate > 0) targetSampleRate else sampleRate)
        }

        val resolvedEncoding = when {
            isUsbExclusiveActive -> resolveUsbEncoding(bitDepth, usbDev, channelConfig)
            bitPerfectEnabled -> resolveBestEncodingForBitDepth(bitDepth, channelConfig)
            resolvedActiveMode == OutputMode.AAUDIO -> AudioFormat.ENCODING_PCM_FLOAT
            else -> resolveBestEncoding(bitDepth, channelConfig)
        }

        val resolvedPerformanceMode = if (isUsbExclusiveActive) {
            AudioTrack.PERFORMANCE_MODE_LOW_LATENCY
        } else {
            AudioTrack.PERFORMANCE_MODE_NONE
        }

        val resolvedPreferredDevice = if (isUsbExclusiveActive) usbDev else preferredDevice
        val resolvedBytesPerSample = bytesPerSample(resolvedEncoding)

        if (mmapExclusiveRequested) {
            releaseInternal() 
            val mmap = MmapAudioOutput()
            val format = if (isDoP) 4 else 2 
            if (mmap.init(resolvedSampleRate, channels, mmapRequestedBufferFrames, format, resetOffsets)) {
                mmapOutput = mmap
                usingMmap = true
                this.sampleRate = mmap.outputSampleRate()
                this.channels = channels
                this.currentEncoding = AudioFormat.ENCODING_PCM_FLOAT
                this.currentBytesPerSample = 4
                this.currentPerformanceMode = AudioTrack.PERFORMANCE_MODE_NONE
                
                synchronized(stateLock) {
                    if (resetOffsets) {
                        totalFramesWritten = 0L
                        playbackHeadWraps = 0L
                        lastPlaybackHeadPosition = 0
                        playbackHeadOffset = 0L
                    } else {
                        // RECOVERY PATH: Preserve cumulative position.
                        playbackHeadWraps = 0L
                        lastPlaybackHeadPosition = 0
                        playbackHeadOffset = -lastPosForRecovery
                    }
                }
                Log.i(TAG, "MMAP Exclusive active: rate=${this.sampleRate}")
                return true
            } else {
                Log.w(TAG, "MMAP Exclusive init failed")
                mmap.release()
                usingMmap = false
            }
        }

        val oldTrack = audioTrack
        val canDoSeamless = oldTrack != null && !usingMmap && !mmapExclusiveRequested &&
                this.sampleRate == resolvedSampleRate &&
                this.channels == channels &&
                this.currentPerformanceMode == resolvedPerformanceMode &&
                this.preferredDevice == resolvedPreferredDevice

        if (!canDoSeamless) {
            releaseInternal()
        }

        usingMmap = false
        mmapOutput = null
        activeMode = resolvedActiveMode
        
        val maxSamplesPerBatch = 4_096 * channels
        if (pcm16Buffer.size < maxSamplesPerBatch * 2) pcm16Buffer = ByteArray(maxSamplesPerBatch * 2)
        if (pcm24Buffer.size < maxSamplesPerBatch * 3) pcm24Buffer = ByteArray(maxSamplesPerBatch * 3)
        if (pcm32Buffer.size < maxSamplesPerBatch * 4) pcm32Buffer = ByteArray(maxSamplesPerBatch * 4)

        val minBuffer = AudioTrack.getMinBufferSize(resolvedSampleRate, channelConfig, resolvedEncoding)
        val bufferSize = if (this.bufferFrames > 0) {
            this.bufferFrames * channels * resolvedBytesPerSample * this.bufferCount
        } else {
            // Increased from 8 to 16 to target ~200-250ms by default (assuming ~10-15ms minBuffer)
            minBuffer * 16
        }
        if (bufferSize <= 0) return false

        try {
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()

            val audioFormat = AudioFormat.Builder()
                .setEncoding(resolvedEncoding)
                .setSampleRate(resolvedSampleRate)
                .setChannelMask(channelConfig)
                .build()

            val builder = AudioTrack.Builder()
                .setAudioAttributes(audioAttributes)
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(resolvedPerformanceMode)

            val newTrack = try {
                builder.build()
            } catch (e: Exception) {
                Log.e(TAG, "AudioTrack.Builder.build() failed: ${e.message}")
                if (channels > 2) return init(sampleRate, 2, bitDepth, isDoP)
                return false
            }

            if (newTrack.state != AudioTrack.STATE_INITIALIZED) {
                newTrack.release()
                if (channels > 2) return init(sampleRate, 2, bitDepth, isDoP)
                return false
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && resolvedPreferredDevice != null) {
                newTrack.setPreferredDevice(resolvedPreferredDevice)
            }

            if (canDoSeamless && oldTrack != null) {
                val isPlaying = oldTrack.playState == AudioTrack.PLAYSTATE_PLAYING
                if (isPlaying) newTrack.play()
                audioTrack = newTrack
                
                synchronized(stateLock) {
                    if (resetOffsets) {
                        totalFramesWritten = 0L
                        playbackHeadWraps = 0L
                        lastPlaybackHeadPosition = 0
                        playbackHeadOffset = 0L
                    } else {
                        // RECOVERY PATH: Preserve cumulative position.
                        // The new track's hardware head starts at 0. We want playbackPositionFrames()
                        // to continue from lastPosForRecovery.
                        // Formula: (head - offset) = lastPosForRecovery.
                        // Since head is 0: (0 - offset) = lastPosForRecovery => offset = -lastPosForRecovery.
                        playbackHeadWraps = 0L
                        lastPlaybackHeadPosition = 0
                        playbackHeadOffset = -lastPosForRecovery
                        // totalFramesWritten is intentionally NOT reset
                    }
                }
                try {
                    oldTrack.pause()
                    oldTrack.flush()
                    oldTrack.stop()
                    oldTrack.release()
                } catch (e: Exception) {
                    Log.e(TAG, "Error releasing old track", e)
                }
            } else {
                audioTrack = newTrack
                synchronized(stateLock) {
                    if (resetOffsets) {
                        totalFramesWritten = 0L
                        playbackHeadWraps = 0L
                        lastPlaybackHeadPosition = 0
                        playbackHeadOffset = 0L
                    } else {
                        // RECOVERY PATH: Preserve cumulative position.
                        playbackHeadWraps = 0L
                        lastPlaybackHeadPosition = 0
                        playbackHeadOffset = -lastPosForRecovery
                    }
                }
            }

            this.sampleRate = resolvedSampleRate
            this.channels = channels
            this.currentEncoding = resolvedEncoding
            this.currentBytesPerSample = resolvedBytesPerSample
            this.currentPerformanceMode = resolvedPerformanceMode

            Log.d(TAG, "AudioTrack init: rate=${this.sampleRate} enc=${encodingName(currentEncoding)}")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Init failed", e)
            return false
        }
    }

    private fun resolveUsbSampleRate(requested: Int, device: AudioDeviceInfo): Int {
        val rates = device.sampleRates.toList().ifEmpty { return requested }
        return if (rates.contains(requested)) requested
        else rates.minByOrNull { kotlin.math.abs(it - requested) } ?: requested
    }

    private fun resolveUsbEncoding(bitDepth: Int, device: AudioDeviceInfo, channelConfig: Int): Int {
        val supported = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.encodings.toList()
        } else {
            listOf(AudioFormat.ENCODING_PCM_16BIT, AudioFormat.ENCODING_PCM_FLOAT)
        }

        if (sampleFormat != SampleFormat.AUTO) {
            val preferred = when (sampleFormat) {
                SampleFormat.PCM_16BIT -> AudioFormat.ENCODING_PCM_16BIT
                SampleFormat.PCM_24BIT -> AudioFormat.ENCODING_PCM_24BIT_PACKED
                SampleFormat.PCM_32BIT -> AudioFormat.ENCODING_PCM_32BIT
                SampleFormat.FLOAT_32BIT -> AudioFormat.ENCODING_PCM_FLOAT
                else -> null
            }
            if (preferred != null && (supported.contains(preferred) || supported.isEmpty())) return preferred
        }

        return when {
            bitDepth >= 32 && supported.contains(AudioFormat.ENCODING_PCM_FLOAT) -> AudioFormat.ENCODING_PCM_FLOAT
            bitDepth >= 32 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && supported.contains(AudioFormat.ENCODING_PCM_32BIT) -> AudioFormat.ENCODING_PCM_32BIT
            bitDepth >= 24 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && supported.contains(AudioFormat.ENCODING_PCM_24BIT_PACKED) -> AudioFormat.ENCODING_PCM_24BIT_PACKED
            supported.contains(AudioFormat.ENCODING_PCM_FLOAT) -> AudioFormat.ENCODING_PCM_FLOAT
            else -> AudioFormat.ENCODING_PCM_16BIT
        }
    }

    private fun resolveBestEncodingForBitDepth(bitDepth: Int, channelConfig: Int): Int {
        return when {
            bitDepth >= 24 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                isDirectPlaybackSupported(sampleRate, channelConfig, AudioFormat.ENCODING_PCM_32BIT) -> AudioFormat.ENCODING_PCM_32BIT
            bitDepth >= 24 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
                isDirectPlaybackSupported(sampleRate, channelConfig, AudioFormat.ENCODING_PCM_24BIT_PACKED) -> AudioFormat.ENCODING_PCM_24BIT_PACKED
            else -> AudioFormat.ENCODING_PCM_FLOAT
        }
    }

    override fun start() {
        lifecycleLock.readLock().withLock {
            if (usingMmap) { mmapOutput?.start(); return }
            val track = audioTrack ?: return
            try {
                applyTrackVolume()
                track.play()
            } catch (_: Exception) {}
        }
    }

    override fun pause() {
        lifecycleLock.readLock().withLock {
            if (usingMmap) { mmapOutput?.pause(); return }
            try { audioTrack?.pause() } catch (_: Exception) {}
        }
    }

    override fun stop() {
        lifecycleLock.readLock().withLock {
            if (usingMmap) { mmapOutput?.stop(); return }
            try {
                audioTrack?.let {
                    if (it.playState == AudioTrack.PLAYSTATE_PLAYING) {
                        it.pause(); it.flush()
                    }
                }
            } catch (_: Exception) {}
            synchronized(stateLock) {
                totalFramesWritten = 0L
                playbackHeadWraps = 0L
                lastPlaybackHeadPosition = 0
                playbackHeadOffset = 0L
            }
        }
    }

    override fun flush() {
        lifecycleLock.readLock().withLock {
            if (usingMmap) { mmapOutput?.flush(); return }
            try {
                audioTrack?.let {
                    it.pause()
                    it.flush()
                    playbackHeadOffset = getAbsolutePlaybackHeadPositionInternal()
                    synchronized(stateLock) { totalFramesWritten = 0L }
                    if (it.state == AudioTrack.STATE_INITIALIZED) it.play()
                }
            } catch (_: Exception) {}
        }
    }

    override fun release() {
        lifecycleLock.writeLock().withLock {
            releaseInternal()
        }
    }

    private fun releaseInternal() {
        mmapOutput?.release()
        mmapOutput = null
        usingMmap = false
        val track = audioTrack
        audioTrack = null
        if (track != null) {
            // SECURITY: AudioTrack.stop()/release() can block for 100ms+ on some drivers.
            // We offload this to a background thread to prevent the engine control loop from "sticking"
            // during source changes (e.g. Telegram to GDrive).
            Thread {
                try {
                    if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                        track.pause()
                        track.flush()
                    }
                    track.stop()
                    track.release()
                    Log.d(TAG, "AudioTrack released successfully in background")
                } catch (e: Exception) {
                    Log.w(TAG, "Error in background track release: ${e.message}")
                }
            }.start()
        }
    }

    override fun write(data: FloatArray, offsetInSamples: Int, frameCount: Int): Int {
        if (lastThreadId != Thread.currentThread().id) {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            lastThreadId = Thread.currentThread().id
        }

        lifecycleLock.readLock().withLock {
            val mmap = mmapOutput
            val track = audioTrack
            val isMmap = usingMmap

            if (frameCount > 0) stashLiveCaptureFloat(data, offsetInSamples, frameCount * channels, channels)

            if (isMmap && mmap != null) return mmap.write(data, offsetInSamples, frameCount)
            if (track == null) return 0

            val sampleCount = frameCount * channels
            return try {
                val writtenFrames = when (currentEncoding) {
                    AudioFormat.ENCODING_PCM_16BIT -> {
                        toPcm16InPlace(data, offsetInSamples, sampleCount)
                        val writtenBytes = track.write(pcm16Buffer, 0, sampleCount * 2, AudioTrack.WRITE_NON_BLOCKING)
                        if (writtenBytes == 0 && track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                            try { track.play() } catch (_: Exception) {}
                        }
                        if (writtenBytes > 0) writtenBytes / (channels * 2) else writtenBytes
                    }
                    AudioFormat.ENCODING_PCM_24BIT_PACKED -> {
                        toPcm24InPlace(data, offsetInSamples, sampleCount)
                        val writtenBytes = track.write(pcm24Buffer, 0, sampleCount * 3, AudioTrack.WRITE_NON_BLOCKING)
                        if (writtenBytes == 0 && track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                            try { track.play() } catch (_: Exception) {}
                        }
                        if (writtenBytes > 0) writtenBytes / (channels * 3) else writtenBytes
                    }
                    AudioFormat.ENCODING_PCM_32BIT -> {
                        toPcm32InPlace(data, offsetInSamples, sampleCount)
                        val writtenBytes = track.write(pcm32Buffer, 0, sampleCount * 4, AudioTrack.WRITE_NON_BLOCKING)
                        if (writtenBytes == 0 && track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                            try { track.play() } catch (_: Exception) {}
                        }
                        if (writtenBytes > 0) writtenBytes / (channels * 4) else writtenBytes
                    }
                    else -> {
                        val writtenSamples = track.write(data, offsetInSamples, sampleCount, AudioTrack.WRITE_NON_BLOCKING)
                        if (writtenSamples == 0 && track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                            try { track.play() } catch (_: Exception) {}
                        }
                        if (writtenSamples > 0) writtenSamples / channels else writtenSamples
                    }
                }
                if (writtenFrames > 0) {
                    synchronized(stateLock) { totalFramesWritten += writtenFrames.toLong() }
                }
                writtenFrames
            } catch (_: Exception) { 0 }
        }
    }

    override fun writeInt(data: IntArray, offsetInSamples: Int, frameCount: Int): Int {
        lifecycleLock.readLock().withLock {
            val mmap = mmapOutput
            val track = audioTrack
            if (frameCount > 0) stashLiveCaptureInt(data, offsetInSamples, frameCount * channels, channels, currentEncoding)
            if (usingMmap && mmap != null) return mmap.writeInt(data, offsetInSamples, frameCount)
            if (track == null) return 0

            val sampleCount = frameCount * channels
            return try {
                val writtenFrames = when (currentEncoding) {
                    AudioFormat.ENCODING_PCM_24BIT_PACKED -> {
                        var outIndex = 0
                        for (i in 0 until sampleCount) {
                            val sample = data[offsetInSamples + i]
                            pcm24Buffer[outIndex++] = (sample and 0xFF).toByte()
                            pcm24Buffer[outIndex++] = ((sample shr 8) and 0xFF).toByte()
                            pcm24Buffer[outIndex++] = ((sample shr 16) and 0xFF).toByte()
                        }
                        val writtenBytes = track.write(pcm24Buffer, 0, sampleCount * 3, AudioTrack.WRITE_NON_BLOCKING)
                        if (writtenBytes > 0) writtenBytes / (channels * 3) else writtenBytes
                    }
                    AudioFormat.ENCODING_PCM_32BIT -> {
                        var outIndex = 0
                        for (i in 0 until sampleCount) {
                            val sample = data[offsetInSamples + i]
                            pcm32Buffer[outIndex++] = (sample and 0xFF).toByte()
                            pcm32Buffer[outIndex++] = ((sample shr 8) and 0xFF).toByte()
                            pcm32Buffer[outIndex++] = ((sample shr 16) and 0xFF).toByte()
                            pcm32Buffer[outIndex++] = ((sample shr 24) and 0xFF).toByte()
                        }
                        val writtenBytes = track.write(pcm32Buffer, 0, sampleCount * 4, AudioTrack.WRITE_NON_BLOCKING)
                        if (writtenBytes > 0) writtenBytes / (channels * 4) else writtenBytes
                    }
                    else -> 0
                }
                if (writtenFrames > 0) {
                    synchronized(stateLock) { totalFramesWritten += writtenFrames.toLong() }
                }
                writtenFrames
            } catch (_: Exception) { 0 }
        }
    }

    override fun playbackPositionFrames(): Long {
        if (lifecycleLock.readLock().tryLock()) {
            try {
                if (usingMmap) return mmapOutput?.playbackPositionFrames() ?: 0L
                return (getAbsolutePlaybackHeadPositionInternal() - playbackHeadOffset).coerceAtLeast(0L)
            } finally {
                lifecycleLock.readLock().unlock()
            }
        }
        return 0L 
    }

    override fun totalFramesWritten(): Long {
        if (lifecycleLock.readLock().tryLock()) {
            try {
                if (usingMmap) return mmapOutput?.totalFramesWritten() ?: 0L
                return synchronized(stateLock) { totalFramesWritten }
            } finally {
                lifecycleLock.readLock().unlock()
            }
        }
        return synchronized(stateLock) { totalFramesWritten }
    }

    private fun getAbsolutePlaybackHeadPositionInternal(): Long {
        val track = audioTrack ?: return 0L
        return try {
            val head = track.playbackHeadPosition
            synchronized(stateLock) {
                if (head < lastPlaybackHeadPosition) playbackHeadWraps++
                lastPlaybackHeadPosition = head
                (playbackHeadWraps shl 32) + (head.toLong() and 0xFFFFFFFFL)
            }
        } catch (_: Exception) { 0L }
    }

    override fun setTargetSampleRate(sampleRate: Int) {
        lifecycleLock.writeLock().withLock { targetSampleRate = sampleRate }
    }

    override fun setDvcState(enabled: Boolean, mode: String, level: Float) {
        lifecycleLock.writeLock().withLock {
            dvcEnabled = enabled
            dvcMode = DvcMode.entries.firstOrNull { it.name == mode } ?: DvcMode.DAC
            dvcLevel = level.coerceIn(0f, 1f)
            applyTrackVolume()
        }
    }

    override fun setDitherState(enabled: Boolean, type: Int) {
        synchronized(stateLock) {
            ditherEnabled = enabled
            ditherType = type
        }
    }

    private fun applyTrackVolume() {
        val track = audioTrack ?: return
        track.setVolume(1.0f)
    }

    override fun setSampleFormat(format: SampleFormat) {
        lifecycleLock.writeLock().withLock { this.sampleFormat = format }
    }

    override fun outputSampleRate(): Int = lifecycleLock.readLock().withLock { sampleRate }
    override fun outputBitDepth(): Int = lifecycleLock.readLock().withLock { currentBytesPerSample * 8 }

    override fun outputPathLabel(): String = lifecycleLock.readLock().withLock {
        if (usingMmap) return@withLock "MMAP Exclusive"
        when {
            usbExclusiveEnabled && detectedUsbDevice != null -> "USB Direct"
            activeMode == OutputMode.HI_RES -> "MTK HiFi"
            else -> "AAudio"
        }
    }

    override fun outputDeviceLabel(): String = lifecycleLock.readLock().withLock { outputDeviceName }

    override fun estimatedLatencyMs(): Int {
        val (rate, pos, written) = lifecycleLock.readLock().withLock {
            val r = sampleRate
            val p = playbackPositionFrames()
            val w = synchronized(stateLock) { totalFramesWritten }
            Triple(r, p, w)
        }
        val queued = (written - pos).coerceAtLeast(0L)
        if (usingMmap) return mmapOutput?.estimatedLatencyMs() ?: 0
        if (rate <= 0) return 0
        return ((queued * 1000L) / rate).toInt()
    }

    override fun setMmapExclusiveMode(enabled: Boolean, requestedBufferFrames: Int) {
        lifecycleLock.writeLock().withLock {
            mmapExclusiveRequested = enabled
            mmapRequestedBufferFrames = requestedBufferFrames
        }
    }

    override fun setBufferConfig(bufferFrames: Int, bufferCount: Int, postFadeFrames: Int) {
        lifecycleLock.writeLock().withLock {
            this.bufferFrames = bufferFrames
            this.bufferCount = bufferCount
            this.postFadeFrames = postFadeFrames
            mmapOutput?.setBufferConfig(bufferFrames, bufferCount, postFadeFrames)
        }
    }

    override fun isMmapActive(): Boolean = lifecycleLock.readLock().withLock { usingMmap }

    override fun getAudioSessionId(): Int = lifecycleLock.readLock().withLock {
        // MMAP-exclusive path bypasses the regular mixer track Visualizer taps into.
        if (usingMmap) return@withLock 0
        audioTrack?.audioSessionId ?: 0
    }

    override fun captureLiveWindow(): AudioOutput.LiveCapture? {
        val snapshot = liveCaptureSamples ?: return null
        return AudioOutput.LiveCapture(snapshot, liveCaptureChannels)
    }

    /** Stashes a bounded copy of a normalized float PCM window for [captureLiveWindow].
     *  Called from write() before the mmap/mixer branch, so it covers both output paths
     *  uniformly. Cheap (array copy only) and never throws into the audio path. */
    private fun stashLiveCaptureFloat(data: FloatArray, offsetInSamples: Int, sampleCount: Int, ch: Int) {
        try {
            val len = sampleCount.coerceAtMost(4096)
            if (len <= 0) return
            val snap = FloatArray(len)
            System.arraycopy(data, offsetInSamples, snap, 0, len)
            liveCaptureSamples = snap
            liveCaptureChannels = ch
        } catch (_: Exception) {}
    }

    /** Same as [stashLiveCaptureFloat] but for the Int-PCM write path (bit-perfect integer
     *  output), normalizing samples to -1f..1f based on the active encoding's bit width. */
    private fun stashLiveCaptureInt(data: IntArray, offsetInSamples: Int, sampleCount: Int, ch: Int, encoding: Int) {
        try {
            val len = sampleCount.coerceAtMost(4096)
            if (len <= 0) return
            val divisor = if (encoding == AudioFormat.ENCODING_PCM_32BIT) 2147483648f else 8388608f
            val snap = FloatArray(len)
            for (i in 0 until len) {
                snap[i] = (data[offsetInSamples + i] / divisor).coerceIn(-1f, 1f)
            }
            liveCaptureSamples = snap
            liveCaptureChannels = ch
        } catch (_: Exception) {}
    }

    override fun mmapActualBufferFrames(): Int = lifecycleLock.readLock().withLock { mmapOutput?.mmapActualBufferFrames() ?: 0 }

    private fun resolveBestEncoding(bitDepth: Int, channelConfig: Int): Int {
        if (sampleFormat != SampleFormat.AUTO) {
            return when (sampleFormat) {
                SampleFormat.PCM_16BIT -> AudioFormat.ENCODING_PCM_16BIT
                SampleFormat.PCM_24BIT -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) AudioFormat.ENCODING_PCM_24BIT_PACKED else AudioFormat.ENCODING_PCM_16BIT
                SampleFormat.PCM_32BIT -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) AudioFormat.ENCODING_PCM_32BIT else AudioFormat.ENCODING_PCM_FLOAT
                SampleFormat.FLOAT_32BIT -> AudioFormat.ENCODING_PCM_FLOAT
                else -> AudioFormat.ENCODING_PCM_FLOAT
            }
        }
        if (activeMode == OutputMode.HI_RES) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && (isDirectPlaybackSupported(sampleRate, channelConfig, AudioFormat.ENCODING_PCM_32BIT) || isMtkDevice)) return AudioFormat.ENCODING_PCM_32BIT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && (isDirectPlaybackSupported(sampleRate, channelConfig, AudioFormat.ENCODING_PCM_24BIT_PACKED) || isMtkDevice)) return AudioFormat.ENCODING_PCM_24BIT_PACKED
            if (isDirectPlaybackSupported(sampleRate, channelConfig, AudioFormat.ENCODING_PCM_FLOAT) || isMtkDevice) return AudioFormat.ENCODING_PCM_FLOAT
            return AudioFormat.ENCODING_PCM_16BIT
        }
        return if (dvcEnabled || bitDepth > 16) AudioFormat.ENCODING_PCM_FLOAT else AudioFormat.ENCODING_PCM_16BIT
    }

    private fun resolveSupportedSampleRate(requestedRate: Int): Int {
        val device = preferredDevice ?: resolvePreferredOutputDevice()
        val isBluetooth = device?.type in BLUETOOTH_TYPES
        if (isBluetooth) return BLUETOOTH_RATE_CANDIDATES.minByOrNull { kotlin.math.abs(it - requestedRate) } ?: 48_000
        if (selectedMode == OutputMode.HI_RES) {
            val directRates = detectDirectRates().sortedDescending()
            if (directRates.isNotEmpty()) {
                if (directRates.contains(requestedRate)) return requestedRate
                if (bitPerfectEnabled) return requestedRate
                return directRates.firstOrNull { it == 192000 } ?: directRates.firstOrNull { it == 176400 } ?: directRates.firstOrNull { it == 96000 } ?: directRates.firstOrNull { it == 88200 } ?: directRates.first()
            }
        }
        return requestedRate
    }

    private fun detectDirectRates(): List<Int> {
        val channelMask = AudioFormat.CHANNEL_OUT_STEREO
        val encodings = mutableListOf(AudioFormat.ENCODING_PCM_16BIT, AudioFormat.ENCODING_PCM_FLOAT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) encodings.add(AudioFormat.ENCODING_PCM_24BIT_PACKED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) encodings.add(AudioFormat.ENCODING_PCM_32BIT)
        val rates = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val supported = DIRECT_RATE_CANDIDATES.filter { rate -> encodings.any { enc -> isDirectPlaybackSupported(rate, channelMask, enc) } }
            if (supported.isEmpty() && (selectedMode == OutputMode.HI_RES || isMtkDevice)) DIRECT_RATE_CANDIDATES.filter { it <= 192000 } else supported
        } else {
            if (isMtkDevice) DIRECT_RATE_CANDIDATES.filter { it <= 192000 } else DIRECT_RATE_CANDIDATES.filter { it <= 96000 }
        }
        return if (rates.isEmpty()) listOf(44100, 48000) else rates
    }

    private fun isDirectPlaybackSupported(sampleRate: Int, channelMask: Int, encoding: Int): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        return runCatching {
            AudioTrack.isDirectPlaybackSupported(
                AudioFormat.Builder().setEncoding(encoding).setSampleRate(sampleRate).setChannelMask(channelMask).build(),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
            )
        }.getOrDefault(false)
    }

    private fun resolvePreferredOutputDevice(): AudioDeviceInfo? {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
        if (usbExclusiveEnabled) {
            val usbDev = devices.firstOrNull { (it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET) && it.sampleRates.isNotEmpty() }
            if (usbDev != null) return usbDev
        }
        return devices.firstOrNull { it.type in BLUETOOTH_TYPES } ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET } ?: devices.firstOrNull { it.type in WIRED_TYPES } ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER } ?: devices.firstOrNull()
    }

    private fun deviceTypeLabel(device: AudioDeviceInfo?): String {
        return when (device?.type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER, AudioDeviceInfo.TYPE_BLE_BROADCAST -> OutputDeviceType.BLUETOOTH.displayName
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> OutputDeviceType.USB_DAC.displayName
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_LINE_DIGITAL -> OutputDeviceType.WIRED.displayName
            AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_HDMI_ARC, AudioDeviceInfo.TYPE_HDMI_EARC -> OutputDeviceType.HDMI.displayName
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> OutputDeviceType.SPEAKER.displayName
            else -> OutputDeviceType.UNKNOWN.displayName
        }
    }

    private fun bytesPerSample(encoding: Int): Int = when (encoding) {
        AudioFormat.ENCODING_PCM_16BIT -> 2
        AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
        AudioFormat.ENCODING_PCM_32BIT, AudioFormat.ENCODING_PCM_FLOAT -> 4
        else -> 4
    }

    private fun toPcm16InPlace(data: FloatArray, offset: Int, sampleCount: Int) {
        var inIndex = offset
        var outIndex = 0
        repeat(sampleCount) {
            val sample = ditherSample(data[inIndex++] * Short.MAX_VALUE, 1f).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            pcm16Buffer[outIndex++] = (sample.toInt() and 0xFF).toByte()
            pcm16Buffer[outIndex++] = ((sample.toInt() shr 8) and 0xFF).toByte()
        }
    }

    private fun toPcm24InPlace(data: FloatArray, offset: Int, sampleCount: Int) {
        var inIndex = offset
        var outIndex = 0
        repeat(sampleCount) {
            val sample = ditherSample(data[inIndex++] * PCM_24_MAX, 256f).roundToInt().coerceIn(-8_388_608, 8_388_607)
            pcm24Buffer[outIndex++] = (sample and 0xFF).toByte()
            pcm24Buffer[outIndex++] = ((sample shr 8) and 0xFF).toByte()
            pcm24Buffer[outIndex++] = ((sample shr 16) and 0xFF).toByte()
        }
    }

    private fun toPcm32InPlace(data: FloatArray, offset: Int, sampleCount: Int) {
        var inIndex = offset
        var outIndex = 0
        repeat(sampleCount) {
            val sample = (data[inIndex++] * Int.MAX_VALUE).roundToInt()
            pcm32Buffer[outIndex++] = (sample and 0xFF).toByte()
            pcm32Buffer[outIndex++] = ((sample shr 8) and 0xFF).toByte()
            pcm32Buffer[outIndex++] = ((sample shr 16) and 0xFF).toByte()
            pcm32Buffer[outIndex++] = ((sample shr 24) and 0xFF).toByte()
        }
    }

    private fun getHardwareSampleRate(): Int = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 48000

    private fun ditherSample(inputSample: Float, scale: Float): Float {
        if (!ditherEnabled || ditherType == 0) return inputSample

        ditherState0 = 1_664_525 * ditherState0 + 1_013_904_223
        val a = ((ditherState0 ushr 1) and 0x7FFF) / 32767f
        ditherState1 = 22_695_477 * ditherState1 + 1
        val b = ((ditherState1 ushr 1) and 0x7FFF) / 32767f
        val noise = a - b

        return when (ditherType) {
            2 -> { // Shaped: simple error-feedback
                val res = inputSample + (noise / scale) - 0.9f * ditherLastErr
                val rounded = res.roundToInt().toFloat()
                ditherLastErr = rounded - res
                rounded
            }
            3 -> { // HighPass: one-pole high-pass to the noise
                val currentNoise = noise
                val hpNoise = currentNoise - 0.5f * ditherLastNoise
                ditherLastNoise = currentNoise
                inputSample + (hpNoise / scale)
            }
            else -> { // TPDF (type 1)
                inputSample + (noise / scale)
            }
        }
    }

    private fun encodingName(enc: Int) = when (enc) {
        AudioFormat.ENCODING_PCM_16BIT -> "PCM16"
        AudioFormat.ENCODING_PCM_24BIT_PACKED -> "PCM24"
        AudioFormat.ENCODING_PCM_32BIT -> "PCM32"
        AudioFormat.ENCODING_PCM_FLOAT -> "Float32"
        else -> "enc=$enc"
    }

    companion object {
        private const val TAG = "AudioTrackOutput"
        private const val PCM_24_MAX = 8_388_607f
        private val DIRECT_RATE_CANDIDATES = listOf(44_100, 48_000, 88_200, 96_000, 176_400, 192_000, 352_800, 384_000)
        private val BLUETOOTH_RATE_CANDIDATES = listOf(44_100, 48_000)
        private val BLUETOOTH_TYPES = setOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER, AudioDeviceInfo.TYPE_BLE_BROADCAST)
        private val WIRED_TYPES = setOf(AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_LINE_DIGITAL)
    }
}
