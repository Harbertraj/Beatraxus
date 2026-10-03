package com.beatraxus.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.beatraxus.app.repository.LyricsCandidate
import com.beatraxus.app.repository.LyricsType

private val WordColor = Color(0xFF00C2A8)
private val LineColor = Color(0xFF2196F3)
private val PlainColor = Color(0xFF9E9E9E)
private val FileColor = Color(0xFFFF9800)

private fun typeColor(type: LyricsType) = when (type) {
    LyricsType.WORD_BY_WORD -> WordColor
    LyricsType.SYNCED -> LineColor
    LyricsType.PLAIN -> PlainColor
}

private fun typeLabel(type: LyricsType) = when (type) {
    LyricsType.WORD_BY_WORD -> "WORD"
    LyricsType.SYNCED -> "LINE"
    LyricsType.PLAIN -> "PLAIN"
}

/** word -> line -> plain (same priority the lyrics repository uses). */
private fun typeRank(type: LyricsType) = when (type) {
    LyricsType.WORD_BY_WORD -> 0
    LyricsType.SYNCED -> 1
    LyricsType.PLAIN -> 2
}

/**
 * Floating "source picker" card for lyrics. It is a centred glass dialog (not a bottom sheet):
 * a header with live counts per sync type, then provider cards ordered Word -> Line -> Plain,
 * the best valid one tagged BEST, and the applied one highlighted with a check.
 */
@Composable
fun LyricsSourcesPopup(
    candidates: List<LyricsCandidate>,
    isLoading: Boolean,
    appliedProviderId: String?,
    albumArtUri: Any?,
    useArtBackdrop: Boolean,
    onApply: (LyricsCandidate) -> Unit,
    onDismiss: () -> Unit
) {
    val sorted = remember(candidates) { candidates.sortedBy { typeRank(it.type) } }
    val bestId = remember(sorted) { sorted.firstOrNull { it.warning.isNullOrBlank() }?.providerId }
    val wordCount = sorted.count { it.type == LyricsType.WORD_BY_WORD }
    val lineCount = sorted.count { it.type == LyricsType.SYNCED }
    val plainCount = sorted.count { it.type == LyricsType.PLAIN }

    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }

    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.74f).dp
    val cardShape = RoundedCornerShape(32.dp)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss
                ),
            contentAlignment = Alignment.Center
        ) {
            AnimatedVisibility(
                visible = shown,
                enter = fadeIn(tween(200)) + scaleIn(
                    initialScale = 0.9f,
                    animationSpec = spring(dampingRatio = 0.78f, stiffness = Spring.StiffnessMedium)
                )
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.92f)
                        .heightIn(max = maxHeight)
                        .clip(cardShape)
                        .background(Color(0xFF0E0E14))
                        .border(
                            BorderStroke(
                                1.dp,
                                Brush.verticalGradient(listOf(Color.White.copy(0.22f), Color.White.copy(0.04f)))
                            ),
                            cardShape
                        )
                        // swallow taps so touching the card doesn't dismiss
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {}
                        )
                ) {
                    if (useArtBackdrop && albumArtUri != null) {
                        AsyncImage(
                            model = ImageRequest.Builder(LocalContext.current).data(albumArtUri).size(200, 200).build(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .matchParentSize()
                                .graphicsLayer { scaleX = 1.4f; scaleY = 1.4f }
                                .blur(60.dp)
                                .alpha(0.28f)
                        )
                    }

                    Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 18.dp)) {
                        // ── header ──
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(42.dp)
                                    .clip(CircleShape)
                                    .background(Brush.linearGradient(listOf(Color(0xFF8E6BFF), Color(0xFFFF4D9D)))),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Rounded.MusicNote, null, tint = Color.White, modifier = Modifier.size(22.dp))
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "Lyrics Sources",
                                    color = Color.White,
                                    fontSize = 19.sp,
                                    fontWeight = FontWeight.ExtraBold
                                )
                                Text(
                                    text = when {
                                        isLoading -> "Searching… ${sorted.size} found so far"
                                        sorted.isEmpty() -> "Nothing found"
                                        else -> "${sorted.size} found · best sync first"
                                    },
                                    color = Color.White.copy(alpha = 0.5f),
                                    fontSize = 12.sp
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(Color.White.copy(alpha = 0.08f))
                                    .clickable(onClick = onDismiss),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Rounded.Close, "Close", tint = Color.White.copy(0.85f), modifier = Modifier.size(18.dp))
                            }
                        }

                        if (sorted.isNotEmpty()) {
                            Spacer(Modifier.height(14.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                CountChip("Word", wordCount, WordColor)
                                CountChip("Line", lineCount, LineColor)
                                CountChip("Plain", plainCount, PlainColor)
                            }
                        }

                        Spacer(Modifier.height(14.dp))

                        when {
                            sorted.isEmpty() && isLoading -> SkeletonList()
                            sorted.isEmpty() -> {
                                Box(
                                    modifier = Modifier.fillMaxWidth().height(110.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        "No lyrics found from the enabled sources.",
                                        color = Color.White.copy(0.5f),
                                        fontSize = 14.sp,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                            else -> {
                                LazyColumn(
                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                    contentPadding = PaddingValues(bottom = 4.dp)
                                ) {
                                    items(sorted, key = { it.providerId }) { candidate ->
                                        SourceCard(
                                            candidate = candidate,
                                            isApplied = candidate.providerId == appliedProviderId,
                                            isBest = candidate.providerId == bestId,
                                            onClick = { onApply(candidate) }
                                        )
                                    }
                                    if (isLoading) {
                                        item {
                                            Row(
                                                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                                horizontalArrangement = Arrangement.Center,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                CircularProgressIndicator(
                                                    modifier = Modifier.size(16.dp),
                                                    strokeWidth = 2.dp,
                                                    color = Color.White.copy(0.7f)
                                                )
                                                Spacer(Modifier.width(8.dp))
                                                Text("Checking more sources…", color = Color.White.copy(0.45f), fontSize = 12.sp)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CountChip(label: String, count: Int, color: Color) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = if (count > 0) 0.14f else 0.05f))
            .border(0.8.dp, color.copy(alpha = if (count > 0) 0.4f else 0.12f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "$label $count",
            color = if (count > 0) color else Color.White.copy(0.3f),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun SourceCard(
    candidate: LyricsCandidate,
    isApplied: Boolean,
    isBest: Boolean,
    onClick: () -> Unit
) {
    val color = typeColor(candidate.type)
    val shape = RoundedCornerShape(22.dp)
    val isFile = candidate.providerId == "embedded"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (isApplied) color.copy(alpha = 0.14f) else Color.White.copy(alpha = 0.05f))
            .border(
                BorderStroke(
                    if (isApplied) 1.4.dp else 1.dp,
                    if (isApplied) color.copy(alpha = 0.7f) else Color.White.copy(alpha = 0.07f)
                ),
                shape
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // avatar: first letter, tinted by sync type
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(CircleShape)
                .background(Brush.linearGradient(listOf(color.copy(0.9f), color.copy(0.45f)))),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = candidate.providerName.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?",
                color = Color.White,
                fontSize = 17.sp,
                fontWeight = FontWeight.Black
            )
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = candidate.providerName,
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (isBest) {
                    Spacer(Modifier.width(6.dp))
                    Tag("BEST", Color(0xFFFFD54F))
                }
                if (isFile) {
                    Spacer(Modifier.width(6.dp))
                    Tag("FILE", FileColor)
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Tag(typeLabel(candidate.type), color)
                Spacer(Modifier.width(8.dp))
                Text("${candidate.lineCount} lines", color = Color.White.copy(0.45f), fontSize = 11.sp)
            }
            Spacer(Modifier.height(5.dp))
            Text(
                text = candidate.preview,
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 12.5.sp,
                fontStyle = FontStyle.Italic,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!candidate.warning.isNullOrBlank()) {
                Spacer(Modifier.height(5.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Warning, null, tint = FileColor, modifier = Modifier.size(12.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = candidate.warning,
                        color = FileColor,
                        fontSize = 11.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        if (isApplied) {
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier.size(28.dp).clip(CircleShape).background(color),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.Check, "Applied", tint = Color.Black, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun Tag(text: String, color: Color) {
    Text(
        text = text,
        color = color,
        fontSize = 9.sp,
        fontWeight = FontWeight.Black,
        letterSpacing = 0.6.sp,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.15f))
            .border(0.8.dp, color.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

@Composable
private fun SkeletonList() {
    val transition = rememberInfiniteTransition(label = "lyricsSkeleton")
    val a by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(tween(800, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "skeletonAlpha"
    )
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        repeat(3) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(22.dp))
                    .background(Color.White.copy(alpha = 0.05f))
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(42.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.12f * a)))
                Spacer(Modifier.width(12.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.width(120.dp).height(12.dp).clip(RoundedCornerShape(6.dp)).background(Color.White.copy(alpha = 0.14f * a)))
                    Box(Modifier.width(190.dp).height(10.dp).clip(RoundedCornerShape(6.dp)).background(Color.White.copy(alpha = 0.09f * a)))
                }
            }
        }
    }
}
