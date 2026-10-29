package com.beatraxus.app.fpsbooster

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InterpShaderSourceTest {
    private val dir: File = listOf("src/main/assets/fpsbooster", "app/src/main/assets/fpsbooster")
        .map(::File).first { it.isDirectory }
    private val src = File(dir, "flow_interp.frag").readText()

    @Test fun defaultProgramOnlyAddsDisabledDefines() {
        val out = InterpShaderSource.build(src)
        assertTrue(out.startsWith("#version 300 es\n#define AI_FUSION 0\n#define FUSE_PASS 0\n#define AI_DUMP 0\n"))
        assertTrue(out.endsWith(src.substringAfter('\n')))
    }

    @Test fun compositeProgramEnablesOnlyAiFusion() {
        val out = InterpShaderSource.build(src, ai = true)
        assertTrue(out.contains("#define AI_FUSION 1\n#define FUSE_PASS 0\n"))
        assertTrue(out.contains(InterpShaderSource.WEIGHTS_MARKER)) // untouched: only the fuse program takes weights
    }

    @Test fun fuseProgramInsertsTheGeneratedNetwork() {
        val weights = File(dir, "ai_fusion.glsl").readText()
        val out = InterpShaderSource.build(src, fuse = true, weights = weights)
        assertTrue(out.contains("#define FUSE_PASS 1"))
        assertFalse(out.contains(InterpShaderSource.WEIGHTS_MARKER))
        assertTrue(out.contains("void aiNet(in float x[AI_NF], out float w[AI_NK])"))
    }

    @Test(expected = IllegalArgumentException::class) fun fuseWithoutWeightsIsRejected() {
        InterpShaderSource.build(src, fuse = true)
    }

    @Test fun generatedNetworkMatchesTheShaderContract() {
        val net = File(dir, "ai_fusion.glsl").readText()
        assertTrue(net.contains("const int AI_NF = 22;"))
        assertTrue(net.contains("const int AI_NK = 6;"))
        assertFalse("no out-of-range input index", Regex("""x\[(2[2-9]|[3-9]\d)\]""").containsMatchIn(net))
        assertFalse("the Kotlin template character", net.contains('$'))
    }

    @Test fun fuseSizeIsCappedAndKeepsAspect() {
        assertEquals(640 to 360, InterpShaderSource.fuseSize(640, 360))
        assertEquals(960 to 540, InterpShaderSource.fuseSize(1280, 720))
        assertEquals(960 to 400, InterpShaderSource.fuseSize(1920, 800))
        assertEquals(16 to 16, InterpShaderSource.fuseSize(16, 16))
    }
}
