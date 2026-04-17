package com.beatflowy.app.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.beatflowy.app.ui.theme.*
import com.beatflowy.app.viewmodel.PlayerViewModel
import kotlin.math.abs

private val BAND_LABELS = listOf("31","62","125","250","500","1K","2K","4K","8K","16K")
private val EQ_PRESETS = mapOf(
    "Flat"       to FloatArray(10) { 0f },
    "Bass Boost" to floatArrayOf(8f,7f,5f,3f,1f,0f,0f,0f,0f,0f),
    "Treble"     to floatArrayOf(0f,0f,0f,0f,0f,2f,3f,5f,7f,8f),
    "V-Shape"    to floatArrayOf(6f,5f,2f,-1f,-3f,-3f,-1f,2f,5f,6f),
    "Vocal"      to floatArrayOf(-2f,-1f,0f,2f,5f,6f,5f,3f,1f,0f),
    "Rock"       to floatArrayOf(5f,4f,2f,0f,-1f,0f,1f,3f,4f,5f)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EqualizerScreen(viewModel: PlayerViewModel, onBack: () -> Unit) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val gains    = uiState.eqGains
    var preset by remember { mutableStateOf("Flat") }

    Scaffold(
        containerColor = BgBase,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BgDeep),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Rounded.ArrowBackIosNew, "Back", tint = TextPrimary)
                    }
                },
                title = { Text("Equalizer", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = TextPrimary) },
                actions = {
                    Row(Modifier.padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(if (uiState.equalizerEnabled) "ON" else "OFF",
                            fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
                            color = if (uiState.equalizerEnabled) AccentBlue else TextMuted)
                        Switch(
                            checked = uiState.equalizerEnabled,
                            onCheckedChange = { viewModel.toggleEqualizer() },
                            modifier = Modifier.height(24.dp),
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = AccentBlue, uncheckedTrackColor = ToggleOff)
                        )
                    }
                }
            )
        }
    ) { pv ->
        Column(Modifier.fillMaxSize().padding(pv).verticalScroll(rememberScrollState())) {

            // EQ Curve
            EqCurve(gains = gains, enabled = uiState.equalizerEnabled,
                modifier = Modifier.fillMaxWidth().height(140.dp).padding(horizontal = 16.dp, vertical = 12.dp))

            // Presets
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(EQ_PRESETS.size) { idx ->
                    val name = EQ_PRESETS.keys.toList()[idx]
                    FilterChip(
                        selected = name == preset,
                        onClick = {
                            preset = name
                            EQ_PRESETS[name]?.forEachIndexed { b, g -> viewModel.setEqBandGain(b, g) }
                        },
                        label = { Text(name, fontSize = 12.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = AccentBlue.copy(alpha = 0.25f),
                            selectedLabelColor = AccentBlueSoft,
                            containerColor = BgElevated, labelColor = TextSecondary
                        )
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = Divider, thickness = 0.5.dp)
            Spacer(Modifier.height(16.dp))

            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween) {
                Text("+12dB", fontSize = 9.sp, color = EqPositive, fontWeight = FontWeight.Bold)
                Text("0dB",   fontSize = 9.sp, color = TextMuted)
                Text("-12dB", fontSize = 9.sp, color = EqNegative, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(8.dp))

            // Sliders
            Row(Modifier.fillMaxWidth().height(260.dp).padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly) {
                gains.forEachIndexed { band, gain ->
                    BandSlider(
                        label = BAND_LABELS[band], gainDb = gain,
                        enabled = uiState.equalizerEnabled,
                        onGainChange = { g ->
                            preset = "Custom"
                            viewModel.setEqBandGain(band, g)
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            OutlinedButton(
                onClick = {
                    preset = "Flat"
                    repeat(10) { viewModel.setEqBandGain(it, 0f) }
                },
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 24.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary)
            ) {
                Icon(Icons.Rounded.Refresh, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Reset to Flat", fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun EqCurve(gains: FloatArray, enabled: Boolean, modifier: Modifier) {
    val animGains = gains.map { g ->
        animateFloatAsState(
            targetValue   = if (enabled) g else 0f,
            animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
            label         = "g"
        ).value
    }
    Canvas(modifier) {
        val w = size.width; val h = size.height; val mid = h / 2f
        val toY = { g: Float -> mid - (g / 12f) * (h * 0.44f) }
        drawLine(EqNeutral.copy(0.4f), Offset(0f,mid), Offset(w,mid),
            1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f,8f)))
        val pts = animGains.mapIndexed { i, g -> Offset(i * w / (animGains.size - 1).toFloat(), toY(g)) }
        val path = Path().apply {
            moveTo(pts[0].x, pts[0].y)
            for (i in 0 until pts.size - 1) {
                val cx = (pts[i].x + pts[i+1].x) / 2f
                cubicTo(cx, pts[i].y, cx, pts[i+1].y, pts[i+1].x, pts[i+1].y)
            }
        }
        val fill = Path().apply { addPath(path); lineTo(w,mid); lineTo(0f,mid); close() }
        drawPath(fill, Brush.verticalGradient(
            listOf(AccentBlue.copy(if (enabled) 0.25f else 0.08f), Color.Transparent)))
        drawPath(path,
            if (enabled) Brush.linearGradient(listOf(AccentBlue, GradientMid, AccentRed),
                Offset.Zero, Offset(w,0f))
            else SolidColor(EqNeutral.copy(0.5f)),
            style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
        pts.forEach { drawCircle(if (enabled) AccentBlue else EqNeutral, 3.dp.toPx(), it) }
    }
}

@Composable
private fun BandSlider(label: String, gainDb: Float, enabled: Boolean,
                       onGainChange: (Float) -> Unit, modifier: Modifier) {
    val sliderVal = (gainDb + 12f) / 24f
    val gainColor = when {
        !enabled      -> TextMuted
        gainDb > 0.5f -> lerpColor(EqNeutral, EqPositive, gainDb / 12f)
        gainDb < -0.5f-> lerpColor(EqNeutral, EqNegative, -gainDb / 12f)
        else          -> EqNeutral
    }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(gainLabel(gainDb), fontSize = 9.sp, fontWeight = FontWeight.Bold,
            color = gainColor, textAlign = TextAlign.Center, modifier = Modifier.height(16.dp))
        Spacer(Modifier.height(4.dp))
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Slider(
                value = sliderVal,
                onValueChange = { v ->
                    val g = (v * 24f - 12f).coerceIn(-12f,12f).let { if (abs(it) < 0.3f) 0f else it }
                    onGainChange(g)
                },
                enabled  = enabled,
                modifier = Modifier.fillMaxWidth(0.85f).graphicsLayer { rotationZ = -90f },
                colors   = SliderDefaults.colors(
                    thumbColor = gainColor, activeTrackColor = gainColor.copy(0.8f),
                    inactiveTrackColor = EqTrack)
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(label, fontSize = 9.sp, fontWeight = FontWeight.Medium,
            color = TextSecondary, textAlign = TextAlign.Center)
        Text(if (label.endsWith("K")) "kHz" else "Hz",
            fontSize = 7.sp, color = TextMuted, textAlign = TextAlign.Center)
    }
}

private fun gainLabel(g: Float) = if (abs(g) < 0.1f) "0" else "${if (g>0) "+" else ""}${"%.1f".format(g)}"

private fun lerpColor(a: Color, b: Color, t: Float): Color {
    val tc = t.coerceIn(0f,1f)
    return Color(a.red+(b.red-a.red)*tc, a.green+(b.green-a.green)*tc,
        a.blue+(b.blue-a.blue)*tc, a.alpha+(b.alpha-a.alpha)*tc)
}
