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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.beatraxus.app.fpsbooster.BenchmarkState
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
    onSettingsChange: (FpsBoosterSettings) -> Unit,
    onRunBenchmark: () -> Unit = {},
    onSkipBenchmark: () -> Unit = {}
) {
    val context = LocalContext.current
    val panelRates = remember { DisplayRefreshRate.supportedRates(context) }
    val peakHz = panelRates.max().toInt()
    val s = uiState.fpsBooster
    val plan = uiState.fpsBoosterPlan
    val stats = uiState.fpsBoosterStats
    val bench = uiState.fpsBoosterBenchmark
    // The Quality presets only exist for Optical flow; Classic has a single fixed pipeline.
    val classicEngine = s.engine == BoosterEngine.CLASSIC || (plan.active && plan.engine == BoosterEngine.CLASSIC)

    // First time the booster sheet is opened: measure the device once and pick settings it can sustain.
    LaunchedEffect(Unit) {
        if (bench is BenchmarkState.Idle && !uiState.fpsBoosterBenchmarkSkipped) onRunBenchmark()
    }

    // First use: the device test is the only thing shown. The moment it finishes the state becomes Done and the
    // same sheet switches to the booster options (no need to close and reopen it).
    val firstRunTest = bench is BenchmarkState.Running ||
        (bench is BenchmarkState.Idle && !uiState.fpsBoosterBenchmarkSkipped)
    if (firstRunTest) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = "Setting up FPS Booster for your device. This only happens once.",
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 13.sp
            )
            DeviceTestCard(bench, onRunBenchmark, onSkipBenchmark)
            Spacer(Modifier.height(4.dp))
        }
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = "Generates in-between frames from motion estimation so low-FPS video (e.g. 24 FPS films) looks smoother.",
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 13.sp
        )

        // Only a failed test is shown next to the options (so it can be retried); a finished or skipped test is hidden.
        if (bench is BenchmarkState.Failed) DeviceTestCard(bench, onRunBenchmark, onSkipBenchmark)

        // The booster can only deliver its target if the panel is really running that fast right now.
        val nowHz = DisplayRefreshRate.currentRate(context)
        if (plan.active && nowHz + 1f < plan.targetFps) {
            Text(
                text = "Display is running at %.0f Hz right now, below the %d FPS target (it can reach %d Hz). ".format(nowHz, plan.targetFps, peakHz) +
                    "Set the phone's screen refresh rate to High / 120 Hz and turn off battery saver; the system decides, so this is not guaranteed.",
                color = Color(0xFFFFB74D),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
        }

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
                Chip(q.label, s.quality == q && !classicEngine, enabled = !classicEngine) { onSettingsChange(s.copy(quality = q)) }
            }
        }
        Hint(
            if (classicEngine) "Quality presets apply to the Optical flow engine only. The Classic engine has no quality levels."
            else "Higher quality analyses motion on a larger image: fewer artifacts, more GPU and battery. The picture itself always keeps the original video resolution."
        )

        ToggleRow(
            "Adaptive resolution",
            "Off: always uses the quality you chose. On: may lower the motion-analysis size when the device is struggling. The booster stays on either way.",
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
                        StatLine("Motion analysis size", "${stats.workEdge} px")
                        StatLine("Video delay", "%.0f ms".format(stats.addedDelayMs))
                    }
                }
                StatLine("Display refresh", "%.0f Hz (not a generated rate)".format(DisplayRefreshRate.currentRate(context)))
            }
        }

        Hint(
            "Uses extra GPU and battery. Fast scenes and scene cuts can show small artifacts. " +
                "Not available for HDR videos. Does nothing for videos already at the chosen rate."
        )
        Spacer(Modifier.height(4.dp))
    }
}

/** First-run / re-runnable CPU + GPU test with its result and the settings it chose. */
@Composable
private fun DeviceTestCard(state: BenchmarkState, onRun: () -> Unit, onSkip: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.06f))
            .border(1.dp, Orange.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        when (state) {
            is BenchmarkState.Running -> {
                Text("Testing your device…", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                LinearProgressIndicator(
                    progress = { state.progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                    color = Orange,
                    trackColor = Color.White.copy(alpha = 0.1f)
                )
                Text("${state.stage}. Playback pauses for a few seconds.", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
                Chip("Skip", false) { onSkip() }
            }
            is BenchmarkState.Done -> {
                val r = state.result
                Text("Device test", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                StatLine("CPU score", "${r.cpuScore}  ·  ${r.cores} cores")
                StatLine("GPU score", if (r.gpuScore > 0) r.gpuScore.toString() else "Optical flow not supported")
                StatLine("Device class", state.recommendation.tier.label)
                Text(state.recommendation.summary, color = Orange, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Hint("These options were applied. You can still change them below.")
                Chip("Test again", false) { onRun() }
            }
            is BenchmarkState.Failed -> {
                Text(state.message, color = Color(0xFFFFB74D), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Chip("Try again", false) { onRun() }
            }
            BenchmarkState.Idle -> {
                Text("Device test", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Hint("Measures your CPU and GPU once and picks the engine, quality and FPS target your phone can keep up with.")
                Chip("Run device test", false) { onRun() }
            }
        }
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
