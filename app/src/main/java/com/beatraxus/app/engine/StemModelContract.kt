package com.beatraxus.app.engine

/**
 * Checks of the ONNX tensor contract that need no ONNX Runtime, so they can be tested on the JVM.
 * The shapes come from the loaded session (never assumed); a mismatch fails cleanly with a message.
 */
object StemModelContract {
    /** htdemucs_6s waveform model: one 7.8 s stereo segment in, six stereo sources out. */
    const val HTDEMUCS_SEGMENT_SAMPLES = 343_980
    const val HTDEMUCS_SOURCES = 6

    /** More non-finite model outputs than this share of one chunk means the model output is garbage. */
    const val MAX_BAD_RATIO = 0.05

    /** True when [bad] non-finite values out of [total] is too many to trust the chunk (more than 5 %). */
    fun tooManyBad(bad: Long, total: Long): Boolean = bad > total * MAX_BAD_RATIO

    /** A dimension the model leaves dynamic is reported as -1 by ONNX Runtime and is accepted. */
    private fun dimMatches(actual: Long, expected: Long) = actual == expected || actual < 0

    private fun shapeMatches(actual: LongArray, expected: LongArray): Boolean =
        actual.size == expected.size && actual.indices.all { dimMatches(actual[it], expected[it]) }

    /** Null when the shapes are the htdemucs_6s ones, else why not. */
    fun verifyHtDemucs6s(inputShape: LongArray?, outputShape: LongArray?): String? {
        val expectedIn = longArrayOf(1, 2, HTDEMUCS_SEGMENT_SAMPLES.toLong())
        val expectedOut = longArrayOf(1, HTDEMUCS_SOURCES.toLong(), 2, HTDEMUCS_SEGMENT_SAMPLES.toLong())
        if (inputShape == null) return "model input is not a float tensor"
        if (outputShape == null) return "model output is not a float tensor"
        if (!shapeMatches(inputShape, expectedIn)) {
            return "model input shape ${inputShape.contentToString()} (expected ${expectedIn.contentToString()})"
        }
        if (!shapeMatches(outputShape, expectedOut)) {
            return "model output shape ${outputShape.contentToString()} (expected ${expectedOut.contentToString()}; " +
                "this build needs the 6-source htdemucs_6s model)"
        }
        return null
    }
}
