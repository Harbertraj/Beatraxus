package com.beatflowy.app.engine

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import kotlin.math.roundToInt

class AudioTrackOutput(
    context: Context
) : AudioOutput {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var audioTrack: AudioTrack? = null
    private var sampleRate = 44_100
    private var targetSampleRate = 44_100
    private var channels = 2
    private var totalFramesWritten = 0L
    private var playbackHeadWraps = 0L
    private var lastPlaybackHeadPosition = 0
    private var selectedMode = OutputMode.STANDARD_AUDIO_TRACK
    private var activeMode = OutputMode.STANDARD_AUDIO_TRACK
    private var outputDeviceName = OutputDeviceType.SPEAKER.displayName
    private var preferredDevice: AudioDeviceInfo? = null
    private var currentEncoding = AudioFormat.ENCODING_PCM_FLOAT
    private var currentBytesPerSample = 4
    private var supportedDirectRates: List<Int> = emptyList()

    fun setOutputMode(mode: OutputMode) {
        selectedMode = mode
    }

    fun selectedOutputMode(): OutputMode = selectedMode

    fun refreshRouteState(): OutputRouteState {
        val device = resolvePreferredOutputDevice()
        preferredDevice = device
        outputDeviceName = deviceTypeLabel(device)
        supportedDirectRates = detectDirectRates()
        val maxDirectRate = supportedDirectRates.maxOrNull() ?: 48_000
        val hiResSupported = supportedDirectRates.any { it > 48_000 }
        val summary = if (hiResSupported) {
            "Direct PCM available up to ${formatRate(maxDirectRate)} on $outputDeviceName"
        } else {
            "Direct hi-res not available on $outputDeviceName"
        }
        return OutputRouteState(
            selectedMode = selectedMode,
            activeMode = if (selectedMode == OutputMode.HI_RES_DIRECT && hiResSupported) {
                OutputMode.HI_RES_DIRECT
            } else {
                OutputMode.STANDARD_AUDIO_TRACK
            },
            outputDevice = outputDeviceName,
            hiResDirectSupported = hiResSupported,
            capabilitySummary = summary,
            maxSupportedSampleRate = maxDirectRate
        )
    }

    override fun init(sampleRate: Int, channels: Int, bitDepth: Int): Boolean {
        refreshRouteState()
        this.sampleRate = if (targetSampleRate > 0) targetSampleRate else sampleRate
        this.channels = channels
        val channelConfig = when (channels) {
            1 -> AudioFormat.CHANNEL_OUT_MONO
            2 -> AudioFormat.CHANNEL_OUT_STEREO
            else -> AudioFormat.CHANNEL_OUT_STEREO
        }

        val directEncoding = chooseDirectEncoding(bitDepth)
        val directSupported = selectedMode == OutputMode.HI_RES_DIRECT &&
            isDirectPlaybackSupported(this.sampleRate, channelConfig, directEncoding)

        currentEncoding = if (directSupported) directEncoding else AudioFormat.ENCODING_PCM_FLOAT
        currentBytesPerSample = bytesPerSample(currentEncoding)
        activeMode = if (directSupported) OutputMode.HI_RES_DIRECT else OutputMode.STANDARD_AUDIO_TRACK

        val minBuffer = AudioTrack.getMinBufferSize(this.sampleRate, channelConfig, currentEncoding)
        val bufferSize = maxOf(minBuffer * 4, this.sampleRate * channels * currentBytesPerSample / 5)
        if (bufferSize <= 0) return false

        try {
            audioTrack?.let {
                it.stop()
                it.release()
            }
            val builder = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(currentEncoding)
                        .setSampleRate(this.sampleRate)
                        .setChannelMask(channelConfig)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)

            if (activeMode == OutputMode.STANDARD_AUDIO_TRACK) {
                builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            }

            audioTrack = builder.build()
            if (preferredDevice != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                audioTrack?.setPreferredDevice(preferredDevice)
            }
            totalFramesWritten = 0L
            playbackHeadWraps = 0L
            lastPlaybackHeadPosition = 0
        } catch (_: Exception) {
            return false
        }

        return audioTrack?.state == AudioTrack.STATE_INITIALIZED
    }

    override fun start() {
        try {
            audioTrack?.play()
        } catch (_: Exception) {
        }
    }

    override fun stop() {
        try {
            audioTrack?.pause()
            audioTrack?.flush()
            totalFramesWritten = 0L
            playbackHeadWraps = 0L
            lastPlaybackHeadPosition = 0
        } catch (_: Exception) {
        }
    }

    override fun flush() {
        try {
            audioTrack?.flush()
            totalFramesWritten = 0L
            playbackHeadWraps = 0L
            lastPlaybackHeadPosition = 0
        } catch (_: Exception) {
        }
    }

    override fun release() {
        audioTrack?.let {
            try {
                it.stop()
                it.release()
            } catch (_: Exception) {
            }
        }
        audioTrack = null
    }

    override fun write(data: FloatArray, offsetInSamples: Int, frameCount: Int): Int {
        val track = audioTrack ?: return 0
        val sampleCount = frameCount * channels
        return try {
            val writtenFrames = when (currentEncoding) {
                AudioFormat.ENCODING_PCM_16BIT -> {
                    val buffer = toPcm16(data, offsetInSamples, sampleCount)
                    val writtenBytes = track.write(buffer, 0, buffer.size, AudioTrack.WRITE_BLOCKING)
                    if (writtenBytes > 0) writtenBytes / (channels * currentBytesPerSample) else writtenBytes
                }

                AudioFormat.ENCODING_PCM_24BIT_PACKED -> {
                    val buffer = toPcm24(data, offsetInSamples, sampleCount)
                    val writtenBytes = track.write(buffer, 0, buffer.size, AudioTrack.WRITE_BLOCKING)
                    if (writtenBytes > 0) writtenBytes / (channels * currentBytesPerSample) else writtenBytes
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
        targetSampleRate = sampleRate.coerceIn(8_000, 192_000)
    }

    override fun outputSampleRate(): Int = sampleRate

    override fun outputPathLabel(): String {
        return if (activeMode == OutputMode.HI_RES_DIRECT) {
            "Hi-Res Direct"
        } else {
            "AudioTrack"
        }
    }

    override fun outputDeviceLabel(): String = outputDeviceName

    override fun estimatedLatencyMs(): Int {
        val queuedFrames = (totalFramesWritten - playbackPositionFrames()).coerceAtLeast(0L)
        if (sampleRate <= 0) return 0
        return ((queuedFrames * 1000L) / sampleRate).toInt()
    }

    private fun chooseDirectEncoding(bitDepth: Int): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && bitDepth > 16) {
            AudioFormat.ENCODING_PCM_24BIT_PACKED
        } else {
            AudioFormat.ENCODING_PCM_16BIT
        }
    }

    private fun detectDirectRates(): List<Int> {
        val channelMask = AudioFormat.CHANNEL_OUT_STEREO
        return DIRECT_RATE_CANDIDATES.filter { rate ->
            isDirectPlaybackSupported(rate, channelMask, AudioFormat.ENCODING_PCM_24BIT_PACKED) ||
                isDirectPlaybackSupported(rate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        }
    }

    private fun isDirectPlaybackSupported(
        sampleRate: Int,
        channelMask: Int,
        encoding: Int
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        return runCatching {
            AudioTrack.isDirectPlaybackSupported(
                AudioFormat.Builder()
                    .setEncoding(encoding)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelMask)
                    .build(),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
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
            else -> 4
        }
    }

    private fun toPcm16(data: FloatArray, offset: Int, sampleCount: Int): ByteArray {
        val buffer = ByteArray(sampleCount * 2)
        var inIndex = offset
        var outIndex = 0
        repeat(sampleCount) {
            val sample = (data[inIndex++].coerceIn(-1f, 1f) * Short.MAX_VALUE).roundToInt().toShort()
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
            val sample = (data[inIndex++].coerceIn(-1f, 1f) * PCM_24_MAX).roundToInt()
            buffer[outIndex++] = (sample and 0xFF).toByte()
            buffer[outIndex++] = ((sample shr 8) and 0xFF).toByte()
            buffer[outIndex++] = ((sample shr 16) and 0xFF).toByte()
        }
        return buffer
    }

    private fun formatRate(sampleRate: Int): String {
        return if (sampleRate % 1000 == 0) {
            "${sampleRate / 1000} kHz"
        } else {
            "${sampleRate / 1000f} kHz"
        }
    }

    companion object {
        private const val PCM_24_MAX = 8_388_607f
        private val DIRECT_RATE_CANDIDATES = listOf(44_100, 48_000, 88_200, 96_000, 176_400, 192_000)
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
