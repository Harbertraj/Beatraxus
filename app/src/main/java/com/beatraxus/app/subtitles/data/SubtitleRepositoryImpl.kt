package com.beatraxus.app.subtitles.data

import android.content.Context
import com.beatraxus.app.subtitles.api.DownloadRequest
import com.beatraxus.app.subtitles.api.OpenSubtitlesApi
import com.beatraxus.app.subtitles.api.SubtitleApiConfig
import com.beatraxus.app.subtitles.domain.DownloadInfo
import com.beatraxus.app.subtitles.domain.SubtitleError
import com.beatraxus.app.subtitles.domain.SubtitleException
import com.beatraxus.app.subtitles.domain.SubtitleLanguage
import com.beatraxus.app.subtitles.domain.SubtitleRepository
import com.beatraxus.app.subtitles.domain.SubtitleResult
import com.beatraxus.app.subtitles.domain.SubtitleSearchQuery
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.CancellationException

class SubtitleRepositoryImpl(
    context: Context,
    private val api: OpenSubtitlesApi,
    private val authManager: SubtitleAuthManager,
    private val config: SubtitleApiConfig = SubtitleApiConfig(),
    private val downloadOkHttpClient: OkHttpClient = OkHttpClient()
) : SubtitleRepository {

    private val cachePrefs = context.getSharedPreferences("opensubtitles_cache", Context.MODE_PRIVATE)
    private val appPrefs = context.getSharedPreferences("beatraxus", Context.MODE_PRIVATE)
    private val gson = Gson()
    private var memoryLanguageCache: List<SubtitleLanguage>? = null

    private var memoryRemainingDownloads: Int = -1
    private var memoryResetTime: String? = null

    companion object {
        private const val KEY_LANGUAGES_JSON = "languages_json"
        private const val KEY_LANGUAGES_TIME = "languages_timestamp"
        private const val SEVEN_DAYS_MS = 7 * 24 * 60 * 60 * 1000L

        private const val KEY_REMAINING_DOWNLOADS = "video_subtitle_remaining_downloads"
        private const val KEY_RESET_TIME = "video_subtitle_reset_time"

        val BUILT_IN_LANGUAGES = listOf(
            SubtitleLanguage("en", "English"),
            SubtitleLanguage("ta", "Tamil"),
            SubtitleLanguage("hi", "Hindi"),
            SubtitleLanguage("te", "Telugu"),
            SubtitleLanguage("ml", "Malayalam"),
            SubtitleLanguage("kn", "Kannada"),
            SubtitleLanguage("bn", "Bengali"),
            SubtitleLanguage("mr", "Marathi"),
            SubtitleLanguage("ur", "Urdu"),
            SubtitleLanguage("es", "Spanish"),
            SubtitleLanguage("fr", "French"),
            SubtitleLanguage("de", "German"),
            SubtitleLanguage("ja", "Japanese"),
            SubtitleLanguage("ko", "Korean"),
            SubtitleLanguage("zh-CN", "Chinese (Simplified)"),
            SubtitleLanguage("zh-TW", "Chinese (Traditional)")
        )
    }

    override fun getLastKnownRemainingDownloads(): Int {
        if (memoryRemainingDownloads != -1) return memoryRemainingDownloads
        return appPrefs.getInt(KEY_REMAINING_DOWNLOADS, -1)
    }

    override fun getLastKnownResetTime(): String? {
        if (memoryResetTime != null) return memoryResetTime
        return appPrefs.getString(KEY_RESET_TIME, null)
    }

    private fun updateQuotaInfo(remaining: Int?, resetTime: String?) {
        if (remaining != null) {
            memoryRemainingDownloads = remaining
            appPrefs.edit().putInt(KEY_REMAINING_DOWNLOADS, remaining).apply()
        }
        if (resetTime != null) {
            memoryResetTime = resetTime
            appPrefs.edit().putString(KEY_RESET_TIME, resetTime).apply()
        }
    }

    override suspend fun searchSubtitles(query: SubtitleSearchQuery): Result<List<SubtitleResult>> {
        if (config.apiKeyProvider().isBlank()) {
            return Result.failure(SubtitleException(SubtitleError.ApiNotConfigured))
        }

        return try {
            val response = api.searchSubtitles(
                query = query.query,
                imdbId = query.imdbId,
                tmdbId = query.tmdbId,
                movieHash = query.movieHash,
                languages = query.languages.takeIf { it.isNotEmpty() }?.joinToString(","),
                type = query.type,
                page = query.page
            )

            if (response.isSuccessful) {
                val body = response.body()
                val data = body?.data
                if (data.isNullOrEmpty()) {
                    Result.failure(SubtitleException(SubtitleError.NoResults))
                } else {
                    val results = data.mapNotNull { dto ->
                        val attr = dto.attributes ?: return@mapNotNull null
                        val file = attr.files?.firstOrNull()
                        val fileId = file?.fileId ?: return@mapNotNull null
                        val subId = dto.id ?: attr.subtitleId ?: fileId.toString()

                        SubtitleResult(
                            id = subId,
                            fileId = fileId,
                            fileName = file.fileName ?: attr.release ?: "subtitle_$subId.srt",
                            releaseName = attr.release,
                            language = attr.language ?: "en",
                            languageName = attr.language,
                            downloadCount = attr.downloadCount ?: 0,
                            isHearingImpaired = attr.hearingImpaired ?: false,
                            isMachineTranslated = attr.machineTranslated ?: false,
                            isAiTranslated = attr.aiTranslated ?: false,
                            rating = attr.ratings ?: 0f,
                            uploaderName = attr.uploader?.name,
                            fps = attr.fps,
                            featureTitle = attr.featureDetails?.title ?: attr.featureDetails?.movieName,
                            year = attr.featureDetails?.year,
                            imdbId = attr.featureDetails?.imdbId,
                            tmdbId = attr.featureDetails?.tmdbId
                        )
                    }

                    if (results.isEmpty()) {
                        Result.failure(SubtitleException(SubtitleError.NoResults))
                    } else {
                        Result.success(results)
                    }
                }
            } else {
                when (response.code()) {
                    401, 403 -> Result.failure(SubtitleException(SubtitleError.ApiUnauthorized))
                    429 -> {
                        val retryAfter = response.headers()["Retry-After"]?.toIntOrNull() ?: 10
                        Result.failure(SubtitleException(SubtitleError.ApiRateLimited(retryAfter)))
                    }
                    else -> Result.failure(SubtitleException(SubtitleError.Unknown()))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: IOException) {
            Result.failure(SubtitleException(SubtitleError.NetworkUnavailable))
        } catch (e: Exception) {
            Result.failure(SubtitleException(SubtitleError.Unknown(e)))
        }
    }

    override suspend fun getLanguages(): Result<List<SubtitleLanguage>> {
        memoryLanguageCache?.let { return Result.success(it) }

        val cachedJson = cachePrefs.getString(KEY_LANGUAGES_JSON, null)
        val cachedTime = cachePrefs.getLong(KEY_LANGUAGES_TIME, 0L)
        if (!cachedJson.isNullOrBlank() && (System.currentTimeMillis() - cachedTime) < SEVEN_DAYS_MS) {
            try {
                val type = object : TypeToken<List<SubtitleLanguage>>() {}.type
                val list: List<SubtitleLanguage> = gson.fromJson(cachedJson, type)
                if (list.isNotEmpty()) {
                    memoryLanguageCache = list
                    return Result.success(list)
                }
            } catch (_: Exception) {
            }
        }

        if (config.apiKeyProvider().isNotBlank()) {
            try {
                val response = api.getLanguages()
                if (response.isSuccessful) {
                    val langDtos = response.body()?.data
                    if (!langDtos.isNullOrEmpty()) {
                        val languages = langDtos.mapNotNull { dto ->
                            val code = dto.languageCode ?: return@mapNotNull null
                            val name = dto.languageName ?: code
                            SubtitleLanguage(code, name)
                        }
                        if (languages.isNotEmpty()) {
                            memoryLanguageCache = languages
                            cachePrefs.edit()
                                .putString(KEY_LANGUAGES_JSON, gson.toJson(languages))
                                .putLong(KEY_LANGUAGES_TIME, System.currentTimeMillis())
                                .apply()
                            return Result.success(languages)
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }

        return Result.success(BUILT_IN_LANGUAGES)
    }

    override suspend fun requestDownload(fileId: Long): Result<DownloadInfo> {
        if (config.apiKeyProvider().isBlank()) {
            return Result.failure(SubtitleException(SubtitleError.ApiNotConfigured))
        }

        val token = authManager.getTokenIfSignedIn()
        var authHeader = token?.let { "Bearer $it" }

        try {
            var response = api.download(authHeader, DownloadRequest(fileId = fileId, subFormat = "srt"))

            if (authHeader != null && (response.code() == 401 || response.code() == 403)) {
                authManager.invalidateToken()
                val reloginRes = authManager.relogin()
                if (reloginRes.isSuccess) {
                    val newToken = reloginRes.getOrNull()
                    authHeader = newToken?.let { "Bearer $it" }
                    response = api.download(authHeader, DownloadRequest(fileId = fileId, subFormat = "srt"))
                } else {
                    return Result.failure(SubtitleException(SubtitleError.InvalidCredentials))
                }
            }

            if (response.isSuccessful) {
                val body = response.body()
                val link = body?.link
                val resetTime = body?.resetTime ?: body?.resetTimeUtc
                val remaining = body?.remaining ?: 0

                updateQuotaInfo(remaining, resetTime)

                if (link.isNullOrBlank()) {
                    return Result.failure(SubtitleException(SubtitleError.DownloadFailed("Download link empty")))
                }

                val info = DownloadInfo(
                    link = link,
                    fileName = body.fileName ?: "subtitle_$fileId.srt",
                    remaining = remaining,
                    resetTime = resetTime
                )
                return Result.success(info)
            } else {
                val body = response.body()
                val resetTime = body?.resetTime ?: body?.resetTimeUtc ?: getLastKnownResetTime()

                return when (response.code()) {
                    401 -> {
                        if (authHeader == null) {
                            Result.failure(SubtitleException(SubtitleError.NotSignedIn))
                        } else {
                            Result.failure(SubtitleException(SubtitleError.InvalidCredentials))
                        }
                    }
                    403 -> Result.failure(SubtitleException(SubtitleError.ApiUnauthorized))
                    406 -> Result.failure(SubtitleException(SubtitleError.ApiQuotaExceeded(resetTime)))
                    429 -> {
                        val retryAfter = response.headers()["Retry-After"]?.toIntOrNull() ?: 10
                        Result.failure(SubtitleException(SubtitleError.ApiRateLimited(retryAfter)))
                    }
                    else -> Result.failure(SubtitleException(SubtitleError.DownloadFailed("HTTP ${response.code()}")))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: IOException) {
            return Result.failure(SubtitleException(SubtitleError.NetworkUnavailable))
        } catch (e: Exception) {
            return Result.failure(SubtitleException(SubtitleError.Unknown(e)))
        }
    }

    override suspend fun downloadToFile(downloadUrl: String, destination: File): Result<File> {
        return try {
            val request = Request.Builder().url(downloadUrl).get().build()
            downloadOkHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return Result.failure(SubtitleException(SubtitleError.DownloadFailed("HTTP ${response.code}")))
                }
                val body = response.body
                    ?: return Result.failure(SubtitleException(SubtitleError.DownloadFailed("Empty body")))

                body.byteStream().use { input ->
                    FileOutputStream(destination).use { output ->
                        input.copyTo(output)
                    }
                }
                Result.success(destination)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: IOException) {
            Result.failure(SubtitleException(SubtitleError.NetworkUnavailable))
        } catch (e: SecurityException) {
            Result.failure(SubtitleException(SubtitleError.FileAccessDenied))
        } catch (e: Exception) {
            Result.failure(SubtitleException(SubtitleError.Unknown(e)))
        }
    }
}
