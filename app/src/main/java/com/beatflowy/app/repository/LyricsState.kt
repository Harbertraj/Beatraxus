package com.beatflowy.app.repository

sealed class LyricsState {
    object Loading : LyricsState()
    data class Success(val result: LyricsLoadResult) : LyricsState()
    data class Error(val message: String, val throwable: Throwable? = null) : LyricsState()
}
