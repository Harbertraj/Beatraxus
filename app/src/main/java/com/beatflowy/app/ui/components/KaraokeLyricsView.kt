package com.beatflowy.app.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import com.beatflowy.app.model.LrcLine
import com.beatflowy.app.model.WordTiming
import com.beatflowy.app.repository.LyricsSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun KaraokeLyricsView(
    lyrics: List<LrcLine>,
    currentIndex: Int,
    currentProgressMs: Long,
    lyricsOffsetMs: Long,
    isLoading: Boolean,
    lyricsSource: LyricsSource?,
    onLineClick: (Long) -> Unit,
    onAdjustOffset: (Long) -> Unit,
    onSetOffset: (Long) -> Unit = {},
    modifier: Modifier = Modifier,
    onSwipeDown: () -> Unit = {}
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val isDragged by listState.interactionSource.collectIsDraggedAsState()
    var autoScrollEnabled by remember { mutableStateOf(true) }
    var containerHeight by remember { mutableStateOf(0) }
    var showSyncControls by remember { mutableStateOf(false) }
    var lastScrollTime by remember { mutableLongStateOf(0L) }
    var isLongPressing by remember { mutableStateOf(false) }
    var tempOffsetStr by remember { mutableStateOf("") }

    LaunchedEffect(isDragged) {
        if (isDragged) {
            autoScrollEnabled = false
            showSyncControls = true
            lastScrollTime = System.currentTimeMillis()
        } else {
            delay(1000)
            if (System.currentTimeMillis() - lastScrollTime >= 1000) {
                showSyncControls = false
            }
            delay(2000)
            autoScrollEnabled = true
        }
    }

    LaunchedEffect(showSyncControls) {
        if (showSyncControls) {
            delay(1000)
            if (!isDragged) {
                showSyncControls = false
            }
        }
    }

    LaunchedEffect(currentIndex, autoScrollEnabled, containerHeight) {
        if (autoScrollEnabled && currentIndex in lyrics.indices && containerHeight > 0) {
            scope.launch {
                val offset = -(containerHeight / 3) // Center position adjustment
                listState.animateScrollToItem(index = currentIndex, scrollOffset = offset)
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { containerHeight = it.size.height }
    ) {
        if (isLoading && lyrics.isEmpty()) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = Color.White.copy(alpha = 0.5f)
            )
        } else if (lyrics.isEmpty()) {
            Text(
                "No lyrics available",
                modifier = Modifier.align(Alignment.Center),
                color = Color.White.copy(alpha = 0.4f),
                style = MaterialTheme.typography.bodyLarge
            )
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 100.dp, bottom = 400.dp, start = 24.dp, end = 24.dp),
                horizontalAlignment = Alignment.Start
            ) {
                itemsIndexed(lyrics, key = { _, line -> line.startTime }) { index, line ->
                    val isCurrent = index == currentIndex
                    // Calculate precise progress within this specific line
                    val lineProgress = if (isCurrent) {
                        (currentProgressMs + lyricsOffsetMs - line.startTime).coerceAtLeast(0)
                    } else 0L

                    AppleMusicLyricLine(
                        line = line,
                        isCurrent = isCurrent,
                        progressInLine = lineProgress,
                        onClick = { onLineClick(line.startTime) }
                    )
                }
            }

            // Offset adjustment controls
            AnimatedVisibility(
                visible = showSyncControls,
                enter = fadeIn() + expandVertically(expandFrom = Alignment.Top),
                exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Top),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 16.dp)
            ) {
                Surface(
                    color = Color.Black.copy(alpha = 0.6f),
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f))
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        IconButton(onClick = {
                            onAdjustOffset(-100)
                            lastScrollTime = System.currentTimeMillis()
                        }) {
                            Icon(Icons.Rounded.Remove, "Decrease Offset", tint = Color.White)
                        }

                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .padding(horizontal = 8.dp)
                                .pointerInput(Unit) {
                                    detectTapGestures(
                                        onLongPress = {
                                            tempOffsetStr = lyricsOffsetMs.toString()
                                            isLongPressing = true
                                        },
                                        onTap = {
                                            // Optional: just show/refresh the timer if tapped
                                            lastScrollTime = System.currentTimeMillis()
                                        }
                                    )
                                }
                        ) {
                            Text(
                                text = "${if (lyricsOffsetMs >= 0) "+" else ""}${lyricsOffsetMs}ms",
                                color = Color.White,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Sync",
                                color = Color.White.copy(alpha = 0.5f),
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 10.sp
                            )
                        }

                        IconButton(onClick = {
                            onAdjustOffset(100)
                            lastScrollTime = System.currentTimeMillis()
                        }) {
                            Icon(Icons.Rounded.Add, "Increase Offset", tint = Color.White)
                        }
                    }
                }
            }

            if (isLongPressing) {
                AlertDialog(
                    onDismissRequest = { isLongPressing = false },
                    title = { Text("Adjust Lyrics Offset") },
                    text = {
                        Column {
                            Text("Enter offset in milliseconds (ms):")
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = tempOffsetStr,
                                onValueChange = {
                                    if (it.isEmpty() || it == "-" || it.toLongOrNull() != null) {
                                        tempOffsetStr = it
                                    }
                                },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            tempOffsetStr.toLongOrNull()?.let {
                                onSetOffset(it)
                            }
                            isLongPressing = false
                        }) {
                            Text("Save")
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
}

@Composable
fun AppleMusicLyricLine(
    line: LrcLine,
    isCurrent: Boolean,
    progressInLine: Long,
    onClick: () -> Unit
) {
    val scale by animateFloatAsState(
        targetValue = if (isCurrent) 1.12f else 1.0f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessLow),
        label = "scale"
    )
    
    val opacity by animateFloatAsState(
        targetValue = if (isCurrent) 1.0f else 0.4f,
        animationSpec = tween(400),
        label = "opacity"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = opacity
                // Apply a slight tilt/perspective if current
                rotationX = if (isCurrent) -2f else 0f 
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
    ) {
        if (line.wordTimings != null && isCurrent) {
            // Optimized Word-by-word rendering with gradient highlight
            WordByWordFlow(
                wordTimings = line.wordTimings,
                progressInLine = progressInLine,
                lineStartTime = line.startTime
            )
        } else {
            // Standard smooth line highlight (fallback)
            Text(
                text = line.text,
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 30.sp,
                    lineHeight = 38.sp,
                    textAlign = TextAlign.Start
                ),
                color = Color.White
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WordByWordFlow(
    wordTimings: List<WordTiming>,
    progressInLine: Long,
    lineStartTime: Long
) {
    val baseStyle = MaterialTheme.typography.headlineMedium.copy(
        fontWeight = FontWeight.ExtraBold,
        fontSize = 30.sp,
        lineHeight = 38.sp
    )

    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start
    ) {
        wordTimings.forEach { word ->
            // Use precise timestamps from the model
            val relativeStartTime = word.startTime - lineStartTime
            val wordDuration = word.duration
            
            // Calculate word-level completion (0.0 -> 1.0)
            val wordProgress = if (wordDuration > 0) {
                ((progressInLine - relativeStartTime).toFloat() / wordDuration).coerceIn(0f, 1f)
            } else {
                if (progressInLine >= relativeStartTime) 1f else 0f
            }

            // High-quality Gradient Highlight Effect
            // As the word is sung, it lights up from left to right
            val brush = if (wordProgress > 0f && wordProgress < 1f) {
                Brush.horizontalGradient(
                    0.0f to Color.White,
                    wordProgress to Color.White,
                    (wordProgress + 0.15f).coerceAtMost(1f) to Color.White.copy(alpha = 0.4f),
                    1.0f to Color.White.copy(alpha = 0.4f)
                )
            } else null

            val textColor = when {
                wordProgress >= 1f -> Color.White
                wordProgress <= 0f -> Color.White.copy(alpha = 0.4f)
                else -> Color.Unspecified // Use brush
            }

            Text(
                text = word.text + " ",
                style = if (brush != null) baseStyle.copy(brush = brush) else baseStyle,
                color = textColor,
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }
    }
}
