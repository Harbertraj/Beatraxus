package com.beatraxus.app.subtitles.data

import com.beatraxus.app.subtitles.domain.SubtitleError
import com.beatraxus.app.subtitles.domain.SubtitleException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream

object ZipSubtitleExtractor {

    private const val MAX_UNCOMPRESSED_BYTES = 10 * 1024 * 1024 // 10 MB limit
    private val SUBTITLE_EXTENSIONS = setOf("srt", "vtt", "ass", "ssa")

    fun isZipStream(bytes: ByteArray): Boolean {
        return bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte() &&
                bytes[2] == 0x03.toByte() && bytes[3] == 0x04.toByte()
    }

    fun extractSubtitleFromZip(zipBytes: ByteArray, targetDir: File): Result<Pair<String, ByteArray>> {
        val targetCanonical = targetDir.canonicalPath + File.separator

        try {
            ZipInputStream(ByteArrayInputStream(zipBytes)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val name = sanitizeEntryName(entry.name)
                    val ext = name.substringAfterLast('.', "").lowercase()

                    if (!entry.isDirectory && ext in SUBTITLE_EXTENSIONS) {
                        val outFile = File(targetDir, name)
                        if (!outFile.canonicalPath.startsWith(targetCanonical)) {
                            return Result.failure(SubtitleException(SubtitleError.FileAccessDenied))
                        }

                        val buffer = ByteArray(4096)
                        val out = ByteArrayOutputStream()
                        var totalRead = 0L

                        var read: Int
                        while (zis.read(buffer).also { read = it } != -1) {
                            totalRead += read
                            if (totalRead > MAX_UNCOMPRESSED_BYTES) {
                                return Result.failure(SubtitleException(SubtitleError.InvalidSubtitle))
                            }
                            out.write(buffer, 0, read)
                        }

                        return Result.success(Pair(name, out.toByteArray()))
                    }
                    entry = zis.nextEntry
                }
            }
            return Result.failure(SubtitleException(SubtitleError.InvalidSubtitle))
        } catch (e: Exception) {
            return Result.failure(SubtitleException(SubtitleError.InvalidSubtitle))
        }
    }

    private fun sanitizeEntryName(rawName: String): String {
        val fileName = File(rawName).name
        val clean = fileName.replace(Regex("[^a-zA-Z0-9_\\.-]"), "_")
        return if (clean.length > 100) clean.substring(clean.length - 100) else clean
    }
}
