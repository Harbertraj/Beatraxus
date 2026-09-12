package com.beatraxus.app.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Global coordinator to prevent background tasks (like AI scan or video thumbnails)
 * from competing with active media playback for hardware decoders.
 */
object PlaybackGlobalState {
    private val _isAnyPlaybackActive = MutableStateFlow(false)
    val isAnyPlaybackActive: StateFlow<Boolean> = _isAnyPlaybackActive.asStateFlow()

    fun setPlaybackActive(active: Boolean) {
        _isAnyPlaybackActive.value = active
    }
}
