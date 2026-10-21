package com.beatraxus.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Inline "unbypass" switch for one DSP screen. It is only shown while Bit-Perfect mode is on (that is the only
 * time a screen is bypassed); turning it on lets this one feature run in Bit-Perfect mode. It drives the same
 * setting as the chip in Settings > Bit-Perfect Mode > Selective DSP Unbypass.
 */
@Composable
fun BitPerfectUnbypassRow(
    label: String,
    bitPerfectEnabled: Boolean,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    accentColor: Color = Color(0xFF00C2A8)
) {
    if (!bitPerfectEnabled) return
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White.copy(0.04f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "BIT-PERFECT · UNBYPASS",
                color = accentColor.copy(0.8f),
                fontSize = 9.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 1.sp
            )
            Text(
                if (checked) "$label is active in Bit-Perfect mode" else "$label is bypassed — switch on to use it",
                color = Color.White.copy(0.5f),
                fontSize = 11.sp,
                lineHeight = 14.sp
            )
        }
        PremiumSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            accentColor = accentColor
        )
    }
}
