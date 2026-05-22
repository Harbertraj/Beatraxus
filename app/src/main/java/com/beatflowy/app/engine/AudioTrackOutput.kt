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
    private var playbackHeadWraps = 0L
    private var lastPlaybackHeadPosition = 0
    private var playbackHeadOffset = 0L
    private var selectedMode = OutputMode.AAUDIO
    private var activeMode = OutputMode.AAUDIO
    private var outputDeviceName = OutputDeviceType.SPEAKER.displayName
    private var preferredDevice: AudioDeviceInfo? = null
    private var currentEncoding = AudioFormat.ENCODING_PCM_FLOAT
    private var currentBytesPerSample = 4
    private var supportedDirectRates: List<Int> = emptyList()
    private var dvcEnabled = true
    private var dvcMode = DvcMode.DAC
    private var dvcLevel = 1f
    private var ditherState = 0x1234ABCD
    private var lastThreadId = -1L

    private val isMtkDevice = Build.HARDWARE.lowercase().contains("mt") || 
                             Build.BOARD.lowercase().contains("mt") ||
                             Build.MANUFACTURER.lowercase().contains("mediatek")

    fun setOutputMode(mode: OutputMode) {
        synchronized(stateLock) {
            selectedMode = mode
        }
    }

    fun selectedOutputMode(): OutputMode = synchronized(stateLock) { selectedMode }

    fun refreshRouteState(): OutputRouteState = synchronized(stateLock) {
        val device = resolvePreferredOutputDevice()
        preferredDevice = device
        outputDeviceName = deviceTypeLabel(device)
        supportedDirectRates = detectDirectRates()
        val maxDirectRate = supportedDirectRates.maxOrNull() ?: 48_000
        val hiResSupported = supportedDirectRates.any { it > 48_000 }
        val summary = if (hiResSupported) {
            "MTK HiFi / Direct PCM available on $outputDeviceName"
        } else {
            "Direct hi-res not available on $outputDeviceName"
        }
        return OutputRouteState(
            selectedMode = selectedMode,
            activeMode = if (selectedMode == OutputMode.HI_RES && hiResSupported) {
                OutputMode.HI_RES
            } else {
                OutputMode.AAUDIO
            },
            outputDevice = outputDeviceName,
            hiResDirectSupported = hiResSupported,
            capabilitySummary = summary,
            maxSupportedSampleRate = maxDirectRate
        )
    }

    override fun init(sampleRate: Int, channels: Int, bitDepth: Int): Boolean = synchronized(stateLock) {
        refreshRouteState()

        val channelConfig = when (channels) {
            1 -> AudioFormat.CHANNEL_OUT_MONO
            2 -> AudioFormat.CHANNEL_OUT_STEREO
            else -> AudioFormat.CHANNEL_OUT_STEREO
        }

        activeMode = if (selectedMode == OutputMode.HI_RES) {
            val supportedEncodings = listOf(
                AudioFormat.ENCODING_PCM_FLOAT,
                AudioFormat.ENCODING_PCM_32BIT,
                AudioFormat.ENCODING_PCM_24BIT_PACKED,
                AudioFormat.ENCODING_PCM_16BIT
            ).filter { enc ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    isDirectPlaybackSupported(sampleRate, channelConfig, enc)
                } else true // Assume supported on older if user forced HI_RES
            }

            // For MTK devices, we force HI_RES mode if selected, as API detection often fails
            if (supportedEncodings.isNotEmpty() || sampleRate > 48000 || isMtkDevice) {
                OutputMode.HI_RES
            } else {
                OutputMode.AAUDIO
            }
        } else {
            OutputMode.AAUDIO
        }

        if (activeMode == OutputMode.AAUDIO) {
            // AAudio path: use hardware native rate to avoid Android SRC
            this.sampleRate = getHardwareSampleRate()
            currentEncoding = AudioFormat.ENCODING_PCM_FLOAT
        } else {
            // MTK HiFi path: use the exact target rate requested by the DSP pipeline
            this.sampleRate = resolveSupportedSampleRate(if (targetSampleRate > 0) targetSampleRate else sampleRate)
            currentEncoding = resolveBestEncoding(bitDepth, channelConfig)
        }

        this.channels = channels
        currentBytesPerSample = bytesPerSample(currentEncoding)

        val minBuffer = AudioTrack.getMinBufferSize(this.sampleRate, channelConfig, currentEncoding)
        val bufferSize = minBuffer * 8
        if (bufferSize <= 0) return false

        try {
            val oldTrack = audioTrack
            audioTrack = null // Disconnect immediately

            oldTrack?.let {
                try {
                    it.pause()
                    it.flush()
                    it.stop()
                    it.release()
                } catch (e: Exception) {
                    Log.e("AudioTrackOutput", "Error releasing old track", e)
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

            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(audioAttributes)
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_NONE)
                .build()

            if (audioTrack?.state != AudioTrack.STATE_INITIALIZED) {
                audioTrack?.release()
                audioTrack = null
                return false
            }

            if (preferredDevice != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                audioTrack?.setPreferredDevice(preferredDevice)
            }
            totalFramesWritten = 0L
            playbackHeadWraps = 0L
            lastPlaybackHeadPosition = 0
            playbackHeadOffset = 0L
        } catch (e: Exception) {
            Log.e("AudioTrackOutput", "Init failed", e)
            return false
        }

        return audioTrack?.state == AudioTrack.STATE_INITIALIZED
    }

    override fun start() {
        val track = audioTrack ?: return
        try {
            applyTrackVolume()
            track.play()
        } catch (_: Exception) {}
    }

    override fun pause() {
        val track = audioTrack
        try {
            track?.pause()
        } catch (_: Exception) {}
    }

    override fun stop() {
        val track = audioTrack
        try {
            track?.let {
                if (it.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    it.pause()
                    it.flush()
                }
            }
        } catch (_: Exception) {}
        totalFramesWritten = 0L
        playbackHeadWraps = 0L
        lastPlaybackHeadPosition = 0
    }

    override fun flush() {
        val track = audioTrack
        try {
            track?.let {
                val wasPlaying = it.playState == AudioTrack.PLAYSTATE_PLAYING
                it.pause()
                it.flush()
                playbackHeadOffset = getAbsolutePlaybackHeadPosition()
                totalFramesWritten = 0L
                // Resume if track was playing before flush
                if (wasPlaying && it.state == AudioTrack.STATE_INITIALIZED) {
                    it.play()
                }
            }
        } catch (_: Exception) {}
    }

    override fun release() {
        synchronized(stateLock) {
            val track = audioTrack
            audioTrack = null
            track?.let {
                try {
                    it.stop()
                    it.release()
                } catch (_: Exception) {}
            }
        }
    }

    override fun write(data: FloatArray, offsetInSamples: Int, frameCount: Int): Int {
        if (lastThreadId != Thread.currentThread().id) {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            lastThreadId = Thread.currentThread().id
        }
        val track = audioTrack ?: return 0
        val sampleCount = frameCount * channels
        return try {
            val writtenFrames = when (currentEncoding) {
                AudioFormat.ENCODING_PCM_16BIT -> {
                    val buffer = toPcm16(data, offsetInSamples, sampleCount)
                    val writtenBytes = track.write(buffer, 0, buffer.size, AudioTrack.WRITE_BLOCKING)
                    if (writtenBytes > 0) writtenBytes / (channels * 2) else writtenBytes
                }

                AudioFormat.ENCODING_PCM_24BIT_PACKED -> {
                    val buffer = toPcm24(data, offsetInSamples, sampleCount)
                    val writtenBytes = track.write(buffer, 0, buffer.size, AudioTrack.WRITE_BLOCKING)
                    if (writtenBytes > 0) writtenBytes / (channels * 3) else writtenBytes
                }

                AudioFormat.ENCODING_PCM_32BIT -> {
                    val buffer = toPcm32(data, offsetInSamples, sampleCount)
                    val writtenBytes = track.write(buffer, 0, buffer.size, AudioTrack.WRITE_BLOCKING)
                    if (writtenBytes > 0) writtenBytes / (channels * 4) else writtenBytes
                }

                else -> {
                    val writtenSamples = track.write(data, offsetInSamples, sampleCount, AudioTrack.WRITE_BLOCKING)
                    if (writtenSamples > 0) writtenSamples / channels else writtenSamples
                }
            }
            if (writtenFrames > 0) {
                totalFramesWritten += writtenFrames.toLong()
            }
            writtenFrames
        } catch (_: Exception) {
            0
        }
    }

    override fun playbackPositionFrames(): Long {
        return (getAbsolutePlaybackHeadPosition() - playbackHeadOffset).coerceAtLeast(0L)
    }

    override fun totalFramesWritten(): Long = totalFramesWritten

    private fun getAbsolutePlaybackHeadPosition(): Long {
        val track = audioTrack ?: return 0L
        return try {
            val head = track.playbackHeadPosition
            if (head < lastPlaybackHeadPosition) {
                playbackHeadWraps++
            }
            lastPlaybackHeadPosition = head
            (playbackHeadWraps shl 32) + (head.toLong() and 0xFFFFFFFFL)
        } catch (_: Exception) {
            0L
        }
    }

    override fun setTargetSampleRate(sampleRate: Int) {
        synchronized(stateLock) {
            targetSampleRate = sampleRate
        }
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
        track.setVolume(1.0f)
    }

    override fun setSampleFormat(format: SampleFormat) {
        synchronized(stateLock) {
            this.sampleFormat = format
        }
    }

    /**
     * Returns the actual initialized sample rate of the AudioTrack.
     *
     * Previously, this re-read the hardware property in AAudio mode, which returned a stale
     * value during the window between mode-switch (AAudio <-> MTK HiFi) and the next init().
     * Now we always return [sampleRate], which is set correctly by init() for both modes:
     *   - AAudio:    set to getHardwareSampleRate()
     *   - MTK HiFi:  set to resolveSupportedSampleRate(targetSampleRate)
     *
     * This makes resolveTargetSampleRate() in AudioEngine see the right output rate immediately
     * after a mode toggle, allowing the DSP resampler to update without a full pipeline restart.
     */
    override fun outputSampleRate(): Int = synchronized(stateLock) { sampleRate }

    override fun outputBitDepth(): Int = synchronized(stateLock) { currentBytesPerSample * 8 }

    override fun outputPathLabel(): String = synchronized(stateLock) {
        if (activeMode == OutputMode.HI_RES) "MTK HiFi" else "AAudio"
    }

    override fun outputDeviceLabel(): String = synchronized(stateLock) { outputDeviceName }

    override fun estimatedLatencyMs(): Int {
        val queuedFrames = (totalFramesWritten - playbackPositionFrames()).coerceAtLeast(0L)
        val rate = synchronized(stateLock) { sampleRate }
        if (rate <= 0) return 0
        return ((queuedFrames * 1000L) / rate).toInt()
    }

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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && (isDirectPlaybackSupported(sampleRate, channelConfig, AudioFormat.ENCODING_PCM_32BIT) || isMtkDevice)) {
                return AudioFormat.ENCODING_PCM_32BIT
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && (isDirectPlaybackSupported(sampleRate, channelConfig, AudioFormat.ENCODING_PCM_24BIT_PACKED) || isMtkDevice)) {
                return AudioFormat.ENCODING_PCM_24BIT_PACKED
            }
            if (isDirectPlaybackSupported(sampleRate, channelConfig, AudioFormat.ENCODING_PCM_FLOAT) || isMtkDevice) {
                return AudioFormat.ENCODING_PCM_FLOAT
            }
            return AudioFormat.ENCODING_PCM_16BIT
        }

        return if (dvcEnabled || bitDepth > 16) {
            AudioFormat.ENCODING_PCM_FLOAT
        } else {
            AudioFormat.ENCODING_PCM_16BIT
        }
    }

    private fun resolveSupportedSampleRate(requestedRate: Int): Int {
        val device = preferredDevice ?: resolvePreferredOutputDevice()
        val isBluetooth = device?.type in BLUETOOTH_TYPES
        val isUsb = device?.type == AudioDeviceInfo.TYPE_USB_DEVICE || device?.type == AudioDeviceInfo.TYPE_USB_HEADSET

        if (isBluetooth) {
            return BLUETOOTH_RATE_CANDIDATES.minByOrNull { kotlin.math.abs(it - requestedRate) } ?: 48_000
        }

        if (selectedMode == OutputMode.HI_RES) {
            val directRates = detectDirectRates().sortedDescending()
            if (directRates.isNotEmpty()) {
                if (directRates.contains(requestedRate)) return requestedRate
                return directRates.firstOrNull { it == 192000 }
                    ?: directRates.firstOrNull { it == 176400 }
                    ?: directRates.firstOrNull { it == 96000 }
                    ?: directRates.firstOrNull { it == 88200 }
                    ?: directRates.first()
            }
        }

        if (isUsb) {
            val directRates = detectDirectRates()
            return directRates.minByOrNull { kotlin.math.abs(it - requestedRate) } ?: requestedRate
        }

        return requestedRate
    }

    private fun detectDirectRates(): List<Int> {
        val channelMask = AudioFormat.CHANNEL_OUT_STEREO
        val encodings = mutableListOf(
            AudioFormat.ENCODING_PCM_16BIT,
            AudioFormat.ENCODING_PCM_FLOAT
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) encodings.add(AudioFormat.ENCODING_PCM_24BIT_PACKED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) encodings.add(AudioFormat.ENCODING_PCM_32BIT)

        val isMtkDevice = Build.HARDWARE.lowercase().contains("mt") || 
                         Build.BOARD.lowercase().contains("mt") ||
                         Build.MANUFACTURER.lowercase().contains("mediatek")

        val rates = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val supported = DIRECT_RATE_CANDIDATES.filter { rate ->
                encodings.any { enc -> isDirectPlaybackSupported(rate, channelMask, enc) }
            }
            // If API reports nothing but we are in HI_RES mode OR it's a known MTK device, 
            // assume common hi-res rates are supported. Many MTK devices support hi-res 
            // via direct path but isDirectPlaybackSupported returns false.
            if (supported.isEmpty() && (selectedMode == OutputMode.HI_RES || isMtkDevice)) {
                DIRECT_RATE_CANDIDATES.filter { it <= 192000 }
            } else {
                supported
            }
        } else {
            // Older devices: assume standard rates at least
            if (isMtkDevice) DIRECT_RATE_CANDIDATES.filter { it <= 192000 }
            else DIRECT_RATE_CANDIDATES.filter { it <= 96000 }
        }
        
        return if (rates.isEmpty()) listOf(44100, 48000) else rates
    }

    private fun isDirectPlaybackSupported(
        sampleRate: Int,
        channelMask: Int,
        encoding: Int
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        return runCatching {
            val attrBuilder = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)

            AudioTrack.isDirectPlaybackSupported(
                AudioFormat.Builder()
                    .setEncoding(encoding)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelMask)
                    .build(),
                attrBuilder.build()
            )
        }.getOrDefault(false)
    }

    private fun resolvePreferredOutputDevice(): AudioDeviceInfo? {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
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
            AudioFormat.ENCODING_PCM_32BIT -> 4
            else -> 4 // Float
        }
    }

    private fun toPcm16(data: FloatArray, offset: Int, sampleCount: Int): ByteArray {
        val buffer = ByteArray(sampleCount * 2)
        var inIndex = offset
        var outIndex = 0
        repeat(sampleCount) {
            val sample = (data[inIndex++] * Short.MAX_VALUE + tpdfDither(1f)).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            buffer[outIndex++] = (sample.toInt() and 0xFF).toByte()
            buffer[outIndex++] = ((sample.toInt() shr 8) and 0xFF).toByte()
        }
        return buffer
    }

    private fun toPcm24(data: FloatArray, offset: Int, sampleCount: Int): ByteArray {
        val buffer = ByteArray(sampleCount * 3)
        var inIndex = offset
        var outIndex = 0
        repeat(sampleCount) {
            val sample = (data[inIndex++] * PCM_24_MAX + tpdfDither(256f)).roundToInt().coerceIn(-8388608, 8388607)
            buffer[outIndex++] = (sample and 0xFF).toByte()
            buffer[outIndex++] = ((sample shr 8) and 0xFF).toByte()
            buffer[outIndex++] = ((sample shr 16) and 0xFF).toByte()
        }
        return buffer
    }

    private fun toPcm32(data: FloatArray, offset: Int, sampleCount: Int): ByteArray {
        val buffer = ByteArray(sampleCount * 4)
        var inIndex = offset
        var outIndex = 0
        repeat(sampleCount) {
            val sample = (data[inIndex++] * Int.MAX_VALUE).roundToInt()
            buffer[outIndex++] = (sample and 0xFF).toByte()
            buffer[outIndex++] = ((sample shr 8) and 0xFF).toByte()
            buffer[outIndex++] = ((sample shr 16) and 0xFF).toByte()
            buffer[outIndex++] = ((sample shr 24) and 0xFF).toByte()
        }
        return buffer
    }

    private fun getHardwareSampleRate(): Int {
        val rateStr = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
        return rateStr?.toIntOrNull() ?: 48000
    }

    private fun shouldUseTrackVolume(): Boolean {
        val device = preferredDevice
        val bluetooth = device?.type in BLUETOOTH_TYPES
        return dvcEnabled && when (dvcMode) {
            DvcMode.DAC -> !bluetooth
            DvcMode.BLUETOOTH -> bluetooth
            DvcMode.SYSTEM -> false
        }
    }

    private fun tpdfDither(scale: Float): Float {
        ditherState = 1664525 * ditherState + 1013904223
        val a = ((ditherState ushr 1) and 0x7FFF) / 32767f
        ditherState = 1664525 * ditherState + 1013904223
        val b = ((ditherState ushr 1) and 0x7FFF) / 32767f
        return (a - b) / scale
    }

    companion object {
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