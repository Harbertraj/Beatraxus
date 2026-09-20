package com.beatraxus.app.subtitles.domain

data class SubtitleSearchQuery(
    val query: String? = null,
    val imdbId: Long? = null,
    val tmdbId: Long? = null,
    val movieHash: String? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val year: Int? = null,
    val languages: List<String> = emptyList(),
    val type: String? = null,
    val page: Int = 1
)

data class SubtitleResult(
    val id: String,
    val fileId: Long,
    val fileName: String,
    val releaseName: String?,
    val language: String,
    val languageName: String?,
    val downloadCount: Int,
    val isHearingImpaired: Boolean,
    val isMachineTranslated: Boolean,
    val isAiTranslated: Boolean,
    val rating: Float,
    val uploaderName: String?,
    val fps: Float?,
    val featureTitle: String?,
    val year: Int?,
    val imdbId: Long?,
    val tmdbId: Long?
)

data class SubtitleLanguage(
    val code: String,
    val name: String
)

data class DownloadInfo(
    val link: String,
    val fileName: String,
    val remaining: Int,
    val resetTime: String?
)

sealed interface SubtitleError {
    data object NetworkUnavailable : SubtitleError
    data object ApiNotConfigured : SubtitleError
    data object NotSignedIn : SubtitleError
    data object InvalidCredentials : SubtitleError
    data object ApiUnauthorized : SubtitleError
    data class ApiRateLimited(val retryAfterSec: Int) : SubtitleError
    data class ApiQuotaExceeded(val resetTime: String?) : SubtitleError
    data object NoResults : SubtitleError
    data class DownloadFailed(val reason: String) : SubtitleError
    data object InvalidSubtitle : SubtitleError
    data object UnsupportedFormat : SubtitleError
    data object FileAccessDenied : SubtitleError
    data object HashCalculationFailed : SubtitleError
    data class Unknown(val cause: Throwable? = null) : SubtitleError
}

class SubtitleException(val error: SubtitleError) : Exception(error.toString())
