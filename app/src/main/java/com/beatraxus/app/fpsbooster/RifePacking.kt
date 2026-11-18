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

    /** Pixels converted per bulk copy: keeps the temporary arrays small (a few tens of KB). */
    private const val CHUNK_PIXELS = 8192

    /** Exact `v / 255f` for every byte value, so results match the previous per-pixel division bit for bit. */
    private val BYTE_TO_FLOAT = FloatArray(256) { it / 255.0f }

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

        // Bulk-copy the pixels in chunks instead of one absolute get per byte (which is slow on direct buffers).
        // `duplicate()` keeps the caller's position/limit untouched.
        val s = src.duplicate()
        s.clear()
        s.position(0)
        val bytes = ByteArray(CHUNK_PIXELS * 4)
        var done = 0
        while (done < planeSize) {
            val n = minOf(CHUNK_PIXELS, planeSize - done)
            s.get(bytes, 0, n * 4)
            var k = 0
            for (i in done until done + n) {
                dst.put(rOffset + i, BYTE_TO_FLOAT[bytes[k].toInt() and 0xFF])
                dst.put(gOffset + i, BYTE_TO_FLOAT[bytes[k + 1].toInt() and 0xFF])
                dst.put(bOffset + i, BYTE_TO_FLOAT[bytes[k + 2].toInt() and 0xFF])
                k += 4
            }
            done += n
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
        val gOffset = planeSize
        val bOffset = 2 * planeSize

        val s = src.duplicate()
        val d = dst.duplicate()
        d.clear()
        d.position(0)
        val rArr = FloatArray(CHUNK_PIXELS)
        val gArr = FloatArray(CHUNK_PIXELS)
        val bArr = FloatArray(CHUNK_PIXELS)
        val bytes = ByteArray(CHUNK_PIXELS * 4)
        var done = 0
        while (done < planeSize) {
            val n = minOf(CHUNK_PIXELS, planeSize - done)
            s.clear(); s.position(done); s.get(rArr, 0, n)
            s.clear(); s.position(gOffset + done); s.get(gArr, 0, n)
            s.clear(); s.position(bOffset + done); s.get(bArr, 0, n)
            var k = 0
            for (i in 0 until n) {
                bytes[k] = toByte(rArr[i])
                bytes[k + 1] = toByte(gArr[i])
                bytes[k + 2] = toByte(bArr[i])
                bytes[k + 3] = 255.toByte()
                k += 4
            }
            d.put(bytes, 0, n * 4)
            done += n
        }
    }

    private fun toByte(v: Float): Byte = (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt().coerceIn(0, 255).toByte()

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
