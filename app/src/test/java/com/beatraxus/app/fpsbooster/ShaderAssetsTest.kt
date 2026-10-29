package com.beatraxus.app.fpsbooster

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cheap guard for the GLSL assets the Kotlin renderer loads at runtime (shader compilation itself needs a GPU;
 * see tools/fpsbooster_harness for that). It catches a renamed/removed uniform or a missing file, which would
 * otherwise only show up as a runtime fallback on a phone.
 */
class ShaderAssetsTest {
    private val dir: File = listOf("src/main/assets/fpsbooster", "app/src/main/assets/fpsbooster")
        .map(::File).first { it.isDirectory }

    /** shader file -> uniforms OpticalFlowRenderer sets on it. Keep in sync with the renderer. */
    private val expected = mapOf(
        "luma_work.frag" to listOf("uSrc", "uTexel"),
        "pyramid_down.frag" to listOf("uSrc", "uSrcSize"),
        "flow_search.frag" to listOf(
            "uRef", "uTgt", "uParent", "uGlobal", "uSize", "uGrid", "uParentGrid", "uScale", "uGlobalScale",
            "uMode", "uRange", "uSubpel", "uFullCost", "uLambda"
        ),
        "global_motion.frag" to listOf("uFlow", "uGrid", "uSamples", "uMaxCost"),
        "flow_consistency.frag" to listOf("uSelf", "uOther", "uGrid"),
        "cut_detect.frag" to listOf("uFlow", "uGrid", "uMeanThresh", "uPoorThresh", "uCons", "uConsGrid", "uBadPx"),
        "flow_interp.frag" to listOf("uA", "uB", "uFw", "uBw", "uCut", "uGlobal", "uT", "uWarp", "uWork", "uFlowUv", "uGrade", "uColorDelta", "uConsLo", "uConsHi", "uSharp", "uFuse0", "uFuse1")
    )

    @Test fun everyShaderExistsAndIsEs3() {
        expected.keys.forEach { name ->
            val f = File(dir, name)
            assertTrue("$name missing", f.isFile)
            assertTrue("$name must start with the ES 3.0 version line", f.readText().startsWith("#version 300 es"))
        }
    }

    @Test fun shadersDeclareTheUniformsTheRendererSets() {
        expected.forEach { (name, uniforms) ->
            val src = File(dir, name).readText()
            uniforms.forEach { u ->
                assertTrue("$name: uniform $u not declared", Regex("""uniform\s+\w+\s+$u\s*;""").containsMatchIn(src))
            }
        }
    }

    @Test fun noKotlinTemplateCharactersInShaders() {
        dir.listFiles { f -> f.name.endsWith(".frag") }!!.forEach { assertFalse(it.name, it.readText().contains('$')) }
    }

    @Test fun noUnexpectedShaderFiles() {
        assertEquals(expected.keys, dir.listFiles { f -> f.name.endsWith(".frag") }!!.map { it.name }.toSet())
    }

    @Test fun floatRenderTargetExtensionCheck() {
        assertTrue(GlCapabilityProbe.hasFloatRenderTargets("GL_OES_foo GL_EXT_color_buffer_float GL_bar"))
        assertTrue(GlCapabilityProbe.hasFloatRenderTargets("GL_EXT_color_buffer_half_float"))
        assertFalse(GlCapabilityProbe.hasFloatRenderTargets("GL_OES_texture_float"))
        assertFalse(GlCapabilityProbe.hasFloatRenderTargets(""))
        assertFalse(GlCapabilityProbe.hasFloatRenderTargets(null))
    }
}
