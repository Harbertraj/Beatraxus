package com.beatraxus.app.subtitles.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.beatraxus.app.subtitles.data.CachedSubtitle
import com.beatraxus.app.subtitles.data.SubtitleRepositoryImpl
import com.beatraxus.app.subtitles.domain.SubtitleSourcePrefs
import com.beatraxus.app.subtitles.viewmodel.SubtitleUiState
import com.beatraxus.app.subtitles.viewmodel.SubtitleViewModel

/** Which "next option sheet" the Subtitles sheet is showing in place of its own list. */
enum class SubtitleSubView { NONE, GENERATE, TRANSLATE }

/**
 * The "Auto-generate subtitles" and "Translate subtitles" views. Same look as the "Extract Audio Track" view of the
 * Audio Tracks sheet: a title with a close button, rounded option cards (orange when chosen) and Cancel / action pills.
 * They are shown INSIDE the Subtitles sheet (not as popups), so every option stays reachable on any screen size.
 */
@Composable
fun AutoSubtitleSheetBody(
    subUiState: SubtitleUiState,
    subtitleViewModel: SubtitleViewModel,
    accent: Color,
    onClose: () -> Unit
) {
    val configured = subtitleViewModel.isSpeechConfigured()
    Column(modifier = Modifier.fillMaxWidth()) {
        SubSheetHeader("Auto-generate Subtitles", "Back to subtitles", onClose)
        Text(
            "Made from the speech in the audio track you are listening to. Nothing shows while it works; " +
                "when it is done it appears under Embedded Tracks to select.",
            color = Color.White.copy(0.5f), fontSize = 12.sp
        )
        if (!configured) {
            Spacer(Modifier.height(6.dp))
            Text("Auto subtitles are not configured in this build (SPEECH_API_KEY).", color = Color(0xFFFFB74D), fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(14.dp))
        Text("Subtitle language", color = Color.White.copy(0.7f), fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ChoiceCard(
                selected = subUiState.generateTranslateTo == null, accent = accent, icon = Icons.Rounded.Subtitles,
                title = "Spoken language", line = "Keep the language that is spoken",
                onClick = { subtitleViewModel.setGenerateTranslateTo(null) }
            )
            SubtitleRepositoryImpl.BUILT_IN_LANGUAGES.forEach { lang ->
                ChoiceCard(
                    selected = subUiState.generateTranslateTo == lang.code, accent = accent, icon = Icons.Rounded.Translate,
                    title = lang.name, line = "Translate the speech into ${lang.name}",
                    onClick = { subtitleViewModel.setGenerateTranslateTo(lang.code) }
                )
            }
        }
        subUiState.generationStatus?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, color = Color.White.copy(0.6f), fontSize = 12.sp)
        }
        Spacer(Modifier.height(16.dp))
        SubSheetPills(
            actionLabel = if (subUiState.isGenerating) "Generating…" else "Generate",
            actionEnabled = configured && !subUiState.isGenerating,
            accent = accent,
            onCancel = onClose,
            onAction = {
                subtitleViewModel.generateSubtitles()
                onClose() // back to the Subtitles list, where the progress shows
            }
        )
    }
}

@Composable
fun TranslateSubtitleSheetBody(
    subUiState: SubtitleUiState,
    subtitleViewModel: SubtitleViewModel,
    accent: Color,
    onClose: () -> Unit
) {
    val configured = subtitleViewModel.isTranslationConfigured()
    val sources = subUiState.cachedSubtitles
    var sourceId by remember(sources.size) {
        mutableStateOf((sources.firstOrNull { SubtitleSourcePrefs.isGenerated(it.subtitleId) } ?: sources.firstOrNull())?.subtitleId)
    }
    var targetCode by remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxWidth()) {
        SubSheetHeader("Translate Subtitles", "Back to subtitles", onClose)
        Text(
            "Translates a saved subtitle of this video. Every line keeps its timing. The result appears under Embedded Tracks to select.",
            color = Color.White.copy(0.5f), fontSize = 12.sp
        )
        if (!configured) {
            Spacer(Modifier.height(6.dp))
            Text("Translation is not configured in this build (LLM_API_KEY).", color = Color(0xFFFFB74D), fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(14.dp))

        Text("Translate from", color = Color.White.copy(0.7f), fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(8.dp))
        if (sources.isEmpty()) {
            Text(
                "No downloaded, imported or generated subtitle for this video yet. Subtitles stored inside the video file cannot be translated.",
                color = Color.White.copy(0.5f), fontSize = 12.sp
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                sources.forEach { s ->
                    ChoiceCard(
                        selected = sourceId == s.subtitleId, accent = accent, icon = Icons.Rounded.Subtitles,
                        title = s.releaseName ?: "Subtitle", line = sourceLine(s),
                        onClick = { sourceId = s.subtitleId }
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Text("Translate to", color = Color.White.copy(0.7f), fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SubtitleRepositoryImpl.BUILT_IN_LANGUAGES.forEach { lang ->
                ChoiceCard(
                    selected = targetCode == lang.code, accent = accent, icon = Icons.Rounded.Translate,
                    title = lang.name, line = null,
                    onClick = { targetCode = lang.code }
                )
            }
        }

        subUiState.translationStatus?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, color = Color.White.copy(0.6f), fontSize = 12.sp)
        }
        Spacer(Modifier.height(16.dp))
        val canGo = configured && !subUiState.isTranslating && sourceId != null && targetCode != null
        SubSheetPills(
            actionLabel = if (subUiState.isTranslating) "Translating…" else "Translate",
            actionEnabled = canGo,
            accent = accent,
            onCancel = onClose,
            onAction = {
                val src = sourceId
                val tgt = targetCode
                if (src != null && tgt != null) {
                    subtitleViewModel.translateSubtitle(src, tgt)
                    onClose() // back to the Subtitles list, where the progress shows
                }
            }
        )
    }
}

private fun sourceLine(s: CachedSubtitle): String {
    val kind = when {
        SubtitleSourcePrefs.isGenerated(s.subtitleId) -> "Auto-generated"
        SubtitleSourcePrefs.isTranslated(s.subtitleId) -> "Translated"
        else -> "Downloaded / imported"
    }
    return "$kind · ${s.language.uppercase()}"
}

@Composable
private fun SubSheetHeader(title: String, closeDescription: String, onClose: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
        )
        IconButton(onClick = onClose) {
            Icon(Icons.Rounded.Close, closeDescription, tint = Color.White.copy(0.8f))
        }
    }
}

@Composable
private fun ChoiceCard(
    selected: Boolean,
    accent: Color,
    icon: ImageVector,
    title: String,
    line: String?,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) accent.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.06f))
            .border(
                width = 1.dp,
                color = if (selected) accent.copy(alpha = 0.8f) else Color.White.copy(alpha = 0.08f),
                shape = RoundedCornerShape(16.dp)
            )
            .clickable(onClick = onClick)
            .heightIn(min = 64.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = if (selected) accent else Color.White.copy(0.7f), modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title, color = Color.White, fontSize = 15.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            if (line != null) {
                Spacer(Modifier.height(2.dp))
                Text(line, color = Color.White.copy(0.6f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun SubSheetPills(
    actionLabel: String,
    actionEnabled: Boolean,
    accent: Color,
    onCancel: () -> Unit,
    onAction: () -> Unit
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            modifier = Modifier
                .weight(1f).height(44.dp)
                .clip(RoundedCornerShape(50))
                .background(Color.White.copy(0.08f))
                .border(1.dp, Color.White.copy(0.14f), RoundedCornerShape(50))
                .clickable(onClick = onCancel),
            contentAlignment = Alignment.Center
        ) { Text("Cancel", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
        Box(
            modifier = Modifier
                .weight(1f).height(44.dp)
                .clip(RoundedCornerShape(50))
                .background(accent.copy(alpha = if (actionEnabled) 0.22f else 0.08f))
                .border(1.dp, accent.copy(alpha = if (actionEnabled) 0.7f else 0.2f), RoundedCornerShape(50))
                .clickable(enabled = actionEnabled, onClick = onAction),
            contentAlignment = Alignment.Center
        ) {
            Text(
                actionLabel,
                color = Color.White.copy(alpha = if (actionEnabled) 1f else 0.35f),
                fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1
            )
        }
    }
}
