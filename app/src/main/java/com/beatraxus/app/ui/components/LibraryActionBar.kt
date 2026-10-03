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

/**
 * Multi-select toolbar: Select all, Delete, Play next, Add to playlist, Share.
 * Same actions as before, now as labelled glass buttons with their own accent colours.
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
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        GlassActionButton(
            icon = Icons.Rounded.SelectAll,
            contentDescription = "Select all",
            label = "Select all",
            accent = ActionCyan,
            selected = true,
            onClick = onSelectAll
        )
        if (showDelete) {
            GlassActionButton(
                icon = Icons.Rounded.Delete,
                contentDescription = "Delete",
                label = "Delete",
                accent = Color(0xFFFF453A),
                onClick = onDelete
            )
        }
        GlassActionButton(
            icon = Icons.AutoMirrored.Rounded.PlaylistPlay,
            contentDescription = "Play next",
            label = "Play next",
            accent = Color(0xFF30D158),
            onClick = onPlayNext
        )
        GlassActionButton(
            icon = Icons.AutoMirrored.Rounded.PlaylistAdd,
            contentDescription = "Add to playlist",
            label = "Add",
            accent = Color(0xFFFFD60A),
            onClick = onAddToPlaylist
        )
        GlassActionButton(
            icon = Icons.Rounded.Share,
            contentDescription = "Share",
            label = "Share",
            accent = AccentBlue,
            onClick = onShare
        )
    }
}
