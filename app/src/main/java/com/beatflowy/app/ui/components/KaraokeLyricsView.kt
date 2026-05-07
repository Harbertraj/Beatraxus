package com.beatflowy.app.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    modifier: Modifier = Modifier,
    onSwipeDown: () -> Unit = {}
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val isDragged by listState.interactionSource.collectIsDraggedAsState()
    var autoScrollEnabled by remember { mutableStateOf(true) }
    var containerHeight by remember { mutableStateOf(0) }

    LaunchedEffect(isDragged) {
        if (isDragged) {
            autoScrollEnabled = false
        } else {
            delay(3000)
            autoScrollEnabled = true
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
