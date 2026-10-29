package com.beatraxus.app.fpsbooster

import java.nio.ByteBuffer
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Pure Kotlin utilities for pixel buffer layout conversion (RGBA8 <-> planar float NCHW)
 * and dimension scaling for RIFE 4.26 neural interpolation.
 */
object RifePacking {

    /**
     * Unpacks RGBA8 pixels from direct [src] ByteBuffer (row 0 = first row in buffer)
     * into planar float 0..1 RGB layout in [dst] starting at float index [planeOffsetFloats].
     *
     * Planes are laid out sequentially: R plane (w*h floats), G plane (w*h floats), B plane (w*h floats).
     */
    fun rgbaToPlanar(src: ByteBuffer, w: Int, h: Int, dst: FloatBuffer, planeOffsetFloats: Int) {
        val planeSize = w * h
        val rOffset = planeOffsetFloats
        val gOffset = planeOffsetFloats + planeSize
        val bOffset = planeOffsetFloats + 2 * planeSize

        val bytesPerPixel = 4
        for (i in 0 until planeSize) {
            val base = i * bytesPerPixel
            val r = (src.get(base).toInt() and 0xFF) / 255.0f
            val g = (src.get(base + 1).toInt() and 0xFF) / 255.0f
            val b = (src.get(base + 2).toInt() and 0xFF) / 255.0f
            dst.put(rOffset + i, r)
            dst.put(gOffset + i, g)
            dst.put(bOffset + i, b)
        }
    }

    /**
     * Fills one whole plane of [w] x [h] floats starting at [planeOffsetFloats] in [dst] with a constant [value].
     */
    fun fillPlane(dst: FloatBuffer, planeOffsetFloats: Int, w: Int, h: Int, value: Float) {
        val planeSize = w * h
        for (i in 0 until planeSize) {
            dst.put(planeOffsetFloats + i, value)
        }
    }

    /**
     * Packs planar float RGB 0..1 [3 * w * h] from [src] into RGBA8 bytes in [dst].
     *
     * Values are clamped to 0..1, converted via (* 255 + 0.5), and alpha is set to 255 (0xFF).
     */
    fun planarToRgba(src: FloatBuffer, w: Int, h: Int, dst: ByteBuffer) {
        val planeSize = w * h
        val rOffset = 0
        val gOffset = planeSize
        val bOffset = 2 * planeSize

        val bytesPerPixel = 4
        for (i in 0 until planeSize) {
            val r = (src.get(rOffset + i).coerceIn(0f, 1f) * 255f + 0.5f).toInt().coerceIn(0, 255)
            val g = (src.get(gOffset + i).coerceIn(0f, 1f) * 255f + 0.5f).toInt().coerceIn(0, 255)
            val b = (src.get(bOffset + i).coerceIn(0f, 1f) * 255f + 0.5f).toInt().coerceIn(0, 255)

            val base = i * bytesPerPixel
            dst.put(base, r.toByte())
            dst.put(base + 1, g.toByte())
            dst.put(base + 2, b.toByte())
            dst.put(base + 3, 255.toByte())
        }
    }

    /**
     * Calculates model input dimensions given source size [srcW] x [srcH] and target [longEdge],
     * maintaining aspect ratio while rounding both dimensions to a multiple of 32 (min 64).
     */
    fun modelSize(srcW: Int, srcH: Int, longEdge: Int): Pair<Int, Int> {
        val s = longEdge.toFloat() / max(srcW, srcH).coerceAtLeast(1)
        val rawW = (srcW * s).roundToInt()
        val rawH = (srcH * s).roundToInt()

        fun round32(v: Int) = ((v + 16) / 32 * 32).coerceAtLeast(64)

        return round32(rawW) to round32(rawH)
    }
}
