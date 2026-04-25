package com.beatflowy.app.ui.components

import android.graphics.RenderEffect as AndroidRenderEffect
import android.graphics.Shader
import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.beatflowy.app.ui.theme.AccentBlue
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * Full-screen dimming scrim + bubble-shaped menu positioned relative to [anchorBounds].
 * If anchor is in the bottom part of screen, menu shows ABOVE.
 * If anchor is in the top part of screen, menu shows BELOW.
 */
@Composable
fun GlassMenuPopup(
    expanded: Boolean,
    onDismiss: () -> Unit,
    /** Icon (or control) bounds in root coordinates; use [Rect.Zero] until first layout. */
    anchorBounds: Rect,
    cardWidth: Dp = 170.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    if (!expanded) return

    Popup(
        onDismissRequest = onDismiss,
        properties = PopupProperties(
            focusable = true,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        val scale = remember { Animatable(0.88f) }
        val fade = remember { Animatable(0f) }
        LaunchedEffect(Unit) {
            launch { scale.animateTo(1f, tween(220, easing = FastOutSlowInEasing)) }
            fade.animateTo(1f, tween(180, easing = FastOutSlowInEasing))
        }

        BoxWithConstraints(Modifier.fillMaxSize()) {
            val density = LocalDensity.current
            val cardWpx = with(density) { cardWidth.toPx() }
            val maxWpx = with(density) { maxWidth.toPx() }
            val maxHpx = with(density) { maxHeight.toPx() }
            val hasAnchor = anchorBounds.width > 1f && anchorBounds.height > 1f
            val anchorCx = if (hasAnchor) anchorBounds.center.x else maxWpx / 2f
            
            // Auto-position: if anchor is in the bottom 40% of the screen, show above. 
            // Otherwise show below (especially for "top icons").
            val showAbove = hasAnchor && anchorBounds.top > maxHpx * 0.6f

            var menuLeftPx = anchorCx - cardWpx / 2f
            menuLeftPx = menuLeftPx.coerceIn(8f, maxWpx - cardWpx - 8f)

            val scrimBlur = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Modifier.graphicsLayer {
                    renderEffect = AndroidRenderEffect.createBlurEffect(
                        20f,
                        20f,
                        Shader.TileMode.CLAMP
                    ).asComposeRenderEffect()
                }
            } else {
                Modifier
            }

            Box(
                Modifier
                    .fillMaxSize()
                    .then(scrimBlur)
                    .graphicsLayer { alpha = fade.value.coerceIn(0f, 1f) }
                    .drawBehind {
                        if (hasAnchor) {
                            val fullRectPath = Path().apply {
                                addRect(Rect(0f, 0f, size.width, size.height))
                            }
                            val holePath = Path().apply {
                                // Create a circular or rounded-rect hole for the icon
                                addOval(anchorBounds.inflate(2.dp.toPx()))
                            }
                            val combinedPath = Path.combine(
                                operation = PathOperation.Difference,
                                path1 = fullRectPath,
                                path2 = holePath
                            )
                            drawPath(
                                path = combinedPath,
                                color = Color.Black.copy(alpha = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) 0.45f else 0.85f)
                            )
                        } else {
                            drawRect(color = Color.Black.copy(alpha = 0.75f))
                        }
                    }
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) { onDismiss() }
            )

            val arrowWidth = 28.dp
            val arrowHeight = 14.dp
            val cornerRadius = 26.dp
            val arrowCxRelativeToCard = anchorCx - menuLeftPx
            
            val bubbleShape = remember(density, arrowCxRelativeToCard, showAbove) {
                GenericShape { size, _ ->
                    val cr = with(density) { cornerRadius.toPx() }
                    val aw = with(density) { arrowWidth.toPx() }
                    val ah = with(density) { arrowHeight.toPx() }
                    val acx = arrowCxRelativeToCard.coerceIn(cr + aw / 2f, size.width - cr - aw / 2f)

                    if (!showAbove) { // Arrow at Top
                        moveTo(cr, ah)
                        // Arrow
                        lineTo(acx - aw / 2f, ah)
                        lineTo(acx - 6f, 6f)
                        quadraticTo(acx, 0f, acx + 6f, 6f)
                        lineTo(acx + aw / 2f, ah)
                        
                        lineTo(size.width - cr, ah)
                        arcTo(Rect(size.width - 2 * cr, ah, size.width, ah + 2 * cr), 270f, 90f, false)
                        lineTo(size.width, size.height - cr)
                        arcTo(Rect(size.width - 2 * cr, size.height - 2 * cr, size.width, size.height), 0f, 90f, false)
                        lineTo(cr, size.height)
                        arcTo(Rect(0f, size.height - 2 * cr, 2 * cr, size.height), 90f, 90f, false)
                        lineTo(0f, ah + cr)
                        arcTo(Rect(0f, ah, 2 * cr, ah + 2 * cr), 180f, 90f, false)
                    } else { // Arrow at Bottom
                        moveTo(cr, 0f)
                        lineTo(size.width - cr, 0f)
                        arcTo(Rect(size.width - 2 * cr, 0f, size.width, 2 * cr), 270f, 90f, false)
                        lineTo(size.width, size.height - ah - cr)
                        arcTo(Rect(size.width - 2 * cr, size.height - ah - 2 * cr, size.width, size.height - ah), 0f, 90f, false)
                        
                        // Arrow
                        lineTo(acx + aw / 2f, size.height - ah)
                        lineTo(acx + 6f, size.height - 6f)
                        quadraticTo(acx, size.height, acx - 6f, size.height - 6f)
                        lineTo(acx - aw / 2f, size.height - ah)
                        
                        lineTo(cr, size.height - ah)
                        arcTo(Rect(0f, size.height - ah - 2 * cr, 2 * cr, size.height - ah), 90f, 90f, false)
                        lineTo(0f, cr)
                        arcTo(Rect(0f, 0f, 2 * cr, 2 * cr), 180f, 90f, false)
                    }
                    close()
                }
            }

            if (showAbove) {
                val anchorTop = anchorBounds.top
                val overlapPx = with(density) { 20.dp.toPx() }
                Box(
                    modifier = Modifier
                        .offset { IntOffset(menuLeftPx.roundToInt(), (anchorTop + overlapPx).roundToInt() - maxHeight.roundToPx()) }
                        .width(cardWidth)
                        .height(maxHeight),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    MenuCard(scale.value, fade.value, bubbleShape, arrowHeight, false, content)
                }
            } else {
                val anchorBottom = if (hasAnchor) anchorBounds.bottom else with(density) { 100.dp.toPx() }
                val overlapPx = with(density) { 20.dp.toPx() }
                Box(
                    modifier = Modifier
                        .offset { IntOffset(menuLeftPx.roundToInt(), (anchorBottom - overlapPx).roundToInt()) }
                        .width(cardWidth)
                        .wrapContentHeight()
                ) {
                    MenuCard(scale.value, fade.value, bubbleShape, arrowHeight, true, content)
                }
            }
        }
    }
}

@Composable
private fun MenuCard(
    scale: Float,
    alpha: Float,
    cardShape: Shape,
    arrowHeight: Dp,
    isArrowAtTop: Boolean,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(22.dp, cardShape)
            .clip(cardShape)
            .background(Color(0xF21A1A22))
            .border(
                width = 1.dp,
                brush = Brush.verticalGradient(
                    listOf(
                        AccentBlue.copy(alpha = 0.32f),
                        Color.White.copy(alpha = 0.07f)
                    )
                ),
                shape = cardShape
            )
            .padding(
                top = if (isArrowAtTop) arrowHeight + 4.dp else 4.dp,
                bottom = if (!isArrowAtTop) arrowHeight + 4.dp else 4.dp
            )
            .graphicsLayer {
                this.scaleX = scale
                this.scaleY = scale
                this.alpha = alpha
            },
        content = content
    )
}
