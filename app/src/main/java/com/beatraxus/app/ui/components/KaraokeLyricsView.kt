package com.beatraxus.app.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.beatraxus.app.model.LrcLine
import com.beatraxus.app.model.WordTiming
import com.beatraxus.app.repository.LyricsSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Spring-based alternative to LazyListState.animateScrollToItem, which only offers a fixed
 * (non-bouncy) easing curve. Uses LazyListState.scroll{}'s dispatchRawDelta, the standard
 * Compose mechanism for driving a scroll from a custom AnimationSpec — same pattern used
 * for e.g. fling/snap behaviors that need something other than the built-in animation.
 *
 * Falls back to an instant snap for jumps to an item that isn't currently laid out at all
 * (e.g. the user scrubbed the seek bar far ahead) — bouncing across a large off-screen
 * distance wouldn't read as an intentional "line change" animation, just a slow scroll.
 */
private suspend fun LazyListState.bouncyScrollToItem(index: Int, targetOffset: Int) {
    val itemInfo = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
    if (itemInfo == null) {
        // LazyListState.scrollToItem's scrollOffset uses the OPPOSITE sign convention from
        // our targetOffset: passing a positive scrollOffset here pushes the item further
        // *past* the top of the viewport (final position = -scrollOffset). Our targetOffset
        // below is defined as the literal desired final on-screen position, so it must be
        // negated when handing off to the real API — otherwise a jump-scroll (e.g. seeking
        // far ahead) lands the item mirrored above/below where the bouncy path would put it.
        scrollToItem(index, -targetOffset)
        return
    }
    val delta = (itemInfo.offset - targetOffset).toFloat()
    if (abs(delta) < 1f) return

    scroll {
        var previous = 0f
        Animatable(0f).animateTo(
            targetValue = delta,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessVeryLow
            )
        ) {
            dispatchRawDelta(value - previous)
            previous = value
        }
    }
}

@Composable
fun KaraokeLyricsView(
    lyrics: List<LrcLine>,
    currentIndex: Int,
    progressMs: () -> Long,
    lyricsOffsetMs: Long,
    isLoading: Boolean,
    lyricsSource: LyricsSource?,
    onLineClick: (Long) -> Unit,
    onAdjustOffset: (Long) -> Unit,
    modifier: Modifier = Modifier,
    onSetOffset: (Long) -> Unit = {},
    onSwipeDown: () -> Unit = {},
    onSearchOnline: (() -> Unit)? = null,
    lyricsErrorMessage: String? = null
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val isDragged by listState.interactionSource.collectIsDraggedAsState()
    var autoScrollEnabled by remember { mutableStateOf(true) }
    var containerHeight by remember { mutableStateOf(0) }
    var showSyncControls by remember { mutableStateOf(false) }
    var lastInteractionTime by remember { mutableLongStateOf(0L) }
    var isLongPressing by remember { mutableStateOf(false) }
    var tempOffsetStr by remember { mutableStateOf("") }

    val currentProgressMs = progressMs()

    // Calculate current line progress (0f to 1f) for "Smooth Flow" logic
    val currentLineProgress = remember(currentIndex, currentProgressMs, lyricsOffsetMs) {
        val line = lyrics.getOrNull(currentIndex) ?: return@remember 0f
        val nextLine = lyrics.getOrNull(currentIndex + 1)
        val endTime = if (line.duration > 0) line.startTime + line.duration 
                      else nextLine?.startTime ?: (line.startTime + 5000L)
        
        val elapsed = (currentProgressMs + lyricsOffsetMs - line.startTime).toFloat()
        val total = (endTime - line.startTime).toFloat()
        (elapsed / total).coerceIn(0f, 1f)
    }

    // "Liquid Flow" Vertical Offset: Subtle upward creep as the line progresses
    val flowVerticalOffset by animateFloatAsState(
        targetValue = -currentLineProgress * 24f, // Creep up by 24dp over the line's duration
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "flowScroll"
    )

    // Logic to re-enable auto-scroll after manual interaction
    LaunchedEffect(isDragged) {
        if (isDragged) {
            autoScrollEnabled = false
            showSyncControls = true
            lastInteractionTime = System.currentTimeMillis()
        } else {
            delay(3000)
            if (System.currentTimeMillis() - lastInteractionTime >= 3000) {
                autoScrollEnabled = true
                showSyncControls = false
            }
        }
    }

    LaunchedEffect(currentIndex, autoScrollEnabled, containerHeight) {
        if (autoScrollEnabled && currentIndex in lyrics.indices && containerHeight > 0) {
            scope.launch {
                // Centering with "Smooth Flow" compensation
                val offset = (containerHeight * 0.35f).toInt()
                listState.bouncyScrollToItem(index = currentIndex, targetOffset = offset)
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { containerHeight = it.size.height }
            .graphicsLayer {
                // Apply the "Smooth Flow" translation to the entire lyrics area
                translationY = flowVerticalOffset
                compositingStrategy = CompositingStrategy.Offscreen
            }
            .drawWithContent {
                drawContent()
                // Premium Fading Edges
                val colors = listOf(Color.Transparent, Color.Black, Color.Black, Color.Transparent)
                val stops = floatArrayOf(0f, 0.15f, 0.85f, 1f)
                drawRect(
                    brush = Brush.verticalGradient(
                        colorStops = stops.zip(colors).toTypedArray(),
                        startY = 0f,
                        endY = size.height
                    ),
                    blendMode = BlendMode.DstIn
                )
            }
    ) {
        if (isLoading && lyrics.isEmpty()) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = Color.White.copy(alpha = 0.5f)
            )
        } else if (lyrics.isEmpty()) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { onSearchOnline?.invoke() }
                    ),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "No lyrics found",
                    color = Color.White.copy(alpha = 0.4f),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                if (lyricsErrorMessage != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        lyricsErrorMessage,
                        color = Color(0xFFFF8A80).copy(alpha = 0.85f),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 24.dp)
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Tap to retry",
                        color = Color.White.copy(alpha = 0.3f),
                        style = MaterialTheme.typography.bodySmall
                    )
                } else if (lyricsSource == null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Tap to search online",
                        color = Color.White.copy(alpha = 0.25f),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                // Reduced top padding to allow lyrics to move higher up the screen
                contentPadding = PaddingValues(top = 40.dp, bottom = 450.dp, start = 32.dp, end = 32.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.Start
            ) {
                itemsIndexed(lyrics, key = { _, line -> line.startTime }) { index, line ->
                    val isCurrent = index == currentIndex
                    val distance = abs(index - currentIndex)
                    
                    // Progressive blur and fade for non-active lines
                    val lineAlpha = when {
                        isCurrent -> 1.0f
                        distance == 1 -> 0.45f
                        distance == 2 -> 0.25f
                        else -> 0.12f
                    }

                    val lineProgress = when {
                        index < currentIndex -> Long.MAX_VALUE
                        isCurrent -> (currentProgressMs + lyricsOffsetMs - line.startTime).coerceAtLeast(0)
                        else -> 0L
                    }

                    SyncedLyricLine(
                        line = line,
                        isCurrent = isCurrent,
                        distance = distance,
                        progressInLine = lineProgress,
                        targetAlpha = lineAlpha,
                        isWordByWord = line.wordTimings != null,
                        onClick = { 
                            onLineClick(line.startTime)
                            lastInteractionTime = System.currentTimeMillis()
                        },
                        onLongClick = {
                            if (line.wordTimings == null) {
                                onSearchOnline?.invoke()
                            }
                        }
                    )
                }
            }

            // Enhanced Sync Controls
            AnimatedVisibility(
                visible = showSyncControls,
                enter = fadeIn() + slideInVertically { -it },
                exit = fadeOut() + slideOutVertically { -it },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 24.dp)
            ) {
                Surface(
                    color = Color.White.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(24.dp),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f)),
                    modifier = Modifier.clip(RoundedCornerShape(24.dp))
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        IconButton(onClick = {
                            onAdjustOffset(-100)
                            lastInteractionTime = System.currentTimeMillis()
                        }) {
                            Icon(Icons.Rounded.Remove, "Earlier", tint = Color.White)
                        }

                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .padding(horizontal = 16.dp)
                                .clickable { 
                                    tempOffsetStr = lyricsOffsetMs.toString()
                                    isLongPressing = true 
                                }
                        ) {
                            Text(
                                text = "${if (lyricsOffsetMs >= 0) "+" else ""}${lyricsOffsetMs}ms",
                                color = Color.White,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Black
                            )
                            Text(
                                text = "SYNC OFFSET",
                                color = Color.White.copy(alpha = 0.5f),
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 9.sp,
                                letterSpacing = 1.sp
                            )
                        }

                        IconButton(onClick = {
                            onAdjustOffset(100)
                            lastInteractionTime = System.currentTimeMillis()
                        }) {
                            Icon(Icons.Rounded.Add, "Later", tint = Color.White)
                        }
                    }
                }
            }
        }
        
        // Manual offset entry dialog
        if (isLongPressing) {
            AlertDialog(
                onDismissRequest = { isLongPressing = false },
                title = { Text("Manual Sync Adjustment") },
                text = {
                    Column {
                        Text("Enter offset (ms). Positive values delay lyrics, negative values speed them up.")
                        Spacer(Modifier.height(16.dp))
                        OutlinedTextField(
                            value = tempOffsetStr,
                            onValueChange = {
                                if (it.isEmpty() || it == "-" || it.toLongOrNull() != null) {
                                    tempOffsetStr = it
                                }
                            },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Offset (ms)") }
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        tempOffsetStr.toLongOrNull()?.let { onSetOffset(it) }
                        isLongPressing = false
                    }) {
                        Text("Save Offset")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { isLongPressing = false }) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SyncedLyricLine(
    line: LrcLine,
    isCurrent: Boolean,
    distance: Int,
    progressInLine: Long,
    targetAlpha: Float,
    isWordByWord: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {}
) {
    // Ambient "Floating" Motion: Subtle sinusoidal swaying for the active line
    val infiniteTransition = rememberInfiniteTransition(label = "ambient")
    val ambientSway by infiniteTransition.animateFloat(
        initialValue = -3f,
        targetValue = 3f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = CubicBezierEasing(0.445f, 0.05f, 0.55f, 0.95f)),
            repeatMode = RepeatMode.Reverse
        ),
        label = "sway"
    )

    val animatedAlpha by animateFloatAsState(
        targetValue = targetAlpha, 
        animationSpec = tween(durationMillis = 600),
        label = "alpha"
    )

    // Bouncy scale for EVERY line, not just the active one
    val targetScale = when {
        isCurrent -> 1.08f
        distance == 1 -> 0.97f
        distance == 2 -> 0.93f
        else -> 0.90f
    }
    val animatedScale by animateFloatAsState(
        targetValue = targetScale,
        animationSpec = spring(
            dampingRatio = if (isCurrent) Spring.DampingRatioMediumBouncy else Spring.DampingRatioLowBouncy,
            stiffness = if (isCurrent) Spring.StiffnessVeryLow else Spring.StiffnessLow
        ),
        label = "lineScale"
    )

    // Bouncy vertical settle for every line
    val targetOffset = when {
        isCurrent -> 0f
        else -> (distance.coerceAtMost(4) * 5f)
    }
    val verticalOffset by animateFloatAsState(
        targetValue = targetOffset,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = if (isCurrent) Spring.StiffnessVeryLow else Spring.StiffnessLow
        ),
        label = "upwardMovement"
    )

    // Remove sequentialWords - no longer needed for single text block
    
    val baseStyle = MaterialTheme.typography.headlineMedium.copy(
        fontWeight = FontWeight.ExtraBold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
        textAlign = TextAlign.Start,
        letterSpacing = (-0.5).sp
    )

    var textLayoutResult by remember { mutableStateOf<androidx.compose.ui.text.TextLayoutResult?>(null) }
    val lineDuration = remember(line.duration) { if (line.duration > 0) line.duration else 3000L }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                alpha = animatedAlpha
                scaleX = animatedScale
                scaleY = animatedScale
                translationY = verticalOffset
                translationX = if (isCurrent) ambientSway else 0f
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f)
            }
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick
            )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .drawWithContent {
                    val layout = textLayoutResult ?: run {
                        drawContent()
                        return@drawWithContent
                    }

                    // 1. Draw the dimmed background text
                    // The Text composable is already set to 0.35f alpha
                    drawContent()

                    // 2. Draw the Opaque Highlight (Fill)
                    if (progressInLine > 0) {
                        val totalChars = line.text.length.coerceAtLeast(1)
                        val sweepDuration = 450f
                        var elapsedChars = 0

                        // Use a layer to isolate the SrcAtop/DstIn blending
                        drawContext.canvas.saveLayer(
                            androidx.compose.ui.geometry.Rect(0f, 0f, size.width, size.height),
                            androidx.compose.ui.graphics.Paint()
                        )

                        for (lineIdx in 0 until layout.lineCount) {
                            val lineStart = layout.getLineStart(lineIdx)
                            val lineEnd = layout.getLineEnd(lineIdx)
                            val lineChars = lineEnd - lineStart
                            
                            val relStart = (elapsedChars.toFloat() / totalChars) * lineDuration
                            val relDuration = (lineChars.toFloat() / totalChars) * lineDuration
                            val timeIntoLine = progressInLine - relStart
                            
                            val lineLeft = layout.getLineLeft(lineIdx)
                            val lineRight = layout.getLineRight(lineIdx)
                            val lineTop = layout.getLineTop(lineIdx)
                            val lineBottom = layout.getLineBottom(lineIdx)

                            if (timeIntoLine >= relDuration) {
                                // Fully completed visual line
                                drawRect(
                                    color = Color.White,
                                    topLeft = androidx.compose.ui.geometry.Offset(lineLeft, lineTop),
                                    size = androidx.compose.ui.geometry.Size(lineRight - lineLeft, lineBottom - lineTop)
                                )
                            } else if (timeIntoLine > -sweepDuration) {
                                // In-progress visual line
                                val sweepCenter = (timeIntoLine / relDuration).coerceIn(0f, 1f)
                                val halfSweep = sweepDuration / 2f
                                val sweepStart = ((timeIntoLine - halfSweep) / relDuration).coerceIn(0f, 1f)
                                val sweepEnd = ((timeIntoLine + halfSweep) / relDuration).coerceIn(0f, 1f)

                                if (sweepEnd > 0f) {
                                    val highlightBrush = Brush.horizontalGradient(
                                        0.0f to Color.White,
                                        sweepStart to Color.White,
                                        sweepCenter to Color.White.copy(alpha = 0.95f),
                                        sweepEnd to Color.Transparent,
                                        1.0f to Color.Transparent,
                                        startX = lineLeft,
                                        endX = lineRight
                                    )

                                    drawRect(
                                        brush = highlightBrush,
                                        topLeft = androidx.compose.ui.geometry.Offset(lineLeft, lineTop),
                                        size = androidx.compose.ui.geometry.Size(lineRight - lineLeft, lineBottom - lineTop)
                                    )
                                }
                            }
                            elapsedChars += lineChars
                            if (progressInLine < relStart - sweepDuration) break
                        }

                        // 3. Clip the white fill to the text shape
                        // We draw the text AGAIN with 1.0 alpha and BlendMode.DstIn
                        // This makes the text visible ONLY where we drew the white progress rects/gradients
                        drawText(
                            textLayoutResult = layout,
                            color = Color.White,
                            blendMode = BlendMode.DstIn
                        )

                        drawContext.canvas.restore()
                    }
                }
                .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
        ) {
            Text(
                text = line.text,
                style = baseStyle,
                color = Color.White.copy(alpha = 0.35f),
                onTextLayout = { textLayoutResult = it }
            )
        }
    }
}

