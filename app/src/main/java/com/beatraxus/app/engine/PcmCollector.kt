package com.beatraxus.app.engine

/**
 * Collects decoded PCM for analysis: at most [maxFrames] frames, downmixed to stereo (or kept mono), as one
 * interleaved float array. Pure Kotlin so it can be unit-tested without a decoder.
 */
internal class PcmCollector(private val maxFrames: Int) : DecoderSink {
    var sampleRate = 44100
        private set
    var isDoP = false
        private set

    /** Channels of the collected output: 1 for mono sources, otherwise 2. */
    var outChannels = 2
        private set
    var frames = 0
        private set

    private var srcChannels = 2
    private var buf = FloatArray(0)
    private val carry = FloatArray(MAX_SRC_CHANNELS)
    private var carryCount = 0

    fun isFull() = frames >= maxFrames

    /** The native analyzer needs a few thousand frames to say anything meaningful. */
    fun hasEnough() = frames >= MIN_FRAMES && !isDoP

    /** Exactly [frames] x [outChannels] samples. */
    fun samples(): FloatArray = buf.copyOf(frames * outChannels)

    override suspend fun configure(format: PcmAudioFormat) {
        sampleRate = if (format.sampleRate > 0) format.sampleRate else 44100
        srcChannels = format.channels.coerceIn(1, MAX_SRC_CHANNELS)
        outChannels = if (srcChannels == 1) 1 else 2
        isDoP = format.isDoP
        carryCount = 0
    }

    override suspend fun write(data: FloatArray, sampleCount: Int) {
        if (isDoP) return // DSD-over-PCM is not audio samples; analysing it would be garbage
        val n = sampleCount.coerceIn(0, data.size)
        val frame = FloatArray(srcChannels)
        var i = 0
        while (i < n && frames < maxFrames) {
            // Finish a frame left over from the previous (partial) write first.
            if (carryCount > 0) {
                while (carryCount < srcChannels && i < n) carry[carryCount++] = data[i++]
                if (carryCount < srcChannels) return
                System.arraycopy(carry, 0, frame, 0, srcChannels)
                carryCount = 0
                push(frame)
                continue
            }
            if (n - i < srcChannels) {
                while (i < n) carry[carryCount++] = data[i++]
                return
            }
            System.arraycopy(data, i, frame, 0, srcChannels)
            i += srcChannels
            push(frame)
        }
    }

    private fun push(f: FloatArray) {
        val need = (frames + 1) * outChannels
        if (need > buf.size) buf = buf.copyOf(maxOf(need, minOf(buf.size * 2, maxFrames * outChannels)).coerceAtLeast(1 shl 16))
        val base = frames * outChannels
        if (outChannels == 1) {
            buf[base] = f[0]
        } else {
            val (l, r) = downmix(f, srcChannels)
            buf[base] = l
            buf[base + 1] = r
        }
        frames++
    }

    companion object {
        const val MIN_FRAMES = 4096
        const val MAX_SRC_CHANNELS = 8
        private const val C = 0.70710677f

        /** Stereo passes through; 3+ channels fold to stereo (FL FR FC LFE BL BR [SL SR]), scaled so peaks cannot exceed 1. */
        fun downmix(f: FloatArray, channels: Int): Pair<Float, Float> = when {
            channels <= 2 -> f[0] to (if (channels == 2) f[1] else f[0])
            channels == 3 -> ((f[0] + C * f[2]) / (1f + C)) to ((f[1] + C * f[2]) / (1f + C))
            channels < 6 -> f[0] to f[1] // quad / 5.0: front pair carries the content
            channels < 8 -> {
                val k = 1f + 2f * C
                ((f[0] + C * f[2] + C * f[4]) / k) to ((f[1] + C * f[2] + C * f[5]) / k)
            }
            else -> {
                val k = 1f + 3f * C
                ((f[0] + C * f[2] + C * f[4] + C * f[6]) / k) to ((f[1] + C * f[2] + C * f[5] + C * f[7]) / k)
            }
        }
    }
}
