package com.beatraxus.app.subtitles.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.beatraxus.app.subtitles.domain.SecondarySubtitlePlacement
import com.beatraxus.app.subtitles.viewmodel.SubtitleViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Draws the second subtitle of a dual-subtitle pair (video feature 22) on top of the video.
 *
 * ExoPlayer's own subtitle view shows the main subtitle. The second one is a separate list of cues, looked up here
 * against the player's position about ten times a second, so it follows seeking, pausing and speed changes.
 * The overlay takes no touches, so the player gestures work through it.
 *
 * @param positionProvider the player's current position in milliseconds
 * @param primarySizeSp the main subtitle's text size, which the second subtitle's size is a percentage of
 * @param primaryTranslationY the vertical shift the player applies to the main subtitle (position preset + offset)
 * @param primaryBold whether the main subtitle is bold; the second one matches it
 */
@Composable
fun SecondarySubtitleOverlay(
    subtitleViewModel: SubtitleViewModel,
    positionProvider: () -> Long,
    primarySizeSp: Float,
    primaryTranslationY: Float,
    primaryBold: Boolean,
    modifier: Modifier = Modifier
) {
    val uiState by subtitleViewModel.uiState.collectAsState()
    val timeline by subtitleViewModel.secondaryTimeline.collectAsState()
    val active = uiState.enabled && uiState.dualEnabled && timeline != null

    var text by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(timeline, active) {
        if (!active) {
            text = null
            return@LaunchedEffect
        }
        val current = timeline
        while (isActive) {
            val next = current?.textAt(positionProvider())
            if (next != text) text = next
            delay(POLL_INTERVAL_MS)
        }
    }

    val shown = text ?: return
    val style = uiState.secondaryStyle
    val textSizeSp = (primarySizeSp * style.sizePercent / 100f).coerceAtLeast(MIN_TEXT_SP)

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val textStyle = TextStyle(
            color = Color(style.textColor),
            fontSize = textSizeSp.sp,
            lineHeight = (textSizeSp * 1.2f).sp,
            fontWeight = if (primaryBold) FontWeight.Bold else FontWeight.Normal,
            textAlign = TextAlign.Center,
            shadow = Shadow(color = Color.Black, offset = Offset(1.5f, 1.5f), blurRadius = 6f)
        )

        when (style.placement) {
            SecondarySubtitlePlacement.TOP -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.TopCenter
            ) {
                Text(
                    text = shown,
                    style = textStyle,
                    modifier = Modifier.padding(top = TOP_MARGIN, start = SIDE_MARGIN, end = SIDE_MARGIN)
                )
            }

            SecondarySubtitlePlacement.ABOVE_PRIMARY -> {
                // The main subtitle sits about 8% up from the bottom edge (the SubtitleView default) and can run to
                // two lines, so the second one is lifted by that plus two lines of the main text size, and then
                // moved by the same shift the player applies to the main subtitle.
                val primaryTwoLines = with(LocalDensity.current) { (primarySizeSp * 2.6f).sp.toDp() }
                val bottomPadding = maxHeight * BOTTOM_FRACTION + primaryTwoLines
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { translationY = primaryTranslationY },
                    contentAlignment = Alignment.BottomCenter
                ) {
                    Text(
                        text = shown,
                        style = textStyle,
                        modifier = Modifier.padding(bottom = bottomPadding, start = SIDE_MARGIN, end = SIDE_MARGIN)
                    )
                }
            }
        }
    }
}

private const val POLL_INTERVAL_MS = 100L
private const val MIN_TEXT_SP = 10f
private const val BOTTOM_FRACTION = 0.08f
private val TOP_MARGIN = 28.dp
private val SIDE_MARGIN = 32.dp
