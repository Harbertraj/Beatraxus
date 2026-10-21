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
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

/**
 * Reads this app's own logcat output and filters it by time-of-day window.
 *
 * Without the READ_LOGS permission (only grantable over adb) Android only lets an app see its own
 * process' log lines, which is exactly what an in-app viewer needs. The log buffer is a ring buffer,
 * so very old lines (typically more than a few minutes of busy logging) may already be gone.
 */
object LogcatReader {
    private val LINE_REGEX = Regex(
        """^(\d{2})-(\d{2})\s+(\d{2}):(\d{2}):(\d{2})\.\d{3}\s+\d+\s+\d+\s+([VDIWEFA])\s"""
    )

    /** Seconds since midnight for [line], or null when the line has no timestamp (e.g. buffer headers). */
    fun secondOfDay(line: String): Int? {
        val m = LINE_REGEX.find(line) ?: return null
        val (_, _, h, min, s) = m.destructured
        return h.toInt() * 3600 + min.toInt() * 60 + s.toInt()
    }

    fun levelOf(line: String): Char? = LINE_REGEX.find(line)?.groupValues?.get(6)?.firstOrNull()

    /** Dumps the current logcat buffer (threadtime format, one entry per line). */
    suspend fun dump(): List<String> = withContext(Dispatchers.IO) {
        val process = ProcessBuilder("logcat", "-d", "-v", "threadtime")
            .redirectErrorStream(true)
            .start()
        try {
            process.inputStream.bufferedReader().useLines { it.toList() }
        } finally {
            process.destroy()
        }
    }

    /**
     * Keeps lines whose time falls in [startMinute, endMinute] (minutes since midnight, end minute
     * inclusive up to :59) and whose level is one of [levels] (V, D, I, W, E). If start > end the window wraps over midnight (e.g. 23:50 to 00:10).
     */
    fun filter(
        lines: List<String>,
        startMinute: Int,
        endMinute: Int,
        levels: Set<Char>,
        query: String
    ): List<String> {
        val startSec = startMinute * 60
        val endSec = endMinute * 60 + 59
        val needle = query.trim()
        return lines.filter { line ->
            val sec = secondOfDay(line) ?: return@filter false
            val inWindow = if (startSec <= endSec) sec in startSec..endSec else (sec >= startSec || sec <= endSec)
            if (!inWindow) return@filter false
            val level = levelOf(line) ?: return@filter false
            // Fatal/assert lines are shown under the E chip.
            val chip = if (level == 'F' || level == 'A') 'E' else level
            if (chip !in levels) return@filter false
            needle.isEmpty() || line.contains(needle, ignoreCase = true)
        }
    }
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
 * In-app logcat viewer with a start/end time filter (for example 12:30 to 12:50), a minimum log level,
 * a text search, and copy / share of the filtered result.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogcatContent() {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    val now = remember { Calendar.getInstance() }
    val nowMinute = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
    var startMinute by remember { mutableStateOf(((nowMinute - 20) + 1440) % 1440) }
    var endMinute by remember { mutableStateOf(nowMinute) }
    var levels by remember { mutableStateOf(setOf('V', 'D', 'I', 'W', 'E')) }
    var query by remember { mutableStateOf("") }

    var rawLines by remember { mutableStateOf<List<String>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var pickerFor by remember { mutableStateOf<String?>(null) } // "start" | "end" | null

    fun reload() {
        scope.launch {
            loading = true
            error = null
            try {
                rawLines = LogcatReader.dump()
            } catch (e: Exception) {
                error = e.message ?: "Could not read logcat"
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(Unit) { reload() }

    val filtered = remember(rawLines, startMinute, endMinute, levels, query) {
        LogcatReader.filter(rawLines, startMinute, endMinute, levels, query)
    }
    // Rendering tens of thousands of lines is pointless on a phone; keep the newest ones.
    val shown = remember(filtered) { if (filtered.size > MAX_SHOWN) filtered.takeLast(MAX_SHOWN) else filtered }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        Text(
            "Time range (today, 24h)",
            color = LogText.copy(alpha = 0.7f),
            style = TextStyle(fontSize = 12.sp)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { pickerFor = "start" },
                border = BorderStroke(1.dp, LogAccent.copy(alpha = 0.6f)),
                modifier = Modifier.weight(1f)
            ) { Text("From  ${fmt(startMinute)}", color = LogText) }
            OutlinedButton(
                onClick = { pickerFor = "end" },
                border = BorderStroke(1.dp, LogAccent.copy(alpha = 0.6f)),
                modifier = Modifier.weight(1f)
            ) { Text("To  ${fmt(endMinute)}", color = LogText) }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            listOf('V', 'D', 'I', 'W', 'E').forEach { lvl ->
                FilterChip(
                    selected = lvl in levels,
                    onClick = { levels = if (lvl in levels) levels - lvl else levels + lvl },
                    label = { Text(lvl.toString()) }
                )
            }
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
                onClick = { reload() },
                enabled = !loading,
                colors = ButtonDefaults.buttonColors(containerColor = LogAccent, contentColor = Color.Black)
            ) {
                Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(if (loading) "  Loading…" else "  Refresh")
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
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "Beatraxus logcat ${fmt(startMinute)}-${fmt(endMinute)}")
                        putExtra(Intent.EXTRA_TEXT, text)
                    }
                    context.startActivity(Intent.createChooser(send, "Share logcat"))
                },
                enabled = filtered.isNotEmpty()
            ) {
                Icon(Icons.Rounded.Share, contentDescription = "Share", modifier = Modifier.size(18.dp))
            }
        }

        val summary = when {
            error != null -> "Error: $error"
            else -> "${filtered.size} lines in ${fmt(startMinute)}–${fmt(endMinute)}" +
                if (filtered.size > shown.size) " (showing last ${shown.size})" else ""
        }
        Text(summary, color = LogText.copy(alpha = 0.7f), style = TextStyle(fontSize = 12.sp))

        val listState = rememberLazyListState()
        LaunchedEffect(shown) {
            if (shown.isNotEmpty()) listState.scrollToItem(shown.size - 1)
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
                items(shown) { line ->
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
            "Shows this app's own logs only. Android keeps a limited log buffer, so older entries may be missing.",
            color = LogText.copy(alpha = 0.5f),
            style = TextStyle(fontSize = 11.sp)
        )
    }

    pickerFor?.let { which ->
        val initial = if (which == "start") startMinute else endMinute
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

private const val MAX_SHOWN = 5000
private const val MAX_SHARE_CHARS = 150_000
