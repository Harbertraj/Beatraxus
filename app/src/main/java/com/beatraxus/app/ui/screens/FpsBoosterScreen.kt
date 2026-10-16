package com.beatraxus.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.beatraxus.app.fpsbooster.BoosterEngine
import com.beatraxus.app.fpsbooster.BoosterMode
import com.beatraxus.app.fpsbooster.BoosterQuality
import com.beatraxus.app.fpsbooster.BoosterTargets
import com.beatraxus.app.fpsbooster.FpsBoosterSettings
import com.beatraxus.app.motionboost.DisplayRefreshRate
import com.beatraxus.app.motionboost.MotionBoostTiming
import com.beatraxus.app.viewmodel.VideoPlayerUiState

private val Orange = Color(0xFFFF8F00)

/**
 * FPS Booster settings and live statistics (opened from the video player's tool row).
 *
 * Only implemented options are shown: Off/On/Auto, targets the panel can pace evenly, the Optical flow and
 * Classic engines, three quality presets, adaptive resolution, battery saver. There is no AI engine and no
 * "motion strength" slider because neither exists. The display refresh rate is shown separately from the
 * generated frame rate on purpose: they are different things.
 */
@Composable
fun FpsBoosterSheetContent(
    uiState: VideoPlayerUiState,
    onSettingsChange: (FpsBoosterSettings) -> Unit
) {
    val context = LocalContext.current
    val panelRates = remember { DisplayRefreshRate.supportedRates(context) }
    val peakHz = panelRates.max().toInt()
    val s = uiState.fpsBooster
    val plan = uiState.fpsBoosterPlan
    val stats = uiState.fpsBoosterStats

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = "Generates in-between frames from motion estimation so low-FPS video (e.g. 24 FPS films) looks smoother.",
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 13.sp
        )

        // Status: what is actually happening for this video
        val status = when {
            uiState.fpsBoosterSuspended != null -> uiState.fpsBoosterSuspended
            plan.active -> plan.note ?: "Running: ${plan.engine.label}, ${plan.targetFps} FPS"
            s.isOn -> plan.note
            else -> null
        }
        if (status != null) {
            Text(
                text = status,
                color = if (plan.active && uiState.fpsBoosterSuspended == null) Orange else Color(0xFFFFB74D),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
        }

        SectionLabel("FPS Booster")
        ChipRow {
            BoosterMode.entries.forEach { m ->
                Chip(m.label, s.mode == m) { onSettingsChange(s.copy(mode = m)) }
            }
        }

        SectionLabel("Target FPS")
        ChipRow {
            BoosterTargets.ALL.forEach { fps ->
                val ok = MotionBoostTiming.isRateSupported(panelRates, fps)
                Chip("$fps", s.targetFps == fps && s.mode != BoosterMode.AUTO, enabled = ok && s.mode != BoosterMode.AUTO) {
                    onSettingsChange(s.copy(targetFps = fps))
                }
            }
        }
        Hint(
            if (s.mode == BoosterMode.AUTO) "Auto picks the target for each video."
            else "Your display supports up to $peakHz Hz; unavailable targets cannot be paced evenly on it."
        )

        SectionLabel("Engine")
        ChipRow {
            BoosterEngine.entries.forEach { e ->
                Chip(e.label, s.engine == e) { onSettingsChange(s.copy(engine = e)) }
            }
        }
        Hint(s.engine.description)

        SectionLabel("Quality")
        ChipRow {
            BoosterQuality.entries.forEach { q ->
                Chip(q.label, s.quality == q) { onSettingsChange(s.copy(quality = q)) }
            }
        }
        Hint("Higher quality estimates motion on a larger image: smoother results, more GPU and battery.")

        ToggleRow(
            "Adaptive resolution",
            "Lowers the processing resolution if the device cannot keep up, and plays normally if it still cannot.",
            s.adaptiveResolution
        ) { onSettingsChange(s.copy(adaptiveResolution = it)) }
        ToggleRow(
            "Battery saver",
            "Caps the target at 60 FPS and uses Performance quality.",
            s.batterySaver
        ) { onSettingsChange(s.copy(batterySaver = it)) }
        ToggleRow("Live statistics", null, s.showStats) { onSettingsChange(s.copy(showStats = it)) }

        if (s.showStats) {
            SectionLabel("Live statistics")
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White.copy(alpha = 0.06f))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (!plan.active || !stats.running && stats.sourceFps <= 0f) {
                    StatLine("Status", if (plan.active) "Waiting for playback…" else "Not running")
                } else {
                    StatLine("Engine", stats.engine.label + if (stats.qualityLabel.isNotEmpty()) " · ${stats.qualityLabel}" else "")
                    StatLine("Source", "%.1f FPS".format(stats.sourceFps))
                    StatLine("Target", "${stats.targetFps} FPS")
                    StatLine("Generated", "%.1f frames/s".format(stats.generatedFps))
                    StatLine("Rendered", "%.1f FPS".format(stats.renderedFps))
                    StatLine("Dropped frames", stats.droppedFrames.toString())
                    if (stats.engine == BoosterEngine.OPTICAL_FLOW) {
                        StatLine("Processing latency", "%.1f ms / frame pair".format(stats.estimateLatencyMs))
                        StatLine("Processing size", "${stats.workEdge} px")
                        StatLine("Video delay", "%.0f ms".format(stats.addedDelayMs))
                    }
                }
                StatLine("Display refresh", "%.0f Hz (not a generated rate)".format(DisplayRefreshRate.currentRate(context)))
            }
        }

        Hint(
            "Uses extra GPU and battery. Fast scenes and scene cuts can show small artifacts. " +
                "Not available for HDR videos. Turns itself off for videos already at the chosen rate."
        )
        Spacer(Modifier.height(4.dp))
    }
}

@Composable private fun SectionLabel(text: String) =
    Text(text, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))

@Composable private fun Hint(text: String) =
    Text(text, color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp)

@Composable private fun StatLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp)
        Text(value, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable private fun ChipRow(content: @Composable () -> Unit) =
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) { content() }

@Composable
private fun Chip(label: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Text(
        text = label,
        color = Color.White.copy(alpha = if (enabled) 1f else 0.35f),
        fontSize = 14.sp,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) Orange.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.06f))
            .border(1.dp, if (selected) Orange.copy(alpha = 0.8f) else Color.White.copy(alpha = 0.08f), RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp)
    )
}

@Composable
private fun ToggleRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (checked) Orange.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.06f))
            .border(1.dp, if (checked) Orange.copy(alpha = 0.8f) else Color.White.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
            .clickable { onChange(!checked) }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 15.sp, fontWeight = if (checked) FontWeight.Bold else FontWeight.Medium)
            if (subtitle != null) Text(subtitle, color = Color.White.copy(alpha = 0.55f), fontSize = 12.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedThumbColor = Orange, checkedTrackColor = Orange.copy(0.4f))
        )
    }
}
