package com.beatraxus.app.subtitles.viewmodel

sealed interface SubtitleUiEvent {
    data class ShowToast(val message: String) : SubtitleUiEvent
    data object NeedSignIn : SubtitleUiEvent
    data class SignInForHigherLimit(val resetTime: String?) : SubtitleUiEvent
    data class DownloadSuccess(val subtitleName: String) : SubtitleUiEvent
    data class Error(val message: String) : SubtitleUiEvent
}
