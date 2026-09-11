package com.beatraxus.app.motionboost

import android.opengl.*
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Handles high-frequency GL rendering for Motion Boost output.
 * Runs on its own thread to avoid blocking the UI.
 */
@UnstableApi
class MotionBoostRenderer {
    private val TAG = "MotionBoostRenderer"
    
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null
    
    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    
    private var outputSurface: Surface? = null
    private val generator = MotionCompensatedFrameGenerator()
    private val isInitialized = AtomicBoolean(false)

    fun start() {
        if (handlerThread != null) return
        handlerThread = HandlerThread("MotionBoostGL").apply {
            start()
            handler = Handler(looper)
        }
        
        handler?.post {
            initEGL()
            runBlocking {
                generator.initialize(MotionBoostConfig()) 
            }
            isInitialized.set(true)
        }
    }

    fun setSurface(surface: Surface?) {
        handler?.post {
            if (eglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, eglSurface)
                eglSurface = EGL14.EGL_NO_SURFACE
            }
            
            outputSurface = surface
            if (surface != null && eglDisplay != EGL14.EGL_NO_DISPLAY) {
                val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
                eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, surface, surfaceAttribs, 0)
                makeCurrent()
            }
        }
    }

    fun render(frameA: VideoFrame, frameB: VideoFrame, alpha: Float) {
        if (!isInitialized.get() || eglSurface == EGL14.EGL_NO_SURFACE) return
        
        handler?.post {
            makeCurrent()
            
            // Log once in a while to confirm activity
            if (System.currentTimeMillis() % 1000 < 16) {
                Log.v(TAG, "Rendering interpolated frame: alpha=$alpha")
            }
            
            // 1. Perform Interpolation
            // Note: In a real implementation, we'd need to share EGL contexts 
            // with Media3 to access frameA.textureId. 
            // For now, we perform a passthrough or stub interpolation.
            
            // 2. Draw to output
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            
            // TODO: Use WarpShaderProgram to draw the result to the screen
            
            EGL14.eglSwapBuffers(eglDisplay, eglSurface)
        }
    }

    private var eglConfig: EGLConfig? = null

    private fun initEGL() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        EGL14.eglInitialize(eglDisplay, version, 0, version, 1)
        
        val configAttribs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0)
        eglConfig = configs[0]
        
        val contextAttribs = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
            EGL14.EGL_NONE
        )
        eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
    }

    private fun makeCurrent() {
        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            Log.e(TAG, "eglMakeCurrent failed")
        }
    }

    fun stop() {
        handler?.post {
            generator.release()
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
                if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
                EGL14.eglTerminate(eglDisplay)
            }
            eglDisplay = EGL14.EGL_NO_DISPLAY
            eglContext = EGL14.EGL_NO_CONTEXT
            eglSurface = EGL14.EGL_NO_SURFACE
            isInitialized.set(false)
            handlerThread?.quitSafely()
        }
    }
}
