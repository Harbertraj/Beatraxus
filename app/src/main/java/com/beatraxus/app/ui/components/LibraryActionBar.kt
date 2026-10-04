package com.beatraxus.app.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.beatraxus.app.ui.theme.AccentBlue
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.rounded.Check
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlin.math.roundToInt

private val ActionCyan = Color(0xFF00F2FF)

/**
 * Round glass icon button used in the library top action row
 * (filter / sort, cloud, density, select-all, share, ...).
 *
 * - Frosted glass at rest, accent-tinted glass + glowing rim when [selected].
 * - Springy press feedback.
 * - Optional small [label] under the icon.
 */
@Composable
fun GlassActionButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = AccentBlue,
    selected: Boolean = false,
    size: Dp = 46.dp,
    iconSize: Dp = 22.dp,
    label: String? = null
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.88f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "actionScale"
    )
    val sel by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = tween(320),
        label = "actionSelected"
    )

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(size)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .clip(CircleShape)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.White.copy(alpha = 0.17f), Color.White.copy(alpha = 0.05f))
                    )
                )
                .background(
                    Brush.verticalGradient(
                        listOf(accent.copy(alpha = 0.34f * sel), accent.copy(alpha = 0.10f * sel))
                    )
                )
                .border(
                    1.dp,
                    Brush.verticalGradient(
                        listOf(
                            lerp(Color.White.copy(alpha = 0.34f), accent.copy(alpha = 0.95f), sel),
                            lerp(Color.White.copy(alpha = 0.06f), accent.copy(alpha = 0.30f), sel)
                        )
                    ),
                    CircleShape
                )
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    role = Role.Button,
                    onClick = onClick
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = lerp(Color.White.copy(alpha = 0.92f), accent, sel),
                modifier = Modifier.size(iconSize)
            )
        }
        if (label != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = label,
                color = lerp(Color.White.copy(alpha = 0.6f), accent, sel),
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
        }
    }
}

/**
 * "Shuffle All" pill: glass capsule with a cyan->blue rim and a glowing shuffle badge.
 */
@Composable
fun ShuffleAllPill(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Shuffle All"
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.95f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "shuffleScale"
    )
    val shape = RoundedCornerShape(50)
    val gradient = Brush.horizontalGradient(listOf(ActionCyan, AccentBlue))

    Row(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(shape)
            .background(
                Brush.horizontalGradient(
                    listOf(ActionCyan.copy(alpha = 0.20f), AccentBlue.copy(alpha = 0.16f))
                )
            )
            .border(
                1.dp,
                Brush.horizontalGradient(
                    listOf(ActionCyan.copy(alpha = 0.85f), AccentBlue.copy(alpha = 0.55f))
                ),
                shape
            )
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClick = onClick
            )
            .padding(start = 6.dp, end = 18.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(gradient),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.Shuffle,
                contentDescription = null,
                tint = Color.Black,
                modifier = Modifier.size(19.dp)
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = label,
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 0.2.sp,
            maxLines = 1
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Stripe-style action bar
// ─────────────────────────────────────────────────────────────────────────────

/** One segment of [LibraryActionStrip]. [onClick] receives the segment bounds (root coords) for dropdown anchoring. */
data class StripAction(
    val icon: ImageVector,
    val contentDescription: String,
    val accent: Color,
    val selected: Boolean = false,
    val label: String? = null,
    val onClick: (anchor: Rect) -> Unit
)

/**
 * A single slim glass "stripe" holding every library action as a segment.
 * Segments are separated by hairline dividers; the active segment lights up
 * with an accent wash and a glowing underline stripe.
 */
@Composable
fun LibraryActionStrip(
    actions: List<StripAction>,
    modifier: Modifier = Modifier,
    showShuffle: Boolean = false,
    onShuffle: () -> Unit = {}
) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(46.dp)
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    listOf(Color.White.copy(alpha = 0.10f), Color.White.copy(alpha = 0.04f))
                )
            )
            .border(
                1.dp,
                Brush.verticalGradient(
                    listOf(Color.White.copy(alpha = 0.22f), Color.White.copy(alpha = 0.05f))
                ),
                shape
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (showShuffle) {
            ShuffleSegment(onShuffle, Modifier.weight(2.3f).fillMaxHeight())
            StripDivider()
        }
        actions.forEachIndexed { index, action ->
            if (index > 0) StripDivider()
            StripSegment(action, Modifier.weight(1f).fillMaxHeight())
        }
    }
}

@Composable
private fun StripDivider() {
    Box(
        Modifier
            .width(1.dp)
            .height(22.dp)
            .background(Color.White.copy(alpha = 0.12f))
    )
}

@Composable
private fun ShuffleSegment(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val press by animateFloatAsState(
        targetValue = if (pressed) 0.94f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "shuffleSegScale"
    )
    // Same neutral glass look as the other strip icons (no cyan/blue colour).
    Box(
        modifier = modifier
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier.graphicsLayer { scaleX = press; scaleY = press },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Rounded.Shuffle,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.85f),
                modifier = Modifier.size(21.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "Shuffle All",
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.2.sp,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun StripSegment(action: StripAction, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    var bounds by remember { mutableStateOf(Rect.Zero) }
    val press by animateFloatAsState(
        targetValue = if (pressed) 0.86f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "segScale"
    )
    val sel by animateFloatAsState(
        targetValue = if (action.selected) 1f else 0f,
        animationSpec = tween(260),
        label = "segSel"
    )
    Box(
        modifier = modifier
            .onGloballyPositioned { bounds = it.boundsInRoot() }
            .background(
                Brush.verticalGradient(
                    listOf(Color.Transparent, action.accent.copy(alpha = 0.22f * sel))
                )
            )
            .clickable(interactionSource = interaction, indication = null, role = Role.Button) {
                action.onClick(bounds)
            },
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.graphicsLayer { scaleX = press; scaleY = press }
        ) {
            Icon(
                imageVector = action.icon,
                contentDescription = action.contentDescription,
                tint = lerp(Color.White.copy(alpha = 0.85f), action.accent, sel),
                modifier = Modifier.size(21.dp)
            )
            if (action.label != null) {
                Text(
                    text = action.label,
                    color = lerp(Color.White.copy(alpha = 0.55f), action.accent, sel),
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
            }
        }
        // glowing underline stripe, grows from the centre when active
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(0.62f)
                .height(3.dp)
                .graphicsLayer { scaleX = sel; alpha = sel }
                .background(action.accent, RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
        )
    }
}

/**
 * Multi-select toolbar in the same stripe style:
 * Select all, Delete, Play next, Add to playlist, Share.
 */
@Composable
fun MultiSelectActionBar(
    showDelete: Boolean,
    onSelectAll: () -> Unit,
    onDelete: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier
) {
    val actions = buildList {
        add(StripAction(Icons.Rounded.SelectAll, "Select all", ActionCyan, selected = true, label = "ALL") { onSelectAll() })
        if (showDelete) add(StripAction(Icons.Rounded.Delete, "Delete", Color(0xFFFF453A), label = "DELETE") { onDelete() })
        add(StripAction(Icons.AutoMirrored.Rounded.PlaylistPlay, "Play next", Color(0xFF30D158), label = "NEXT") { onPlayNext() })
        add(StripAction(Icons.AutoMirrored.Rounded.PlaylistAdd, "Add to playlist", Color(0xFFFFD60A), label = "ADD") { onAddToPlaylist() })
        add(StripAction(Icons.Rounded.Share, "Share", AccentBlue, label = "SHARE") { onShare() })
    }
    LibraryActionStrip(actions = actions, modifier = modifier.padding(vertical = 2.dp))
}

// ─────────────────────────────────────────────────────────────────────────────
//  Compact dropdown (replaces the big bottom sheets)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Small dropdown card that opens right under [anchor] (bounds of the tapped strip segment),
 * scaling out from the anchor. Only a light dim is drawn behind it.
 */
@Composable
fun LibraryDropdown(
    expanded: Boolean,
    onDismiss: () -> Unit,
    anchor: Rect,
    modifier: Modifier = Modifier,
    width: Dp = 210.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    if (!expanded) return
    Popup(
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true, dismissOnBackPress = true, dismissOnClickOutside = false)
    ) {
        val progress = remember { Animatable(0f) }
        LaunchedEffect(Unit) { progress.animateTo(1f, tween(190, easing = FastOutSlowInEasing)) }

        BoxWithConstraints(Modifier.fillMaxSize()) {
            val density = LocalDensity.current
            val widthPx = with(density) { width.toPx() }
            val maxWidthPx = with(density) { maxWidth.toPx() }
            val marginPx = with(density) { 10.dp.toPx() }
            val hasAnchor = anchor.width > 1f && anchor.height > 1f
            val anchorCx = if (hasAnchor) anchor.center.x else maxWidthPx / 2f
            val leftPx = (anchorCx - widthPx / 2f)
                .coerceIn(marginPx, (maxWidthPx - widthPx - marginPx).coerceAtLeast(marginPx))
            val topPx = if (hasAnchor) anchor.bottom + with(density) { 6.dp.toPx() } else with(density) { 100.dp.toPx() }
            val originX = ((anchorCx - leftPx) / widthPx).coerceIn(0.1f, 0.9f)
            val cardShape = RoundedCornerShape(18.dp)

            // light dim + tap-outside to close
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = progress.value }
                    .background(Color.Black.copy(alpha = 0.22f))
                    .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onDismiss() }
            )

            Column(
                modifier = modifier
                    .offset { IntOffset(leftPx.roundToInt(), topPx.roundToInt()) }
                    .width(width)
                    .heightIn(max = maxHeight * 0.6f)
                    .graphicsLayer {
                        val p = progress.value
                        scaleX = 0.9f + 0.1f * p
                        scaleY = 0.82f + 0.18f * p
                        alpha = p
                        transformOrigin = TransformOrigin(originX, 0f)
                    }
                    .shadow(14.dp, cardShape, ambientColor = Color.Black, spotColor = Color.Black.copy(alpha = 0.4f))
                    .clip(cardShape)
                    .background(
                        Brush.verticalGradient(listOf(Color(0xFA1E1E28), Color(0xFA131319)))
                    )
                    .border(
                        1.dp,
                        Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.22f), Color.White.copy(alpha = 0.05f))),
                        cardShape
                    )
                    .verticalScroll(rememberScrollState())
                    .padding(6.dp),
                content = content
            )
        }
    }
}

@Composable
fun DropdownHeader(title: String, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            color = Color.White.copy(alpha = 0.5f),
            fontSize = 10.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 1.2.sp,
            modifier = Modifier.weight(1f)
        )
        trailing?.invoke()
    }
}

@Composable
fun DropdownRow(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = AccentBlue,
    subLabel: String? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(if (selected) accent.copy(alpha = 0.16f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = if (selected) accent else Color.White.copy(alpha = 0.55f), modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                label,
                color = if (selected) Color.White else Color.White.copy(alpha = 0.78f),
                fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1
            )
            if (subLabel != null) {
                Text(subLabel, color = Color.White.copy(alpha = 0.4f), fontSize = 10.sp, maxLines = 1)
            }
        }
        if (trailing != null) trailing()
        else if (selected) Icon(Icons.Rounded.Check, null, tint = accent, modifier = Modifier.size(16.dp))
    }
}
