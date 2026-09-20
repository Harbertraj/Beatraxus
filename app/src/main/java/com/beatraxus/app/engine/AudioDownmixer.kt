package com.beatraxus.app.engine

object AudioDownmixer {

    /**
     * Downmixes interleaved float PCM data from [srcChannels] to [targetChannels].
     * Returns the total number of samples written to [outputBuffer].
     */
    fun downmixFloat(
        input: FloatArray,
        inputSampleCount: Int,
        srcChannels: Int,
        targetChannels: Int,
        outputBuffer: FloatArray
    ): Int {
        if (srcChannels == targetChannels) {
            System.arraycopy(input, 0, outputBuffer, 0, inputSampleCount)
            return inputSampleCount
        }

        val frames = inputSampleCount / srcChannels
        var outIdx = 0

        if (srcChannels == 6 && targetChannels == 2) {
            // Standard 5.1 -> Stereo ITU-R downmix with scaling to prevent clipping
            val scale = 0.70710678f
            val coef = 0.70710678f
            for (i in 0 until frames) {
                val inIdx = i * 6
                val l = input[inIdx]
                val r = input[inIdx + 1]
                val c = input[inIdx + 2]
                // inIdx + 3 is LFE
                val sl = input[inIdx + 4]
                val sr = input[inIdx + 5]

                outputBuffer[outIdx++] = (l + c * coef + sl * coef) * scale
                outputBuffer[outIdx++] = (r + c * coef + sr * coef) * scale
            }
        } else if (srcChannels == 1 && targetChannels == 2) {
            // Mono -> Stereo
            for (i in 0 until frames) {
                val sample = input[i]
                outputBuffer[outIdx++] = sample
                outputBuffer[outIdx++] = sample
            }
        } else if (srcChannels == 2 && targetChannels == 1) {
            // Stereo -> Mono
            for (i in 0 until frames) {
                val inIdx = i * 2
                outputBuffer[outIdx++] = 0.5f * (input[inIdx] + input[inIdx + 1])
            }
        } else if (targetChannels == 2) {
            // General N-channel -> Stereo fallback
            val scale = 1.0f / (srcChannels / 2f).coerceAtLeast(1f)
            for (i in 0 until frames) {
                val inIdx = i * srcChannels
                var sumL = 0f
                var sumR = 0f
                for (c in 0 until srcChannels) {
                    if (c % 2 == 0) sumL += input[inIdx + c]
                    else sumR += input[inIdx + c]
                }
                outputBuffer[outIdx++] = sumL * scale
                outputBuffer[outIdx++] = sumR * scale
            }
        } else {
            // Fallback for other channel mappings
            for (i in 0 until frames) {
                val inIdx = i * srcChannels
                for (c in 0 until targetChannels) {
                    outputBuffer[outIdx++] = if (c < srcChannels) input[inIdx + c] else 0f
                }
            }
        }

        return outIdx
    }
}
