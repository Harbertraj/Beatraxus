package com.beatflowy.app.ui.theme

import androidx.compose.foundation.shape.GenericShape
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.abs

/**
 * A Squircle shape implementation.
 * @param smoothing The smoothing factor (0.0 to 1.0). 0.0 is a square, 1.0 is a circle.
 */
fun SquircleShape(smoothing: Float = 0.5f): Shape = GenericShape { size, _ ->
    val path = Path()
    val width = size.width
    val height = size.height
    val radius = (if (width < height) width else height) / 2f
    val centerX = width / 2f
    val centerY = height / 2f

    val exponent = 2f / (1f - smoothing.coerceIn(0f, 0.99f))
    
    val steps = 100
    for (i in 0..steps) {
        val angle = (i.toFloat() / steps) * 2f * Math.PI.toFloat()
        val cos = Math.cos(angle.toDouble()).toFloat()
        val sin = Math.sin(angle.toDouble()).toFloat()
        
        val r = Math.pow(
            (Math.pow(Math.abs(cos).toDouble(), exponent.toDouble()) + 
             Math.pow(Math.abs(sin).toDouble(), exponent.toDouble())), 
            -1.0 / exponent.toDouble()
        ).toFloat() * radius
        
        val x = centerX + r * cos
        val y = centerY + r * sin
        
        if (i == 0) {
            path.moveTo(x, y)
        } else {
            path.lineTo(x, y)
        }
    }
    path.close()
    this.addPath(path)
}
