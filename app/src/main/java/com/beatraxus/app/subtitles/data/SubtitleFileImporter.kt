package com.beatraxus.app.subtitles.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.beatraxus.app.subtitles.domain.EncodingNormalizer
import com.beatraxus.app.subtitles.domain.SubtitleConverter
import com.beatraxus.app.subtitles.domain.SubtitleError
import com.beatraxus.app.subtitles.domain.SubtitleException
import com.beatraxus.app.subtitles.domain.SubtitleFormatDetector
import com.beatraxus.app.subtitles.domain.SubtitleValidator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class SubtitleFileImporter(
    private val context: Context,
    private val subtitleCache: SubtitleCache
) {

    suspend fun importSubtitleFromUri(
        mediaKey: String,
        uri: Uri,
        languageCode: String = "en"
    ): Result<CachedSubtitle> = withContext(Dispatchers.IO) {
        try {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return@withContext Result.failure(SubtitleException(SubtitleError.FileAccessDenied))

            val (rawContent, originalName) = if (ZipSubtitleExtractor.isZipStream(bytes)) {
                val tempDir = File(context.cacheDir, "zip_temp").apply { mkdirs() }
                val zipRes = ZipSubtitleExtractor.extractSubtitleFromZip(bytes, tempDir)
                if (zipRes.isFailure) {
                    return@withContext Result.failure(zipRes.exceptionOrNull()!!)
                }
                val (name, extractedBytes) = zipRes.getOrThrow()
                Pair(EncodingNormalizer.normalizeToUtf8(extractedBytes, languageCode), name)
            } else {
                val name = getFileNameFromUri(uri) ?: "local_subtitle.srt"
                Pair(EncodingNormalizer.normalizeToUtf8(bytes, languageCode), name)
            }

            val format = SubtitleFormatDetector.detect(rawContent)
            val srtContent = SubtitleConverter.convertToSrt(rawContent, format)
                ?: return@withContext Result.failure(SubtitleException(SubtitleError.UnsupportedFormat))

            SubtitleValidator.validate(srtContent).getOrThrow()

            val subtitleId = "local_" + System.currentTimeMillis()
            val cachedSubtitle = CachedSubtitle(
                subtitleId = subtitleId,
                language = languageCode,
                format = "SRT",
                releaseName = originalName,
                localPath = "",
                source = SubtitleSource.LOCAL
            )

            val savedFile = subtitleCache.saveSubtitle(mediaKey, cachedSubtitle, srtContent)
            val updated = cachedSubtitle.copy(localPath = savedFile.absolutePath)
            Result.success(updated)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (e is SubtitleException) Result.failure(e)
            else Result.failure(SubtitleException(SubtitleError.Unknown(e)))
        }
    }

    private fun getFileNameFromUri(uri: Uri): String? {
        var name: String? = null
        if (uri.scheme == "content") {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) {
                        name = cursor.getString(idx)
                    }
                }
            }
        }
        if (name == null) {
            name = uri.path?.substringAfterLast('/')
        }
        return name
    }
}
