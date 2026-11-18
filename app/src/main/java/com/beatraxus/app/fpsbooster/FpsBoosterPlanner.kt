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
    val note: String?
) {
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
 *  - AUTO mode only acts on sources below 45 fps and picks the highest usable of 60 / 30 above 1.2x the source.
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

        val wanted: Int
        var note: String? = engineNote
        if (settings.mode == BoosterMode.AUTO) {
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

        if (engine == BoosterEngine.AI_RIFE) {
            if (source.fps > 0f) {
                val is2x = abs(wanted - 2 * source.fps) / (2 * source.fps) <= 0.03f
                if (!is2x) {
                    engine = BoosterEngine.OPTICAL_FLOW
                    note = "RIFE supports 2x only: using Optical flow"
                } else if (!HybridScheduling.fitsEveryPair(rifeInferenceMs, source.fps)) {
                    engine = BoosterEngine.OPTICAL_FLOW
                    note = String.format(Locale.US, "RIFE too slow on this device (%.0f ms per frame): using Optical flow", rifeInferenceMs)
                }
            }
        }

        // Hybrid runs the model on every pair or not at all: a model that only fits every 2nd / 3rd pair would alternate
        // between AI-assisted and plain pairs, a quality flicker. Same rule as the RIFE engine, same budget as the renderer.
        if (engine == BoosterEngine.HYBRID && source.fps > 0f && !HybridScheduling.fitsEveryPair(rifeInferenceMs, source.fps)) {
            engine = BoosterEngine.OPTICAL_FLOW
            note = String.format(
                Locale.US, "RIFE needs %.0f ms per frame, more than the %.0f ms this video allows: using Optical flow",
                rifeInferenceMs, 0.9f * 1000f / source.fps
            )
        }

        return BoosterPlan(true, wanted, engine, quality, note)
    }

    /**
     * A target is usable when the panel has a mode that shows it evenly (a whole multiple of the target). 45 and 90 need
     * a 90 Hz mode; on a 60 / 120 Hz panel they would be shown as an uneven mix (judder), so they are not offered.
     */
    private fun usable(caps: DeviceCaps, fps: Int) = MotionBoostTiming.isCleanRate(caps.panelRates, fps)
}
