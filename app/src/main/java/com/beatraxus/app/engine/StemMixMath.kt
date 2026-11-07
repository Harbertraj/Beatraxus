package com.beatraxus.app.engine

/** Allocation-free helpers of the stem mixer stage, kept apart from Android so they can be tested on the JVM. */
object StemMixMath {

    /** True when no stem is audible (every gain is 0), i.e. the mix is silence. */
    fun allSilent(gains: FloatArray, stemCount: Int): Boolean {
        for (i in 0 until stemCount) if (gains[i] > 0f) return false
        return true
    }

    /**
     * Sums `gains[i] * stem_i` of the first [stemCount] stems into [mix] (interleaved stereo, [samples] values; it is
     * zeroed first). [readStem] fills the scratch array with stem i and returns false if it cannot be read; such a
     * stem is skipped. Stems with gain 0 are not read at all. Returns true if at least one stem was mixed.
     * Inline, so the lambda costs nothing on the audio thread.
     */
    inline fun mixInto(
        gains: FloatArray,
        stemCount: Int,
        samples: Int,
        mix: FloatArray,
        scratch: FloatArray,
        readStem: (stem: Int, dst: FloatArray) -> Boolean
    ): Boolean {
        java.util.Arrays.fill(mix, 0, samples, 0f)
        var any = false
        for (stem in 0 until stemCount) {
            val g = gains[stem]
            if (g <= 0f) continue
            if (!readStem(stem, scratch)) continue
            any = true
            for (i in 0 until samples) mix[i] += scratch[i] * g
        }
        return any
    }
}
