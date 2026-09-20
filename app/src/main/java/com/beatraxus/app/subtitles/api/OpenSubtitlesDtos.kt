package com.beatraxus.app.subtitles.api

import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName

@Keep
data class LoginRequest(
    @SerializedName("username") val username: String? = null,
    @SerializedName("password") val password: String? = null
)

@Keep
data class LoginResponse(
    @SerializedName("user") val user: UserDto? = null,
    @SerializedName("token") val token: String? = null,
    @SerializedName("status") val status: Int? = null,
    @SerializedName("message") val message: String? = null
)

@Keep
data class UserDto(
    @SerializedName("user_id") val userId: Long? = null,
    @SerializedName("username") val username: String? = null,
    @SerializedName("level") val level: String? = null,
    @SerializedName("vip") val vip: Boolean? = null
)

@Keep
data class SubtitlesResponse(
    @SerializedName("total_pages") val totalPages: Int? = null,
    @SerializedName("total_count") val totalCount: Int? = null,
    @SerializedName("page") val page: Int? = null,
    @SerializedName("data") val data: List<SubtitleDataDto>? = null
)

@Keep
data class SubtitleDataDto(
    @SerializedName("id") val id: String? = null,
    @SerializedName("type") val type: String? = null,
    @SerializedName("attributes") val attributes: SubtitleAttributesDto? = null
)

@Keep
data class SubtitleAttributesDto(
    @SerializedName("subtitle_id") val subtitleId: String? = null,
    @SerializedName("language") val language: String? = null,
    @SerializedName("download_count") val downloadCount: Int? = null,
    @SerializedName("new_download_count") val newDownloadCount: Int? = null,
    @SerializedName("hearing_impaired") val hearingImpaired: Boolean? = null,
    @SerializedName("hd") val hd: Boolean? = null,
    @SerializedName("fps") val fps: Float? = null,
    @SerializedName("votes") val votes: Int? = null,
    @SerializedName("ratings") val ratings: Float? = null,
    @SerializedName("from_trusted") val fromTrusted: Boolean? = null,
    @SerializedName("foreign_parts_only") val foreignPartsOnly: Boolean? = null,
    @SerializedName("ai_translated") val aiTranslated: Boolean? = null,
    @SerializedName("machine_translated") val machineTranslated: Boolean? = null,
    @SerializedName("upload_date") val uploadDate: String? = null,
    @SerializedName("release") val release: String? = null,
    @SerializedName("comments") val comments: String? = null,
    @SerializedName("uploader") val uploader: SubtitleUploaderDto? = null,
    @SerializedName("feature_details") val featureDetails: SubtitleFeatureDetailsDto? = null,
    @SerializedName("files") val files: List<SubtitleFileDto>? = null
)

@Keep
data class SubtitleUploaderDto(
    @SerializedName("uploader_id") val uploaderId: Long? = null,
    @SerializedName("name") val name: String? = null,
    @SerializedName("rank") val rank: String? = null
)

@Keep
data class SubtitleFeatureDetailsDto(
    @SerializedName("feature_id") val featureId: Long? = null,
    @SerializedName("feature_type") val featureType: String? = null,
    @SerializedName("year") val year: Int? = null,
    @SerializedName("title") val title: String? = null,
    @SerializedName("movie_name") val movieName: String? = null,
    @SerializedName("imdb_id") val imdbId: Long? = null,
    @SerializedName("tmdb_id") val tmdbId: Long? = null
)

@Keep
data class SubtitleFileDto(
    @SerializedName("file_id") val fileId: Long? = null,
    @SerializedName("cd_number") val cdNumber: Int? = null,
    @SerializedName("file_name") val fileName: String? = null
)

@Keep
data class DownloadRequest(
    @SerializedName("file_id") val fileId: Long? = null,
    @SerializedName("sub_format") val subFormat: String? = "srt"
)

@Keep
data class DownloadResponse(
    @SerializedName("link") val link: String? = null,
    @SerializedName("file_name") val fileName: String? = null,
    @SerializedName("requests") val requests: Int? = null,
    @SerializedName("remaining") val remaining: Int? = null,
    @SerializedName("message") val message: String? = null,
    @SerializedName("reset_time") val resetTime: String? = null,
    @SerializedName("reset_time_utc") val resetTimeUtc: String? = null
)

@Keep
data class LanguagesResponse(
    @SerializedName("data") val data: List<LanguageDataDto>? = null
)

@Keep
data class LanguageDataDto(
    @SerializedName("language_code") val languageCode: String? = null,
    @SerializedName("language_name") val languageName: String? = null
)
