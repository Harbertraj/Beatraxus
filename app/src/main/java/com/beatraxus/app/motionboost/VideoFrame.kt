package com.beatraxus.app.motionboost

import android.opengl.GLES20

/**
 * Wraps a GLES texture representing a decoded video frame.
 *
 * @property textureId The GLES texture ID.
 * @property width Width of the frame in pixels.
 * @property height Height of the frame in pixels.
 * @property presentationTimeUs Presentation timestamp in microseconds.
 */
data class VideoFrame(
    val textureId: Int,
    val width: Int,
    val height: Int,
    val presentationTimeUs: Long
) {
    /**
     * No-op release. Textures from Media3 Effect pipeline are managed by the pipeline.
     */
    fun release() {
        // Do nothing. Pipeline manages texture lifecycle.
    }
}
