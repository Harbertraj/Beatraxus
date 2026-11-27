package com.beatraxus.app.fpsbooster

import com.beatraxus.app.motionboost.MotionBoostTiming
import java.util.Locale
import kotlin.math.abs

/** What the device can do (queried once; see [DeviceCapabilities.query]). */
data class DeviceCaps(
    val panelRates: List<Float>,
    val glEs3: Boolean,
    /** RGBA16F / RGBA32F render targets (EXT_color_buffer_float or EXT_color_buffer_half_float). */
    val floatRenderTargets: Boolean,
    val lowRamDevice: Boolean = false
)

/** Facts about the video being played. [fps] is 0 when unknown. */
data class SourceInfo(val fps: Float, val width: Int, val height: Int, val isHdr: Boolean)

/** Outcome of resolving the user's settings against the device and the video. */
data class BoosterPlan(
    val active: Boolean,
    val targetFps: Int,
    val engine: BoosterEngine,        // never AUTO once resolved
    val quality: BoosterQuality,
    /** Why the booster is not running (or was adjusted); shown in the UI. Null when running as asked. */
    val note: String?,
    /**
     * Targets this session may run at, ascending: only ones the panel shows evenly (see [TargetGovernor.candidates]). The run-time
     * [TargetGovernor] moves between them; [targetFps] is where it starts. Manual mode: up to the user's target.
     */
    val candidates: List<Int> = listOf(targetFps),
    /** True in Auto mode: the highest candidate is the ceiling the governor may climb to. */
    val auto: Boolean = false,
    /** Multiple style: the output is a (whole or fractional) multiple of the video's rate, aligned to it by timestamps (see [PhaseLock]). */
    val phaseLock: Boolean = false
) {
    /** The highest target this plan allows (the user's choice in manual mode). */
    val ceilingFps: Int get() = maxOf(candidates.maxOrNull() ?: 0, targetFps)

    companion object {
        fun inactive(note: String) =
            BoosterPlan(false, 0, BoosterEngine.OPTICAL_FLOW, BoosterQuality.BALANCED, note)
    }
}

/**
 * Pure decision logic: turns settings + device + source into a [BoosterPlan]. No Android types, unit tested.
 *
 * Rules (in order):
 *  - OFF, HDR sources and devices without OpenGL ES 3 never run the booster. HDR is excluded because the
 *    interpolation pipeline works on 8-bit SDR frames and has no tone-mapping; claiming otherwise would be wrong.
 *  - A source already at (>= 95 % of) the target needs no interpolation.
 *  - The target must divide some panel rate into a whole number of vsyncs ([MotionBoostTiming.isCleanRate]),
 *    otherwise frames would be shown unevenly (45 or 90 FPS on a 60 / 120 Hz panel); in that case the next lower
 *    usable target is used and the user is told.
 *  - An explicitly chosen engine is never replaced by a different one for speed or frame-rate reasons (RIFE AI and Hybrid
 *    stay RIFE AI and Hybrid). It changes only when it cannot run at all: RIFE model missing, or no float render targets.
 *  - AUTO mode only acts on sources below 45 fps. It starts at the highest usable of 60 / 30 above 1.2x the source and may climb
 *    to the highest target the panel shows evenly (see [TargetGovernor]) once the device has proven it can sustain it.
 *  - Battery saver caps the target at 60 and the quality at PERFORMANCE.
 */
object FpsBoosterPlanner {

    fun plan(
        settings: FpsBoosterSettings,
        caps: DeviceCaps,
        source: SourceInfo,
        rifeModelInstalled: Boolean = false,
        rifeInferenceMs: Float? = null
    ): BoosterPlan {
        if (settings.mode == BoosterMode.OFF) return BoosterPlan.inactive("Off")
        if (source.isHdr) return BoosterPlan.inactive("Not available for HDR videos")
        if (!caps.glEs3) return BoosterPlan.inactive("This device does not support OpenGL ES 3")

        var engine: BoosterEngine
        var engineNote: String? = null

        when (settings.engine) {
            BoosterEngine.AUTO -> {
                engine = if (caps.floatRenderTargets) {
                    if (rifeModelInstalled && rifeInferenceMs != null && rifeInferenceMs <= 35f &&
                        HybridScheduling.fitsEveryPair(rifeInferenceMs, source.fps.takeIf { it > 0f } ?: 24f)) {
                        BoosterEngine.HYBRID
                    } else {
                        BoosterEngine.OPTICAL_FLOW
                    }
                } else {
                    BoosterEngine.CLASSIC
                }
            }
            BoosterEngine.HYBRID -> {
                if (!caps.floatRenderTargets) {
                    engine = BoosterEngine.CLASSIC
                    engineNote = "GPU cannot render to float textures: using Classic engine"
                } else if (!rifeModelInstalled) {
                    engine = BoosterEngine.OPTICAL_FLOW
                    engineNote = "RIFE model not installed: using Optical flow"
                } else {
                    engine = BoosterEngine.HYBRID
                }
            }
            BoosterEngine.AI_RIFE -> {
                if (!caps.floatRenderTargets) {
                    engine = BoosterEngine.CLASSIC
                    engineNote = "GPU cannot render to float textures: using Classic engine"
                } else if (!rifeModelInstalled) {
                    engine = BoosterEngine.OPTICAL_FLOW
                    engineNote = "RIFE model not installed: using Optical flow"
                } else {
                    engine = BoosterEngine.AI_RIFE
                }
            }
            BoosterEngine.OPTICAL_FLOW -> {
                if (caps.floatRenderTargets) {
                    engine = BoosterEngine.OPTICAL_FLOW
                } else {
                    engine = BoosterEngine.CLASSIC
                    engineNote = "GPU cannot render to float textures: using Classic engine"
                }
            }
            BoosterEngine.CLASSIC -> {
                engine = BoosterEngine.CLASSIC
            }
        }

        var quality = settings.quality
        var ceiling = BoosterTargets.ALL.max()
        if (settings.batterySaver) {
            quality = BoosterQuality.PERFORMANCE
            ceiling = 60
        }

        // Multiple style: the output is target / source times the video's rate (a whole or a FRACTIONAL multiple: 25 -> 60 is
        // 2.4x) on a target the panel shows evenly, placed by exact timestamps (see [MultipleMode], [PhaseLock]). Only when the
        // video's rate is unknown, or nothing fits (the source is already at the targets), does it say so and the Generate rules apply.
        var multipleOptions: List<MultipleOption> = emptyList()
        var multiplePick: MultipleOption? = null
        var multipleNote: String? = null
        val multipleAuto = settings.style == BoosterStyle.MULTIPLE && (settings.mode == BoosterMode.AUTO || settings.multipleTarget == 0)
        if (settings.style == BoosterStyle.MULTIPLE) {
            if (settings.mode == BoosterMode.AUTO && source.fps >= 45f) return BoosterPlan.inactive("Auto: video is already smooth")
            val nominalFps = SourceRate.snap(source.fps)
            multipleOptions = MultipleMode.options(nominalFps, caps.panelRates, ceiling)
            if (multipleOptions.isEmpty()) {
                multipleNote = if (source.fps <= 0f) "Multiple: the video's frame rate is not known yet, using Generate"
                else String.format(
                    Locale.US,
                    "Multiple: no target above %.1f FPS fits this display evenly, using Generate", nominalFps
                )
            } else if (multipleAuto) {
                // Auto: the highest suitable target. The run-time governor lowers it when the device cannot sustain it and brings
                // it back gradually (see [TargetGovernor]).
                multiplePick = multipleOptions.last()
            } else {
                val want = settings.multipleTarget
                val exact = multipleOptions.firstOrNull { it.targetFps == want }
                multiplePick = exact ?: multipleOptions.lastOrNull { it.targetFps < want } ?: multipleOptions.first()
                if (exact == null) {
                    multipleNote = String.format(
                        Locale.US, "%d FPS does not fit a %.1f FPS video on this display evenly, using %d FPS",
                        want, nominalFps, multiplePick.targetFps
                    )
                }
            }
        }

        val wanted: Int
        var note: String? = engineNote
        val pickedMultiple = multiplePick
        if (pickedMultiple != null) {
            // Auto starts at the highest suitable target and steps down when the device cannot sustain it; a manual choice runs
            // at exactly that target (the governor pins it).
            wanted = pickedMultiple.targetFps
            note = multipleNote ?: engineNote ?: String.format(
                Locale.US, "Multiple: %.1f FPS x %s = %d FPS", SourceRate.snap(source.fps),
                MultipleMode.formatMultiplier(pickedMultiple.multiplier), pickedMultiple.targetFps
            )
        } else if (settings.mode == BoosterMode.AUTO) {
            if (source.fps > 0f && source.fps >= 45f) return BoosterPlan.inactive("Auto: video is already smooth")
            val floor = if (source.fps > 0f) source.fps * 1.2f else 0f
            val pick = listOf(60, 30).filter { it <= ceiling && it > floor && usable(caps, it) }.firstOrNull()
                ?: return BoosterPlan.inactive("Auto: no suitable target for this display")
            wanted = pick
        } else {
            val requested = settings.targetFps.coerceAtMost(ceiling)
            if (requested < settings.targetFps) note = "Battery saver limits the target to $ceiling FPS"
            if (source.fps > 0f && MotionBoostTiming.shouldBypass(source.fps, requested)) {
                return BoosterPlan.inactive("Video is already at or above $requested FPS")
            }
            wanted = if (usable(caps, requested)) requested else {
                val lower = BoosterTargets.ALL.filter { it < requested && usable(caps, it) }.maxOrNull()
                    ?: return BoosterPlan.inactive("Display cannot show $requested FPS evenly")
                if (source.fps > 0f && MotionBoostTiming.shouldBypass(source.fps, lower)) {
                    return BoosterPlan.inactive("Display cannot show $requested FPS evenly and the video is already near $lower")
                }
                note = "Display cannot show $requested FPS evenly: using $lower FPS"
                lower
            }
        }

        // The engine the user picked is the engine that runs. RIFE AI and Hybrid are never swapped for Optical flow because
        // of the video's frame rate or because the model is slow: the renderers adapt instead (RIFE lowers its model size on
        // the quality ladder; Hybrid keeps full-rate output and uses RIFE on as many pairs as it can). The notes below only
        // tell the user what to expect while the chosen engine keeps running.
        if (engine == BoosterEngine.AI_RIFE && source.fps > 0f) {
            val is2x = abs(wanted - 2 * source.fps) / (2 * source.fps) <= 0.03f
            if (!is2x) {
                note = String.format(
                    Locale.US,
                    "RIFE AI makes one AI frame between each pair of real frames (2x). For %d FPS from %.0f FPS the other " +
                        "positions are blended; Hybrid (RIFE 2x, then Optical flow up to the target) is smoother here",
                    wanted, source.fps
                )
            } else if (!HybridScheduling.fitsEveryPair(rifeInferenceMs, source.fps)) {
                note = String.format(
                    Locale.US, "RIFE needs %.0f ms per frame: it will lower its analysis size to keep up", rifeInferenceMs
                )
            }
        }

        if (engine == BoosterEngine.HYBRID && source.fps > 0f && !HybridScheduling.fitsEveryPair(rifeInferenceMs, source.fps)) {
            note = String.format(
                Locale.US,
                "RIFE needs %.0f ms per frame, more than the %.0f ms this video allows: RIFE helps on some pairs, " +
                    "Optical flow carries the rest",
                rifeInferenceMs, 0.9f * 1000f / source.fps
            )
        }

        // Targets the run-time governor may move between. Auto: everything the panel shows evenly up to the ceiling (battery
        // saver: 60), starting at [wanted] (60 where possible) and climbing only when the device proves it can. Manual: up to the
        // user's own choice, which is the ceiling; it can still step DOWN when the device cannot sustain it.
        val auto = settings.mode == BoosterMode.AUTO
        if (pickedMultiple != null) {
            val list = multipleOptions.map { it.targetFps }.filter { it <= (if (multipleAuto) ceiling else pickedMultiple.targetFps) }
            return BoosterPlan(true, wanted, engine, quality, note, (list + wanted).distinct().sorted(), multipleAuto, phaseLock = true)
        }
        if (multipleNote != null && note == null) note = multipleNote
        val candidates = (TargetGovernor.candidates(if (auto) ceiling else wanted, caps.panelRates, source.fps, auto) + wanted)
            .distinct().sorted()
        return BoosterPlan(true, wanted, engine, quality, note, candidates, auto)
    }

    /**
     * A target is usable when the panel has a mode that shows it evenly (a whole multiple of the target). 45 and 90 need
     * a 90 Hz mode; on a 60 / 120 Hz panel they would be shown as an uneven mix (judder), so they are not offered.
     */
    private fun usable(caps: DeviceCaps, fps: Int) = MotionBoostTiming.isCleanRate(caps.panelRates, fps)
}
