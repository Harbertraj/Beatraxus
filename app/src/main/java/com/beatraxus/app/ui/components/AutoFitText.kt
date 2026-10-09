package com.beatraxus.app.ui.components

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * Single-line text that shrinks (down to [minFontSize]) until the whole string fits the width it
 * is given, so long titles are shown in full instead of being cut with "...". Ellipsis is only
 * used as a last resort at the minimum size. Hidden until sized, so there is no visible jump.
 */
@Composable
fun AutoFitText(
    text: String,
    baseFontSize: TextUnit,
    modifier: Modifier = Modifier,
    minFontSize: TextUnit = 11.sp,
    color: Color = Color.Unspecified,
    fontWeight: FontWeight? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    style: TextStyle = LocalTextStyle.current
) {
    var fontSize by remember(text, baseFontSize) { mutableStateOf(baseFontSize) }
    var fitted by remember(text, baseFontSize) { mutableStateOf(false) }
    val atMin = fontSize.value <= minFontSize.value

    Text(
        text = text,
        modifier = modifier.drawWithContent { if (fitted) drawContent() },
        color = color,
        fontSize = fontSize,
        fontWeight = fontWeight,
        letterSpacing = letterSpacing,
        style = style,
        maxLines = 1,
        softWrap = false,
        overflow = if (atMin) TextOverflow.Ellipsis else TextOverflow.Clip,
        onTextLayout = { result ->
            if (result.hasVisualOverflow && !atMin) {
                fontSize = (fontSize.value * 0.92f).coerceAtLeast(minFontSize.value).sp
            } else {
                fitted = true
            }
        }
    )
}
