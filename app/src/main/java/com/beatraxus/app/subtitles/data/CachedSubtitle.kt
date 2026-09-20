package com.beatraxus.app.subtitles.data

import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName

@Keep
enum class SubtitleSource {
    @SerializedName("ONLINE") ONLINE,
    @SerializedName("LOCAL") LOCAL
}

@Keep
data class CachedSubtitle(
    @SerializedName("subtitle_id") val subtitleId: String,
    @SerializedName("language") val language: String,
    @SerializedName("format") val format: String = "SRT",
    @SerializedName("release_name") val releaseName: String? = null,
    @SerializedName("local_path") val localPath: String,
    @SerializedName("last_used") val lastUsed: Long = System.currentTimeMillis(),
    @SerializedName("delay_ms") val delayMs: Long = 0L,
    @SerializedName("source") val source: SubtitleSource = SubtitleSource.ONLINE
)

@Keep
data class MediaCacheIndex(
    @SerializedName("media_key") val mediaKey: String,
    @SerializedName("subtitles") val subtitles: List<CachedSubtitle> = emptyList()
)
