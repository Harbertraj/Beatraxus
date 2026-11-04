package com.beatraxus.app.model

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Studio DSP > Surround > Upmix Lab, orbit styles and gyro follow.
 *
 * Everything here is pure settings / geometry logic. The Upmix Lab values reach the audio engine through
 * [surroundEffectiveTuning] (the same [SurroundTuning] the Movie / Music / Game modes use), so the engine itself needs
 * no new code path. With the lab off the effective tuning is exactly the mode's own tuning, bit for bit as before.
 */

// ── Upmix Lab: the five numbers behind a surround mode, in the user's hands ──────────────────────────────────

/** One slider of the Upmix Lab. [min] / [max] / [step] are in the knob's own unit ([unit]; 0..1 shows as %). */
enum class SurroundLabKnob(
    val label: String,
    val unit: String,
    val min: Float,
    val max: Float,
    val step: Float,
    val hint: String
) {
    CENTER("Center focus", "%", 0f, 1f, 0.05f, "How much of the voice and dialogue is pulled to the center speaker."),
    WIDTH("Surround width", "%", 0f, 1f, 0.05f, "How much of the wide part of the song is sent to the surround speakers."),
    REAR("Rear share", "%", 0f, 1f, 0.05f, "7.1 only: how much surround sound goes to the back pair instead of the sides."),
    BASS("Bass cutoff", "Hz", 40f, 250f, 5f, "Where the subwoofer feed rolls off. Higher sends more low mids to the sub."),
    AMBIENCE("Ambience delay", "ms", 4f, 40f, 1f, "How late the surround sound arrives. Longer feels like a bigger room.");

    /** Value as shown next to the slider: "55%" or "120 Hz". */
    fun display(value: Float): String =
        if (unit == "%") "${(value * 100f).roundToInt()}%" else "${value.roundToInt()} $unit"
}

/** The back pair is delayed a bit more than the side pair, like the built-in modes (about 1.85 times). */
private const val LAB_REAR_DELAY_FACTOR = 1.85f
private const val LAB_REAR_DELAY_MAX_MS = 58f

/** Always finite values inside every knob's range, whatever was stored. */
fun sanitizeSurroundTuning(t: SurroundTuning): SurroundTuning {
    fun fix(v: Float, def: Float, lo: Float, hi: Float) = if (v.isNaN() || v.isInfinite()) def else v.coerceIn(lo, hi)
    val ambience = fix(t.surroundDelayMs, 12f, SurroundLabKnob.AMBIENCE.min, SurroundLabKnob.AMBIENCE.max)
    return SurroundTuning(
        centerAmount = fix(t.centerAmount, 0.35f, 0f, 1f),
        surroundAmount = fix(t.surroundAmount, 0.5f, 0f, 1f),
        rearShare = fix(t.rearShare, 0.4f, 0f, 1f),
        lfeCrossoverHz = fix(t.lfeCrossoverHz, 110f, SurroundLabKnob.BASS.min, SurroundLabKnob.BASS.max),
        surroundDelayMs = ambience,
        rearDelayMs = fix(t.rearDelayMs, ambience * LAB_REAR_DELAY_FACTOR, 1f, LAB_REAR_DELAY_MAX_MS)
    )
}

/** Current value of one knob. */
fun SurroundTuning.knobValue(knob: SurroundLabKnob): Float = when (knob) {
    SurroundLabKnob.CENTER -> centerAmount
    SurroundLabKnob.WIDTH -> surroundAmount
    SurroundLabKnob.REAR -> rearShare
    SurroundLabKnob.BASS -> lfeCrossoverHz
    SurroundLabKnob.AMBIENCE -> surroundDelayMs
}

/** Sets one knob (clamped to its range, snapped to its step). The ambience knob moves the back delay with it. */
fun SurroundTuning.withKnob(knob: SurroundLabKnob, value: Float): SurroundTuning {
    val raw = if (value.isNaN()) knob.min else value.coerceIn(knob.min, knob.max)
    val snapped = ((raw / knob.step).roundToInt() * knob.step).coerceIn(knob.min, knob.max)
    val v = (snapped * 1000f).roundToInt() / 1000f // 0.35, not 0.35000002
    val next = when (knob) {
        SurroundLabKnob.CENTER -> copy(centerAmount = v)
        SurroundLabKnob.WIDTH -> copy(surroundAmount = v)
        SurroundLabKnob.REAR -> copy(rearShare = v)
        SurroundLabKnob.BASS -> copy(lfeCrossoverHz = v)
        SurroundLabKnob.AMBIENCE -> copy(
            surroundDelayMs = v,
            rearDelayMs = (v * LAB_REAR_DELAY_FACTOR).coerceIn(1f, LAB_REAR_DELAY_MAX_MS)
        )
    }
    return sanitizeSurroundTuning(next)
}

/**
 * The matrix the audio engine must use: the user's Upmix Lab values while the lab is on, otherwise the chosen
 * mode's own tuning (identical to what the engine used before the lab existed).
 */
fun DspConfig.surroundEffectiveTuning(): SurroundTuning {
    val mine = surroundTune
    return if (surroundTuneEnabled && mine != null) sanitizeSurroundTuning(mine) else surroundMode.tuning()
}

/** Lab switch. Turning it on starts from what is playing now (the mode's tuning), so the sound does not jump. */
fun DspConfig.withSurroundLab(enabled: Boolean): DspConfig =
    if (enabled) copy(surroundTuneEnabled = true, surroundTune = surroundMode.tuning())
    else copy(surroundTuneEnabled = false)

/** Moves one slider. Starts from the values currently heard and switches the lab on. */
fun DspConfig.withSurroundKnob(knob: SurroundLabKnob, value: Float): DspConfig =
    copy(surroundTuneEnabled = true, surroundTune = surroundEffectiveTuning().withKnob(knob, value))

/** Back to the chosen mode's own tuning; the user's values are forgotten. */
fun DspConfig.withSurroundLabReset(): DspConfig = copy(surroundTuneEnabled = false, surroundTune = null)

/** Saved form: six comma separated numbers, empty text for "none". */
fun serializeSurroundTuning(t: SurroundTuning?): String {
    val s = t?.let { sanitizeSurroundTuning(it) } ?: return ""
    return listOf(s.centerAmount, s.surroundAmount, s.rearShare, s.lfeCrossoverHz, s.surroundDelayMs, s.rearDelayMs)
        .joinToString(",")
}

fun deserializeSurroundTuning(raw: String?): SurroundTuning? {
    if (raw.isNullOrBlank()) return null
    val v = raw.split(',').mapNotNull { it.trim().toFloatOrNull() }
    if (v.size != 6) return null
    return sanitizeSurroundTuning(SurroundTuning(v[0], v[1], v[2], v[3], v[4], v[5]))
}

// ── Orbit styles ──────────────────────────────────────────────────────────────────────────────────────────

/** How the sound travels while Auto-orbit runs. */
enum class SurroundOrbitPattern(val label: String) {
    /** Once around the listener. */
    CIRCLE("Circle"),

    /** Swings left and right across the front stage. */
    SWAY("Sway"),

    /** Two loops, one in front and one behind, crossing at the listener. */
    FIGURE_EIGHT("Figure 8"),

    /** Once around while drifting in and out three times. */
    SPIRAL("Spiral")
}

const val SURROUND_ORBIT_MIN_SECONDS = 3f
const val SURROUND_ORBIT_MAX_SECONDS = 30f
const val SURROUND_ORBIT_DEFAULT_SECONDS = 8f

/** Moves the orbit phase (0..1, wraps) forward by [dtSeconds]; one full cycle takes [periodSeconds]. */
fun advanceSurroundOrbitPhase(phase: Float, dtSeconds: Float, periodSeconds: Float): Float {
    val period = if (periodSeconds.isNaN()) SURROUND_ORBIT_DEFAULT_SECONDS
    else periodSeconds.coerceIn(SURROUND_ORBIT_MIN_SECONDS, SURROUND_ORBIT_MAX_SECONDS)
    val p = if (phase.isNaN() || phase.isInfinite()) 0f else phase
    val dt = if (dtSeconds.isNaN() || dtSeconds < 0f) 0f else dtSeconds
    val next = (p + dt / period) % 1f
    return if (next < 0f) next + 1f else next
}

/**
 * Where the sound is at [phase] (0..1) of one orbit cycle: angle (0..360, clockwise from straight ahead) and
 * distance (0.15..1). [baseDistance] is the distance the user chose; some patterns breathe around it.
 */
fun surroundOrbitPoint(pattern: SurroundOrbitPattern, phase: Float, baseDistance: Float): Pair<Float, Float> {
    val p = advanceSurroundOrbitPhase(phase, 0f, SURROUND_ORBIT_DEFAULT_SECONDS)
    val base = sanitizeSurroundDistance(baseDistance)
    val t = 2f * PI.toFloat() * p
    return when (pattern) {
        SurroundOrbitPattern.CIRCLE -> normalizeSurroundAngle(p * 360f) to base
        SurroundOrbitPattern.SWAY -> normalizeSurroundAngle(50f * sin(t)) to base
        SurroundOrbitPattern.FIGURE_EIGHT -> {
            val x = 0.6f * sin(2f * t)
            val y = sin(t)
            val angle = Math.toDegrees(atan2(x.toDouble(), y.toDouble())).toFloat()
            normalizeSurroundAngle(angle) to sanitizeSurroundDistance(hypot(x, y) * base)
        }
        SurroundOrbitPattern.SPIRAL -> {
            val breathe = 0.5f - 0.5f * cos(3f * t)
            normalizeSurroundAngle(p * 360f) to
                sanitizeSurroundDistance(SURROUND_POSITION_MIN_DISTANCE + (base - SURROUND_POSITION_MIN_DISTANCE) * breathe)
        }
    }
}

// ── Gyro follow: the sound stays where it is in the room while you turn ──────────────────────────────────────

/** Signed shortest turn from [from] to [to], -180..180 degrees (positive = clockwise). */
fun shortestAngleDelta(from: Float, to: Float): Float {
    var d = (to - from) % 360f
    if (d > 180f) d -= 360f
    if (d < -180f) d += 360f
    return d
}

/** Moves [current] a share [amount] (0..1) of the way to [target] along the shortest turn. Used to calm sensor noise. */
fun smoothSurroundAngle(current: Float, target: Float, amount: Float): Float =
    normalizeSurroundAngle(current + shortestAngleDelta(current, target) * amount.coerceIn(0f, 1f))

/**
 * Compass heading (degrees clockwise from north) of where the phone points, from the 3x3 row-major rotation matrix
 * that SensorManager.getRotationMatrixFromVector fills (device axes -> world East / North / Up).
 * An upright phone points with its back (like a camera); a phone lying flat points with its top edge.
 * Null when the matrix is unusable or the phone points straight up or down.
 */
fun surroundPhoneHeadingDeg(m: FloatArray): Float? {
    if (m.size < 9 || m.any { it.isNaN() || it.isInfinite() }) return null
    val upright = hypot(m[2], m[5]) >= 0.6f
    val east = if (upright) -m[2] else m[1]
    val north = if (upright) -m[5] else m[4]
    if (hypot(east, north) < 1e-3f) return null
    return normalizeSurroundAngle(Math.toDegrees(atan2(east.toDouble(), north.toDouble())).toFloat())
}

/**
 * Sound angle that keeps the sound fixed in the room: it started at [anchorDeg] when the phone pointed to
 * [headingAtStartDeg]; turning right by some degrees moves the sound that many degrees to the left of you.
 */
fun surroundGyroAngle(anchorDeg: Float, headingDeg: Float, headingAtStartDeg: Float): Float =
    normalizeSurroundAngle(anchorDeg - shortestAngleDelta(headingAtStartDeg, headingDeg))
