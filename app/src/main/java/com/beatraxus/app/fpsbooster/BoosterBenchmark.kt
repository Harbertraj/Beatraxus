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
 * @property fuseMs milliseconds the AI fusion pass needs for one generated frame (real shader, 960x540 fusion map,
 *   worst case: every pixel goes through the network). [FUSE_NOT_MEASURED] when it could not be measured: the GPU
 *   rejected two float render targets or the shader did not compile, so AI fusion is not offered.
 */
data class BenchmarkResult(
    val cpuScore: Int,
    val gpuScore: Int,
    val cores: Int,
    val pairMs: Map<BoosterQuality, Float>,
    val warpMs: Float,
    val floatRtOk: Boolean,
    val fuseMs: Float = FUSE_NOT_MEASURED,
    val measuredAt: Long = 0L,
    val version: Int = VERSION
) {
    /** Compact single-string form for SharedPreferences. */
    fun toStored(): String = buildString {
        append(version).append('|').append(cpuScore).append('|').append(gpuScore).append('|').append(cores).append('|')
        append(BoosterQuality.entries.joinToString(",") { q -> pairMs[q]?.toString() ?: "-" }).append('|')
        append(warpMs).append('|').append(if (floatRtOk) 1 else 0).append('|').append(measuredAt).append('|').append(fuseMs)
    }

    companion object {
        /** Bump when the test changes so old results are discarded and the test runs again. */
        const val VERSION = 2

        /** [fuseMs] value for "the fusion pass could not run on this GPU". */
        const val FUSE_NOT_MEASURED = -1f

        fun fromStored(s: String?): BenchmarkResult? {
            if (s.isNullOrBlank()) return null
            return try {
                val p = s.split('|')
                if (p.size != 9 || p[0].toInt() != VERSION) return null
                val ms = p[4].split(',')
                if (ms.size != BoosterQuality.entries.size) return null
                val map = HashMap<BoosterQuality, Float>()
                BoosterQuality.entries.forEachIndexed { i, q -> ms[i].toFloatOrNull()?.let { map[q] = it } }
                BenchmarkResult(
                    cpuScore = p[1].toInt(), gpuScore = p[2].toInt(), cores = p[3].toInt(),
                    pairMs = map, warpMs = p[5].toFloat(), floatRtOk = p[6] == "1",
                    fuseMs = p[8].toFloat(), measuredAt = p[7].toLong()
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

    /** AI fusion is refused when one pass alone costs more than this (ms, 960x540 map, worst case). */
    const val FUSE_MAX_MS = 4f

    /**
     * The benchmark gate for AI fusion. Per second the GPU already spends 30 pair estimations + one warp per displayed
     * frame (see [recommend]); fusion adds one fusion pass per displayed frame and the AI composite costs about as much
     * as the hand-tuned warp it replaces. Allowed only if the same [GPU_BUDGET_MS_PER_S] still holds with that added.
     * Pure: unit tested without a GPU.
     */
    fun aiFusionVerdict(r: BenchmarkResult?, quality: BoosterQuality, targetFps: Int): AiFusionVerdict {
        if (r == null) return AiFusionVerdict.NOT_TESTED
        if (!r.floatRtOk || r.fuseMs <= 0f) return AiFusionVerdict.UNSUPPORTED
        val pair = r.pairMs[quality] ?: return AiFusionVerdict.TOO_SLOW
        if (pair > PAIR_BUDGET_MS || r.fuseMs > FUSE_MAX_MS) return AiFusionVerdict.TOO_SLOW
        val perSecond = SOURCE_FPS * pair + targetFps * (r.warpMs + r.fuseMs)
        return if (perSecond <= GPU_BUDGET_MS_PER_S) AiFusionVerdict.OK else AiFusionVerdict.TOO_SLOW
    }

    /**
     * The single decision the player acts on: AI fusion runs only if the user turned it on, the optical-flow engine is
     * the one running (not RIFE / Classic), battery saver is off and the device test passed the gate for the quality and
     * target that are actually in use. Anything else keeps the hand-tuned blend.
     */
    fun aiFusionEnabled(s: FpsBoosterSettings, plan: BoosterPlan, bench: BenchmarkState): Boolean =
        s.aiFusion && !s.batterySaver && plan.active && plan.engine == BoosterEngine.OPTICAL_FLOW &&
            aiFusionVerdict((bench as? BenchmarkState.Done)?.result, plan.quality, plan.targetFps) == AiFusionVerdict.OK

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
                val recommendedEngine = if (q == BoosterQuality.QUALITY && r.gpuScore > 1200) BoosterEngine.AI_RIFE else BoosterEngine.OPTICAL_FLOW
                val engineLabel = if (recommendedEngine == BoosterEngine.AI_RIFE) "RIFE AI" else "Optical flow"
                return Recommendation(
                    recommendedEngine, q, target, tier,
                    "${tier.label} device: $engineLabel, ${q.label} quality, up to $target FPS.$cpuNote"
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

/** Whether the learned fusion may run, as decided by [BoosterRecommender.aiFusionVerdict]. */
enum class AiFusionVerdict(val message: String?) {
    OK(null),
    NOT_TESTED("Run the device test to find out whether your GPU can run AI fusion."),
    UNSUPPORTED("This GPU cannot run AI fusion (it needs two float render targets)."),
    TOO_SLOW("Too heavy for this GPU at the current quality and FPS target. Lower either one to use AI fusion.")
}

/** What the settings sheet shows for the device test. */
sealed interface BenchmarkState {
    /** Never run (or the stored result is from an older version of the test). */
    data object Idle : BenchmarkState
    data class Running(val progress: Float, val stage: String) : BenchmarkState
    data class Done(val result: BenchmarkResult, val recommendation: Recommendation) : BenchmarkState
    data class Failed(val message: String) : BenchmarkState
}
