package com.beatraxus.app.subtitles.viewmodel

import com.beatraxus.app.subtitles.data.CachedSubtitle
import com.beatraxus.app.subtitles.domain.ScoredSubtitle
import com.beatraxus.app.subtitles.domain.SecondarySubtitleStyle
import com.beatraxus.app.subtitles.domain.SubtitleEncodings
import com.beatraxus.app.subtitles.domain.SubtitleError
import com.beatraxus.app.subtitles.domain.SubtitleLanguage

enum class AutoSearchMode(val displayName: String) {
    OFF("Off"),
    ON("On"),
    ON_PREFERRED_LANGUAGE("On (Preferred Language Only)")
}

/** Which subtitle of a dual pair a tap-to-sync (video feature 20) moves. */
enum class SyncTarget {
    MAIN,
    SECOND
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
    val autoSearchMode: AutoSearchMode = AutoSearchMode.OFF,
    // Video feature 19: also download and apply the best match the automatic search finds
    val autoDownload: Boolean = false,
    // Dual subtitles (video feature 22)
    val dualEnabled: Boolean = false,
    val secondarySubtitleName: String? = null,
    val secondaryDelayMs: Long = 0L,
    val secondaryStyle: SecondarySubtitleStyle = SecondarySubtitleStyle(),
    // Custom font and text encoding (video feature 23)
    val encodingId: String = SubtitleEncodings.AUTO,
    val customFontName: String? = null
)
