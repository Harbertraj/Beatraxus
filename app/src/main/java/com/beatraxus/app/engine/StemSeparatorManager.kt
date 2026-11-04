package com.beatraxus.app.engine

import android.content.Context
import android.util.Log
import com.beatraxus.app.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface StemSeparationState {
    object Idle : StemSeparationState
    data class Separating(val songId: String, val progress: Float) : StemSeparationState
    data class Ready(val songId: String) : StemSeparationState
    data class Failed(val songId: String, val errorText: String) : StemSeparationState
}

object StemSeparatorManager {
    private const val TAG = "StemSeparatorManager"

    private val _state = MutableStateFlow<StemSeparationState>(StemSeparationState.Idle)
    val state: StateFlow<StemSeparationState> = _state.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.Default)
    private var activeJob: Job? = null
    private var activeSeparator: StemSeparator? = null
    private var activeSongId: String? = null

    fun getStatusForSong(context: Context, songId: String?): StemSeparationState {
        if (songId == null) return StemSeparationState.Idle
        if (StemCache.isStemsCached(context, songId)) return StemSeparationState.Ready(songId)

        val current = _state.value
        return when (current) {
            is StemSeparationState.Separating -> if (current.songId == songId) current else StemSeparationState.Idle
            is StemSeparationState.Ready -> if (current.songId == songId) current else StemSeparationState.Idle
            is StemSeparationState.Failed -> if (current.songId == songId) current else StemSeparationState.Idle
            else -> StemSeparationState.Idle
        }
    }

    fun requestSeparation(context: Context, song: Song) {
        val songId = song.id
        if (StemCache.isStemsCached(context, songId)) {
            _state.value = StemSeparationState.Ready(songId)
            return
        }

        if (activeSongId == songId && _state.value is StemSeparationState.Separating) {
            return // Already separating this song
        }

        cancelSeparation()
        activeSongId = songId

        activeJob = scope.launch {
            _state.value = StemSeparationState.Separating(songId, 0f)

            val separator = StemSeparator(context.applicationContext)
            activeSeparator = separator

            try {
                // Step 1: Decode song audio to 44.1 kHz stereo float
                val pcm = StemAudioDecoder.decodeToStereoFloat44100(
                    context.applicationContext,
                    song,
                    isCancelled = { activeJob?.isCancelled == true }
                )

                if (pcm == null || activeJob?.isCancelled == true) {
                    if (activeJob?.isCancelled != true) {
                        _state.value = StemSeparationState.Failed(songId, "Failed to decode song audio")
                    } else {
                        _state.value = StemSeparationState.Idle
                    }
                    return@launch
                }

                // Step 2: Separate into 4 stems
                val stems = separator.separate(
                    inputPcm = pcm,
                    progressCallback = { pct ->
                        _state.value = StemSeparationState.Separating(songId, pct)
                    },
                    isCancelled = { activeJob?.isCancelled == true }
                )

                if (stems == null || activeJob?.isCancelled == true) {
                    if (activeJob?.isCancelled != true) {
                        _state.value = StemSeparationState.Failed(songId, "Stem separation failed")
                    } else {
                        _state.value = StemSeparationState.Idle
                    }
                    return@launch
                }

                // Step 3: Cache raw PCM stems
                StemCache.saveStemsToCache(context.applicationContext, songId, stems)
                _state.value = StemSeparationState.Ready(songId)
                Log.i(TAG, "Successfully separated and cached 4 stems for song $songId")

            } catch (e: Exception) {
                Log.e(TAG, "Separation error: ${e.message}", e)
                _state.value = StemSeparationState.Failed(songId, e.message ?: "Separation error")
            } finally {
                separator.release()
                if (activeSeparator == separator) activeSeparator = null
            }
        }
    }

    fun cancelSeparation() {
        activeJob?.cancel()
        activeJob = null
        activeSeparator?.release()
        activeSeparator = null
        activeSongId = null
        _state.value = StemSeparationState.Idle
    }
}
