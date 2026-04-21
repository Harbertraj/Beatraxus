package com.beatflowy.app.service

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import com.beatflowy.app.engine.AudioEngine
import com.beatflowy.app.engine.AudioTrackOutput
import com.beatflowy.app.model.Song
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import com.beatflowy.app.MainActivity
import com.beatflowy.app.R

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class AudioPlaybackService : Service() {
    private val binder = LocalBinder()
    private lateinit var engine: AudioEngine
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    inner class LocalBinder : Binder() {
        fun getService(): AudioPlaybackService = this@AudioPlaybackService
    }

    override fun onCreate() {
        super.onCreate()
        engine = AudioEngine(this, AudioTrackOutput())
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
        
        // Handle song completion for Loop/Repeat and Next
        serviceScope.launch {
            engine.onCompletion.collect {
                handleCompletion()
            }
        }
    }

    private fun handleCompletion() {
        val currentRepeatMode = engine.playbackStateFlow.value.repeatMode
        when (currentRepeatMode) {
            com.beatflowy.app.engine.RepeatMode.ONE -> {
                val song = engine.playbackStateFlow.value.currentSong
                if (song != null) engine.play(song)
            }
            com.beatflowy.app.engine.RepeatMode.ALL -> {
                next()
            }
            com.beatflowy.app.engine.RepeatMode.OFF -> {
                if (playlist.isNotEmpty() && currentIndex < playlist.size - 1) {
                    next()
                } else {
                    engine.stop()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder = binder

    private var originalPlaylist: List<Song> = emptyList()
    private var playlist: List<Song> = emptyList()
    private var currentIndex: Int = -1

    private val _upcomingSongs = MutableStateFlow<List<Song>>(emptyList())
    val upcomingSongs: StateFlow<List<Song>> = _upcomingSongs.asStateFlow()

    private fun updateUpcomingSongs() {
        if (playlist.isEmpty() || currentIndex >= playlist.size - 1) {
            _upcomingSongs.value = emptyList()
        } else {
            _upcomingSongs.value = playlist.subList(currentIndex + 1, playlist.size)
        }
    }

    fun togglePlayPause() {
        if (engine.playbackStateFlow.value.isPlaying) {
            engine.stop()
        } else {
            val song = engine.playbackStateFlow.value.currentSong
            if (song != null) {
                // To support "resume", we should not call play(song) which resets position
                // Instead, we just need the engine to start decoding/playing again
                engine.resume()
            } else if (playlist.isNotEmpty()) {
                currentIndex = 0
                engine.play(playlist[currentIndex])
            }
        }
    }

    fun next() {
        if (playlist.isEmpty()) return
        currentIndex = (currentIndex + 1) % playlist.size
        engine.play(playlist[currentIndex])
        updateUpcomingSongs()
    }

    fun previous() {
        if (playlist.isEmpty()) return
        currentIndex = if (currentIndex <= 0) playlist.size - 1 else currentIndex - 1
        engine.play(playlist[currentIndex])
        updateUpcomingSongs()
    }

    fun seekTo(pos: Long) {
        engine.seekTo(pos)
    }

    fun setShuffleMode(enabled: Boolean) {
        if (engine.playbackStateFlow.value.shuffleMode == enabled) return
        
        val currentSong = engine.playbackStateFlow.value.currentSong
        if (enabled) {
            playlist = playlist.shuffled()
            currentIndex = playlist.indexOf(currentSong)
        } else {
            playlist = originalPlaylist
            currentIndex = playlist.indexOf(currentSong)
        }
        updateUpcomingSongs()
        engine.setShuffleMode(enabled)
    }

    fun toggleShuffle() {
        setShuffleMode(!engine.playbackStateFlow.value.shuffleMode)
    }

    fun toggleRepeat() {
        val current = engine.playbackStateFlow.value.repeatMode
        val next = when (current) {
            com.beatflowy.app.engine.RepeatMode.OFF -> com.beatflowy.app.engine.RepeatMode.ALL
            com.beatflowy.app.engine.RepeatMode.ALL -> com.beatflowy.app.engine.RepeatMode.ONE
            com.beatflowy.app.engine.RepeatMode.ONE -> com.beatflowy.app.engine.RepeatMode.OFF
        }
        engine.setRepeatMode(next)
    }
    fun setEqualizerEnabled(enabled: Boolean) {}
    fun setEqBandGain(band: Int, gain: Float) {}
    fun updateScanningProgress(progress: Float, count: Int, completed: Boolean) {}
    fun getNextSong(): Song? = if (playlist.isNotEmpty() && currentIndex < playlist.size - 1) playlist[currentIndex + 1] else null
    
    fun getUpcomingSongs(): List<Song> {
        if (playlist.isEmpty() || currentIndex >= playlist.size - 1) return emptyList()
        return playlist.subList(currentIndex + 1, playlist.size)
    }

    fun removeFromQueue(songId: String) {
        val indexToRemove = playlist.indexOfFirst { it.id == songId }
        if (indexToRemove != -1 && indexToRemove != currentIndex) {
            val newList = playlist.toMutableList()
            newList.removeAt(indexToRemove)
            playlist = newList
            if (indexToRemove < currentIndex) {
                currentIndex--
            }
            updateUpcomingSongs()
        }
    }
    
    fun moveInQueue(from: Int, to: Int) {
        if (from !in playlist.indices || to !in playlist.indices) return
        val mutable = playlist.toMutableList()
        val item = mutable.removeAt(from)
        mutable.add(to, item)
        playlist = mutable
        
        // Update currentIndex if the current song was moved or affected by the move
        if (from == currentIndex) {
            currentIndex = to
        } else if (from < currentIndex && to >= currentIndex) {
            currentIndex--
        } else if (from > currentIndex && to <= currentIndex) {
            currentIndex++
        }
        updateUpcomingSongs()
    }

    fun moveInUpcomingQueue(from: Int, to: Int) {
        if (currentIndex < 0) return
        moveInQueue(currentIndex + 1 + from, currentIndex + 1 + to)
    }

    fun playFromQueue(songId: String) {
        val index = playlist.indexOfFirst { it.id == songId }
        if (index != -1) {
            currentIndex = index
            engine.play(playlist[currentIndex])
            updateUpcomingSongs()
        }
    }

    fun playNext(song: Song) {
        val newList = playlist.toMutableList()
        val existingIndex = newList.indexOfFirst { it.id == song.id }
        if (existingIndex != -1) {
            newList.removeAt(existingIndex)
            if (existingIndex < currentIndex) {
                currentIndex--
            }
        }
        newList.add(currentIndex + 1, song)
        playlist = newList
        updateUpcomingSongs()
    }

    fun addToQueue(song: Song) {
        if (playlist.any { it.id == song.id }) return
        val newList = playlist.toMutableList()
        newList.add(song)
        playlist = newList
        updateUpcomingSongs()
    }
    
    fun playList(songs: List<Song>, startIndex: Int) {
        originalPlaylist = songs
        playlist = if (engine.playbackStateFlow.value.shuffleMode) songs.shuffled() else songs
        currentIndex = if (startIndex in songs.indices) {
            playlist.indexOf(songs[startIndex])
        } else 0

        if (playlist.isNotEmpty()) {
            engine.play(playlist[currentIndex])
        }
        updateUpcomingSongs()
    }

    val audioStateFlow get() = engine.audioStateFlow
    val playbackStateFlow get() = engine.playbackStateFlow
    val currentPositionMs get() = engine.currentPositionMs()

    fun playSong(song: Song) {
        engine.play(song)
    }

    fun stopSong() {
        engine.stop()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Playback Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Beatraxus")
            .setContentText("Music player is running")
            .setSmallIcon(R.drawable.app_icon_fg)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "playback_channel"
        private const val NOTIFICATION_ID = 1
    }

    override fun onDestroy() {
        serviceScope.cancel()
        engine.release()
        super.onDestroy()
    }
}
