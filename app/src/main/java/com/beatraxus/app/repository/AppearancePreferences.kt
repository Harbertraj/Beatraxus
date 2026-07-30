package com.beatraxus.app.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.beatraxus.app.model.AppearanceConfig
import com.beatraxus.app.model.NowPlayingBackgroundMode
import com.beatraxus.app.model.AlbumArtTransform
import com.beatraxus.app.model.QualityBadgeStyle
import com.beatraxus.app.model.NowPlayingIconStyle
import com.beatraxus.app.model.SeekbarStyle
import com.beatraxus.app.utils.DeviceUtils
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "appearance_settings")

class AppearancePreferences(context: Context) {
    private val dataStore = context.dataStore

    val appearanceConfig: Flow<AppearanceConfig> = dataStore.data.map { preferences ->
        val isClassic = DeviceUtils.isClassicDevice()
        val defaultMode = if (isClassic) NowPlayingBackgroundMode.BLACK.name else NowPlayingBackgroundMode.BLUR.name
        val defaultNowPlayingMode = if (isClassic) NowPlayingBackgroundMode.SOLID.name else NowPlayingBackgroundMode.BLUR.name

        AppearanceConfig(
            nowPlayingBackgroundMode = NowPlayingBackgroundMode.valueOf(
                preferences[NOW_PLAYING_BACKGROUND_MODE] ?: defaultNowPlayingMode
            ),
            nowPlayingSolidColorIntensity = preferences[SOLID_COLOR_INTENSITY] ?: 0.6f,
            nowPlayingSolidColorDarkness = preferences[SOLID_COLOR_DARKNESS] ?: 0.4f,
            nowPlayingBlurIntensity = preferences[BLUR_INTENSITY] ?: 210f,
            nowPlayingBlurDarkness = preferences[BLUR_DARKNESS] ?: 0.3f,
            albumArtTransform = AlbumArtTransform.valueOf(
                preferences[ALBUM_ART_TRANSFORM] ?: AlbumArtTransform.NONE.name
            ),
            qualityBadgeStyle = QualityBadgeStyle.valueOf(
                preferences[QUALITY_BADGE_STYLE] ?: QualityBadgeStyle.GLASSMORPHIC.name
            ),
            nowPlayingIconStyle = NowPlayingIconStyle.valueOf(
                preferences[NOW_PLAYING_ICON_STYLE] ?: NowPlayingIconStyle.FILLED.name
            ),
            seekbarStyle = SeekbarStyle.valueOf(
                preferences[SEEKBAR_STYLE] ?: SeekbarStyle.WAVEFORM.name
            ),

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

            // Main Screen Background
            mainBackgroundMode = NowPlayingBackgroundMode.valueOf(
                preferences[MAIN_BACKGROUND_MODE] ?: defaultMode
            ),
            mainSolidColorIntensity = preferences[MAIN_SOLID_COLOR_INTENSITY] ?: 0.6f,
            mainSolidColorDarkness = preferences[MAIN_SOLID_COLOR_DARKNESS] ?: 0.4f,
            mainBlurIntensity = preferences[MAIN_BLUR_INTENSITY] ?: 120f,
            mainBlurDarkness = preferences[MAIN_BLUR_DARKNESS] ?: 0.5f,

            // Home Screen Background
            homeBackgroundMode = NowPlayingBackgroundMode.valueOf(
                preferences[HOME_BACKGROUND_MODE] ?: defaultMode
            ),
            homeSolidColorIntensity = preferences[HOME_SOLID_COLOR_INTENSITY] ?: 0.6f,
            homeSolidColorDarkness = preferences[HOME_SOLID_COLOR_DARKNESS] ?: 0.4f,
            homeBlurIntensity = preferences[HOME_BLUR_INTENSITY] ?: 120f,
            homeBlurDarkness = preferences[HOME_BLUR_DARKNESS] ?: 0.5f,

            // Settings Screen Background
            settingsBackgroundMode = NowPlayingBackgroundMode.valueOf(
                preferences[SETTINGS_BACKGROUND_MODE] ?: defaultMode
            ),
            settingsSolidColorIntensity = preferences[SETTINGS_SOLID_COLOR_INTENSITY] ?: 0.6f,
            settingsSolidColorDarkness = preferences[SETTINGS_SOLID_COLOR_DARKNESS] ?: 0.4f,
            settingsBlurIntensity = preferences[SETTINGS_BLUR_INTENSITY] ?: 120f,
            settingsBlurDarkness = preferences[SETTINGS_BLUR_DARKNESS] ?: 0.5f,

            // Mini Player Background
            miniPlayerBackgroundMode = NowPlayingBackgroundMode.valueOf(
                preferences[MINI_PLAYER_BACKGROUND_MODE] ?: defaultNowPlayingMode
            ),
            miniPlayerSolidColorIntensity = preferences[MINI_PLAYER_SOLID_COLOR_INTENSITY] ?: 0.6f,
            miniPlayerSolidColorDarkness = preferences[MINI_PLAYER_SOLID_COLOR_DARKNESS] ?: 0.4f,
            miniPlayerBlurIntensity = preferences[MINI_PLAYER_BLUR_INTENSITY] ?: 70f,
            miniPlayerBlurDarkness = preferences[MINI_PLAYER_BLUR_DARKNESS] ?: 0.5f,

            homeScreenSectionsOrder = preferences[HOME_SCREEN_SECTIONS_ORDER]?.split(",")?.filter { it.isNotBlank() } ?: listOf(
                "GREETING", "ACTION_CHIPS", "CLOUD_LIBRARY", "MOODS",
                "MADE_FOR_YOU", "LISTEN_AGAIN", "RECENTLY_ADDED",
                "YOUR_FAVORITES", "FEATURED_ALBUMS", "ARTISTS_YOU_LOVE", "YOUR_PLAYLISTS"
            )
        )
    }

    suspend fun setNowPlayingBackgroundMode(mode: NowPlayingBackgroundMode) {
        dataStore.edit { it[NOW_PLAYING_BACKGROUND_MODE] = mode.name }
    }

    suspend fun setNowPlayingSolidColorIntensity(value: Float) {
        dataStore.edit { it[SOLID_COLOR_INTENSITY] = value }
    }

    suspend fun setNowPlayingSolidColorDarkness(value: Float) {
        dataStore.edit { it[SOLID_COLOR_DARKNESS] = value }
    }

    suspend fun setNowPlayingBlurIntensity(value: Float) {
        dataStore.edit { it[BLUR_INTENSITY] = value }
    }

    suspend fun setNowPlayingBlurDarkness(value: Float) {
        dataStore.edit { it[BLUR_DARKNESS] = value }
    }

    suspend fun setAlbumArtTransform(transform: AlbumArtTransform) {
        dataStore.edit { it[ALBUM_ART_TRANSFORM] = transform.name }
    }

    suspend fun setSeekbarStyle(style: SeekbarStyle) {
        dataStore.edit { it[SEEKBAR_STYLE] = style.name }
    }

    suspend fun setQualityBadgeStyle(style: QualityBadgeStyle) {
        dataStore.edit { it[QUALITY_BADGE_STYLE] = style.name }
    }

    suspend fun setNowPlayingIconStyle(style: NowPlayingIconStyle) {
        dataStore.edit { it[NOW_PLAYING_ICON_STYLE] = style.name }
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

    // Main Screen Background Setters
    suspend fun setMainBackgroundMode(mode: NowPlayingBackgroundMode) {
        dataStore.edit { it[MAIN_BACKGROUND_MODE] = mode.name }
    }

    suspend fun setMainSolidColorIntensity(value: Float) {
        dataStore.edit { it[MAIN_SOLID_COLOR_INTENSITY] = value }
    }

    suspend fun setMainSolidColorDarkness(value: Float) {
        dataStore.edit { it[MAIN_SOLID_COLOR_DARKNESS] = value }
    }

    suspend fun setMainBlurIntensity(value: Float) {
        dataStore.edit { it[MAIN_BLUR_INTENSITY] = value }
    }

    suspend fun setMainBlurDarkness(value: Float) {
        dataStore.edit { it[MAIN_BLUR_DARKNESS] = value }
    }

    // Home Screen Background Setters
    suspend fun setHomeBackgroundMode(mode: NowPlayingBackgroundMode) {
        dataStore.edit { it[HOME_BACKGROUND_MODE] = mode.name }
    }

    suspend fun setHomeSolidColorIntensity(value: Float) {
        dataStore.edit { it[HOME_SOLID_COLOR_INTENSITY] = value }
    }

    suspend fun setHomeSolidColorDarkness(value: Float) {
        dataStore.edit { it[HOME_SOLID_COLOR_DARKNESS] = value }
    }

    suspend fun setHomeBlurIntensity(value: Float) {
        dataStore.edit { it[HOME_BLUR_INTENSITY] = value }
    }

    suspend fun setHomeBlurDarkness(value: Float) {
        dataStore.edit { it[HOME_BLUR_DARKNESS] = value }
    }

    // Settings Screen Background Setters
    suspend fun setSettingsBackgroundMode(mode: NowPlayingBackgroundMode) {
        dataStore.edit { it[SETTINGS_BACKGROUND_MODE] = mode.name }
    }

    suspend fun setSettingsSolidColorIntensity(value: Float) {
        dataStore.edit { it[SETTINGS_SOLID_COLOR_INTENSITY] = value }
    }

    suspend fun setSettingsSolidColorDarkness(value: Float) {
        dataStore.edit { it[SETTINGS_SOLID_COLOR_DARKNESS] = value }
    }

    suspend fun setSettingsBlurIntensity(value: Float) {
        dataStore.edit { it[SETTINGS_BLUR_INTENSITY] = value }
    }

    suspend fun setSettingsBlurDarkness(value: Float) {
        dataStore.edit { it[SETTINGS_BLUR_DARKNESS] = value }
    }

    // Mini Player Background Setters
    suspend fun setMiniPlayerBackgroundMode(mode: NowPlayingBackgroundMode) {
        dataStore.edit { it[MINI_PLAYER_BACKGROUND_MODE] = mode.name }
    }

    suspend fun setMiniPlayerSolidColorIntensity(value: Float) {
        dataStore.edit { it[MINI_PLAYER_SOLID_COLOR_INTENSITY] = value }
    }

    suspend fun setMiniPlayerSolidColorDarkness(value: Float) {
        dataStore.edit { it[MINI_PLAYER_SOLID_COLOR_DARKNESS] = value }
    }

    suspend fun setMiniPlayerBlurIntensity(value: Float) {
        dataStore.edit { it[MINI_PLAYER_BLUR_INTENSITY] = value }
    }

    suspend fun setMiniPlayerBlurDarkness(value: Float) {
        dataStore.edit { it[MINI_PLAYER_BLUR_DARKNESS] = value }
    }

    suspend fun setHomeScreenSectionsOrder(order: List<String>) {
        dataStore.edit { it[HOME_SCREEN_SECTIONS_ORDER] = order.joinToString(",") }
    }

    suspend fun resetNowPlayingBackground() {
        dataStore.edit {
            it[SOLID_COLOR_INTENSITY] = 0.6f
            it[SOLID_COLOR_DARKNESS] = 0.4f
            it[BLUR_INTENSITY] = 210f
            it[BLUR_DARKNESS] = 0.3f
        }
    }

    suspend fun resetMainBackground() {
        dataStore.edit {
            it[MAIN_SOLID_COLOR_INTENSITY] = 0.6f
            it[MAIN_SOLID_COLOR_DARKNESS] = 0.4f
            it[MAIN_BLUR_INTENSITY] = 120f
            it[MAIN_BLUR_DARKNESS] = 0.5f
        }
    }

    suspend fun resetHomeBackground() {
        dataStore.edit {
            it[HOME_SOLID_COLOR_INTENSITY] = 0.6f
            it[HOME_SOLID_COLOR_DARKNESS] = 0.4f
            it[HOME_BLUR_INTENSITY] = 120f
            it[HOME_BLUR_DARKNESS] = 0.5f
        }
    }

    suspend fun resetSettingsBackground() {
        dataStore.edit {
            it[SETTINGS_SOLID_COLOR_INTENSITY] = 0.6f
            it[SETTINGS_SOLID_COLOR_DARKNESS] = 0.4f
            it[SETTINGS_BLUR_INTENSITY] = 120f
            it[SETTINGS_BLUR_DARKNESS] = 0.5f
        }
    }

    suspend fun resetMiniPlayerBackground() {
        dataStore.edit {
            it[MINI_PLAYER_SOLID_COLOR_INTENSITY] = 0.6f
            it[MINI_PLAYER_SOLID_COLOR_DARKNESS] = 0.4f
            it[MINI_PLAYER_BLUR_INTENSITY] = 70f
            it[MINI_PLAYER_BLUR_DARKNESS] = 0.5f
        }
    }

    companion object {
        private val NOW_PLAYING_BACKGROUND_MODE = stringPreferencesKey("now_playing_background_mode")
        private val SOLID_COLOR_INTENSITY = floatPreferencesKey("solid_color_intensity")
        private val SOLID_COLOR_DARKNESS = floatPreferencesKey("solid_color_darkness")
        private val BLUR_INTENSITY = floatPreferencesKey("blur_intensity")
        private val BLUR_DARKNESS = floatPreferencesKey("blur_darkness")
        private val ALBUM_ART_TRANSFORM = stringPreferencesKey("album_art_transform")
        private val QUALITY_BADGE_STYLE = stringPreferencesKey("quality_badge_style")
        private val NOW_PLAYING_ICON_STYLE = stringPreferencesKey("now_playing_icon_style")
        private val SEEKBAR_STYLE = stringPreferencesKey("seekbar_style")

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

        // Main Screen Background
        private val MAIN_BACKGROUND_MODE = stringPreferencesKey("main_background_mode")
        private val MAIN_SOLID_COLOR_INTENSITY = floatPreferencesKey("main_solid_color_intensity")
        private val MAIN_SOLID_COLOR_DARKNESS = floatPreferencesKey("main_solid_color_darkness")
        private val MAIN_BLUR_INTENSITY = floatPreferencesKey("main_blur_intensity")
        private val MAIN_BLUR_DARKNESS = floatPreferencesKey("main_blur_darkness")

        // Home Screen Background
        private val HOME_BACKGROUND_MODE = stringPreferencesKey("home_background_mode")
        private val HOME_SOLID_COLOR_INTENSITY = floatPreferencesKey("home_solid_color_intensity")
        private val HOME_SOLID_COLOR_DARKNESS = floatPreferencesKey("home_solid_color_darkness")
        private val HOME_BLUR_INTENSITY = floatPreferencesKey("home_blur_intensity")
        private val HOME_BLUR_DARKNESS = floatPreferencesKey("home_blur_darkness")

        // Settings Screen Background
        private val SETTINGS_BACKGROUND_MODE = stringPreferencesKey("settings_background_mode")
        private val SETTINGS_SOLID_COLOR_INTENSITY = floatPreferencesKey("settings_solid_color_intensity")
        private val SETTINGS_SOLID_COLOR_DARKNESS = floatPreferencesKey("settings_solid_color_darkness")
        private val SETTINGS_BLUR_INTENSITY = floatPreferencesKey("settings_blur_intensity")
        private val SETTINGS_BLUR_DARKNESS = floatPreferencesKey("settings_blur_darkness")

        // Mini Player Background
        private val MINI_PLAYER_BACKGROUND_MODE = stringPreferencesKey("mini_player_background_mode")
        private val MINI_PLAYER_SOLID_COLOR_INTENSITY = floatPreferencesKey("mini_player_solid_color_intensity")
        private val MINI_PLAYER_SOLID_COLOR_DARKNESS = floatPreferencesKey("mini_player_solid_color_darkness")
        private val MINI_PLAYER_BLUR_INTENSITY = floatPreferencesKey("mini_player_blur_intensity")
        private val MINI_PLAYER_BLUR_DARKNESS = floatPreferencesKey("mini_player_blur_darkness")

        private val HOME_SCREEN_SECTIONS_ORDER = stringPreferencesKey("home_screen_sections_order")
    }
}