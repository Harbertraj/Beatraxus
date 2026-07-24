package com.beatraxus.app.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.beatraxus.app.model.AppearanceConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "appearance_settings")

class AppearancePreferences(context: Context) {
    private val dataStore = context.dataStore

    val appearanceConfig: Flow<AppearanceConfig> = dataStore.data.map { preferences ->
        AppearanceConfig(
            showMiniPlayer = preferences[SHOW_MINI_PLAYER] ?: true,
            showNowPlayingBlurBackground = preferences[SHOW_BLUR_BACKGROUND] ?: true,
            showAudioQualityBadge = preferences[SHOW_QUALITY_BADGE] ?: true,
            showAudioPipelineOverlay = preferences[SHOW_PIPELINE_OVERLAY] ?: true,
            showTechnicalInfoPanel = preferences[SHOW_TECHNICAL_INFO] ?: true,
            showLyricsButton = preferences[SHOW_LYRICS_BUTTON] ?: true
        )
    }

    suspend fun setShowMiniPlayer(value: Boolean) {
        dataStore.edit { it[SHOW_MINI_PLAYER] = value }
    }

    suspend fun setShowNowPlayingBlurBackground(value: Boolean) {
        dataStore.edit { it[SHOW_BLUR_BACKGROUND] = value }
    }

    suspend fun setShowAudioQualityBadge(value: Boolean) {
        dataStore.edit { it[SHOW_QUALITY_BADGE] = value }
    }

    suspend fun setShowAudioPipelineOverlay(value: Boolean) {
        dataStore.edit { it[SHOW_PIPELINE_OVERLAY] = value }
    }

    suspend fun setShowTechnicalInfoPanel(value: Boolean) {
        dataStore.edit { it[SHOW_TECHNICAL_INFO] = value }
    }

    suspend fun setShowLyricsButton(value: Boolean) {
        dataStore.edit { it[SHOW_LYRICS_BUTTON] = value }
    }

    companion object {
        private val SHOW_MINI_PLAYER = booleanPreferencesKey("show_mini_player")
        private val SHOW_BLUR_BACKGROUND = booleanPreferencesKey("show_blur_background")
        private val SHOW_QUALITY_BADGE = booleanPreferencesKey("show_quality_badge")
        private val SHOW_PIPELINE_OVERLAY = booleanPreferencesKey("show_pipeline_overlay")
        private val SHOW_TECHNICAL_INFO = booleanPreferencesKey("show_technical_info")
        private val SHOW_LYRICS_BUTTON = booleanPreferencesKey("show_lyrics_button")
    }
}
