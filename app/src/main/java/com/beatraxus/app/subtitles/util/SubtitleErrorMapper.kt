package com.beatraxus.app.subtitles.util

import com.beatraxus.app.subtitles.domain.SubtitleError
import com.beatraxus.app.subtitles.domain.SubtitleFontError

object SubtitleErrorMapper {

    fun toUserMessage(error: SubtitleError): String {
        return when (error) {
            is SubtitleError.NetworkUnavailable -> "Internet connection is unavailable."
            is SubtitleError.ApiNotConfigured -> "OpenSubtitles API key is not configured."
            is SubtitleError.NotSignedIn -> "Please sign in to OpenSubtitles to download subtitles."
            is SubtitleError.InvalidCredentials -> "Invalid username or password."
            is SubtitleError.ApiUnauthorized -> "Subtitle service authorization failed."
            is SubtitleError.ApiRateLimited -> "Too many requests. Please try again in ${error.retryAfterSec} seconds."
            is SubtitleError.ApiQuotaExceeded -> {
                val reset = error.resetTime
                if (!reset.isNullOrBlank()) {
                    "Daily free download limit reached. Sign in for a higher limit, or try again in $reset."
                } else {
                    "Daily download limit reached. Please sign in for a higher limit or try again later."
                }
            }
            is SubtitleError.NoResults -> "No subtitles found for this video."
            is SubtitleError.DownloadFailed -> "Subtitle download failed. Please try again."
            is SubtitleError.InvalidSubtitle -> "The subtitle file was invalid or corrupted."
            is SubtitleError.UnsupportedFormat -> "Subtitle format is not supported."
            is SubtitleError.FileAccessDenied -> "Cannot access subtitle file."
            is SubtitleError.HashCalculationFailed -> "Failed to compute video hash."
            is SubtitleError.Unknown -> "Subtitle service is temporarily unavailable."
        }
    }

    fun toFontMessage(error: SubtitleFontError?): String {
        return when (error) {
            SubtitleFontError.NOT_A_FONT -> "That file is not a TTF or OTF font."
            SubtitleFontError.TOO_LARGE -> "That file is too large to be a font."
            SubtitleFontError.UNUSABLE -> "This font could not be loaded."
            SubtitleFontError.UNREADABLE, null -> "Cannot read the font file."
        }
    }
}
