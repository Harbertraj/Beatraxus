package com.beatraxus.app.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun PremiumSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val interactionSource = remember { MutableInteractionSource() }
    
    val thumbOffset by animateDpAsState(
        targetValue = if (checked) 28.dp else 0.dp,
        animationSpec = tween(durationMillis = 300),
        label = "thumbOffset"
    )
    
    val indicatorColor = if (checked) Color(0xFFFF9800) else Color(0xFF1A1D24)
    val indicatorGlow by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = tween(300),
        label = "indicatorGlow"
    )

    Box(
        modifier = modifier
            .width(64.dp)
            .height(36.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xFF0F1218)) // Deep background
            .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(18.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled
            ) {
                onCheckedChange?.invoke(!checked)
            },
        contentAlignment = Alignment.CenterStart
    ) {
        // "ON" / "OFF" Text
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "ON",
                color = if (checked) Color(0xFFFF9800).copy(alpha = 0.9f) else Color.Transparent,
                fontSize = 11.sp,
                fontWeight = FontWeight.Black,
                modifier = Modifier.graphicsLayer {
                    alpha = if (checked) 1f else 0f
                }
            )
            Text(
                text = "OFF",
                color = if (!checked) Color(0xFF343D49) else Color.Transparent,
                fontSize = 11.sp,
                fontWeight = FontWeight.Black,
                modifier = Modifier.graphicsLayer {
                    alpha = if (!checked) 1f else 0f
                }
            )
        }

        // Thumb
        Box(
            modifier = Modifier
                .padding(2.dp)
                .offset(x = thumbOffset)
                .size(32.dp)
                .shadow(4.dp, CircleShape)
                .clip(CircleShape)
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xFF2C323D), Color(0xFF161921))
                    )
                )
                .border(1.dp, Color.White.copy(alpha = 0.08f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            // Small central light
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(indicatorColor)
                    .graphicsLayer {
                        if (checked) {
                            shadowElevation = 8f * indicatorGlow
                        }
                    }
                    .border(
                        1.dp, 
                        if (checked) Color.White.copy(0.3f) else Color.Black.copy(0.3f), 
                        CircleShape
                    )
            )
            
            // Glow effect
            if (checked) {
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .graphicsLayer { alpha = 0.3f * indicatorGlow }
                        .background(Brush.radialGradient(listOf(Color(0xFFFF9800), Color.Transparent)), CircleShape)
                )
            }
        }
    }
}
