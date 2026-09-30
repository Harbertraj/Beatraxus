package com.beatraxus.app.service

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.beatraxus.app.engine.AudioEngine
import kotlinx.coroutines.delay

/**
 * Clean bridge API for [com.beatraxus.app.viewmodel.VideoPlayerViewModel] to access
 * the running [AudioEngine] instance hosted by [AudioPlaybackService].
 */
object VideoAudioBridge {
    fun getAudioEngine(): AudioEngine? {
        return AudioPlaybackService.instance?.getAudioEngine()
    }

    suspend fun awaitAudioEngine(context: Context, timeoutMs: Long = 3000L): AudioEngine? {
        getAudioEngine()?.let { return it }

        Log.d("VideoAudioBridge", "Audio Engine not running, starting AudioPlaybackService...")
        try {
            val intent = Intent(context, AudioPlaybackService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (e: Exception) {
            Log.w("VideoAudioBridge", "Failed to start AudioPlaybackService", e)
        }

        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            getAudioEngine()?.let { return it }
            delay(100)
        }
        return getAudioEngine()
    }
}
