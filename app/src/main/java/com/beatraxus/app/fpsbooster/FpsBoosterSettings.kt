package com.beatraxus.app.fpsbooster

/** FPS Booster master switch. [AUTO] decides per video (see [FpsBoosterPlanner]). */
enum class BoosterMode(val label: String) {
    OFF("Off"),
    ON("On"),
    AUTO("Auto");

    companion object {
        fun fromName(name: String?): BoosterMode = entries.firstOrNull { it.name == name } ?: OFF
    }
}

/**
 * Interpolation engine. Only engines that actually exist are listed here; an AI (RIFE-style) engine is
 * deliberately NOT an option because it is not implemented (see docs/FPS_BOOSTER.md for the assessment).
 */
enum class BoosterEngine(val label: String, val description: String) {
    AUTO("Auto", "Optical flow when the GPU supports it, otherwise Classic"),
    OPTICAL_FLOW("Optical flow", "Hierarchical bidirectional flow with occlusion handling"),
    CLASSIC("Classic", "Original block-matching engine");

    companion object {
        fun fromName(name: String?): BoosterEngine = entries.firstOrNull { it.name == name } ?: AUTO
    }
}

/**
 * Output frame cap. Quality presets only change the (cheap) motion-search size; the frame that is shown is
 * kept at the video's original resolution (4K cap is a memory safety limit only).
 */
const val FRAME_EDGE_ORIGINAL = 3840

/**
 * Quality preset for the optical-flow engine. [workEdge] is the longest side (px) of the luma image used
 * for motion search; [frameEdge] caps the stored/shown frame size; [searchRange] is the integer search
 * radius (px) at the coarsest pyramid level; [subpixel] enables half/quarter-pixel refinement.
 */
enum class BoosterQuality(
    val label: String,
    val workEdge: Int,
    val frameEdge: Int,
    val searchRange: Int,
    val subpixel: Boolean
) {
    PERFORMANCE("Performance", 640, FRAME_EDGE_ORIGINAL, 6, true),   // sub-pixel on: without it every vector is up to half a work pixel (1.5 px at 1080p) off => smeared detail
    BALANCED("Balanced", 960, FRAME_EDGE_ORIGINAL, 6, true),
    QUALITY("Quality", 1280, FRAME_EDGE_ORIGINAL, 8, true);

    companion object {
        fun fromName(name: String?): BoosterQuality = entries.firstOrNull { it.name == name } ?: DEFAULT

        /** Start light and let the user (or a benchmark) raise it: motion search at 640 px is the cheapest preset. */
        val DEFAULT = PERFORMANCE
    }
}

/** Target output rates offered in the UI. Whether one is usable depends on the panel (see [FpsBoosterPlanner]). */
object BoosterTargets {
    val ALL: List<Int> = listOf(30, 45, 60, 90, 120)
    const val DEFAULT = 60
    fun sanitize(fps: Int): Int = if (fps in ALL) fps else DEFAULT
}

/**
 * Persistent user preferences. There is intentionally no "motion strength" slider: the interpolator has no
 * such parameter (warp trust is decided per pixel from measured consistency), so exposing one would be fake.
 */
data class FpsBoosterSettings(
    val mode: BoosterMode = BoosterMode.OFF,
    val targetFps: Int = BoosterTargets.DEFAULT,
    val engine: BoosterEngine = BoosterEngine.AUTO,
    val quality: BoosterQuality = BoosterQuality.DEFAULT,
    /** Let the controller lower the processing resolution (and finally stop) when the device cannot keep up. */
    val adaptiveResolution: Boolean = true,
    /** Cap target FPS at 60, force Performance quality and never raise quality automatically. */
    val batterySaver: Boolean = false,
    /** Show the live statistics overlay in the settings sheet. */
    val showStats: Boolean = true
) {
    val isOn: Boolean get() = mode != BoosterMode.OFF

    companion object {
        /** Builds settings from raw stored values; unknown / missing / corrupt values fall back to defaults. */
        fun fromStored(
            mode: String?, targetFps: Int?, engine: String?, quality: String?,
            adaptive: Boolean?, batterySaver: Boolean?, showStats: Boolean?
        ) = FpsBoosterSettings(
            mode = BoosterMode.fromName(mode),
            targetFps = BoosterTargets.sanitize(targetFps ?: BoosterTargets.DEFAULT),
            engine = BoosterEngine.fromName(engine),
            quality = BoosterQuality.fromName(quality),
            adaptiveResolution = adaptive ?: true,
            batterySaver = batterySaver ?: false,
            showStats = showStats ?: true
        )

        /**
         * One-time migration from the old single-choice "motion boost" preference (ORIGINAL / FPS_45 / FPS_60 ...).
         * The old "extreme" flag and the default both map to the lightest preset ([legacyExtreme] is kept for the signature).
         * Returns null when there is nothing to migrate.
         */
        fun migrateLegacy(legacyMode: String?, legacyExtreme: Boolean): FpsBoosterSettings? {
            val fps = when (legacyMode) {
                "FPS_45" -> 45
                "FPS_60" -> 60
                "FPS_90" -> 90
                "FPS_120" -> 120
                else -> return null
            }
            return FpsBoosterSettings(
                mode = BoosterMode.ON,
                targetFps = fps,
                engine = BoosterEngine.AUTO,
                quality = BoosterQuality.PERFORMANCE
            )
        }
    }
}

/** SharedPreferences keys (same file as the rest of the video player settings: "beatraxus"). */
object FpsBoosterPrefs {
    const val MODE = "fpsb_mode"
    const val TARGET = "fpsb_target"
    const val ENGINE = "fpsb_engine"
    const val QUALITY = "fpsb_quality"
    const val ADAPTIVE = "fpsb_adaptive"
    const val BATTERY = "fpsb_battery"
    const val SHOW_STATS = "fpsb_stats"
    const val MIGRATED = "fpsb_migrated_v1"
    /** Stored [BenchmarkResult] (see [BenchmarkResult.toStored]). */
    const val BENCH = "fpsb_bench"
    /** The user dismissed the first-run device test; do not start it automatically again. */
    const val BENCH_SKIPPED = "fpsb_bench_skipped"
}
