package com.beatraxus.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoOrientationTest {

    private val all: List<VideoOrientation> = (0..3).flatMap { turns ->
        listOf(false, true).flatMap { h -> listOf(false, true).map { v -> VideoOrientation(turns, h, v) } }
    }

    /** Applies android.graphics.Matrix.setValues() order values to a pixel point. */
    private fun map(m: FloatArray, x: Float, y: Float): Pair<Float, Float> =
        (m[0] * x + m[1] * y + m[2]) to (m[3] * x + m[4] * y + m[5])

    private fun assertPoint(expected: Pair<Float, Float>, actual: Pair<Float, Float>) {
        assertEquals(expected.first, actual.first, 0.001f)
        assertEquals(expected.second, actual.second, 0.001f)
    }

    @Test
    fun noneIsIdentityEverywhere() {
        val o = VideoOrientation.NONE
        assertTrue(o.isIdentity)
        assertFalse(o.swapsAxes)
        assertEquals(0, o.rotationDegrees)
        assertEquals(listOf(1f, 0f, 0f, 1f), o.videoFromScreen().toList())
        assertEquals(
            listOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f),
            o.textureMatrixValues(200f, 100f).toList()
        )
    }

    @Test
    fun turnsAreNormalisedToZeroThroughThree() {
        assertEquals(3, VideoOrientation(quarterTurns = -1).turns)
        assertEquals(1, VideoOrientation(quarterTurns = 5).turns)
        assertTrue(VideoOrientation(quarterTurns = 4).isIdentity)
    }

    @Test
    fun rotateCyclesThroughFourStepsBackToStart() {
        var o = VideoOrientation.NONE
        val degrees = mutableListOf<Int>()
        repeat(4) { o = o.rotatedClockwise(); degrees += o.rotationDegrees }
        assertEquals(listOf(90, 180, 270, 0), degrees)
        assertTrue(o.isIdentity)
    }

    @Test
    fun onlyQuarterTurnsSwapTheAxes() {
        assertFalse(VideoOrientation(0).swapsAxes)
        assertTrue(VideoOrientation(1).swapsAxes)
        assertFalse(VideoOrientation(2).swapsAxes)
        assertTrue(VideoOrientation(3).swapsAxes)
        // flips never change the shape
        assertFalse(VideoOrientation(0, flipHorizontal = true, flipVertical = true).swapsAxes)
    }

    @Test
    fun flipsAreNotIdentityAndRotatingKeepsThem() {
        val o = VideoOrientation.NONE.withFlipHorizontal(true)
        assertFalse(o.isIdentity)
        val r = o.rotatedClockwise()
        assertTrue(r.flipHorizontal)
        assertEquals(1, r.turns)
    }

    @Test
    fun textureMatrixRotatesClockwise() {
        // view 200 x 100: the top-centre of the picture goes to the right-centre when turned 90 degrees clockwise
        val m = VideoOrientation(1).textureMatrixValues(200f, 100f)
        assertPoint(200f to 50f, map(m, 100f, 0f))
        // and the top-left corner goes to the top-right corner
        assertPoint(200f to 0f, map(m, 0f, 0f))
    }

    @Test
    fun textureMatrixHalfAndThreeQuarterTurns() {
        assertPoint(200f to 100f, map(VideoOrientation(2).textureMatrixValues(200f, 100f), 0f, 0f))
        // 270 degrees clockwise: the top-centre goes to the left-centre
        assertPoint(0f to 50f, map(VideoOrientation(3).textureMatrixValues(200f, 100f), 100f, 0f))
    }

    @Test
    fun mirrorSwapsLeftAndRightAndFlipSwapsTopAndBottom() {
        val mirror = VideoOrientation(flipHorizontal = true).textureMatrixValues(200f, 100f)
        assertPoint(200f to 0f, map(mirror, 0f, 0f))
        assertPoint(0f to 100f, map(mirror, 200f, 100f))

        val flip = VideoOrientation(flipVertical = true).textureMatrixValues(200f, 100f)
        assertPoint(0f to 100f, map(flip, 0f, 0f))
        assertPoint(200f to 0f, map(flip, 200f, 100f))
    }

    @Test
    fun mirrorMeansLeftRightAsSeenAfterRotation() {
        // Rotated 90 degrees, then mirrored: the picture's top (which is now on the right) must end up on the left.
        val m = VideoOrientation(1, flipHorizontal = true).textureMatrixValues(200f, 100f)
        assertPoint(0f to 50f, map(m, 100f, 0f))
    }

    @Test
    fun everyOrientationKeepsTheCentreAndMapsCornersToCorners() {
        val w = 320f
        val h = 180f
        val corners = setOf(0f to 0f, w to 0f, 0f to h, w to h)
        all.forEach { o ->
            val m = o.textureMatrixValues(w, h)
            assertPoint(w / 2 to h / 2, map(m, w / 2, h / 2))
            val mapped = corners.map { (x, y) -> map(m, x, y) }
            mapped.forEach { p ->
                assertTrue("$o: $p is not a corner", corners.any { c -> Math.abs(c.first - p.first) < 0.01f && Math.abs(c.second - p.second) < 0.01f })
            }
            assertEquals("$o: corners must stay distinct", 4, mapped.map { "%.1f,%.1f".format(it.first, it.second) }.toSet().size)
        }
    }

    @Test
    fun shaderMatrixAgreesWithTextureMatrixForEveryOrientation() {
        // Take a picture point, send it through the TextureView matrix to the screen, then ask the shader matrix
        // where that screen point reads from: it must be the point we started with.
        val w = 400f
        val h = 150f
        val samples = listOf(0.1f to 0.2f, 0.8f to 0.3f, 0.5f to 0.5f, 0.25f to 0.9f)
        all.forEach { o ->
            val tex = o.textureMatrixValues(w, h)
            val g = o.videoFromScreen()                       // column-major mat2: [m00, m10, m01, m11]
            samples.forEach { (u, v) ->
                // picture uv (Y up) -> pixel (Y down) -> screen pixel -> screen uv (Y up)
                val (sx, sy) = map(tex, u * w, (1f - v) * h)
                val su = sx / w
                val sv = 1f - sy / h
                val cx = su - 0.5f
                val cy = sv - 0.5f
                val pu = g[0] * cx + g[2] * cy + 0.5f
                val pv = g[1] * cx + g[3] * cy + 0.5f
                assertEquals("$o u", u, pu, 0.001f)
                assertEquals("$o v", v, pv, 0.001f)
            }
        }
    }

    @Test
    fun describeNamesWhatIsApplied() {
        assertEquals("Original orientation", VideoOrientation.NONE.describe())
        assertEquals("Rotated 90°", VideoOrientation(1).describe())
        assertEquals("Mirrored", VideoOrientation(flipHorizontal = true).describe())
        assertEquals("Flipped vertically", VideoOrientation(flipVertical = true).describe())
        assertEquals("Rotated 180°, mirrored, flipped vertically", VideoOrientation(2, true, true).describe())
    }
}
