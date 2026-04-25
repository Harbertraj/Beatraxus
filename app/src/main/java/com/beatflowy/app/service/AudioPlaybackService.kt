package com.beatflowy.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.media.app.NotificationCompat.MediaStyle
import com.beatflowy.app.MainActivity
import com.beatflowy.app.R
import com.beatflowy.app.engine.AudioEngine
import com.beatflowy.app.engine.OutputMode
import com.beatflowy.app.engine.OutputRouteState
import com.beatflowy.app.engine.AudioTrackOutput
import com.beatflowy.app.engine.PlaybackState
import com.beatflowy.app.engine.RepeatMode
import com.beatflowy.app.model.DspConfig
import com.beatflowy.app.model.Song
import com.beatflowy.app.widget.MusicWidgetKeys
import com.beatflowy.app.widget.MusicWidgetLarge
import com.beatflowy.app.widget.MusicWidgetMedium
import com.beatflowy.app.widget.MusicWidgetSmall
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class AudioPlaybackService : Service() {
    private val binder = LocalBinder()
    private lateinit var engine: AudioEngine
    private lateinit var audioOutput: AudioTrackOutput
    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var audioManager: AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val _outputRouteStateFlow = MutableStateFlow(OutputRouteState())
    val outputRouteStateFlow: StateFlow<OutputRouteState> = _outputRouteStateFlow.asStateFlow()
    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            refreshOutputRoute(reconfigure = true)
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            refreshOutputRoute(reconfigure = true)
        }
    }

    private var lastSongId: String? = null
    private var currentAlbumArt: Bitmap? = null

    inner class LocalBinder : Binder() {
        fun getService(): AudioPlaybackService = this@AudioPlaybackService
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioOutput = AudioTrackOutput(this)
        engine = AudioEngine(this, audioOutput)
        refreshOutputRoute()
        audioManager.registerAudioDeviceCallback(audioDeviceCallback, null)

        mediaSession = MediaSessionCompat(this, "AudioPlaybackService").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() { togglePlayPause() }
                override fun onPause() { togglePlayPause() }
                override fun onSkipToNext() { next() }
                override fun onSkipToPrevious() { previous() }
                override fun onSeekTo(pos: Long) { seekTo(pos) }
            })
            isActive = true
        }

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
        
        // Handle song completion for Loop/Repeat and Next
        serviceScope.launch {
            engine.onCompletion.collect {
                handleCompletion()
            }
        }

        // Update notification and widgets when playback state changes
        serviceScope.launch {
            engine.playbackStateFlow
                .collectLatest { state ->
                    val songChanged = state.currentSong?.id != lastSongId
                    
                    if (songChanged) {
                        lastSongId = state.currentSong?.id
                        // Load art in background to not block widget updates
                        serviceScope.launch {
                            loadAlbumArt(state.currentSong)
                            updateNotification()
                        }
                    }

                    // Update widgets and notification immediately
                    updateAllWidgets(state)
                    updateNotification()
                }
        }
    }

    private suspend fun loadAlbumArt(song: Song?) {
        currentAlbumArt = withContext(Dispatchers.IO) {
            val uri = song?.albumArtUri ?: return@withContext null
            try {
                contentResolver.openInputStream(uri)?.use { input ->
                    val original = BitmapFactory.decodeStream(input)
                    if (original != null) {
                        // Scale down to avoid transaction too large exception (max 500x500 is safe)
                        val size = 500
                        val ratio = original.width.toFloat() / original.height.toFloat()
                        val w = if (ratio > 1) size else (size * ratio).toInt()
                        val h = if (ratio > 1) (size / ratio).toInt() else size
                        Bitmap.createScaledBitmap(original, w, h, true)
                    } else null
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    private fun handleCompletion() {
        val currentRepeatMode = engine.playbackStateFlow.value.repeatMode
        when (currentRepeatMode) {
            RepeatMode.ONE -> {
                val song = engine.playbackStateFlow.value.currentSong
                if (song != null) engine.play(song)
            }
            RepeatMode.ALL -> {
                next()
            }
            RepeatMode.OFF -> {
                if (playlist.isNotEmpty() && currentIndex < playlist.size - 1) {
                    next()
                } else {
                    engine.stop()
                    abandonAudioFocus()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY_PAUSE -> togglePlayPause()
            ACTION_NEXT -> next()
            ACTION_PREVIOUS -> previous()
            ACTION_TOGGLE_SHUFFLE -> toggleShuffle()
            ACTION_TOGGLE_REPEAT -> toggleRepeat()
        }
        return START_STICKY
    }

    private fun updateAllWidgets(state: PlaybackState) {
        val song = state.currentSong
        val title = song?.title ?: "Not Playing"
        val artist = song?.artist ?: "Beatraxus"
        val isPlaying = state.isPlaying
        val albumArtUri = song?.albumArtUri?.toString() ?: ""
        val shuffleOn = state.shuffleMode
        val repeatMode = state.repeatMode.name

        // Use a separate scope to ensure updates complete and don't block main thread
        serviceScope.launch(Dispatchers.Default) {
            try {
                val context = this@AudioPlaybackService
                val manager = GlanceAppWidgetManager(context)
                
                val widgetClasses = listOf(
                    MusicWidgetSmall::class.java to MusicWidgetSmall(),
                    MusicWidgetMedium::class.java to MusicWidgetMedium(),
                    MusicWidgetLarge::class.java to MusicWidgetLarge()
                )

                widgetClasses.forEach { (clazz, widget) ->
                    val ids = manager.getGlanceIds(clazz)
                    for (id in ids) {
                        try {
                            updateAppWidgetState(context, PreferencesGlanceStateDefinition, id) { prefs ->
                                prefs.toMutablePreferences().apply {
                                    set(MusicWidgetKeys.TITLE, title)
                                    set(MusicWidgetKeys.ARTIST, artist)
                                    set(MusicWidgetKeys.IS_PLAYING, isPlaying)
                                    set(MusicWidgetKeys.ALBUM_ART_URI, albumArtUri)
                                    set(MusicWidgetKeys.SHUFFLE_ON, shuffleOn)
                                    set(MusicWidgetKeys.REPEAT_MODE, repeatMode)
                                }.toPreferences()
                            }
                            widget.update(context, id)
                        } catch (e: Exception) {
                            Log.e("AudioPlaybackService", "Error updating widget instance $id", e)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("AudioPlaybackService", "Failed to update widgets", e)
            }
        }
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

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                if (engine.playbackStateFlow.value.isPlaying) {
                    togglePlayPause()
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                if (engine.playbackStateFlow.value.isPlaying) {
                    engine.stop()
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // Ducking not implemented
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (!engine.playbackStateFlow.value.isPlaying && playlist.isNotEmpty()) {
                    engine.resume()
                }
            }
        }
    }

    private fun requestAudioFocus(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val playbackAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(playbackAttributes)
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener(audioFocusChangeListener)
                .build()
            audioManager.requestAudioFocus(audioFocusRequest!!) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                audioFocusChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(audioFocusChangeListener)
        }
    }

    fun togglePlayPause() {
        if (engine.playbackStateFlow.value.isPlaying) {
            engine.stop()
            abandonAudioFocus()
        } else {
            if (requestAudioFocus()) {
                val song = engine.playbackStateFlow.value.currentSong
                if (song != null) {
                    engine.resume()
                } else if (playlist.isNotEmpty()) {
                    currentIndex = 0
                    engine.play(playlist[currentIndex])
                }
            }
        }
    }

    fun next() {
        if (playlist.isEmpty()) return
        if (requestAudioFocus()) {
            currentIndex = (currentIndex + 1) % playlist.size
            engine.play(playlist[currentIndex])
            updateUpcomingSongs()
        }
    }

    fun previous() {
        if (playlist.isEmpty()) return
        if (requestAudioFocus()) {
            currentIndex = if (currentIndex <= 0) playlist.size - 1 else currentIndex - 1
            engine.play(playlist[currentIndex])
            updateUpcomingSongs()
        }
    }

    fun seekTo(pos: Long) {
        engine.seekTo(pos)
    }

    fun updateDspConfig(config: DspConfig) {
        engine.updateDspConfig(config)
    }

    fun setOutputMode(mode: OutputMode) {
        audioOutput.setOutputMode(mode)
        refreshOutputRoute(reconfigure = true)
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
            RepeatMode.OFF -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
        }
        engine.setRepeatMode(next)
    }

    fun updateScanningProgress(progress: Float, count: Int, completed: Boolean) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (completed) {
            notificationManager.cancel(SCAN_NOTIFICATION_ID)
        } else {
            val intent = Intent(this, MainActivity::class.java)
            val pendingIntent = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Syncing Music...")
                .setContentText("Found $count songs so far")
                .setSmallIcon(R.drawable.app_icon_fg)
                .setProgress(100, (progress * 100).toInt(), false)
                .setOngoing(true)
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOnlyAlertOnce(true)
                .build()
            
            notificationManager.notify(SCAN_NOTIFICATION_ID, notification)
        }
    }

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
            if (requestAudioFocus()) {
                currentIndex = index
                engine.play(playlist[currentIndex])
                updateUpcomingSongs()
            }
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
        if (requestAudioFocus()) {
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
    }

    val audioStateFlow get() = engine.audioStateFlow
    val playbackStateFlow get() = engine.playbackStateFlow
    val currentPositionMs get() = engine.currentPositionMs()

    fun playSong(song: Song) {
        if (requestAudioFocus()) {
            engine.play(song)
        }
    }

    fun stopSong() {
        engine.stop()
        abandonAudioFocus()
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
        val state = engine.playbackStateFlow.value
        val song = state.currentSong
        
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val playPauseAction = if (state.isPlaying) {
            NotificationCompat.Action(
                android.R.drawable.ic_media_pause, "Pause",
                getPendingIntent(ACTION_PLAY_PAUSE)
            )
        } else {
            NotificationCompat.Action(
                android.R.drawable.ic_media_play, "Play",
                getPendingIntent(ACTION_PLAY_PAUSE)
            )
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.app_icon_fg)
            .setContentIntent(pendingIntent)
            .setOngoing(state.isPlaying)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setStyle(MediaStyle()
                .setMediaSession(mediaSession.sessionToken)
                .setShowActionsInCompactView(0, 1, 2))
            .addAction(android.R.drawable.ic_media_previous, "Previous", getPendingIntent(ACTION_PREVIOUS))
            .addAction(playPauseAction)
            .addAction(android.R.drawable.ic_media_next, "Next", getPendingIntent(ACTION_NEXT))

        if (song != null) {
            builder.setContentTitle(song.title)
            builder.setContentText(song.artist)
            if (currentAlbumArt != null) {
                builder.setLargeIcon(currentAlbumArt)
            } else {
                builder.setLargeIcon(BitmapFactory.decodeResource(resources, R.drawable.ic_album_placeholder))
            }
        } else {
            builder.setContentTitle("Beatraxus")
            builder.setContentText("No song playing")
        }

        return builder.build()
    }

    private fun getPendingIntent(action: String): PendingIntent {
        val intent = Intent(this, AudioPlaybackService::class.java).apply {
            this.action = action
        }
        return PendingIntent.getService(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
    }

    private fun updateMediaSessionState() {
        val state = engine.playbackStateFlow.value
        val song = state.currentSong

        // Update Metadata
        if (song != null) {
            val metadataBuilder = android.support.v4.media.MediaMetadataCompat.Builder()
                .putString(android.support.v4.media.MediaMetadataCompat.METADATA_KEY_TITLE, song.title)
                .putString(android.support.v4.media.MediaMetadataCompat.METADATA_KEY_ARTIST, song.artist)
                .putString(android.support.v4.media.MediaMetadataCompat.METADATA_KEY_ALBUM, song.album)
                .putLong(android.support.v4.media.MediaMetadataCompat.METADATA_KEY_DURATION, song.durationMs)
            
            if (currentAlbumArt != null) {
                metadataBuilder.putBitmap(android.support.v4.media.MediaMetadataCompat.METADATA_KEY_ALBUM_ART, currentAlbumArt)
                metadataBuilder.putBitmap(android.support.v4.media.MediaMetadataCompat.METADATA_KEY_ART, currentAlbumArt)
            }

            mediaSession.setMetadata(metadataBuilder.build())
        }

        val playbackState = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackStateCompat.ACTION_SEEK_TO
            )
            .setState(
                if (state.isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                engine.currentPositionMs(),
                1.0f
            )
            .build()
        mediaSession.setPlaybackState(playbackState)
    }

    private fun updateNotification() {
        updateMediaSessionState()
        try {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(NOTIFICATION_ID, createNotification())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    companion object {
        private const val CHANNEL_ID = "playback_channel"
        private const val NOTIFICATION_ID = 1
        private const val SCAN_NOTIFICATION_ID = 101
        const val ACTION_PLAY_PAUSE = "com.beatflowy.app.ACTION_PLAY_PAUSE"
        const val ACTION_NEXT = "com.beatflowy.app.ACTION_NEXT"
        const val ACTION_PREVIOUS = "com.beatflowy.app.ACTION_PREVIOUS"
        const val ACTION_TOGGLE_SHUFFLE = "com.beatflowy.app.ACTION_TOGGLE_SHUFFLE"
        const val ACTION_TOGGLE_REPEAT = "com.beatflowy.app.ACTION_TOGGLE_REPEAT"
    }

    private fun refreshOutputRoute(reconfigure: Boolean = false) {
        val routeState = audioOutput.refreshRouteState()
        _outputRouteStateFlow.value = routeState
        if (reconfigure) {
            engine.reconfigureOutput()
        }
    }

    override fun onDestroy() {
        // Update widgets one last time to reflect stopped state
        val finalState = engine.playbackStateFlow.value.copy(isPlaying = false)
        
        // Use GlobalScope or a new scope that isn't tied to the service's lifecycle 
        // to ensure the final widget update actually happens
        CoroutineScope(Dispatchers.Default + SupervisorJob()).launch {
            updateAllWidgets(finalState)
        }

        serviceScope.cancel()
        audioManager.unregisterAudioDeviceCallback(audioDeviceCallback)
        engine.release()
        mediaSession.release()
        abandonAudioFocus()
        super.onDestroy()
    }
}
