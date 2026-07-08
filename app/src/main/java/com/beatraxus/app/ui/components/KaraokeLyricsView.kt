package com.beatraxus.app.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.TextStyle
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
    onSearchOnline: (() -> Unit)? = null
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
                // Centering: Active line at ~8% from the top (moved even higher up)
                val offset = (containerHeight * 0.08f).toInt()
                listState.animateScrollToItem(index = currentIndex, scrollOffset = -offset)
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { containerHeight = it.size.height }
            .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
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
                if (lyricsSource == null) {
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

                    val lineProgress = if (isCurrent) {
                        (currentProgressMs + lyricsOffsetMs - line.startTime).coerceAtLeast(0)
                    } else 0L

                    SyncedLyricLine(
                        line = line,
                        isCurrent = isCurrent,
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
    progressInLine: Long,
    targetAlpha: Float,
    isWordByWord: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {}
) {
    val animatedAlpha by animateFloatAsState(
        targetValue = targetAlpha, 
        animationSpec = tween(durationMillis = 600),
        label = "alpha"
    )
    
    // Bouncy scale for the active line
    val animatedScale by animateFloatAsState(
        targetValue = if (isCurrent) 1.06f else 1.0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "lineScale"
    )

    // Unique upward movement animation when line becomes active
    val verticalOffset by animateFloatAsState(
        targetValue = if (isCurrent) 0f else 8f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "upwardMovement"
    )

    // Generate word timings if they don't exist to support word-by-word highlighting
    val words = remember(line.text, line.wordTimings, line.duration) {
        line.wordTimings ?: run {
            val wordsList = line.text.split(Regex("\\s+")).filter { it.isNotBlank() }
            if (wordsList.isEmpty()) return@run emptyList<WordTiming>()
            
            val totalDuration = if (line.duration > 0) line.duration else 3000L
            val wordDuration = totalDuration / wordsList.size
            wordsList.mapIndexed { index, text ->
                WordTiming(
                    startTime = line.startTime + (index * wordDuration),
                    duration = wordDuration,
                    text = text
                )
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                alpha = animatedAlpha
                scaleX = animatedScale
                scaleY = animatedScale
                translationY = verticalOffset
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f)
            }
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick
            )
    ) {
        if (isCurrent && words.isNotEmpty()) {
            BouncyWordByWordFlow(
                wordTimings = words,
                progressInLine = progressInLine,
                lineStartTime = line.startTime,
                isWordByWord = isWordByWord
            )
        } else {
            Text(
                text = line.text,
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 24.sp,
                    lineHeight = 30.sp,
                    textAlign = TextAlign.Start,
                    letterSpacing = (-0.5).sp
                ),
                color = Color.White
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BouncyWordByWordFlow(
    wordTimings: List<WordTiming>,
    progressInLine: Long,
    lineStartTime: Long,
    isWordByWord: Boolean
) {
    val baseStyle = MaterialTheme.typography.headlineMedium.copy(
        fontWeight = FontWeight.ExtraBold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
        letterSpacing = (-0.5).sp
    )

    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        wordTimings.forEach { word ->
            val relativeStartTime = (word.startTime - lineStartTime).coerceAtLeast(0)
            
            // For LRC (not word-by-word), we highlight the whole line immediately
            val isWordActive = if (isWordByWord) progressInLine >= relativeStartTime else true
            
            val wordAlpha by animateFloatAsState(
                targetValue = if (isWordActive) 1f else 0.35f,
                // Snap alpha for ELRC to allow brush to handle the smooth fill transition
                animationSpec = if (isWordByWord && isWordActive) snap() else tween(durationMillis = 250),
                label = "wordAlpha"
            )
            
            val wordScale by animateFloatAsState(
                targetValue = if (isWordActive) 1.05f else 1.0f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessLow
                ),
                label = "wordScale"
            )

            // Letter by letter flow animation using a brush gradient (only for ELRC)
            val wordDuration = word.duration.coerceAtLeast(1L)
            val wordProgress = ((progressInLine - relativeStartTime).toFloat() / wordDuration).coerceIn(0f, 1f)

            // Enhanced "Smooth Fill Effect": Gradient with soft edge transition
            val brush = if (isWordByWord && isWordActive && wordProgress < 1f) {
                val transitionStart = (wordProgress - 0.15f).coerceAtLeast(0f)
                val transitionEnd = (wordProgress + 0.15f).coerceAtMost(1f)
                Brush.horizontalGradient(
                    0.0f to Color.White,
                    transitionStart to Color.White,
                    transitionEnd to Color.White.copy(alpha = 0.35f),
                    1.0f to Color.White.copy(alpha = 0.35f)
                )
            } else if (isWordByWord && isWordActive && wordProgress >= 1f) {
                null // Full white
            } else if (isWordByWord && !isWordActive) {
                null // Handled by alpha
            } else null

            Text(
                text = word.text,
                style = if (brush != null) baseStyle.copy(brush = brush) else baseStyle,
                color = if (brush != null) Color.Unspecified else Color.White,
                modifier = Modifier
                    .padding(bottom = 6.dp)
                    .graphicsLayer {
                        alpha = wordAlpha
                        scaleX = wordScale
                        scaleY = wordScale
                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f)
                    }
            )
        }
    }
}
