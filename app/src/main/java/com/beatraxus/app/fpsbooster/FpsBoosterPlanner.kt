package com.beatraxus.app.fpsbooster

import com.beatraxus.app.motionboost.MotionBoostTiming

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
 *  - The target must be an integer multiple of some panel rate, otherwise frames would be paced unevenly;
 *    in that case the next lower usable target is used and the user is told.
 *  - AUTO mode only acts on sources below 45 fps and picks the highest usable of 60 / 30 above 1.2x the source.
 *  - Battery saver caps the target at 60 and the quality at PERFORMANCE.
 */
object FpsBoosterPlanner {

    fun plan(settings: FpsBoosterSettings, caps: DeviceCaps, source: SourceInfo): BoosterPlan {
        if (settings.mode == BoosterMode.OFF) return BoosterPlan.inactive("Off")
        if (source.isHdr) return BoosterPlan.inactive("Not available for HDR videos")
        if (!caps.glEs3) return BoosterPlan.inactive("This device does not support OpenGL ES 3")

        val engine = when (settings.engine) {
            BoosterEngine.AUTO -> if (caps.floatRenderTargets) BoosterEngine.OPTICAL_FLOW else BoosterEngine.CLASSIC
            BoosterEngine.AI_RIFE -> BoosterEngine.AI_RIFE
            BoosterEngine.OPTICAL_FLOW ->
                if (caps.floatRenderTargets) BoosterEngine.OPTICAL_FLOW else BoosterEngine.CLASSIC
            BoosterEngine.CLASSIC -> BoosterEngine.CLASSIC
        }
        val engineNote = if (settings.engine == BoosterEngine.OPTICAL_FLOW && engine == BoosterEngine.CLASSIC)
            "GPU cannot render to float textures: using Classic engine" else null

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
                    ?: return BoosterPlan.inactive("Display cannot show ${requested} FPS evenly")
                if (source.fps > 0f && MotionBoostTiming.shouldBypass(source.fps, lower)) {
                    return BoosterPlan.inactive("Display cannot show $requested FPS and the video is already near $lower")
                }
                note = "Display cannot show $requested FPS evenly: using $lower FPS"
                lower
            }
        }
        return BoosterPlan(true, wanted, engine, quality, note)
    }

    private fun usable(caps: DeviceCaps, fps: Int) = MotionBoostTiming.isRateSupported(caps.panelRates, fps)
}
