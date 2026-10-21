package com.beatraxus.app.fpsbooster

import com.beatraxus.app.motionboost.MotionBoostTiming

/**
 * What the one-time device test measured. Everything is a plain number so the result can be stored,
 * shown, and turned into a recommendation without a GPU (see [BoosterRecommender], unit tested).
 *
 * @property cpuScore multi-core floating-point throughput, in points (higher is better; informational plus
 *   a weak-CPU cap in the recommendation).
 * @property gpuScore motion-search throughput on the GPU, in points (higher is better). Measured with the
 *   booster's real motion-search shader, so it reflects what the Optical flow engine will actually cost.
 * @property pairMs milliseconds the GPU needs to estimate the motion of one frame pair (forward + backward),
 *   per quality preset at 16:9. A preset that was not measured (device too slow to bother) is absent.
 * @property warpMs milliseconds to produce one 1080p output frame (the work done for every displayed frame).
 * @property floatRtOk false when the GPU cannot render to float textures: Optical flow cannot run at all.
 */
data class BenchmarkResult(
    val cpuScore: Int,
    val gpuScore: Int,
    val cores: Int,
    val pairMs: Map<BoosterQuality, Float>,
    val warpMs: Float,
    val floatRtOk: Boolean,
    val measuredAt: Long = 0L,
    val version: Int = VERSION
) {
    /** Compact single-string form for SharedPreferences. */
    fun toStored(): String = buildString {
        append(version).append('|').append(cpuScore).append('|').append(gpuScore).append('|').append(cores).append('|')
        append(BoosterQuality.entries.joinToString(",") { q -> pairMs[q]?.toString() ?: "-" }).append('|')
        append(warpMs).append('|').append(if (floatRtOk) 1 else 0).append('|').append(measuredAt)
    }

    companion object {
        /** Bump when the test changes so old results are discarded and the test runs again. */
        const val VERSION = 2   // v2: Performance now includes sub-pixel refinement and the cheaper refinement shader

        fun fromStored(s: String?): BenchmarkResult? {
            if (s.isNullOrBlank()) return null
            return try {
                val p = s.split('|')
                if (p.size != 8 || p[0].toInt() != VERSION) return null
                val ms = p[4].split(',')
                if (ms.size != BoosterQuality.entries.size) return null
                val map = HashMap<BoosterQuality, Float>()
                BoosterQuality.entries.forEachIndexed { i, q -> ms[i].toFloatOrNull()?.let { map[q] = it } }
                BenchmarkResult(
                    cpuScore = p[1].toInt(), gpuScore = p[2].toInt(), cores = p[3].toInt(),
                    pairMs = map, warpMs = p[5].toFloat(), floatRtOk = p[6] == "1", measuredAt = p[7].toLong()
                )
            } catch (e: NumberFormatException) {
                null
            }
        }
    }
}

enum class DeviceTier(val label: String) { LOW("Entry"), MID("Mid-range"), HIGH("High-end") }

/** The settings the device test suggests. [summary] is the one-line explanation shown to the user. */
data class Recommendation(
    val engine: BoosterEngine,
    val quality: BoosterQuality,
    val targetFps: Int,
    val tier: DeviceTier,
    val summary: String
) {
    /** Applies the suggestion to the user's settings, leaving On/Off/Auto and the other toggles alone. */
    fun applyTo(s: FpsBoosterSettings) = s.copy(engine = engine, quality = quality, targetFps = targetFps)
}

/**
 * Turns a [BenchmarkResult] into settings the device can actually sustain, so the booster does not start at a
 * setting that immediately falls back to "device could not keep up".
 *
 * Budget model (all numbers are deliberately conservative; phones throttle):
 *  - The motion of one frame pair must be estimated inside half of a 30 FPS source interval (16.7 ms).
 *    24 FPS films get more room, 30 FPS content is the worst common case.
 *  - Over one second the GPU has to do 30 pair estimations plus one warp per displayed frame, and stay under
 *    [GPU_BUDGET_MS_PER_S] of GPU time so there is headroom for decoding, the UI and thermal throttling.
 *  - The target must also be a rate the panel can pace evenly.
 */
object BoosterRecommender {
    const val SOURCE_FPS = 30f
    const val PAIR_BUDGET_MS = 1000f / SOURCE_FPS / 2f
    const val GPU_BUDGET_MS_PER_S = 650f
    /** Below this CPU score the target is capped at 60 FPS (the CPU also decodes and feeds the GPU). */
    const val WEAK_CPU_SCORE = 500

    fun recommend(r: BenchmarkResult, panelRates: List<Float>): Recommendation {
        val usableTargets = BoosterTargets.ALL.filter { MotionBoostTiming.isRateSupported(panelRates, it) }.ifEmpty { listOf(30) }
        val weakCpu = r.cpuScore in 1 until WEAK_CPU_SCORE
        val ceiling = if (weakCpu) 60 else usableTargets.max()

        fun targetFor(perSecondFixedMs: Float): Int? =
            usableTargets.filter { it <= ceiling && perSecondFixedMs + it * r.warpMs <= GPU_BUDGET_MS_PER_S }.maxOrNull()

        if (r.floatRtOk) {
            for (q in listOf(BoosterQuality.QUALITY, BoosterQuality.BALANCED, BoosterQuality.PERFORMANCE)) {
                val pair = r.pairMs[q] ?: continue
                if (pair > PAIR_BUDGET_MS) continue
                val target = targetFor(SOURCE_FPS * pair) ?: continue
                val tier = when (q) { BoosterQuality.QUALITY -> DeviceTier.HIGH; BoosterQuality.BALANCED -> DeviceTier.MID; else -> DeviceTier.LOW }
                val cpuNote = if (weakCpu) " The CPU is on the slow side, so the target is capped at 60." else ""
                return Recommendation(
                    BoosterEngine.OPTICAL_FLOW, q, target, tier,
                    "${tier.label} device: Optical flow, ${q.label} quality, up to $target FPS.$cpuNote"
                )
            }
        }

        // Optical flow would not keep up (or cannot run): the Classic engine is light enough for almost any GPU.
        val target = targetFor(0f) ?: usableTargets.filter { it <= ceiling }.minOrNull() ?: 30
        val why = if (!r.floatRtOk) "this GPU cannot render the textures Optical flow needs"
        else "Optical flow is too heavy for this GPU to run in real time"
        return Recommendation(
            BoosterEngine.CLASSIC, BoosterQuality.PERFORMANCE, target, DeviceTier.LOW,
            "Entry device: $why, so Classic at up to $target FPS is the smooth choice."
        )
    }
}

/** What the settings sheet shows for the device test. */
sealed interface BenchmarkState {
    /** Never run (or the stored result is from an older version of the test). */
    data object Idle : BenchmarkState
    data class Running(val progress: Float, val stage: String) : BenchmarkState
    data class Done(val result: BenchmarkResult, val recommendation: Recommendation) : BenchmarkState
    data class Failed(val message: String) : BenchmarkState
}
