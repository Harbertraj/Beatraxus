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
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.beatraxus.app.model.LrcLine
import com.beatraxus.app.model.LyricSpeaker
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
    progressMs: () -> Long = { 0L }, // Live playback position, drives the word-fill sweep
    providerLabel: String? = null,
    alignBySinger: Boolean = true,
    onShowAllLyrics: (() -> Unit)? = null,
    accentColor: Color = Color.White
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

                    val speakerToUse = if (alignBySinger) line.speaker else LyricSpeaker.NONE

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
                        },
                        accentColor = accentColor,
                        speaker = speakerToUse
                    )
                }

                if (providerLabel != null) {
                    item {
                        Text(
                            text = "Source: $providerLabel",
                            color = Color.White.copy(alpha = 0.2f),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 32.dp)
                        )
                    }
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
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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

                    if (onShowAllLyrics != null) {
                        Surface(
                            color = Color.White.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(24.dp),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f)),
                            modifier = Modifier
                                .clip(RoundedCornerShape(24.dp))
                                .clickable { onShowAllLyrics.invoke() }
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp).height(48.dp) // match icon button height
                            ) {
                                Text(
                                    text = "SOURCES",
                                    color = Color.White,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold
                                )
                            }
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
    onLongClick: () -> Unit = {},
    accentColor: Color = Color.White,
    speaker: LyricSpeaker = LyricSpeaker.NONE
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

    val lineTextAlign = when (speaker) {
        LyricSpeaker.NONE, LyricSpeaker.MALE -> TextAlign.Start
        LyricSpeaker.FEMALE -> TextAlign.End
        LyricSpeaker.DUET_BOTH, LyricSpeaker.CHORUS -> TextAlign.Center
    }

    val lineTransformOrigin = when (speaker) {
        LyricSpeaker.NONE, LyricSpeaker.MALE -> TransformOrigin(0f, 0.5f)
        LyricSpeaker.FEMALE -> androidx.compose.ui.graphics.TransformOrigin(1f, 0.5f)
        LyricSpeaker.DUET_BOTH, LyricSpeaker.CHORUS -> androidx.compose.ui.graphics.TransformOrigin(0.5f, 0.5f)
    }

    val linePadding = when (speaker) {
        LyricSpeaker.NONE -> PaddingValues(0.dp)
        LyricSpeaker.MALE -> PaddingValues(end = 32.dp)
        LyricSpeaker.FEMALE -> PaddingValues(start = 32.dp)
        LyricSpeaker.DUET_BOTH, LyricSpeaker.CHORUS -> PaddingValues(horizontal = 16.dp)
    }

    val isTamil = remember(line.text) { line.text.any { it in '\u0B80'..'\u0BFF' } }
    val baseStyle = MaterialTheme.typography.headlineMedium.copy(
        fontWeight = FontWeight.ExtraBold,
        fontSize = if (isTamil) 22.sp else 24.sp,
        lineHeight = if (isTamil) 28.sp else 30.sp,
        textAlign = lineTextAlign,
        letterSpacing = (-0.5).sp
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(linePadding)
            .graphicsLayer {
                alpha = animatedAlpha
                scaleX = animatedScale
                scaleY = animatedScale
                translationY = verticalOffset
                translationX = when (speaker) {
                    LyricSpeaker.FEMALE -> if (isCurrent) -ambientSway else 0f
                    LyricSpeaker.DUET_BOTH, LyricSpeaker.CHORUS -> if (isCurrent) ambientSway * 0.5f else 0f
                    else -> if (isCurrent) ambientSway else 0f
                }
                transformOrigin = lineTransformOrigin
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
                accentColor = accentColor,
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            Text(
                text = line.text,
                style = baseStyle,
                color = lerp(accentColor, Color.White, 0.35f).copy(alpha = 0.35f),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

private class WordCharRange(
    val startTime: Long,
    val duration: Long,
    val startChar: Int,
    val endChar: Int
)

@Composable
fun KaraokeText(
    line: LrcLine,
    progressMs: () -> Long,
    style: TextStyle,
    accentColor: Color = Color.White,
    modifier: Modifier = Modifier
) {
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current

    val featherPx = remember(density) { with(density) { 32.dp.toPx() } }
    val glowRadiusPx = remember(density) { with(density) { 10.dp.toPx() } }

    val unfilledColor = remember(accentColor) { accentColor.copy(alpha = 0.35f) }
    val glowShadow = remember(accentColor, glowRadiusPx) {
        Shadow(
            color = accentColor.copy(alpha = 0.35f),
            blurRadius = glowRadiusPx
        )
    }
    val highlightColor = remember(accentColor) { lerp(accentColor, Color.White, 0.25f) }

    val wordRanges = remember(line.wordTimings, line.text) {
        val wordTimings = line.wordTimings
        if (wordTimings.isNullOrEmpty()) {
            null
        } else {
            val ranges = ArrayList<WordCharRange>(wordTimings.size)
            var currentIdx = 0
            for (w in wordTimings) {
                val wordLen = w.text.length
                val start = line.text.indexOf(w.text, currentIdx).let {
                    if (it != -1) it else currentIdx
                }
                val end = (start + wordLen).coerceAtMost(line.text.length)
                ranges.add(WordCharRange(w.startTime, w.duration, start, end))
                currentIdx = (end + 1).coerceAtMost(line.text.length)
            }
            ranges
        }
    }

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
            color = unfilledColor,
            modifier = Modifier.fillMaxWidth()
        )

        // Foreground (filled) text with soft feathered sweep & glow
        Canvas(modifier = Modifier.matchParentSize()) {
            val currentProgress = progressMs()
            if (currentProgress < line.startTime) return@Canvas

            val lineCount = textLayoutResult.lineCount
            val textLength = line.text.length
            if (textLength == 0) return@Canvas

            val (activeLine, activeX) = calculateActivePosition(
                line = line,
                progressMs = currentProgress,
                textLayout = textLayoutResult,
                wordRanges = wordRanges
            )

            for (l in 0 until lineCount) {
                val lStart = textLayoutResult.getLineStart(l)
                val lEnd = textLayoutResult.getLineEnd(l)
                if (lStart >= lEnd) continue

                val lTop = textLayoutResult.getLineTop(l)
                val lBottom = textLayoutResult.getLineBottom(l)
                val lLeft = textLayoutResult.getLineLeft(l)
                val lRight = textLayoutResult.getLineRight(l)

                val isRtl = textLayoutResult.getParagraphDirection(lStart) == ResolvedTextDirection.Rtl

                if (l < activeLine) {
                    clipRect(
                        left = lLeft - featherPx,
                        top = lTop,
                        right = lRight + featherPx,
                        bottom = lBottom
                    ) {
                        drawText(
                            textLayoutResult = textLayoutResult,
                            color = accentColor,
                            shadow = glowShadow
                        )
                    }
                } else if (l == activeLine) {
                    clipRect(
                        left = lLeft - featherPx,
                        top = lTop,
                        right = lRight + featherPx,
                        bottom = lBottom
                    ) {
                        val brush = if (!isRtl) {
                            val startX = activeX - featherPx
                            val endX = activeX
                            Brush.horizontalGradient(
                                0.0f to accentColor,
                                0.65f to highlightColor,
                                1.0f to accentColor.copy(alpha = 0f),
                                startX = startX,
                                endX = endX
                            )
                        } else {
                            val startX = activeX
                            val endX = activeX + featherPx
                            Brush.horizontalGradient(
                                0.0f to accentColor.copy(alpha = 0f),
                                0.35f to highlightColor,
                                1.0f to accentColor,
                                startX = startX,
                                endX = endX
                            )
                        }

                        drawText(
                            textLayoutResult = textLayoutResult,
                            brush = brush,
                            shadow = glowShadow
                        )
                    }
                }
            }
        }
    }
}

private fun calculateActivePosition(
    line: LrcLine,
    progressMs: Long,
    textLayout: TextLayoutResult,
    wordRanges: List<WordCharRange>?
): Pair<Int, Float> {
    val textLength = line.text.length
    if (textLength == 0) return Pair(0, 0f)

    if (!wordRanges.isNullOrEmpty()) {
        val firstWord = wordRanges.first()
        val lastWord = wordRanges.last()

        if (progressMs < firstWord.startTime) {
            val x0 = textLayout.getLineLeft(0)
            return Pair(0, x0)
        }

        val lastEnd = lastWord.startTime + lastWord.duration
        if (progressMs >= lastEnd) {
            val lastLine = textLayout.lineCount - 1
            val xLast = textLayout.getLineRight(lastLine)
            return Pair(lastLine, xLast)
        }

        for (i in wordRanges.indices) {
            val w = wordRanges[i]
            val wEnd = w.startTime + w.duration

            if (progressMs in w.startTime until wEnd) {
                val frac = if (w.duration > 0) {
                    (progressMs - w.startTime).toFloat() / w.duration.toFloat()
                } else {
                    1f
                }.coerceIn(0f, 1f)

                return interpolateCharPosition(textLayout, w.startChar, w.endChar, frac)
            } else if (progressMs < w.startTime) {
                if (i > 0) {
                    val prev = wordRanges[i - 1]
                    val safeIdx = prev.endChar.coerceIn(0, textLength)
                    val prevLine = textLayout.getLineForOffset(safeIdx)
                    val prevX = textLayout.getHorizontalPosition(safeIdx, true)
                    return Pair(prevLine, prevX)
                } else {
                    val x0 = textLayout.getLineLeft(0)
                    return Pair(0, x0)
                }
            }
        }

        val lastLine = textLayout.lineCount - 1
        return Pair(lastLine, textLayout.getLineRight(lastLine))
    } else {
        val duration = if (line.duration > 0) line.duration else 3000L
        val elapsed = (progressMs - line.startTime).coerceIn(0L, duration)
        val frac = (elapsed.toFloat() / duration.toFloat()).coerceIn(0f, 1f)

        return interpolateCharPosition(textLayout, 0, textLength, frac)
    }
}

private fun interpolateCharPosition(
    textLayout: TextLayoutResult,
    startChar: Int,
    endChar: Int,
    fraction: Float
): Pair<Int, Float> {
    val textLength = textLayout.layoutInput.text.length
    if (startChar >= endChar || textLength == 0) {
        val safeIdx = startChar.coerceIn(0, maxOf(0, textLength - 1))
        val line = textLayout.getLineForOffset(safeIdx)
        val x = textLayout.getHorizontalPosition(safeIdx, true)
        return Pair(line, x)
    }

    val totalChars = endChar - startChar
    val charPos = startChar + fraction * totalChars
    val charIndex = charPos.toInt().coerceIn(startChar, endChar - 1)
    val charFrac = charPos - charIndex

    val safeIndex = charIndex.coerceIn(0, textLength - 1)
    val line = textLayout.getLineForOffset(safeIndex)

    val startX = textLayout.getHorizontalPosition(safeIndex, true)
    val nextIdx = (safeIndex + 1).coerceAtMost(textLength)
    val endX = textLayout.getHorizontalPosition(nextIdx, true)

    val activeX = startX + (endX - startX) * charFrac
    return Pair(line, activeX)
}