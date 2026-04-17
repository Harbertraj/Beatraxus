package com.beatflowy.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.beatflowy.app.model.PlayerUiState
import com.beatflowy.app.model.Song
import com.beatflowy.app.repository.MusicRepository
import com.beatflowy.app.service.AudioPlaybackService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class PlayerViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = MusicRepository(application)

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    private val _songs = MutableStateFlow<List<Song>>(emptyList())
    val songs: StateFlow<List<Song>> = _songs.asStateFlow()

    private var service: AudioPlaybackService? = null
    private var progressJob: Job? = null

    fun attachService(svc: AudioPlaybackService) {
        service = svc
        viewModelScope.launch {
            svc.audioStateFlow.collect { audioState ->
                _uiState.update { it.copy(
                    inputSampleRate  = audioState.inputSampleRateHz,
                    outputSampleRate = audioState.outputSampleRateHz,
                    outputDevice     = audioState.outputDevice
                )}
            }
        }
        viewModelScope.launch {
            svc.playbackStateFlow.collect { pbState ->
                _uiState.update { it.copy(
                    isPlaying   = pbState.isPlaying,
                    isBuffering = pbState.isBuffering,
                    currentSong = pbState.currentSong
                )}
                if (pbState.isPlaying) startProgressPolling() else stopProgressPolling()
            }
        }
    }

    fun loadLibrary() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingLibrary = true) }
            try {
                _songs.value = repository.scanAudioFiles()
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "Failed to load library: ${e.message}") }
            } finally {
                _uiState.update { it.copy(isLoadingLibrary = false) }
            }
        }
    }

    fun onPermissionDenied() {
        _uiState.update { it.copy(permissionDenied = true) }
    }

    fun playSong(song: Song) {
        viewModelScope.launch {
            _uiState.update { it.copy(currentSong = song, progressMs = 0L) }
            service?.playSong(song) ?: run {
                val outRate = computeOutputRate(song.sampleRateHz, _uiState.value.resamplingEnabled)
                _uiState.update { it.copy(
                    inputSampleRate = song.sampleRateHz,
                    outputSampleRate = outRate
                )}
            }
        }
    }

    fun togglePlayPause() {
        service?.togglePlayPause()
            ?: _uiState.update { it.copy(isPlaying = !it.isPlaying) }
    }

    fun skipToNext() {
        val list  = _songs.value
        val index = list.indexOfFirst { it.id == _uiState.value.currentSong?.id }
        if (index >= 0 && index < list.size - 1) playSong(list[index + 1])
    }

    fun skipToPrevious() {
        val list  = _songs.value
        val index = list.indexOfFirst { it.id == _uiState.value.currentSong?.id }
        if (_uiState.value.progressMs > 3000L) seekTo(0L)
        else if (index > 0) playSong(list[index - 1])
    }

    fun seekTo(positionMs: Long) {
        service?.seekTo(positionMs)
        _uiState.update { it.copy(progressMs = positionMs) }
    }

    fun toggleResampling() {
        val enabled = !_uiState.value.resamplingEnabled
        _uiState.update { it.copy(
            resamplingEnabled = enabled,
            outputSampleRate  = computeOutputRate(_uiState.value.inputSampleRate, enabled)
        )}
        service?.setResamplingEnabled(enabled)
    }

    fun toggleEqualizer() {
        val enabled = !_uiState.value.equalizerEnabled
        _uiState.update { it.copy(equalizerEnabled = enabled) }
        service?.setEqualizerEnabled(enabled)
    }

    fun setEqBandGain(band: Int, gain: Float) {
        val newGains = _uiState.value.eqGains.copyOf()
        newGains[band] = gain.coerceIn(-12f, 12f)
        _uiState.update { it.copy(eqGains = newGains) }
        service?.setEqBandGain(band, gain)
    }

    private fun startProgressPolling() {
        progressJob?.cancel()
        progressJob = viewModelScope.launch {
            while (true) {
                delay(250)
                val pos = service?.currentPositionMs ?: break
                _uiState.update { it.copy(progressMs = pos) }
            }
        }
    }

    private fun stopProgressPolling() {
        progressJob?.cancel()
        progressJob = null
    }

    private fun computeOutputRate(inputHz: Int, resamplingOn: Boolean): Int {
        if (!resamplingOn) return inputHz
        return when {
            inputHz <= 44_100 -> 48_000
            inputHz <= 48_000 -> 96_000
            else              -> inputHz
        }
    }

    override fun onCleared() {
        super.onCleared()
        progressJob?.cancel()
    }
}

class PlayerViewModelFactory(private val application: Application) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(PlayerViewModel::class.java)) {
            return PlayerViewModel(application) as T
        }
        throw IllegalArgumentException("Unknown ViewModel: ${modelClass.name}")
    }
}
