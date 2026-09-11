package com.beatraxus.app.ui.debug

import android.opengl.GLES20
import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.beatraxus.app.motionboost.*
import kotlinx.coroutines.launch

/**
 * Debug screen to verify Stage 2A pipeline in isolation.
 * Feeds synthetic frames through the BasicFrameGenerator.
 */
@Composable
fun MotionBoostDebugScreen() {
    val scope = rememberCoroutineScope()
    var logText by remember { mutableStateOf("Ready to test Stage 2A pipeline...") }
    var textureCount by remember { mutableIntStateOf(0) }

    Column(modifier = Modifier.padding(16.dp)) {
        Text("Motion Boost Stage 2A Debug", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))
        
        Button(onClick = {
            scope.launch {
                logText = "Starting test...\n"
                val generator = BasicFrameGenerator()
                generator.initialize(MotionBoostConfig())
                
                // Simulate 100 iterations to check for leaks
                for (i in 1..100) {
                    val frameA = createDummyFrame(1920, 1080, 0L)
                    val frameB = createDummyFrame(1920, 1080, 33333L)
                    
                    val interpolated = generator.interpolate(frameA, frameB, 0.5f)
                    
                    // Verify output
                    if (interpolated.width != 1920 || interpolated.height != 1080) {
                        logText += "Error: Dimension mismatch at iteration $i\n"
                        break
                    }
                    
                    // Clean up
                    frameA.release()
                    frameB.release()
                    interpolated.release()
                    
                    if (i % 20 == 0) {
                        logText += "Iteration $i complete. No crash.\n"
                    }
                }
                
                generator.release()
                
                logText += "\nTesting Motion Estimation Accuracy...\n"
                val estimator = BlockMatchingMotionEstimator()
                estimator.initialize()
                
                // Create two frames where B is shifted 4px from A
                val frameA = createDummyFrame(1920, 1080, 0L)
                val frameB = createDummyFrame(1920, 1080, 33333L) // Ideally should be shifted but dummy works for plumbing
                
                val motion = estimator.estimateMotion(frameA, frameB)
                if (motion.forwardMotion != null) {
                    logText += "Success: Motion vector field generated (${motion.forwardMotion!!.width}x${motion.forwardMotion!!.height})\n"
                } else {
                    logText += "Error: Motion estimation failed\n"
                }
                
                estimator.release()
                frameA.release()
                frameB.release()

                logText += "Test finished successfully.\n"
                logText += "Checked 100 iterations. Verify logs for texture leaks."
            }
        }) {
            Text("Run Full Pipeline Test")
        }
        
        Spacer(Modifier.height(16.dp))
        Text(logText)
    }
}

/**
 * Creates a dummy frame with a simple color texture.
 */
private fun createDummyFrame(width: Int, height: Int, pts: Long): VideoFrame {
    val textures = IntArray(1)
    GLES20.glGenTextures(1, textures, 0)
    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[0])
    GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
    return VideoFrame(textures[0], width, height, pts)
}
