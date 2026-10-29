package com.beatraxus.app.fpsbooster

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Builds the programs of `flow_interp.frag` (one source file, three programs; see the header of that file):
 *  - default: the hand-tuned trust logic (what shipped before the AI fusion existed),
 *  - [build] with `ai = true`: composite that reads the fusion map,
 *  - [build] with `fuse = true`: the pass that writes the fusion map (needs the generated `ai_fusion.glsl` weights).
 *
 * The `#define`s are inserted right after the `#version` line, which must be the first line of the source.
 * Mirror of `interp_source` in tools/fpsbooster_harness/new_engine.py: change both together.
 * Pure string work, so it is unit-tested on the JVM (InterpShaderSourceTest).
 */
object InterpShaderSource {
    /** Placeholder line in flow_interp.frag that is replaced by the contents of ai_fusion.glsl. */
    const val WEIGHTS_MARKER = "// @AI_FUSION_WEIGHTS@"

    /** Longest side (px) of the fusion map: the motion-search size, capped. Keep in sync with FUSE_MAX_EDGE in new_engine.py. */
    const val FUSE_MAX_EDGE = 960

    fun build(source: String, ai: Boolean = false, fuse: Boolean = false, weights: String? = null): String {
        val nl = source.indexOf('\n')
        require(nl > 0 && source.startsWith("#version")) { "shader must start with a #version line" }
        val head = source.substring(0, nl)
        var rest = source.substring(nl + 1)
        if (fuse) {
            requireNotNull(weights) { "the fusion pass needs the ai_fusion.glsl weights" }
            require(rest.contains(WEIGHTS_MARKER)) { "flow_interp.frag has no $WEIGHTS_MARKER line" }
            rest = rest.replace(WEIGHTS_MARKER, weights)
        }
        val defs = "#define AI_FUSION ${if (ai) 1 else 0}\n#define FUSE_PASS ${if (fuse) 1 else 0}\n#define AI_DUMP 0\n"
        return head + "\n" + defs + rest
    }

    /** Size of the fusion map for a motion-search image of [workW] x [workH]. */
    fun fuseSize(workW: Int, workH: Int): Pair<Int, Int> {
        val sc = min(1f, FUSE_MAX_EDGE.toFloat() / max(workW, workH))
        return max(16, (workW * sc).roundToInt()) to max(16, (workH * sc).roundToInt())
    }
}
