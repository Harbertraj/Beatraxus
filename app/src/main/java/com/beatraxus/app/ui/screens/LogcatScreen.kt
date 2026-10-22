package com.beatraxus.app.ui.screens

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import com.beatraxus.app.ui.theme.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.util.Calendar

/**
 * Live stream of this app's own logcat output.
 *
 * `logcat -v threadtime` (without -d) first prints everything currently in the buffer and then keeps
 * running, so the viewer behaves like a real logcat window. A background thread reads the process
 * output into a pending list and the UI drains it a few times per second, so a chatty app can't
 * flood Compose with one recomposition per line.
 *
 * Without the READ_LOGS permission (only grantable over adb) Android only lets an app see its own
 * process' log lines, which is exactly what an in-app viewer needs.
 */
class LogcatStream {
    private val pending = ArrayList<String>()
    @Volatile private var process: Process? = null
    @Volatile private var running = false
    @Volatile private var failure: String? = null

    val error: String? get() = failure

    fun start() {
        if (running) return
        running = true
        failure = null
        Thread({
            try {
                val p = ProcessBuilder("logcat", "-v", "threadtime")
                    .redirectErrorStream(true)
                    .start()
                process = p
                p.inputStream.bufferedReader().use { reader ->
                    while (running) {
                        val line = reader.readLine() ?: break
                        synchronized(pending) {
                            pending.add(line)
                            // Paused for a long time: never let the backlog grow without bound.
                            if (pending.size > LogcatReader.MAX_BUFFER * 2) {
                                pending.subList(0, LogcatReader.MAX_BUFFER).clear()
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                if (running) failure = e.message ?: "Could not read logcat"
            } finally {
                running = false
            }
        }, "beatraxus-logcat").apply { isDaemon = true }.start()
    }

    /** Returns (and removes) the lines read since the last call. */
    fun drain(): List<String> = synchronized(pending) {
        if (pending.isEmpty()) emptyList() else ArrayList(pending).also { pending.clear() }
    }

    fun stop() {
        running = false
        process?.destroy()
        process = null
    }
}

object LogcatReader {
    /** Most log lines kept in memory for the viewer. */
    const val MAX_BUFFER = 20_000

    private val LINE_REGEX = Regex(
        """^(\d{2})-(\d{2})\s+(\d{2}):(\d{2}):(\d{2})\.\d{3}\s+\d+\s+\d+\s+([VDIWEFA])\s"""
    )

    fun levelOf(line: String): Char? = LINE_REGEX.find(line)?.groupValues?.get(6)?.firstOrNull()

    /**
     * Filters [lines].
     *
     * The time filter is only applied when [startMinute] and/or [endMinute] is set (minutes since
     * midnight, end minute inclusive up to :59). With only a start the window runs to the end of the
     * day, with only an end it runs from midnight. If start > end the window wraps over midnight
     * (e.g. 23:50 to 00:10). With no time set, every line passes, so the viewer shows the full live log.
     *
     * [levels] is the set of V, D, I, W, E chips that are switched on (fatal/assert lines count as E).
     */
    fun filter(
        lines: List<String>,
        startMinute: Int?,
        endMinute: Int?,
        levels: Set<Char>,
        query: String
    ): List<String> {
        val timeFilter = startMinute != null || endMinute != null
        val allLevels = levels.size >= ALL_LEVELS.size
        val needle = query.trim()
        if (!timeFilter && allLevels && needle.isEmpty()) return lines

        val startSec = (startMinute ?: 0) * 60
        val endSec = (endMinute ?: (24 * 60 - 1)) * 60 + 59
        return lines.filter { line ->
            if (needle.isNotEmpty() && !line.contains(needle, ignoreCase = true)) return@filter false
            if (!timeFilter && allLevels) return@filter true

            // Buffer headers like "--------- beginning of main" have no time or level.
            val m = LINE_REGEX.find(line) ?: return@filter false
            if (timeFilter) {
                val g = m.groupValues
                val sec = g[3].toInt() * 3600 + g[4].toInt() * 60 + g[5].toInt()
                val inWindow = if (startSec <= endSec) sec in startSec..endSec else (sec >= startSec || sec <= endSec)
                if (!inWindow) return@filter false
            }
            if (!allLevels) {
                val level = m.groupValues[6][0]
                val chip = if (level == 'F' || level == 'A') 'E' else level
                if (chip !in levels) return@filter false
            }
            true
        }
    }

    val ALL_LEVELS: Set<Char> = setOf('V', 'D', 'I', 'W', 'E')
}

private val LogBg = Color(0xFF0B0B10)
private val LogAccent = Color(0xFF00C2A8)
private val LogText = Color(0xFFF4F6F8)

private fun levelColor(level: Char?): Color = when (level) {
    'E', 'F', 'A' -> Color(0xFFFF6B6B)
    'W' -> Color(0xFFFFC857)
    'I' -> Color(0xFF7BE0AD)
    'D' -> Color(0xFF7FB7FF)
    else -> Color(0xFFB0B4BC)
}

private fun fmt(minuteOfDay: Int) = "%02d:%02d".format(minuteOfDay / 60, minuteOfDay % 60)

/**
 * In-app logcat viewer. It runs live with no time filter; only when you set a From and/or To time
 * does it show just that time window. Level toggles (V, D, I, W, E) and a text search work on top,
 * and the filtered result can be copied or shared.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogcatContent() {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    val stream = remember { LogcatStream() }
    var lines by remember { mutableStateOf<List<String>>(emptyList()) }
    var paused by remember { mutableStateOf(false) }
    var autoScroll by remember { mutableStateOf(true) }
    var streamError by remember { mutableStateOf<String?>(null) }

    // null = no time filter (show everything, live)
    var startMinute by remember { mutableStateOf<Int?>(null) }
    var endMinute by remember { mutableStateOf<Int?>(null) }
    var levels by remember { mutableStateOf(LogcatReader.ALL_LEVELS) }
    var query by remember { mutableStateOf("") }
    var pickerFor by remember { mutableStateOf<String?>(null) } // "start" | "end" | null

    DisposableEffect(Unit) {
        stream.start()
        onDispose { stream.stop() }
    }

    // Pull new log lines in a few times per second while not paused.
    LaunchedEffect(paused) {
        while (true) {
            if (!paused) {
                val fresh = stream.drain()
                if (fresh.isNotEmpty()) {
                    val merged = lines + fresh
                    lines = if (merged.size > LogcatReader.MAX_BUFFER) merged.takeLast(LogcatReader.MAX_BUFFER) else merged
                }
                streamError = stream.error
            }
            delay(250)
        }
    }

    val filtered = remember(lines, startMinute, endMinute, levels, query) {
        LogcatReader.filter(lines, startMinute, endMinute, levels, query)
    }
    val timeFilterOn = startMinute != null || endMinute != null

    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        Text(
            if (timeFilterOn) "Showing only the selected time (24h)" else "Live – all logs (set a time to filter)",
            color = LogText.copy(alpha = 0.7f),
            style = TextStyle(fontSize = 12.sp)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { pickerFor = "start" },
                border = BorderStroke(1.dp, LogAccent.copy(alpha = 0.6f)),
                modifier = Modifier.weight(1f)
            ) { Text("From  ${startMinute?.let(::fmt) ?: "--:--"}", color = LogText) }
            OutlinedButton(
                onClick = { pickerFor = "end" },
                border = BorderStroke(1.dp, LogAccent.copy(alpha = 0.6f)),
                modifier = Modifier.weight(1f)
            ) { Text("To  ${endMinute?.let(::fmt) ?: "--:--"}", color = LogText) }
            if (timeFilterOn) {
                TextButton(onClick = { startMinute = null; endMinute = null }) { Text("Clear", color = LogAccent) }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            listOf('V', 'D', 'I', 'W', 'E').forEach { lvl ->
                FilterChip(
                    selected = lvl in levels,
                    onClick = { levels = if (lvl in levels) levels - lvl else levels + lvl },
                    label = { Text(lvl.toString()) }
                )
            }
            FilterChip(
                selected = autoScroll,
                onClick = { autoScroll = !autoScroll },
                label = { Text("Auto-scroll") }
            )
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            label = { Text("Search text / tag") },
            modifier = Modifier.fillMaxWidth()
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = { paused = !paused },
                colors = ButtonDefaults.buttonColors(containerColor = LogAccent, contentColor = Color.Black)
            ) {
                Icon(
                    if (paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Text(if (paused) "  Resume" else "  Pause")
            }
            OutlinedButton(onClick = { lines = emptyList(); stream.drain() }) {
                Icon(Icons.Rounded.DeleteSweep, contentDescription = "Clear logs", modifier = Modifier.size(18.dp))
            }
            OutlinedButton(
                onClick = {
                    clipboard.setText(AnnotatedString(filtered.joinToString("\n")))
                    Toast.makeText(context, "Copied ${filtered.size} lines", Toast.LENGTH_SHORT).show()
                },
                enabled = filtered.isNotEmpty()
            ) {
                Icon(Icons.Rounded.ContentCopy, contentDescription = "Copy", modifier = Modifier.size(18.dp))
            }
            OutlinedButton(
                onClick = {
                    // Intent extras are size-limited, so share only the newest part of a very large result.
                    var text = filtered.joinToString("\n")
                    if (text.length > MAX_SHARE_CHARS) text = text.takeLast(MAX_SHARE_CHARS)
                    val range = if (timeFilterOn) " ${startMinute?.let(::fmt) ?: ""}-${endMinute?.let(::fmt) ?: ""}" else ""
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "Beatraxus logcat$range")
                        putExtra(Intent.EXTRA_TEXT, text)
                    }
                    context.startActivity(Intent.createChooser(send, "Share logcat"))
                },
                enabled = filtered.isNotEmpty()
            ) {
                Icon(Icons.Rounded.Share, contentDescription = "Share", modifier = Modifier.size(18.dp))
            }
        }

        val summary = streamError?.let { "Error: $it" }
            ?: buildString {
                append("${filtered.size} lines")
                if (timeFilterOn) append(" in ${startMinute?.let(::fmt) ?: "00:00"}–${endMinute?.let(::fmt) ?: "23:59"}")
                append(if (paused) " · paused" else " · live")
            }
        Text(summary, color = LogText.copy(alpha = 0.7f), style = TextStyle(fontSize = 12.sp))

        val listState = rememberLazyListState()
        LaunchedEffect(filtered.size, autoScroll, paused) {
            if (autoScroll && !paused && filtered.isNotEmpty()) listState.scrollToItem(filtered.size - 1)
        }
        // Fixed height: this content sits inside a vertically scrolling Settings column.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(440.dp)
                .background(LogBg, RoundedCornerShape(14.dp))
                .padding(8.dp)
        ) {
            LazyColumn(state = listState) {
                items(filtered) { line ->
                    Text(
                        text = line,
                        color = levelColor(LogcatReader.levelOf(line)),
                        style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 13.sp),
                        modifier = Modifier.padding(vertical = 1.dp)
                    )
                }
            }
        }
        Text(
            "Shows this app's own logs only. The last ${LogcatReader.MAX_BUFFER} lines are kept in memory.",
            color = LogText.copy(alpha = 0.5f),
            style = TextStyle(fontSize = 11.sp)
        )
    }

    pickerFor?.let { which ->
        val now = remember { Calendar.getInstance() }
        val nowMinute = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val initial = (if (which == "start") startMinute else endMinute) ?: nowMinute
        val state = rememberTimePickerState(
            initialHour = initial / 60,
            initialMinute = initial % 60,
            is24Hour = true
        )
        AlertDialog(
            onDismissRequest = { pickerFor = null },
            title = { Text(if (which == "start") "Start time" else "End time") },
            text = { TimePicker(state = state) },
            confirmButton = {
                TextButton(onClick = {
                    val picked = state.hour * 60 + state.minute
                    if (which == "start") startMinute = picked else endMinute = picked
                    pickerFor = null
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickerFor = null }) { Text("Cancel") } }
        )
    }
}

private const val MAX_SHARE_CHARS = 150_000
