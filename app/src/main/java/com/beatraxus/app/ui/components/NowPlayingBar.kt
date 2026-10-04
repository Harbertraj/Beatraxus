package com.beatraxus.app.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.beatraxus.app.model.AppearanceConfig
import com.beatraxus.app.model.NowPlayingBackgroundMode
import com.beatraxus.app.model.Song
import com.beatraxus.app.utils.ImageUtils
import java.util.Locale

/**
 * Redesigned Now Playing bar (floating glass pill).
 *
 * Fully driven by the "Mini Player Background" appearance settings:
 *  - BLACK : flat dark surface
 *  - SOLID : album colour at miniPlayerSolidColorIntensity + black scrim at ...Darkness
 *  - BLUR  : blurred album art (miniPlayerBlurIntensity) + black scrim at miniPlayerBlurDarkness
 *
 * Progress is shown as a ring around the play/pause button, so there is no extra bar at the bottom.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NowPlayingBar(
    song: Song?,
    isPlaying: Boolean,
    isFromVideo: Boolean,
    appearance: AppearanceConfig,
    dominantColor: Color,
    progressMs: () -> Long,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onResumeVideo: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(26.dp)
    val durationMs = song?.durationMs ?: 0L
    val latestNext by rememberUpdatedState(onNext)
    val latestPrev by rememberUpdatedState(onPrevious)
    val accent = remember(dominantColor) { lerp(dominantColor, Color.White, 0.65f) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp)
            .shadow(
                elevation = 14.dp,
                shape = shape,
                ambientColor = Color.Black.copy(alpha = 0.45f),
                spotColor = Color.Black.copy(alpha = 0.55f)
            )
            .clip(shape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .pointerInput(Unit) {
                var totalX = 0f
                detectHorizontalDragGestures(
                    onDragStart = { totalX = 0f },
                    onDragEnd = {
                        if (totalX > 60) latestPrev() else if (totalX < -60) latestNext()
                    },
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        totalX += dragAmount
                    }
                )
            }
    ) {
        // Background (connected to Settings > Appearance > Mini Player Background)
        NowPlayingBarBackground(song, appearance, dominantColor)

        // hairline glass border
        Box(
            Modifier
                .fillMaxSize()
                .border(
                    width = 0.6.dp,
                    brush = Brush.verticalGradient(
                        listOf(Color.White.copy(alpha = 0.28f), Color.White.copy(alpha = 0.04f))
                    ),
                    shape = shape
                )
        )

        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 10.dp, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Album art
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(15.dp))
                    .background(Color.White.copy(alpha = 0.06f)),
                contentAlignment = Alignment.Center
            ) {
                if (isFromVideo) {
                    Icon(Icons.Rounded.Movie, null, tint = Color.White.copy(0.85f), modifier = Modifier.size(22.dp))
                } else {
                    val context = LocalContext.current
                    val model = remember(song?.id, song?.albumArtUri) {
                        ImageRequest.Builder(context)
                            .data(song?.albumArtUri)
                            .diskCachePolicy(CachePolicy.ENABLED)
                            .memoryCachePolicy(CachePolicy.ENABLED)
                            .crossfade(true)
                            .error(ImageUtils.getDefaultAlbumArtRes())
                            .fallback(ImageUtils.getDefaultAlbumArtRes())
                            .build()
                    }
                    AsyncImage(
                        model = model,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            Spacer(Modifier.width(12.dp))

            // Title / artist · runtime
            // Font padding is trimmed and line heights are explicit, so the three lines sit
            // tightly together instead of floating apart.
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(1.dp, Alignment.CenterVertically)
            ) {
                AnimatedContent(
                    targetState = (song?.title ?: "Unknown") to
                        (if (isFromVideo) "Playing in background" else (song?.artist ?: "Unknown artist")),
                    transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) },
                    label = "nowPlayingBarText"
                ) { (title, subtitle) ->
                    Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                        Text(
                            text = title,
                            color = Color.White,
                            fontSize = 14.5.sp,
                            lineHeight = 18.sp,
                            fontWeight = FontWeight.Bold,
                            style = TightTextStyle,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = subtitle,
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 12.sp,
                            lineHeight = 15.sp,
                            style = TightTextStyle,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                if (durationMs > 0L) {
                    val elapsed by remember { derivedStateOf { formatBarTime(progressMs()) } }
                    Text(
                        text = "$elapsed / ${formatBarTime(durationMs)}",
                        color = accent.copy(alpha = 0.9f),
                        fontSize = 10.5.sp,
                        lineHeight = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        style = TightTextStyle,
                        maxLines = 1
                    )
                }
            }

            Spacer(Modifier.width(8.dp))

            // Play / pause with progress ring
            Box(
                modifier = Modifier.size(48.dp),
                contentAlignment = Alignment.Center
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    val stroke = 2.6.dp.toPx()
                    val inset = stroke / 2f
                    val arcSize = Size(size.width - stroke, size.height - stroke)
                    drawArc(
                        color = Color.White.copy(alpha = 0.16f),
                        startAngle = 0f, sweepAngle = 360f, useCenter = false,
                        topLeft = Offset(inset, inset), size = arcSize,
                        style = Stroke(width = stroke)
                    )
                    val p = if (durationMs > 0) (progressMs().toFloat() / durationMs).coerceIn(0f, 1f) else 0f
                    drawArc(
                        color = accent,
                        startAngle = -90f, sweepAngle = 360f * p, useCenter = false,
                        topLeft = Offset(inset, inset), size = arcSize,
                        style = Stroke(width = stroke, cap = StrokeCap.Round)
                    )
                }
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                        .clickable(role = Role.Button, onClick = onPlayPause),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        tint = Color.Black,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            // Next / fullscreen (video)
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .clickable(role = Role.Button, onClick = if (isFromVideo) onResumeVideo else onNext),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isFromVideo) Icons.Rounded.Fullscreen else Icons.Rounded.SkipNext,
                    contentDescription = if (isFromVideo) "Open video" else "Next",
                    tint = Color.White.copy(alpha = 0.9f),
                    modifier = Modifier.size(26.dp)
                )
            }
        }
    }
}

@Composable
private fun NowPlayingBarBackground(
    song: Song?,
    appearance: AppearanceConfig,
    dominantColor: Color
) {
    Box(Modifier.fillMaxSize().background(Color(0xFF101014))) {
        when (appearance.miniPlayerBackgroundMode) {
            NowPlayingBackgroundMode.BLACK -> Unit

            NowPlayingBackgroundMode.SOLID -> {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(dominantColor.copy(alpha = appearance.miniPlayerSolidColorIntensity.coerceIn(0f, 1f)))
                )
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = appearance.miniPlayerSolidColorDarkness.coerceIn(0f, 1f)))
                )
            }

            NowPlayingBackgroundMode.BLUR -> {
                val context = LocalContext.current
                val model = remember(song?.id, song?.albumArtUri) {
                    ImageRequest.Builder(context)
                        .data(song?.albumArtUri)
                        .size(240, 240)
                        .diskCachePolicy(CachePolicy.ENABLED)
                        .memoryCachePolicy(CachePolicy.ENABLED)
                        .error(ImageUtils.getDefaultAlbumArtRes())
                        .fallback(ImageUtils.getDefaultAlbumArtRes())
                        .build()
                }
                AsyncImage(
                    model = model,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { scaleX = 1.35f; scaleY = 1.35f }
                        .blur(appearance.miniPlayerBlurIntensity.coerceIn(0f, 120f).dp)
                )
                // Was a fixed 0.4-0.6 gradient before; now follows the "darkness" slider.
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = appearance.miniPlayerBlurDarkness.coerceIn(0f, 1f)))
                )
            }
        }
    }
}

private val TightTextStyle = TextStyle(
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    lineHeightStyle = LineHeightStyle(
        alignment = LineHeightStyle.Alignment.Center,
        trim = LineHeightStyle.Trim.Both
    )
)

private fun formatBarTime(ms: Long): String {
    val totalSec = (ms / 1000L).coerceAtLeast(0L)
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.US, "%d:%02d", m, s)
}
