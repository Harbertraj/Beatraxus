package com.beatraxus.app.ui.utils

import android.graphics.ColorMatrixColorFilter
import android.graphics.ColorMatrix
import android.graphics.RenderEffect as AndroidRenderEffect
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.asComposeRenderEffect

/**
 * Helper to safely handle [RenderEffect] which is only available on Android 12 (API 31) and above.
 */
object RenderEffectHelper {

    /**
     * Creates a blur effect. Returns null on Android < 12.
     */
    fun createBlurEffect(radiusX: Float, radiusY: Float): RenderEffect? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        return Api31Impl.createBlurEffect(radiusX, radiusY, Shader.TileMode.DECAL)
    }

    /**
     * Creates a color filter effect with saturation. Returns null on Android < 12.
     */
    fun createSaturationEffect(saturation: Float): RenderEffect? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        val matrix = ColorMatrix().apply { setSaturation(saturation) }
        return Api31Impl.createColorFilterEffect(ColorMatrixColorFilter(matrix))
    }

    /**
     * Creates a chained Blur + Saturation effect.
     */
    fun createBlurAndSaturationEffect(blurRadius: Float, saturation: Float): RenderEffect? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        return Api31Impl.createBlurAndSaturationEffect(blurRadius, saturation)
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private object Api31Impl {
        fun createBlurEffect(radiusX: Float, radiusY: Float, tileMode: Shader.TileMode): RenderEffect {
            return AndroidRenderEffect.createBlurEffect(radiusX, radiusY, tileMode).asComposeRenderEffect()
        }

        fun createColorFilterEffect(filter: ColorMatrixColorFilter): RenderEffect {
            return AndroidRenderEffect.createColorFilterEffect(filter).asComposeRenderEffect()
        }

        fun createBlurAndSaturationEffect(blurRadius: Float, saturation: Float): RenderEffect {
            val blur = AndroidRenderEffect.createBlurEffect(blurRadius, blurRadius, Shader.TileMode.DECAL)
            val matrix = ColorMatrix().apply { setSaturation(saturation) }
            val colorFilter = AndroidRenderEffect.createColorFilterEffect(ColorMatrixColorFilter(matrix))
            return AndroidRenderEffect.createChainEffect(blur, colorFilter).asComposeRenderEffect()
        }
    }
}
