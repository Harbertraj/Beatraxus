package com.beatflowy.app.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import com.beatflowy.app.model.LrcLine
import com.beatflowy.app.model.Word
import com.beatflowy.app.repository.LyricsSource
import kotlinx.coroutines.delay
import kotlin.math.abs

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
    val coroutineScope = rememberCoroutineScope()
    var showOffsetControls by remember { mutableStateOf(false) }
    
    val isDragged by listState.interactionSource.collectIsDraggedAsState()
    var isAutoScrollPaused by remember { mutableStateOf(false) }

    LaunchedEffect(isDragged) {
        if (isDragged) {
            isAutoScrollPaused = true
        } else {
            delay(3000)
            isAutoScrollPaused = false
        }
    }

    LaunchedEffect(currentIndex, isAutoScrollPaused) {
        if (!isAutoScrollPaused && currentIndex >= 0 && currentIndex < lyrics.size) {
            val visibleRange = listState.layoutInfo.visibleItemsInfo
            val firstVisible = visibleRange.firstOrNull()?.index ?: -1
            val lastVisible = visibleRange.lastOrNull()?.index ?: -1
            if (currentIndex !in firstVisible..lastVisible) {
                val distance = if (firstVisible >= 0) abs(currentIndex - firstVisible) else Int.MAX_VALUE
                if (distance <= 2) {
                    listState.animateScrollToItem(currentIndex, scrollOffset = -220)
                } else {
                    listState.scrollToItem(currentIndex, scrollOffset = -220)
                }
            }
        }
    }

    Box(modifier = modifier
        .fillMaxSize()
        .pointerInput(Unit) {
            detectVerticalDragGestures(
                onVerticalDrag = { _, dragAmount ->
                    // If we are at the top and swipe down, trigger dismiss
                    if (dragAmount > 50 && listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0) {
                        onSwipeDown()
                    }
                }
            )
        }
    ) {
        if (isLoading && lyrics.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    color = Color.White,
                    strokeWidth = 2.dp
                )
            }
        } else if (lyrics.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "No lyrics found",
                    color = Color.White.copy(alpha = 0.5f),
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 40.dp, bottom = 80.dp, start = 24.dp, end = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                itemsIndexed(
                    items = lyrics,
                    key = { _, line -> "${line.time}_${line.text}" }
                ) { index, line ->
                    val isCurrent = index == currentIndex
                    val progressInLine = if (isCurrent) {
                        (currentProgressMs + lyricsOffsetMs - line.time).coerceAtLeast(0)
                    } else 0L

                    LyricLineItem(
                        line = line,
                        isCurrent = isCurrent,
                        progressInLine = progressInLine,
                        onClick = { 
                            onLineClick(line.time)
                            showOffsetControls = !showOffsetControls
                        }
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = lyrics.isNotEmpty() && lyricsSource == LyricsSource.EMBEDDED,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 8.dp)
        ) {
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = Color.Black.copy(alpha = 0.6f),
                tonalElevation = 4.dp
            ) {
                Text(
                    text = "Embedded Lyrics",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    color = Color.White.copy(alpha = 0.8f),
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }

        // Offset Controls Overlay
        AnimatedVisibility(
            visible = showOffsetControls,
            enter = fadeIn() + slideInVertically { -it },
            exit = fadeOut() + slideOutVertically { -it },
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 16.dp)
        ) {
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = Color.Black.copy(alpha = 0.7f),
                tonalElevation = 8.dp,
                border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(0.1f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    IconButton(onClick = { onAdjustOffset(-100) }) {
                        Icon(Icons.Rounded.Remove, "Delay", tint = Color.White)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "Sync Offset",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.6f)
                        )
                        Text(
                            "${if (lyricsOffsetMs >= 0) "+" else ""}${lyricsOffsetMs}ms",
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    IconButton(onClick = { onAdjustOffset(100) }) {
                        Icon(Icons.Rounded.Add, "Advance", tint = Color.White)
                    }
                }
            }
            
            LaunchedEffect(showOffsetControls) {
                if (showOffsetControls) {
                    delay(3000)
                    showOffsetControls = false
                }
            }
        }
    }
}

@Composable
fun LyricLineItem(
    line: LrcLine,
    isCurrent: Boolean,
    progressInLine: Long,
    onClick: () -> Unit
) {
    val alpha by animateFloatAsState(if (isCurrent) 1f else 0.4f, label = "alpha")
    val scale by animateFloatAsState(if (isCurrent) 1.05f else 1f, label = "scale")
    val color = if (isCurrent) Color.White else Color.White

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                this.alpha = alpha
            },
        contentAlignment = Alignment.Center
    ) {
        if (line.words.isNotEmpty() && isCurrent) {
            // Karaoke word-by-word highlighting
            WordByWordText(
                words = line.words,
                progressInLine = progressInLine,
                activeColor = Color.White,
                inactiveColor = Color.White.copy(alpha = 0.5f)
            )
        } else {
            Text(
                text = line.text,
                color = color,
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 28.sp,
                    textAlign = TextAlign.Center,
                    lineHeight = 38.sp
                )
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WordByWordText(
    words: List<Word>,
    progressInLine: Long,
    activeColor: Color,
    inactiveColor: Color
) {
    val textStyle = MaterialTheme.typography.headlineMedium.copy(
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        textAlign = TextAlign.Center,
        lineHeight = 38.sp
    )

    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center
    ) {
        val lineStartTime = words.firstOrNull()?.time ?: 0L
        words.forEach { word ->
            val relativeWordStartTime = word.time - lineStartTime
            val isWordStarted = progressInLine >= relativeWordStartTime
            
            val wordColor = if (isWordStarted) activeColor else inactiveColor
            
            Text(
                text = word.text,
                color = wordColor,
                style = textStyle,
                modifier = Modifier.padding(horizontal = 2.dp)
            )
        }
    }
}
