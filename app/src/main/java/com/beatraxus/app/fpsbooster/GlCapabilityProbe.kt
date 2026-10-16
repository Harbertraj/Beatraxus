package com.beatraxus.app.fpsbooster

import android.app.ActivityManager
import android.content.Context
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLExt
import android.opengl.GLES30
import com.beatraxus.app.motionboost.DisplayRefreshRate

/** Queries what the GPU / panel can do, for [FpsBoosterPlanner]. Result is cached for the process. */
object GlCapabilityProbe {

    /** Pure: do these GL_EXTENSIONS allow rendering to RGBA16F textures? */
    fun hasFloatRenderTargets(extensions: String?): Boolean {
        if (extensions.isNullOrEmpty()) return false
        val set = extensions.split(' ').toHashSet()
        return "GL_EXT_color_buffer_float" in set || "GL_EXT_color_buffer_half_float" in set
    }

    private data class Gpu(val glEs3: Boolean, val floatRt: Boolean)

    @Volatile private var cached: Gpu? = null

    /** Creates (and destroys) a 1x1 pbuffer ES3 context once. Call off the main thread; it takes a few ms. */
    private fun gpu(): Gpu {
        cached?.let { return it }
        val result = runCatching { probe() }.getOrDefault(Gpu(false, false))
        cached = result
        return result
    }

    private fun probe(): Gpu {
        val dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (dpy == EGL14.EGL_NO_DISPLAY) return Gpu(false, false)
        val v = IntArray(2)
        if (!EGL14.eglInitialize(dpy, v, 0, v, 1)) return Gpu(false, false)
        try {
            val cfgs = arrayOfNulls<EGLConfig>(1)
            val n = IntArray(1)
            val attrs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE
            )
            if (!EGL14.eglChooseConfig(dpy, attrs, 0, cfgs, 0, 1, n, 0) || n[0] < 1) return Gpu(false, false)
            val ctx = EGL14.eglCreateContext(
                dpy, cfgs[0], EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0
            )
            if (ctx == EGL14.EGL_NO_CONTEXT) return Gpu(false, false)
            val surf = EGL14.eglCreatePbufferSurface(
                dpy, cfgs[0], intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0
            )
            try {
                if (!EGL14.eglMakeCurrent(dpy, surf, surf, ctx)) return Gpu(false, false)
                val ext = GLES30.glGetString(GLES30.GL_EXTENSIONS)
                return Gpu(true, hasFloatRenderTargets(ext))
            } finally {
                EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (surf != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(dpy, surf)
                EGL14.eglDestroyContext(dpy, ctx)
            }
        } finally {
            EGL14.eglTerminate(dpy)
        }
    }

    fun query(context: Context): DeviceCaps {
        val gpu = gpu()
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        return DeviceCaps(
            panelRates = DisplayRefreshRate.supportedRates(context),
            glEs3 = gpu.glEs3,
            floatRenderTargets = gpu.floatRt,
            lowRamDevice = am?.isLowRamDevice ?: false
        )
    }
}
