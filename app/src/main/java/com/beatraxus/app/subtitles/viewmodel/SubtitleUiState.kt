package com.beatraxus.app.subtitles.viewmodel

import com.beatraxus.app.subtitles.data.CachedSubtitle
import com.beatraxus.app.subtitles.domain.ScoredSubtitle
import com.beatraxus.app.subtitles.domain.SubtitleError
import com.beatraxus.app.subtitles.domain.SubtitleLanguage

enum class AutoSearchMode(val displayName: String) {
    OFF("Off"),
    ON("On"),
    ON_PREFERRED_LANGUAGE("On (Preferred Language Only)")
}

enum class SelectedSubtitleType {
    NONE,
    EXTERNAL,
    EMBEDDED
}

data class SubtitleUiState(
    val enabled: Boolean = true,
    val isSearching: Boolean = false,
    val isDownloading: String? = null,
    val languages: List<SubtitleLanguage> = emptyList(),
    val selectedLanguages: List<String> = listOf("en"),
    val searchQueryText: String = "",
    val results: List<ScoredSubtitle> = emptyList(),
    val bestMatchId: String? = null,
    val cachedSubtitles: List<CachedSubtitle> = emptyList(),
    val selectedSubtitleId: String? = null,
    val selectedSubtitleType: SelectedSubtitleType = SelectedSubtitleType.NONE,
    val selectedSubtitleName: String? = null,
    val isSignedIn: Boolean = false,
    val username: String? = null,
    val downloadsRemaining: Int? = null,
    val downloadsResetTime: String? = null,
    val error: SubtitleError? = null,
    val errorMessage: String? = null,
    val delayMs: Long = 0L,
    val delaySupported: Boolean = false,
    val autoSearchMode: AutoSearchMode = AutoSearchMode.OFF
)
