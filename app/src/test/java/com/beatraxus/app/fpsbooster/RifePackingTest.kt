package com.beatraxus.app.fpsbooster

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.abs

class RifePackingTest {

    @Test
    fun rgbaToPlanarAndBackRoundTrip() {
        val w = 4
        val h = 2
        val pixelCount = w * h
        val sampleValues = intArrayOf(0, 1, 127, 128, 200, 254, 255, 64)

        val srcRgba = ByteBuffer.allocateDirect(pixelCount * 4).order(ByteOrder.nativeOrder())
        for (i in 0 until pixelCount) {
            val v = sampleValues[i]
            srcRgba.put((i * 4), v.toByte())
            srcRgba.put((i * 4 + 1), (255 - v).toByte())
            srcRgba.put((i * 4 + 2), ((v * 2) % 256).toByte())
            srcRgba.put((i * 4 + 3), 255.toByte())
        }

        val planar = ByteBuffer.allocateDirect(3 * pixelCount * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        RifePacking.rgbaToPlanar(srcRgba, w, h, planar, 0)

        val dstRgba = ByteBuffer.allocateDirect(pixelCount * 4).order(ByteOrder.nativeOrder())
        RifePacking.planarToRgba(planar, w, h, dstRgba)

        for (i in 0 until pixelCount * 4) {
            val expected = srcRgba.get(i).toInt() and 0xFF
            val actual = dstRgba.get(i).toInt() and 0xFF
            assertTrue("Byte at $i ($actual) differed from expected ($expected) by more than 1", abs(expected - actual) <= 1)
        }
    }

    @Test
    fun fillPlaneFillsSpecifiedRegion() {
        val totalFloats = 50
        val planeW = 4
        val planeH = 3
        val planeSize = planeW * planeH // 12
        val offset = 10

        val dst = FloatBuffer.allocate(totalFloats)
        for (i in 0 until totalFloats) dst.put(i, 0.0f)

        RifePacking.fillPlane(dst, offset, planeW, planeH, 0.5f)

        for (i in 0 until offset) {
            assertEquals(0.0f, dst.get(i), 0.0001f)
        }
        for (i in offset until offset + planeSize) {
            assertEquals(0.5f, dst.get(i), 0.0001f)
        }
        for (i in (offset + planeSize) until totalFloats) {
            assertEquals(0.0f, dst.get(i), 0.0001f)
        }
    }

    @Test
    fun modelSizeProducesMultiplesOf32AndPreservesAspect() {
        val testCases = listOf(
            Triple(1920, 1080, 640),
            Triple(1920, 1080, 960),
            Triple(1920, 1080, 1280),
            Triple(1080, 1920, 640), // Portrait
            Triple(1280, 720, 640),
            Triple(100, 100, 640)
        )

        for ((srcW, srcH, longEdge) in testCases) {
            val (mw, mh) = RifePacking.modelSize(srcW, srcH, longEdge)

            assertTrue("mw ($mw) must be a multiple of 32", mw % 32 == 0)
            assertTrue("mh ($mh) must be a multiple of 32", mh % 32 == 0)
            assertTrue("mw ($mw) must be >= 64", mw >= 64)
            assertTrue("mh ($mh) must be >= 64", mh >= 64)

            val srcAspect = srcW.toFloat() / srcH
            val modelAspect = mw.toFloat() / mh
            val aspectError = abs(modelAspect - srcAspect) / srcAspect
            assertTrue("Aspect error $aspectError for (${srcW}x${srcH} -> ${mw}x${mh}) must be <= 0.03", aspectError <= 0.03f)
        }
    }
}
