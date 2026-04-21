package com.beatflowy.app.repository

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import com.beatflowy.app.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class LyricsRepository(private val context: Context) {

    suspend fun fetchLyrics(song: Song): List<Pair<Long, String>> = withContext(Dispatchers.IO) {
        val fromEmbedded = readEmbeddedLyrics(song.uri)
        if (fromEmbedded != null) {
            val parsed = LrcLineParser.parse(fromEmbedded)
            if (parsed.isNotEmpty()) return@withContext parsed
        }

        val sidecar = readSidecarLrc(song.uri, song.title)
        if (sidecar != null) {
            val parsed = LrcLineParser.parse(sidecar)
            if (parsed.isNotEmpty()) return@withContext parsed
        }

        emptyList()
    }

    private fun readEmbeddedLyrics(uri: Uri): String? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            var raw: String? = null
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val lyricKey = runCatching {
                    MediaMetadataRetriever::class.java.getField("METADATA_KEY_LYRIC").getInt(null)
                }.getOrNull()
                if (lyricKey != null) {
                    raw = retriever.extractMetadata(lyricKey)
                }
            }
            if (raw.isNullOrBlank()) {
                @Suppress("DEPRECATION")
                raw = retriever.extractMetadata(1012)
            }
            raw?.trim()?.takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Loads a UTF-8 `.lrc` next to the audio file when [MediaStore.Audio.Media.DATA] is available,
     * or reconstructs a path from RELATIVE_PATH + DISPLAY_NAME on newer storage layouts.
     */
    private fun readSidecarLrc(uri: Uri, titleFallback: String): String? {
        val dataPath = queryDataPath(uri) ?: return null
        val base = File(dataPath)
        val dir = base.parentFile ?: return null
        val nameWithoutExt = base.nameWithoutExtension.ifBlank { sanitizeFileName(titleFallback) }
        val candidates = listOf(
            File(dir, "$nameWithoutExt.lrc"),
            File(dir, "${base.name}.lrc"),
            File(dir, "${nameWithoutExt}.LRC")
        )
        for (f in candidates) {
            if (f.exists() && f.length() > 0L && f.length() < 2_000_000L) {
                return try {
                    f.readText(Charsets.UTF_8).removePrefix("\uFEFF")
                } catch (_: Exception) {
                    null
                }
            }
        }
        return null
    }

    private fun queryDataPath(uri: Uri): String? {
        if (uri.scheme == "file") return uri.path
        if (uri.scheme != "content") return null
        val dataCol = MediaStore.Audio.Media.DATA
        try {
            context.contentResolver.query(uri, arrayOf(dataCol), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(dataCol)
                    if (idx >= 0) {
                        val p = c.getString(idx)
                        if (!p.isNullOrBlank()) return p
                    }
                }
            }
        } catch (_: Exception) {
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val rel = MediaStore.Audio.Media.RELATIVE_PATH
                val disp = MediaStore.Audio.Media.DISPLAY_NAME
                context.contentResolver.query(uri, arrayOf(rel, disp), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val rIdx = c.getColumnIndex(rel)
                        val dIdx = c.getColumnIndex(disp)
                        if (rIdx >= 0 && dIdx >= 0) {
                            val relPath = c.getString(rIdx)?.trim('/') ?: return@use
                            val display = c.getString(dIdx) ?: return@use
                            val roots = listOf(
                                "/storage/emulated/0/",
                                "/sdcard/"
                            )
                            for (root in roots) {
                                val audioFile = File(File(root.trimEnd('/'), relPath.trim('/')), display)
                                if (audioFile.exists()) return audioFile.absolutePath
                            }
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }
        try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c ->
                    if (c.moveToFirst()) {
                        val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) {
                            val name = c.getString(idx) ?: return@use
                            // Last resort: cannot resolve path
                            if (name.endsWith(".lrc", true)) return null
                        }
                    }
                }
        } catch (_: Exception) {
        }
        return null
    }

    private fun sanitizeFileName(title: String): String =
        title.replace(Regex("""[<>:"/\\|?*]"""), "_").take(120)
}
