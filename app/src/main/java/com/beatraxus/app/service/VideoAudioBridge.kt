package com.beatraxus.app.service

import com.beatraxus.app.engine.AudioEngine

/**
 * Clean bridge API for [com.beatraxus.app.viewmodel.VideoPlayerViewModel] to access
 * the running [AudioEngine] instance hosted by [AudioPlaybackService].
 */
object VideoAudioBridge {
    fun getAudioEngine(): AudioEngine? {
        return AudioPlaybackService.instance?.getAudioEngine()
    }
}
