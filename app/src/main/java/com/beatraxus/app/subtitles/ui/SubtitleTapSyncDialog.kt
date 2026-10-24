package com.beatraxus.app.subtitles.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.beatraxus.app.subtitles.domain.SubtitleCue
import com.beatraxus.app.subtitles.domain.SubtitleTapSync
import com.beatraxus.app.subtitles.viewmodel.SubtitleViewModel
import com.beatraxus.app.subtitles.viewmodel.SyncTarget
import java.util.Locale

/**
 * Video feature 20: syncs a subtitle by tapping the line the viewer is hearing right now.
 *
 * The list shows the subtitle's lines with the times from the file and opens near the current playback position.
 * Tapping a line sets the subtitle's delay so that this line appears at the position the player is at when the tap
 * lands, then closes the dialog. The fine-tune buttons stay available for the last tenth of a second.
 *
 * @param positionProvider the player's current position in milliseconds
 */
@Composable
fun SubtitleTapSyncDialog(
    target: SyncTarget,
    subtitleViewModel: SubtitleViewModel,
    positionProvider: () -> Long,
    onDismiss: () -> Unit
) {
    val mxOrange = Color(0xFFFF8F00)
    var cues by remember(target) { mutableStateOf<List<SubtitleCue>?>(null) }
    LaunchedEffect(target) { cues = subtitleViewModel.loadCuesForSync(target) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1E1E1E),
        shape = RoundedCornerShape(16.dp),
        title = {
            Text(
                if (target == SyncTarget.MAIN) "Sync main subtitle" else "Sync second subtitle",
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(modifier = Modifier.height(380.dp)) {
                Text(
                    "Tap the line you are hearing right now.",
                    color = Color.White.copy(0.6f),
                    fontSize = 13.sp
                )
                Spacer(Modifier.height(10.dp))

                val loaded = cues
                when {
                    loaded == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = mxOrange)
                    }

                    loaded.isEmpty() -> Text(
                        "There are no subtitle lines to show. Load a subtitle from a file first.",
                        color = Color.White.copy(0.5f),
                        fontSize = 13.sp
                    )

                    else -> CueList(
                        cues = loaded,
                        startIndex = SubtitleTapSync.indexNear(loaded, positionProvider()),
                        timeColor = mxOrange,
                        onTap = { cue ->
                            subtitleViewModel.syncToCue(target, cue, positionProvider())
                            onDismiss()
                        }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = mxOrange, fontWeight = FontWeight.Bold)
            }
        }
    )
}

@Composable
private fun CueList(
    cues: List<SubtitleCue>,
    startIndex: Int,
    timeColor: Color,
    onTap: (SubtitleCue) -> Unit
) {
    // Start one line above the likely one so it has a neighbour on each side.
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (startIndex - 1).coerceAtLeast(0))
    LazyColumn(state = listState, modifier = Modifier.fillMaxWidth()) {
        itemsIndexed(cues) { _, cue ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onTap(cue) }
                    .padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Top
            ) {
                Text(
                    formatCueTime(cue.startMs),
                    color = timeColor,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.width(56.dp)
                )
                Text(
                    cue.text.replace('\n', ' '),
                    color = Color.White,
                    fontSize = 14.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

/** "1:05" for a cue at 65 s, "1:02:03" past the first hour. */
private fun formatCueTime(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0L)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.ROOT, "%d:%02d", minutes, seconds)
    }
}
