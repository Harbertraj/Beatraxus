package com.beatraxus.app.subtitles.domain

import java.io.File

interface SubtitleRepository {
    suspend fun searchSubtitles(query: SubtitleSearchQuery): Result<List<SubtitleResult>>
    suspend fun getLanguages(): Result<List<SubtitleLanguage>>
    suspend fun requestDownload(fileId: Long): Result<DownloadInfo>
    suspend fun downloadToFile(downloadUrl: String, destination: File): Result<File>
    fun getLastKnownRemainingDownloads(): Int
    fun getLastKnownResetTime(): String?
}
