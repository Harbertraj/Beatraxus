package com.beatflowy.app.engine

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Process
import android.util.Log
import com.beatflowy.app.model.DvcMode
import com.beatflowy.app.model.OutputMode
import com.beatflowy.app.model.SampleFormat
import kotlin.math.roundToInt

class AudioTrackOutput(
    context: Context
) : AudioOutput {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
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
    private var currentEncoding = AudioFormat.ENCODING_PCM_FLOAT
    private var currentBytesPerSample = 4
    private var supportedDirectRates: List<Int> = emptyList()
    private var dvcEnabled = true
    private var dvcMode = DvcMode.DAC
    private var dvcLevel = 1f
    private var ditherState0 = 0x1234ABCD   // independent seed A
    private var ditherState1 = 0xDEADBEEF.toInt()  // independent seed B
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
    // 4096 frames × 2 channels × 4 bytes = 32KB max. Safe upper bound.
    private var pcm16Buffer = ByteArray(32_768 * 2)
    private var pcm24Buffer = ByteArray(32_768 * 3)
    private var pcm32Buffer = ByteArray(32_768 * 4)

    private val isMtkDevice = Build.HARDWARE.lowercase().contains("mt") ||
                             Build.BOARD.lowercase().contains("mt") ||
                             Build.MANUFACTURER.lowercase().contains("mediatek")

    // ── USB Direct Mode ────────────────────────────────────────────────────────

    override fun setUsbExclusiveMode(enabled: Boolean) {
        synchronized(stateLock) {
            if (usbExclusiveEnabled == enabled) return
            usbExclusiveEnabled = enabled
            Log.d(TAG, "USB Exclusive Mode: $enabled")
        }
        // Caller (AudioEngine.updateDspConfig) will trigger reconfigureOutput()
    }

    override fun setBitPerfectMode(enabled: Boolean) {
        synchronized(stateLock) {
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

    // ──────────────────────────────────────────────────────────────────────────

    fun setOutputMode(mode: OutputMode) {
        synchronized(stateLock) {
            selectedMode = mode
        }
    }

    fun selectedOutputMode(): OutputMode = synchronized(stateLock) { selectedMode }

    fun refreshRouteState(): OutputRouteState = synchronized(stateLock) {
        val usbDev = if (usbExclusiveEnabled) detectUsbDevice() else null
        detectedUsbDevice = usbDev

        val device = resolvePreferredOutputDevice()
        preferredDevice = device
        outputDeviceName = deviceTypeLabel(device)
        supportedDirectRates = detectDirectRates()
        val maxDirectRate = supportedDirectRates.maxOrNull() ?: 48_000
        val hiResSupported = supportedDirectRates.any { it > 48_000 }

        // USB capability snapshot
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

    override fun init(sampleRate: Int, channels: Int, bitDepth: Int, isDoP: Boolean): Boolean = synchronized(stateLock) {
        release()

        // Attempt MMAP exclusive first if requested
        if (mmapExclusiveRequested) {
            val mmap = MmapAudioOutput()
            // Bug 1 Fix: Select I32 format for DoP/DSD
            val format = if (isDoP) 4 else 2 // 4 = AAUDIO_FORMAT_PCM_I32, 2 = AAUDIO_FORMAT_PCM_FLOAT
            val mmapOk = mmap.init(
                sampleRate = sampleRate,
                channels = channels,
                requestedBufferFrames = mmapRequestedBufferFrames,
                format = format
            )
            if (mmapOk) {
                mmapOutput = mmap
                usingMmap = true
                this.sampleRate = mmap.outputSampleRate()
                this.channels = channels
                this.currentEncoding = AudioFormat.ENCODING_PCM_FLOAT
                this.currentBytesPerSample = 4
                
                totalFramesWritten = 0L
                playbackHeadWraps = 0L
                lastPlaybackHeadPosition = 0
                playbackHeadOffset = 0L

                Log.i(TAG, "MMAP Exclusive active: rate=${this.sampleRate} bufferFrames=${mmap.mmapActualBufferFrames()}")
                return true
            }
 else {
                Log.w(TAG, "MMAP Exclusive init failed — falling back to AudioTrack")
                mmap.release()
                usingMmap = false
            }
        }

        usingMmap = false
        mmapOutput = null

        refreshRouteState()

        val channelConfig = when (channels) {
            1 -> AudioFormat.CHANNEL_OUT_MONO
            2 -> AudioFormat.CHANNEL_OUT_STEREO
            4 -> AudioFormat.CHANNEL_OUT_QUAD
            6 -> AudioFormat.CHANNEL_OUT_5POINT1
            8 -> AudioFormat.CHANNEL_OUT_7POINT1
            else -> if (channels > 2) AudioFormat.CHANNEL_OUT_5POINT1 else AudioFormat.CHANNEL_OUT_STEREO
        }

        // ── Resolve active mode ────────────────────────────────────────────────
        // USB Exclusive mode bypasses MTK HiFi and AAudio mode selection logic
        // It uses AAUDIO path with setPreferredDevice() pinned to USB DAC
        val usbDev = detectedUsbDevice
        val isUsbExclusiveActive = usbExclusiveEnabled && usbDev != null

        activeMode = if (isUsbExclusiveActive) {
            // USB path: use AAudio format pipeline, pin device via setPreferredDevice()
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

        // ── Resolve sample rate ────────────────────────────────────────────────
        this.sampleRate = when {
            isUsbExclusiveActive -> resolveUsbSampleRate(
                requested = if (targetSampleRate > 0) targetSampleRate else sampleRate,
                device = usbDev
            )
            activeMode == OutputMode.AAUDIO -> getHardwareSampleRate()
            else -> resolveSupportedSampleRate(if (targetSampleRate > 0) targetSampleRate else sampleRate)
        }

        // ── Resolve encoding ───────────────────────────────────────────────────
        currentEncoding = when {
            isUsbExclusiveActive -> resolveUsbEncoding(bitDepth, usbDev, channelConfig)
            bitPerfectEnabled -> resolveBestEncodingForBitDepth(bitDepth, channelConfig)
            activeMode == OutputMode.AAUDIO -> AudioFormat.ENCODING_PCM_FLOAT
            else -> resolveBestEncoding(bitDepth, channelConfig)
        }

        this.channels = channels
        currentBytesPerSample = bytesPerSample(currentEncoding)

        // ── Grow pre-allocated buffers if needed ───────────────────────────────
        val maxSamplesPerBatch = 4_096 * channels
        val needed16 = maxSamplesPerBatch * 2
        val needed24 = maxSamplesPerBatch * 3
        val needed32 = maxSamplesPerBatch * 4
        if (pcm16Buffer.size < needed16) pcm16Buffer = ByteArray(needed16)
        if (pcm24Buffer.size < needed24) pcm24Buffer = ByteArray(needed24)
        if (pcm32Buffer.size < needed32) pcm32Buffer = ByteArray(needed32)

        val minBuffer = AudioTrack.getMinBufferSize(this.sampleRate, channelConfig, currentEncoding)
        val bufferSize = if (this.bufferFrames > 0) {
            this.bufferFrames * channels * currentBytesPerSample * this.bufferCount
        } else {
            minBuffer * 8
        }
        if (bufferSize <= 0) return false

        try {
            val oldTrack = audioTrack
            audioTrack = null

            oldTrack?.let {
                try {
                    it.pause(); it.flush(); it.stop(); it.release()
                } catch (e: Exception) {
                    Log.e(TAG, "Error releasing old track", e)
                }
            }

            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()

            val audioFormat = AudioFormat.Builder()
                .setEncoding(currentEncoding)
                .setSampleRate(this.sampleRate)
                .setChannelMask(channelConfig)
                .build()

            // Use LOW_LATENCY when USB DAC is active for better scheduling
            val performanceMode = if (isUsbExclusiveActive) {
                AudioTrack.PERFORMANCE_MODE_LOW_LATENCY
            } else {
                AudioTrack.PERFORMANCE_MODE_NONE
            }

            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(audioAttributes)
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(performanceMode)
                .build()

            if (audioTrack?.state != AudioTrack.STATE_INITIALIZED) {
                audioTrack?.release()
                audioTrack = null
                // Fallback to stereo if multi-channel initialization failed
                if (channels > 2) {
                    Log.w(TAG, "Multi-channel ($channels) initialization failed, falling back to stereo")
                    return init(sampleRate, 2, bitDepth, isDoP)
                }
                return false
            }

            // Pin to USB DAC device when USB exclusive mode is active
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val pinDevice = if (isUsbExclusiveActive) usbDev else preferredDevice
                if (pinDevice != null) {
                    audioTrack?.setPreferredDevice(pinDevice)
                }
            }

            totalFramesWritten = 0L
            playbackHeadWraps = 0L
            lastPlaybackHeadPosition = 0
            playbackHeadOffset = 0L

            Log.d(TAG, "AudioTrack init: rate=${this.sampleRate} enc=${encodingName(currentEncoding)} " +
                "usb=$isUsbExclusiveActive perf=$performanceMode")
        } catch (e: Exception) {
            Log.e(TAG, "Init failed", e)
            return false
        }

        return audioTrack?.state == AudioTrack.STATE_INITIALIZED
    }

    // ── Resolve USB-specific sample rate ──────────────────────────────────────
    private fun resolveUsbSampleRate(requested: Int, device: AudioDeviceInfo): Int {
        val rates = device.sampleRates.toList().ifEmpty {
            return requested
        }
        // Prefer exact match, then nearest supported rate
        return if (rates.contains(requested)) requested
        else rates.minByOrNull { kotlin.math.abs(it - requested) } ?: requested
    }

    // ── Resolve USB-specific encoding (best bit depth the DAC supports) ───────
    private fun resolveUsbEncoding(bitDepth: Int, device: AudioDeviceInfo, channelConfig: Int): Int {
        val supported = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.encodings.toList()
        } else {
            listOf(AudioFormat.ENCODING_PCM_16BIT, AudioFormat.ENCODING_PCM_FLOAT)
        }

        // Override via user sampleFormat setting if not AUTO
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

        // Auto: pick best encoding matching source bit depth
        return when {
            bitDepth >= 32 && supported.contains(AudioFormat.ENCODING_PCM_FLOAT) -> AudioFormat.ENCODING_PCM_FLOAT
            bitDepth >= 32 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                supported.contains(AudioFormat.ENCODING_PCM_32BIT) -> AudioFormat.ENCODING_PCM_32BIT
            bitDepth >= 24 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
                supported.contains(AudioFormat.ENCODING_PCM_24BIT_PACKED) -> AudioFormat.ENCODING_PCM_24BIT_PACKED
            supported.contains(AudioFormat.ENCODING_PCM_FLOAT) -> AudioFormat.ENCODING_PCM_FLOAT
            else -> AudioFormat.ENCODING_PCM_16BIT
        }
    }

    private fun resolveBestEncodingForBitDepth(bitDepth: Int, channelConfig: Int): Int {
        return when {
            bitDepth >= 24 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                isDirectPlaybackSupported(sampleRate, channelConfig, AudioFormat.ENCODING_PCM_32BIT) ->
                AudioFormat.ENCODING_PCM_32BIT
            bitDepth >= 24 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
                isDirectPlaybackSupported(sampleRate, channelConfig, AudioFormat.ENCODING_PCM_24BIT_PACKED) ->
                AudioFormat.ENCODING_PCM_24BIT_PACKED
            else ->
                AudioFormat.ENCODING_PCM_FLOAT
        }
    }

    // ─────────────────────────────────────────────────────────────────────────

    override fun start() {
        if (usingMmap) { mmapOutput?.start(); return }
        val track = audioTrack ?: return
        try {
            applyTrackVolume()
            track.play()
        } catch (_: Exception) {}
    }

    override fun pause() {
        if (usingMmap) { mmapOutput?.pause(); return }
        try { audioTrack?.pause() } catch (_: Exception) {}
    }

    override fun stop() {
        if (usingMmap) { mmapOutput?.stop(); return }
        try {
            audioTrack?.let {
                if (it.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    it.pause(); it.flush()
                }
            }
        } catch (_: Exception) {}
        totalFramesWritten = 0L
        playbackHeadWraps = 0L
        lastPlaybackHeadPosition = 0
    }

    override fun flush() {
        if (usingMmap) { mmapOutput?.flush(); return }
        try {
            audioTrack?.let {
                it.pause()
                it.flush()
                playbackHeadOffset = getAbsolutePlaybackHeadPosition()
                totalFramesWritten = 0L
                if (it.state == AudioTrack.STATE_INITIALIZED) it.play()
            }
        } catch (_: Exception) {}
    }

    override fun release() {
        mmapOutput?.release()
        mmapOutput = null
        usingMmap = false
        synchronized(stateLock) {
            val track = audioTrack
            audioTrack = null
            track?.let {
                try { it.stop(); it.release() } catch (_: Exception) {}
            }
        }
    }

    override fun write(data: FloatArray, offsetInSamples: Int, frameCount: Int): Int {
        if (lastThreadId != Thread.currentThread().id) {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            lastThreadId = Thread.currentThread().id
        }

        // Use a local copy of mmapOutput and audioTrack to avoid NullPointerException
        // or using a released object during concurrent init/release calls.
        val (mmap, track, isMmap) = synchronized(stateLock) {
            Triple(mmapOutput, audioTrack, usingMmap)
        }

        if (isMmap && mmap != null) {
            return mmap.write(data, offsetInSamples, frameCount)
        }
        
        if (track == null) return 0
        val sampleCount = frameCount * channels
        return try {
            val writtenFrames = when (currentEncoding) {
                AudioFormat.ENCODING_PCM_16BIT -> {
                    toPcm16InPlace(data, offsetInSamples, sampleCount)
                    val writtenBytes = track.write(pcm16Buffer, 0, sampleCount * 2, AudioTrack.WRITE_BLOCKING)
                    if (writtenBytes > 0) writtenBytes / (channels * 2) else writtenBytes
                }
                AudioFormat.ENCODING_PCM_24BIT_PACKED -> {
                    toPcm24InPlace(data, offsetInSamples, sampleCount)
                    val writtenBytes = track.write(pcm24Buffer, 0, sampleCount * 3, AudioTrack.WRITE_BLOCKING)
                    if (writtenBytes > 0) writtenBytes / (channels * 3) else writtenBytes
                }
                AudioFormat.ENCODING_PCM_32BIT -> {
                    toPcm32InPlace(data, offsetInSamples, sampleCount)
                    val writtenBytes = track.write(pcm32Buffer, 0, sampleCount * 4, AudioTrack.WRITE_BLOCKING)
                    if (writtenBytes > 0) writtenBytes / (channels * 4) else writtenBytes
                }
                else -> {
                    val writtenSamples = track.write(data, offsetInSamples, sampleCount, AudioTrack.WRITE_BLOCKING)
                    if (writtenSamples > 0) writtenSamples / channels else writtenSamples
                }
            }
            if (writtenFrames > 0) totalFramesWritten += writtenFrames.toLong()
            writtenFrames
        } catch (_: Exception) { 0 }
    }

    override fun writeInt(data: IntArray, offsetInSamples: Int, frameCount: Int): Int {
        if (lastThreadId != Thread.currentThread().id) {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            lastThreadId = Thread.currentThread().id
        }

        val (mmap, track, isMmap) = synchronized(stateLock) {
            Triple(mmapOutput, audioTrack, usingMmap)
        }

        if (isMmap && mmap != null) {
            return mmap.writeInt(data, offsetInSamples, frameCount)
        }

        if (track == null) return 0
        val sampleCount = frameCount * channels

        // For DoP, we expect the output format to be PCM_24 or PCM_32
        return try {
            val writtenFrames = when (currentEncoding) {
                AudioFormat.ENCODING_PCM_24BIT_PACKED -> {
                    // Pack IntArray (24-bit in 32-bit int) into ByteArray
                    var outIndex = 0
                    for (i in 0 until sampleCount) {
                        val sample = data[offsetInSamples + i]
                        pcm24Buffer[outIndex++] = (sample and 0xFF).toByte()
                        pcm24Buffer[outIndex++] = ((sample shr 8) and 0xFF).toByte()
                        pcm24Buffer[outIndex++] = ((sample shr 16) and 0xFF).toByte()
                    }
                    val writtenBytes = track.write(pcm24Buffer, 0, sampleCount * 3, AudioTrack.WRITE_BLOCKING)
                    if (writtenBytes > 0) writtenBytes / (channels * 3) else writtenBytes
                }
                AudioFormat.ENCODING_PCM_32BIT -> {
                    // Pack IntArray (32-bit) into ByteArray to avoid ambiguous/missing IntArray overload
                    var outIndex = 0
                    for (i in 0 until sampleCount) {
                        val sample = data[offsetInSamples + i]
                        pcm32Buffer[outIndex++] = (sample and 0xFF).toByte()
                        pcm32Buffer[outIndex++] = ((sample shr 8) and 0xFF).toByte()
                        pcm32Buffer[outIndex++] = ((sample shr 16) and 0xFF).toByte()
                        pcm32Buffer[outIndex++] = ((sample shr 24) and 0xFF).toByte()
                    }
                    val writtenBytes = track.write(pcm32Buffer, 0, sampleCount * 4, AudioTrack.WRITE_BLOCKING)
                    if (writtenBytes > 0) writtenBytes / (channels * 4) else writtenBytes
                }
                else -> 0 // DoP not supported on 16-bit or Float paths
            }
            if (writtenFrames > 0) totalFramesWritten += writtenFrames.toLong()
            writtenFrames
        } catch (_: Exception) { 0 }
    }

    override fun playbackPositionFrames(): Long {
        if (usingMmap) return mmapOutput?.playbackPositionFrames() ?: 0L
        return (getAbsolutePlaybackHeadPosition() - playbackHeadOffset).coerceAtLeast(0L)
    }

    override fun totalFramesWritten(): Long {
        if (usingMmap) return mmapOutput?.totalFramesWritten() ?: 0L
        return totalFramesWritten
    }

    private fun getAbsolutePlaybackHeadPosition(): Long {
        val track = audioTrack ?: return 0L
        return try {
            val head = track.playbackHeadPosition
            // Use synchronized access to prevent torn reads across coroutine threads
            synchronized(stateLock) {
                if (head < lastPlaybackHeadPosition) playbackHeadWraps++
                lastPlaybackHeadPosition = head
                (playbackHeadWraps shl 32) + (head.toLong() and 0xFFFFFFFFL)
            }
        } catch (_: Exception) { 0L }
    }

    override fun setTargetSampleRate(sampleRate: Int) {
        synchronized(stateLock) { targetSampleRate = sampleRate }
    }

    override fun setDvcState(enabled: Boolean, mode: String, level: Float) {
        synchronized(stateLock) {
            dvcEnabled = enabled
            dvcMode = DvcMode.entries.firstOrNull { it.name == mode } ?: DvcMode.DAC
            dvcLevel = level.coerceIn(0f, 1f)
            applyTrackVolume()
        }
    }

    private fun applyTrackVolume() {
        val track = audioTrack ?: return
        // Digital volume is already handled with high precision in the NativeDsp engine 
        // if DVC is enabled. We must avoid double-scaling here which causes "abnormal" volume levels.
        val volume = 1.0f 
        track.setVolume(volume)
    }

    override fun setSampleFormat(format: SampleFormat) {
        synchronized(stateLock) { this.sampleFormat = format }
    }

    override fun outputSampleRate(): Int = synchronized(stateLock) { sampleRate }
    override fun outputBitDepth(): Int = synchronized(stateLock) { currentBytesPerSample * 8 }

    override fun outputPathLabel(): String = synchronized(stateLock) {
        if (usingMmap) return "MMAP Exclusive"
        when {
            usbExclusiveEnabled && detectedUsbDevice != null -> "USB Direct"
            activeMode == OutputMode.HI_RES -> "MTK HiFi"
            else -> "AAudio"
        }
    }

    override fun outputDeviceLabel(): String = synchronized(stateLock) { outputDeviceName }

    override fun estimatedLatencyMs(): Int {
        if (usingMmap) return mmapOutput?.estimatedLatencyMs() ?: 0
        val queuedFrames = (totalFramesWritten - playbackPositionFrames()).coerceAtLeast(0L)
        val rate = synchronized(stateLock) { sampleRate }
        if (rate <= 0) return 0
        return ((queuedFrames * 1000L) / rate).toInt()
    }

    override fun setMmapExclusiveMode(enabled: Boolean, requestedBufferFrames: Int) {
        mmapExclusiveRequested = enabled
        mmapRequestedBufferFrames = requestedBufferFrames
    }

    override fun setBufferConfig(bufferFrames: Int, bufferCount: Int, postFadeFrames: Int) {
        this.bufferFrames = bufferFrames
        this.bufferCount = bufferCount
        this.postFadeFrames = postFadeFrames
        mmapOutput?.setBufferConfig(bufferFrames, bufferCount, postFadeFrames)
    }

    override fun isMmapActive(): Boolean = usingMmap

    override fun mmapActualBufferFrames(): Int = mmapOutput?.mmapActualBufferFrames() ?: 0

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
                
                // If Bit-Perfect is enabled, don't force upsampling to a "direct" rate.
                // It's better to use the requested rate and let AudioTrack handle it (or fail),
                // than to force a mismatch that causes speed issues if not handled by DSP.
                if (bitPerfectEnabled) return requestedRate

                return directRates.firstOrNull { it == 192000 }
                    ?: directRates.firstOrNull { it == 176400 }
                    ?: directRates.firstOrNull { it == 96000 }
                    ?: directRates.firstOrNull { it == 88200 }
                    ?: directRates.first()
            }
        }
        val isUsb = device?.type == AudioDeviceInfo.TYPE_USB_DEVICE || device?.type == AudioDeviceInfo.TYPE_USB_HEADSET
        if (isUsb) {
            val directRates = detectDirectRates()
            return directRates.minByOrNull { kotlin.math.abs(it - requestedRate) } ?: requestedRate
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
        // When USB exclusive mode is on, USB DAC takes highest priority
        if (usbExclusiveEnabled) {
            val usbDev = devices.firstOrNull {
                (it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET) &&
                it.sampleRates.isNotEmpty()
            }
            if (usbDev != null) return usbDev
        }
        // Normal priority: BT > USB > wired > speaker
        return devices.firstOrNull { it.type in BLUETOOTH_TYPES }
            ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET }
            ?: devices.firstOrNull { it.type in WIRED_TYPES }
            ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            ?: devices.firstOrNull()
    }

    private fun deviceTypeLabel(device: AudioDeviceInfo?): String {
        return when (device?.type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_BLE_BROADCAST -> OutputDeviceType.BLUETOOTH.displayName
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_HEADSET -> OutputDeviceType.USB_DAC.displayName
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_LINE_ANALOG,
            AudioDeviceInfo.TYPE_LINE_DIGITAL -> OutputDeviceType.WIRED.displayName
            AudioDeviceInfo.TYPE_HDMI,
            AudioDeviceInfo.TYPE_HDMI_ARC,
            AudioDeviceInfo.TYPE_HDMI_EARC -> OutputDeviceType.HDMI.displayName
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> OutputDeviceType.SPEAKER.displayName
            else -> OutputDeviceType.UNKNOWN.displayName
        }
    }

    private fun bytesPerSample(encoding: Int): Int {
        return when (encoding) {
            AudioFormat.ENCODING_PCM_16BIT -> 2
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
            AudioFormat.ENCODING_PCM_32BIT, AudioFormat.ENCODING_PCM_FLOAT -> 4
            else -> 4
        }
    }

    // ── Pre-allocated PCM conversion (NO ByteArray allocation on hot path) ────

    private fun toPcm16InPlace(data: FloatArray, offset: Int, sampleCount: Int) {
        var inIndex = offset
        var outIndex = 0
        repeat(sampleCount) {
            val sample = (data[inIndex++] * Short.MAX_VALUE + tpdfDither(1f))
                .roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            pcm16Buffer[outIndex++] = (sample.toInt() and 0xFF).toByte()
            pcm16Buffer[outIndex++] = ((sample.toInt() shr 8) and 0xFF).toByte()
        }
    }

    private fun toPcm24InPlace(data: FloatArray, offset: Int, sampleCount: Int) {
        var inIndex = offset
        var outIndex = 0
        repeat(sampleCount) {
            val sample = (data[inIndex++] * PCM_24_MAX + tpdfDither(256f))
                .roundToInt().coerceIn(-8_388_608, 8_388_607)
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

    private fun getHardwareSampleRate(): Int {
        val rateStr = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
        return rateStr?.toIntOrNull() ?: 48000
    }



    // ── True TPDF dither using two independent LCG generators ─────────────────
    private fun tpdfDither(scale: Float): Float {
        // Generator A
        ditherState0 = 1_664_525 * ditherState0 + 1_013_904_223
        val a = ((ditherState0 ushr 1) and 0x7FFF) / 32767f
        // Generator B — different multiplier + addend, truly independent
        ditherState1 = 22_695_477 * ditherState1 + 1
        val b = ((ditherState1 ushr 1) and 0x7FFF) / 32767f
        return (a - b) / scale
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
        private val BLUETOOTH_TYPES = setOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_BLE_BROADCAST
        )
        private val WIRED_TYPES = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_LINE_ANALOG,
            AudioDeviceInfo.TYPE_LINE_DIGITAL
        )
    }
}
