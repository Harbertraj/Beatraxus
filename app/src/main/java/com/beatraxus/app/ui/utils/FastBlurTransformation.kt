package com.beatraxus.app.ui.utils

import android.graphics.Bitmap
import coil.size.Size
import coil.transform.Transformation
import kotlin.math.roundToInt

class FastBlurTransformation(private val radius: Float) : Transformation {

    override val cacheKey: String = "${FastBlurTransformation::class.java.name}-$radius"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        // Downscale for performance: StackBlur is CPU-heavy. 
        // 120-200px wide is plenty for a background blur.
        val scale = if (input.width > 200) 200f / input.width else 1f
        val outW = (input.width * scale).roundToInt().coerceAtLeast(1)
        val outH = (input.height * scale).roundToInt().coerceAtLeast(1)
        
        val scaled = Bitmap.createScaledBitmap(input, outW, outH, true)
        
        // Scale the radius relatively. If radius was for full-res, it should be smaller for downscaled.
        val scaledRadius = (radius * scale).roundToInt().coerceIn(1, 25)
        
        val blurred = FastBlur.blur(scaled, scaledRadius)
        
        return blurred ?: scaled
    }
}
