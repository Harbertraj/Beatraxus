package com.beatraxus.app.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.beatraxus.app.model.LrcLine
import com.beatraxus.app.model.WordTiming
import com.beatraxus.app.repository.LyricsSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private suspend fun LazyListState.bouncyScrollToItem(index: Int, targetOffset: Int) {
    val itemInfo = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
    if (itemInfo == null) {
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
    isLoading: Boolean,
    lyricsSource: LyricsSource?,
    onLineClick: (Long) -> Unit,
    onAdjustOffset: (Long) -> Unit,
    modifier: Modifier = Modifier,
    onSetOffset: (Long) -> Unit = {},
    onSwipeDown: () -> Unit = {},
    onSearchOnline: (() -> Unit)? = null,
    lyricsErrorMessage: String? = null,
    lyricsOffsetMs: Long = 0L, // Keep this for sync controls
    progressMs: () -> Long = { 0L } // Live playback position, drives the word-fill sweep
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
                compositingStrategy = CompositingStrategy.Offscreen
            }
            .drawWithContent {
                drawContent()
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
                        textAlign = TextAlign.Center,
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
                contentPadding = PaddingValues(top = 40.dp, bottom = 450.dp, start = 32.dp, end = 32.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.Start
            ) {
                itemsIndexed(lyrics, key = { index, line -> "${line.startTime}_$index" }) { index, line ->
                    val isCurrent = index == currentIndex
                    val distance = abs(index - currentIndex)

                    val lineAlpha = when {
                        isCurrent -> 1.0f
                        distance == 1 -> 0.45f
                        distance == 2 -> 0.25f
                        else -> 0.12f
                    }

                    SyncedLyricLine(
                        line = line,
                        isCurrent = isCurrent,
                        distance = distance,
                        targetAlpha = lineAlpha,
                        progressMs = progressMs,
                        onClick = {
                            onLineClick(line.startTime)
                            lastInteractionTime = System.currentTimeMillis()
                        },
                        onLongClick = {
                            onSearchOnline?.invoke()
                        }
                    )
                }
            }

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
    targetAlpha: Float,
    progressMs: () -> Long,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {}
) {
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

    val isTamil = remember(line.text) { line.text.any { it in '\u0B80'..'\u0BFF' } }
    val baseStyle = MaterialTheme.typography.headlineMedium.copy(
        fontWeight = FontWeight.ExtraBold,
        fontSize = if (isTamil) 22.sp else 24.sp,
        lineHeight = if (isTamil) 28.sp else 30.sp,
        textAlign = TextAlign.Start,
        letterSpacing = (-0.5).sp
    )

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
        if (isCurrent) {
            KaraokeText(
                line = line,
                progressMs = progressMs,
                style = baseStyle,
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            Text(
                text = line.text,
                style = baseStyle,
                color = Color.White.copy(alpha = 0.35f)
            )
        }
    }
}

@Composable
fun KaraokeText(
    line: LrcLine,
    progressMs: () -> Long,
    style: TextStyle,
    modifier: Modifier = Modifier
) {
    val textMeasurer = rememberTextMeasurer()

    BoxWithConstraints(modifier = modifier) {
        val width = this.constraints.maxWidth
        val textLayoutResult = remember(line.text, style, width) {
            textMeasurer.measure(
                text = line.text,
                style = style,
                constraints = Constraints(maxWidth = width)
            )
        }

        // Background (unfilled) text
        Text(
            text = line.text,
            style = style,
            color = Color.White.copy(alpha = 0.35f),
            modifier = Modifier.fillMaxWidth()
        )

        // Foreground (filled) text with sweep clipping
        Canvas(modifier = Modifier.matchParentSize()) {
            val currentProgress = progressMs()
            if (currentProgress >= line.startTime) {
                val clipPath = calculateKaraokePath(line, currentProgress, textLayoutResult)
                clipPath(clipPath) {
                    drawText(textLayoutResult, color = Color.White)
                }
            }
        }
    }
}

private fun calculateKaraokePath(
    line: LrcLine,
    progressMs: Long,
    textLayout: TextLayoutResult
): Path {
    val path = Path()
    val wordTimings = line.wordTimings

    if (wordTimings.isNullOrEmpty()) {
        // Line-level fallback: sweep character by character
        val duration = if (line.duration > 0) line.duration else 3000L
        val elapsed = progressMs - line.startTime
        val totalFraction = (elapsed.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
        val totalLength = line.text.length

        val targetCharIndex = (totalFraction * totalLength).toInt()
        if (targetCharIndex > 0) {
            path.addPath(textLayout.getPathForRange(0, targetCharIndex))
        }
        
        // Add a smooth sweep for the "active" character
        if (targetCharIndex < totalLength) {
            val charElapsed = (elapsed.toFloat() / duration.toFloat() * totalLength) % 1f
            addSweptRangePath(path, textLayout, targetCharIndex, targetCharIndex + 1, charElapsed)
        }
        return path
    }

    // Enhanced word-level sync
    var currentTextIndex = 0
    for (i in wordTimings.indices) {
        val word = wordTimings[i]
        val wordEnd = word.startTime + word.duration
        val wordLen = word.text.length
        
        if (progressMs >= wordEnd) {
            // Word fully completed
            path.addPath(textLayout.getPathForRange(currentTextIndex, currentTextIndex + wordLen))
        } else if (progressMs >= word.startTime) {
            // Word is currently being sung
            val wordElapsed = progressMs - word.startTime
            val fraction = (wordElapsed.toFloat() / word.duration.toFloat()).coerceIn(0f, 1f)
            addSweptRangePath(path, textLayout, currentTextIndex, currentTextIndex + wordLen, fraction)
            break 
        } else {
            break 
        }
        
        currentTextIndex += wordLen + 1
    }

    return path
}

private fun addSweptRangePath(
    path: Path,
    textLayout: TextLayoutResult,
    startOffset: Int,
    endOffset: Int,
    fraction: Float
) {
    if (startOffset >= endOffset) return
    
    val startLine = textLayout.getLineForOffset(startOffset)
    val endLine = textLayout.getLineForOffset(endOffset)
    
    if (startLine == endLine) {
        // Simple single-line sweep
        val rangePath = textLayout.getPathForRange(startOffset, endOffset)
        val bounds = rangePath.getBounds()
        val sweepRect = Rect(
            bounds.left,
            bounds.top,
            bounds.left + (bounds.width * fraction),
            bounds.bottom
        )
        val rectPath = Path().apply { addRect(sweepRect) }
        val activePath = Path()
        activePath.op(rangePath, rectPath, PathOperation.Intersect)
        path.addPath(activePath)
    } else {
        // Multi-line range sweep (e.g. a very long word wrapping)
        val totalChars = endOffset - startOffset
        var processedChars = 0
        for (line in startLine..endLine) {
            val lineStart = maxOf(startOffset, textLayout.getLineStart(line))
            val lineEnd = minOf(endOffset, textLayout.getLineEnd(line))
            if (lineStart >= lineEnd) continue
            
            val lineChars = lineEnd - lineStart
            val lineStartFraction = processedChars.toFloat() / totalChars
            val lineEndFraction = (processedChars + lineChars).toFloat() / totalChars
            
            if (fraction >= lineEndFraction) {
                // Fully completed line segment within this range
                path.addPath(textLayout.getPathForRange(lineStart, lineEnd))
            } else if (fraction > lineStartFraction) {
                // Currently sweeping this line segment
                val localFraction = (fraction - lineStartFraction) / (lineEndFraction - lineStartFraction)
                val linePath = textLayout.getPathForRange(lineStart, lineEnd)
                val bounds = linePath.getBounds()
                val sweepRect = Rect(
                    bounds.left,
                    bounds.top,
                    bounds.left + (bounds.width * localFraction),
                    bounds.bottom
                )
                val rectPath = Path().apply { addRect(sweepRect) }
                val activePath = Path()
                activePath.op(linePath, rectPath, PathOperation.Intersect)
                path.addPath(activePath)
                break
            }
            processedChars += lineChars
        }
    }
}