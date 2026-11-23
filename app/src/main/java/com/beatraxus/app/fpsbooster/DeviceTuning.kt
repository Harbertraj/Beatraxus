package com.beatraxus.app.fpsbooster

import android.content.Context
import android.os.Build
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * What the booster has learned about THIS phone, kept between sessions (separate file, tied to the system build so a
 * system update starts the learning again).
 *
 *  1. Which inference provider runs the RIFE model fastest ([order] / [tuneProvider]).
 *  2. Which quality rung the phone could sustain for a given engine / target ([learnedLevel] / [rememberLevel]), so a
 *     new session starts where the last one settled instead of walking down from the top and stuttering on the way.
 */
object DeviceTuning {
    private const val TAG = "DeviceTuning"
    private const val PREFS = "fpsb_device_tuning"
    private const val KEY_PROVIDER = "provider"
    private const val KEY_NNAPI_PROBE = "nnapi_probe_running"
    private const val NNAPI = "NNAPI"
    private const val DEFAULT = "DEFAULT"

    /** NNAPI must be at least this much faster than the default providers to be used (it is the riskier path). */
    private const val NNAPI_MIN_GAIN = 0.80f

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun build() = Build.FINGERPRINT.hashCode().toString()

    // ---- 1. Inference provider -------------------------------------------------------------------------------------

    /** Provider order for the RIFE session: NNAPI first only when the device test showed it clearly faster. */
    fun order(context: Context): List<InferenceProvider> = try {
        val p = prefs(context)
        val saved = p.getString(KEY_PROVIDER, null)
        if (saved == "${build()}:$NNAPI" && !p.getBoolean(KEY_NNAPI_PROBE, false)) {
            listOf(InferenceProvider.NNAPI) + InferenceProvider.DEFAULT_ORDER
        } else {
            InferenceProvider.DEFAULT_ORDER
        }
    } catch (_: Throwable) {
        InferenceProvider.DEFAULT_ORDER
    }

    /**
     * Tries NNAPI against the default providers once per system build and remembers the winner. Returns the best
     * inference time (ms) found, or [defaultMs] when the default providers stay the choice.
     *
     * Safety: a probe flag is written before NNAPI is touched. If a vendor driver crashes the process, the flag is still
     * set on the next start and NNAPI is never tried again. The result is also rejected when the output is not a valid
     * picture (some drivers compute in reduced precision and return garbage).
     */
    fun tuneProvider(context: Context, defaultMs: Float, w: Int, h: Int): Float {
        val p = prefs(context)
        val tag = build()
        val saved = p.getString(KEY_PROVIDER, null)
        if (saved != null && saved.startsWith("$tag:")) return defaultMs // already decided for this system build
        if (p.getBoolean(KEY_NNAPI_PROBE, false)) {
            // The last probe never finished: the driver is not safe on this phone.
            p.edit().putString(KEY_PROVIDER, "$tag:$DEFAULT").putBoolean(KEY_NNAPI_PROBE, false).commit()
            return defaultMs
        }
        if (defaultMs <= 0f) return defaultMs
        p.edit().putBoolean(KEY_NNAPI_PROBE, true).commit()
        var result = DEFAULT
        var best = defaultMs
        try {
            val nn = RifeAiEngine(context)
            try {
                if (nn.initialize(listOf(InferenceProvider.NNAPI)) && nn.activeProvider == InferenceProvider.NNAPI && validOutput(nn, w, h)) {
                    val ms = nn.benchmark(w, h, iterations = 6)
                    Log.i(TAG, "RIFE on NNAPI: $ms ms, default providers: $defaultMs ms")
                    if (ms > 0f && ms < defaultMs * NNAPI_MIN_GAIN) { result = NNAPI; best = ms }
                } else {
                    Log.i(TAG, "NNAPI could not run the RIFE model on this phone")
                }
            } finally {
                nn.release()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "NNAPI probe failed", t)
        }
        p.edit().putString(KEY_PROVIDER, "$tag:$result").putBoolean(KEY_NNAPI_PROBE, false).commit()
        return best
    }

    private fun validOutput(engine: RifeAiEngine, w: Int, h: Int): Boolean {
        val packed = ByteBuffer.allocateDirect(7 * w * h * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        val out = ByteBuffer.allocateDirect(3 * w * h * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        // Both frames mid grey, timestep 0.5: a correct interpolation is mid grey too.
        for (i in 0 until 6 * w * h) packed.put(i, 0.5f)
        RifePacking.fillPlane(packed, 6 * w * h, w, h, 0.5f)
        if (!engine.infer(packed, w, h, out)) return false
        var sum = 0.0
        val n = 3 * w * h
        for (i in 0 until n step 97) {
            val v = out.get(i)
            if (v.isNaN() || v.isInfinite() || v < -0.2f || v > 1.2f) return false
            sum += v
        }
        val mean = sum / ((n + 96) / 97)
        return mean in 0.35..0.65
    }

    // ---- 2. Learned quality rung -----------------------------------------------------------------------------------

    private fun levelKey(plan: BoosterPlan) = "lvl_${build()}_${plan.engine.name}_${plan.targetFps}_${plan.quality.name}"

    /** Rung (index into [AdaptiveQualityController.LADDER]) the last session of this kind settled on, or null. */
    fun learnedLevel(context: Context, plan: BoosterPlan): Int? = runCatching {
        val p = prefs(context)
        val key = levelKey(plan)
        if (p.contains(key)) p.getInt(key, 0).coerceIn(0, AdaptiveQualityController.LADDER.lastIndex) else null
    }.getOrNull()

    fun rememberLevel(context: Context, plan: BoosterPlan, index: Int) {
        runCatching { prefs(context).edit().putInt(levelKey(plan), index).apply() }
    }
}
