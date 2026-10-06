package com.beatraxus.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.beatraxus.app.ui.theme.AccentBlue
import com.beatraxus.app.ui.utils.DialogBlurBehind
import com.beatraxus.app.ui.utils.rememberWindowBlurSupported

/**
 * Shared "glass card" popup that mirrors the look of [SongInfoDialog]:
 * blurred backdrop, rounded gradient card, accent glow line, round icon badge + title,
 * scrollable body and a footer row. Used by Edit Tags, Set as, DLNA / UPnP and the
 * video Properties / Rename popups so every popup in the app feels the same.
 *
 * @param footer custom footer buttons; when null a single "Dismiss" button is shown.
 * @param dismissible when false (e.g. while saving) outside taps / back do not close it.
 */
@Composable
fun InfoStyleDialog(
    title: String,
    icon: ImageVector,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    dismissible: Boolean = true,
    maxHeightFraction: Float = 0.72f,
    headerTrailing: (@Composable RowScope.() -> Unit)? = null,
    footer: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val blurWorks = rememberWindowBlurSupported()
    val tapOutside: () -> Unit = { if (dismissible) onDismiss() }

    Dialog(
        onDismissRequest = tapOutside,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = dismissible,
            dismissOnClickOutside = dismissible
        )
    ) {
        DialogBlurBehind(radiusDp = 22)

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = if (blurWorks) 0f else 0.28f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = tapOutside
                )
                .imePadding(),
            contentAlignment = Alignment.Center
        ) {
            val screenHeight = LocalConfiguration.current.screenHeightDp.dp
            val cardShape = RoundedCornerShape(26.dp)

            Column(
                modifier = modifier
                    .fillMaxWidth(0.88f)
                    .widthIn(max = 400.dp)
                    .heightIn(max = screenHeight * maxHeightFraction)
                    .clip(cardShape)
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color(0xFF1B1C26).copy(alpha = 0.86f),
                                Color(0xFF0E0F15).copy(alpha = 0.90f)
                            )
                        )
                    )
                    .border(
                        BorderStroke(
                            1.dp,
                            Brush.verticalGradient(
                                listOf(Color.White.copy(alpha = 0.26f), Color.White.copy(alpha = 0.05f))
                            )
                        ),
                        cardShape
                    )
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {}
                    )
            ) {
                // Accent glow line
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(
                            Brush.horizontalGradient(
                                listOf(Color.Transparent, AccentBlue.copy(alpha = 0.9f), Color.Transparent)
                            )
                        )
                )

                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 14.dp, top = 12.dp, bottom = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f, fill = false),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .background(AccentBlue.copy(alpha = 0.16f), CircleShape)
                                .border(1.dp, AccentBlue.copy(alpha = 0.35f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(icon, null, tint = AccentBlue, modifier = Modifier.size(17.dp))
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(
                            title,
                            color = Color.White,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.ExtraBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (headerTrailing != null) {
                        Spacer(Modifier.width(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically, content = headerTrailing)
                    }
                }

                HorizontalDivider(color = Color.White.copy(0.08f))

                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    content = content
                )

                HorizontalDivider(color = Color.White.copy(0.08f))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (footer != null) {
                        footer()
                    } else {
                        InfoActionButton("Dismiss", onClick = onDismiss, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

/** Footer button in the same style as the Dismiss button of the Song Details popup. */
@Composable
fun InfoActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    enabled: Boolean = true,
    danger: Boolean = false
) {
    val shape = RoundedCornerShape(14.dp)
    val base = when {
        danger -> Color(0xFFFF5252)
        primary -> AccentBlue
        else -> Color.White
    }
    Box(
        modifier = modifier
            .height(44.dp)
            .clip(shape)
            .background(if (primary || danger) base.copy(alpha = if (enabled) 0.90f else 0.35f) else Color.White.copy(0.10f))
            .border(0.5.dp, if (primary || danger) base.copy(0.6f) else Color.White.copy(0.14f), shape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            color = when {
                primary || danger -> Color.Black
                enabled -> Color.White
                else -> Color.White.copy(0.4f)
            },
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp
        )
    }
}

@Composable
fun InfoSectionLabel(text: String) {
    Text(
        text,
        color = Color.White.copy(0.5f),
        fontSize = 10.sp,
        fontWeight = FontWeight.Black,
        letterSpacing = 1.4.sp,
        modifier = Modifier.padding(start = 4.dp, top = 2.dp)
    )
}

/** Glass block that groups rows, identical to the "FILE METADATA" block. */
@Composable
fun InfoGlassBlock(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.White.copy(0.05f), RoundedCornerShape(16.dp))
            .border(0.5.dp, Color.White.copy(0.08f), RoundedCornerShape(16.dp))
            .padding(horizontal = 12.dp, vertical = 4.dp),
        content = content
    )
}

@Composable
fun InfoMetaRow(
    label: String,
    value: String,
    stacked: Boolean = false,
    showDivider: Boolean = true,
    labelWidth: Int = 72
) {
    Column(Modifier.fillMaxWidth()) {
        if (stacked) {
            Column(Modifier.fillMaxWidth().padding(vertical = 9.dp)) {
                Text(label, color = AccentBlue, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(2.dp))
                Text(value, color = Color.White, fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        } else {
            Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.Top) {
                Text(label, color = AccentBlue, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(labelWidth.dp))
                Text(value, color = Color.White, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
        }
        if (showDivider) HorizontalDivider(color = Color.White.copy(0.06f))
    }
}

/** Text field styled for the glass popups. */
@Composable
fun InfoTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    numeric: Boolean = false
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, fontSize = 12.sp) },
        singleLine = true,
        enabled = enabled,
        shape = RoundedCornerShape(14.dp),
        keyboardOptions = if (numeric) KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
        else KeyboardOptions.Default,
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White,
            disabledTextColor = Color.White.copy(0.5f),
            focusedBorderColor = AccentBlue,
            unfocusedBorderColor = Color.White.copy(0.14f),
            focusedLabelColor = AccentBlue,
            unfocusedLabelColor = Color.White.copy(0.5f),
            cursorColor = AccentBlue,
            focusedContainerColor = Color.White.copy(0.05f),
            unfocusedContainerColor = Color.White.copy(0.05f)
        ),
        modifier = modifier.fillMaxWidth()
    )
}

/** A tappable choice row (icon + title + subtitle) inside a glass popup. */
@Composable
fun InfoChoiceRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(0.05f))
            .border(0.5.dp, Color.White.copy(0.08f), RoundedCornerShape(16.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(34.dp).background(AccentBlue.copy(0.14f), CircleShape),
            contentAlignment = Alignment.Center
        ) { Icon(icon, null, tint = AccentBlue, modifier = Modifier.size(18.dp)) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                color = if (enabled) Color.White else Color.White.copy(0.4f),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
            if (subtitle != null) {
                Text(subtitle, color = Color.White.copy(0.5f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
