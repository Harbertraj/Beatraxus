package com.beatraxus.app.engine

/**
 * Thin Kotlin wrapper around the native OpenAL Soft HRTF engine.
 * Native side: app/src/main/cpp/openal_jni_bridge.cpp
 * Native lib name below must match CMakeLists.txt's add_library(openal_jni_bridge ...).
 *
 * Usage:
 *   val engine = OpenAlAudioEngine()
 *   engine.init()
 *   engine.loadMonoPcm16(pcmShortArray, sampleRate = 44100)
 *   engine.setSourcePosition(x = 1.0f, y = 0f, z = -1f) // e.g. "wide right" preset
 *   engine.play()
 *   ...
 *   engine.shutdown()
 *
 * Coordinate convention (OpenAL, right-handed):
 *   x = right, y = up, z = toward the listener (i.e. negative z is "in front").
 *   For your three azimuth presets (narrow/wide/concert), map azimuth degrees
 *   to x/z with simple trig:
 *     x = sin(azimuthRadians)
 *     z = -cos(azimuthRadians)
 *   so 0 deg = straight ahead, 90 deg = directly to the right.
 */
class OpenAlAudioEngine {

    companion object {
        init {
            System.loadLibrary("openal_jni_bridge")
        }

        /** Convenience: degrees -> (x, z) on a unit circle for nativeSetSourcePosition. */
        @JvmStatic
        fun azimuthToXz(azimuthDegrees: Float): Pair<Float, Float> {
            val rad = Math.toRadians(azimuthDegrees.toDouble())
            val x = Math.sin(rad).toFloat()
            val z = -Math.cos(rad).toFloat()
            return x to z
        }
    }

    fun init(): Boolean = nativeInit()

    fun loadMonoPcm16(pcm: ShortArray, sampleRate: Int) = nativeLoadMonoPcm16(pcm, sampleRate)

    fun setSourcePosition(x: Float, y: Float, z: Float) = nativeSetSourcePosition(x, y, z)

    /** Convenience overload driven by your existing azimuth presets (degrees). */
    fun setSourceAzimuth(azimuthDegrees: Float, distance: Float = 1.0f) {
        val (x, z) = azimuthToXz(azimuthDegrees)
        nativeSetSourcePosition(x * distance, 0f, z * distance)
    }

    fun setLooping(loop: Boolean) = nativeSetLooping(loop)

    fun play() = nativePlay()

    fun stop() = nativeStop()

    fun shutdown() = nativeShutdown()

    private external fun nativeInit(): Boolean
    private external fun nativeLoadMonoPcm16(pcm: ShortArray, sampleRate: Int)
    private external fun nativeSetSourcePosition(x: Float, y: Float, z: Float)
    private external fun nativeSetLooping(loop: Boolean)
    private external fun nativePlay()
    private external fun nativeStop()
    private external fun nativeShutdown()
}
