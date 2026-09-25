package com.beatraxus.app.subtitles.ui

import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.TextUnit

@Composable
fun MxFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = TextUnit.Unspecified
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        modifier = modifier,
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = Color(0xFFFF8F00),
            selectedLabelColor = Color.Black
        ),
        label = {
            Text(
                text = label,
                fontSize = fontSize,
                color = if (selected) Color.Black else Color.Unspecified
            )
        }
    )
}
