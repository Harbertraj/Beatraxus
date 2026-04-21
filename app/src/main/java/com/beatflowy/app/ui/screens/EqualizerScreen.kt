package com.beatflowy.app.ui.screens

import androidx.compose.animation.animateColorAsState
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
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
        topBar = {
            TopAppBar(
                title = { Text("Equalizer", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Rounded.ArrowBack, null)
                    }
                },
                actions = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (uiState.equalizerEnabled) "Enabled" else "Disabled", 
                            fontSize = 12.sp, color = if (uiState.equalizerEnabled) AccentBlue else TextMuted)
                        Spacer(Modifier.width(8.dp))
                        Switch(
                            checked = uiState.equalizerEnabled,
                            onCheckedChange = { viewModel.toggleEqualizer() },
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = AccentBlue, uncheckedTrackColor = Color.White.copy(0.1f))
                        )
                    }
                }
            )
        }
    ) { pv ->
        Box(Modifier.fillMaxSize()) {
            if (uiState.currentSong != null) {
                AsyncImage(
                    model = uiState.currentSong?.albumArtUri,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxSize()
                        .blur(70.dp)
                        .graphicsLayer(alpha = 0.55f),
                    contentScale = ContentScale.Crop,
                )
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    Color.Black.copy(0.3f),
                                    Color.Transparent,
                                    Color.Black.copy(0.7f)
                                )
                            )
                        )
                )
            }

            Column(
                Modifier
                    .padding(pv)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Visualizer/Curve Area
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White.copy(0.05f)),
                    border = CardDefaults.outlinedCardBorder().copy(brush = SolidColor(Color.White.copy(0.1f)))
                ) {
                    EqCurve(gains, uiState.equalizerEnabled, Modifier.fillMaxSize().padding(16.dp))
                }

                Spacer(Modifier.height(24.dp))

                // Presets Row
                Text("Presets", modifier = Modifier.fillMaxWidth(), 
                    fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextSecondary)
                Spacer(Modifier.height(12.dp))
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 16.dp)
                ) {
                    EQ_PRESETS.forEach { (name, pGains) ->
                        item {
                            FilterChip(
                                selected = preset == name,
                                onClick = {
                                    preset = name
                                    pGains.forEachIndexed { i, g -> viewModel.setEqBandGain(i, g) }
                                },
                                label = { Text(name) },
                                shape = RoundedCornerShape(12.dp),
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = AccentBlue.copy(0.2f),
                                    selectedLabelColor = AccentBlue
                                )
                            )
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                // Band Sliders
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White.copy(0.05f))
                ) {
                    Row(
                        Modifier
                            .padding(vertical = 20.dp, horizontal = 10.dp)
                            .height(240.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        BAND_LABELS.forEachIndexed { i, label ->
                            BandSlider(
                                label = label,
                                gainDb = gains[i],
                                enabled = uiState.equalizerEnabled,
                                onGainChange = { viewModel.setEqBandGain(i, it) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                Spacer(Modifier.height(24.dp))

                Button(
                    onClick = {
                        preset = "Flat"
                        FloatArray(10) { 0f }.forEachIndexed { i, g -> viewModel.setEqBandGain(i, g) }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(0.1f)),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(Icons.Rounded.Refresh, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Reset to Flat", fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
fun EqCurve(gains: FloatArray, enabled: Boolean, modifier: Modifier) {
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
fun BandSlider(label: String, gainDb: Float, enabled: Boolean,
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

fun gainLabel(g: Float) = if (abs(g) < 0.1f) "0" else "${if (g>0) "+" else ""}${"%.1f".format(g)}"

fun lerpColor(a: Color, b: Color, t: Float): Color {
    val tc = t.coerceIn(0f,1f)
    return Color(a.red+(b.red-a.red)*tc, a.green+(b.green-a.green)*tc,
        a.blue+(b.blue-a.blue)*tc, a.alpha+(b.alpha-a.alpha)*tc)
}
