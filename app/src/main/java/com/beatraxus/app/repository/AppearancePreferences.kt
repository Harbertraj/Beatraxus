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
            showLyricsButton = preferences[SHOW_LYRICS_BUTTON] ?: true,

            // Home Screen Sections
            showGreetingHeader = preferences[SHOW_GREETING_HEADER] ?: true,
            showBrowseByMood = preferences[SHOW_BROWSE_BY_MOOD] ?: true,
            showMadeForYou = preferences[SHOW_MADE_FOR_YOU] ?: true,
            showListenAgain = preferences[SHOW_LISTEN_AGAIN] ?: true,
            showRecentlyAddedHome = preferences[SHOW_RECENTLY_ADDED_HOME] ?: true,
            showYourFavoritesHome = preferences[SHOW_YOUR_FAVORITES_HOME] ?: true,
            showFeaturedAlbums = preferences[SHOW_FEATURED_ALBUMS] ?: true,
            showArtistsYouLove = preferences[SHOW_ARTISTS_YOU_LOVE] ?: true,
            showYourPlaylists = preferences[SHOW_YOUR_PLAYLISTS] ?: true,

            // Now Playing Shortcuts
            showFavoriteButton = preferences[SHOW_FAVORITE_BUTTON] ?: true,
            showEqualizerShortcut = preferences[SHOW_EQUALIZER_SHORTCUT] ?: true,
            showQueueButton = preferences[SHOW_QUEUE_BUTTON] ?: true,
            showSleepTimerIcon = preferences[SHOW_SLEEP_TIMER_ICON] ?: true
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

    // Home Screen Sections Setters
    suspend fun setShowGreetingHeader(value: Boolean) {
        dataStore.edit { it[SHOW_GREETING_HEADER] = value }
    }

    suspend fun setShowBrowseByMood(value: Boolean) {
        dataStore.edit { it[SHOW_BROWSE_BY_MOOD] = value }
    }

    suspend fun setShowMadeForYou(value: Boolean) {
        dataStore.edit { it[SHOW_MADE_FOR_YOU] = value }
    }

    suspend fun setShowListenAgain(value: Boolean) {
        dataStore.edit { it[SHOW_LISTEN_AGAIN] = value }
    }

    suspend fun setShowRecentlyAddedHome(value: Boolean) {
        dataStore.edit { it[SHOW_RECENTLY_ADDED_HOME] = value }
    }

    suspend fun setShowYourFavoritesHome(value: Boolean) {
        dataStore.edit { it[SHOW_YOUR_FAVORITES_HOME] = value }
    }

    suspend fun setShowFeaturedAlbums(value: Boolean) {
        dataStore.edit { it[SHOW_FEATURED_ALBUMS] = value }
    }

    suspend fun setShowArtistsYouLove(value: Boolean) {
        dataStore.edit { it[SHOW_ARTISTS_YOU_LOVE] = value }
    }

    suspend fun setShowYourPlaylists(value: Boolean) {
        dataStore.edit { it[SHOW_YOUR_PLAYLISTS] = value }
    }

    // Now Playing Shortcuts Setters
    suspend fun setShowFavoriteButton(value: Boolean) {
        dataStore.edit { it[SHOW_FAVORITE_BUTTON] = value }
    }

    suspend fun setShowEqualizerShortcut(value: Boolean) {
        dataStore.edit { it[SHOW_EQUALIZER_SHORTCUT] = value }
    }

    suspend fun setShowQueueButton(value: Boolean) {
        dataStore.edit { it[SHOW_QUEUE_BUTTON] = value }
    }

    suspend fun setShowSleepTimerIcon(value: Boolean) {
        dataStore.edit { it[SHOW_SLEEP_TIMER_ICON] = value }
    }

    companion object {
        private val SHOW_MINI_PLAYER = booleanPreferencesKey("show_mini_player")
        private val SHOW_BLUR_BACKGROUND = booleanPreferencesKey("show_blur_background")
        private val SHOW_QUALITY_BADGE = booleanPreferencesKey("show_quality_badge")
        private val SHOW_PIPELINE_OVERLAY = booleanPreferencesKey("show_pipeline_overlay")
        private val SHOW_TECHNICAL_INFO = booleanPreferencesKey("show_technical_info")
        private val SHOW_LYRICS_BUTTON = booleanPreferencesKey("show_lyrics_button")

        // Home Screen Sections
        private val SHOW_GREETING_HEADER = booleanPreferencesKey("show_greeting_header")
        private val SHOW_BROWSE_BY_MOOD = booleanPreferencesKey("show_browse_by_mood")
        private val SHOW_MADE_FOR_YOU = booleanPreferencesKey("show_made_for_you")
        private val SHOW_LISTEN_AGAIN = booleanPreferencesKey("show_listen_again")
        private val SHOW_RECENTLY_ADDED_HOME = booleanPreferencesKey("show_recently_added_home")
        private val SHOW_YOUR_FAVORITES_HOME = booleanPreferencesKey("show_your_favorites_home")
        private val SHOW_FEATURED_ALBUMS = booleanPreferencesKey("show_featured_albums")
        private val SHOW_ARTISTS_YOU_LOVE = booleanPreferencesKey("show_artists_you_love")
        private val SHOW_YOUR_PLAYLISTS = booleanPreferencesKey("show_your_playlists")

        // Now Playing Shortcuts
        private val SHOW_FAVORITE_BUTTON = booleanPreferencesKey("show_favorite_button")
        private val SHOW_EQUALIZER_SHORTCUT = booleanPreferencesKey("show_equalizer_shortcut")
        private val SHOW_QUEUE_BUTTON = booleanPreferencesKey("show_queue_button")
        private val SHOW_SLEEP_TIMER_ICON = booleanPreferencesKey("show_sleep_timer_icon")
    }
}
