package com.beatflowy.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import android.os.SystemClock
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
import com.beatflowy.app.engine.AudioState
import com.beatflowy.app.model.OutputMode
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
import com.beatflowy.app.repository.DspPreferences
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import com.beatflowy.app.drive.DrivePlaybackHelper
import com.beatflowy.app.model.SongSource
import com.beatflowy.app.repository.DriveAccountRepository
import android.net.Uri
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.request.CachePolicy

class AudioPlaybackService : Service() {
    private val binder = LocalBinder()
    private lateinit var engine: AudioEngine
    private lateinit var audioOutput: AudioTrackOutput
    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var audioManager: AudioManager
    private lateinit var dspPreferences: DspPreferences
    private lateinit var cloudCacheManager: com.beatflowy.app.drive.CloudCacheManager
    private var audioFocusRequest: AudioFocusRequest? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val _outputRouteStateFlow = MutableStateFlow(OutputRouteState())
    val outputRouteStateFlow: StateFlow<OutputRouteState> = _outputRouteStateFlow.asStateFlow()
    
    // Playback control state
    private var playbackJob: Job? = null
    private var originalPlaylist: List<Song> = emptyList()
    private var playlist: List<Song> = emptyList()
    private var currentIndex: Int = -1

    fun getPlaylist(): List<Song> = playlist
    fun getOriginalPlaylist(): List<Song> = originalPlaylist
    fun getCurrentIndex(): Int = currentIndex

    fun restorePlaylist(playlist: List<Song>, originalPlaylist: List<Song>, currentIndex: Int, positionMs: Long) {
        if (this.playlist.isNotEmpty()) return
        
        this.playlist = playlist
        this.originalPlaylist = originalPlaylist
        this.currentIndex = currentIndex
        if (currentIndex in playlist.indices) {
            engine.prepare(playlist[currentIndex], positionMs)
        }
        updateUpcomingSongs()
        updateNotification()
        saveState()
    }

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            refreshOutputRoute(reconfigure = true)
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            refreshOutputRoute(reconfigure = true)
        }
    }

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                if (engine.playbackStateFlow.value.isPlaying) {
                    engine.pause()
                    updateNotification()
                }
            }
        }
    }

    private var lastSongId: String? = null
    private var currentAlbumArt: Bitmap? = null
    private var currentAlbumArtSongId: String? = null
    private var albumArtLoadJob: Job? = null

    inner class LocalBinder : Binder() {
        fun getService(): AudioPlaybackService = this@AudioPlaybackService
    }

    override fun onCreate() {
        super.onCreate()
        val prefs = getSharedPreferences("beatraxus", Context.MODE_PRIVATE)
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
        scrobblingEnabled = prefs.getBoolean("scrobbling_enabled", true)

        dspPreferences = DspPreferences(this)
        cloudCacheManager = com.beatflowy.app.drive.CloudCacheManager(this, driveAccountRepository)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioOutput = AudioTrackOutput(this)
        val database = (application as com.beatflowy.app.BeatraxusApplication).database
        engine = AudioEngine(this, audioOutput, cloudCacheManager, database)
        refreshOutputRoute()
        audioManager.registerAudioDeviceCallback(audioDeviceCallback, null)
        registerReceiver(noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))

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

        serviceScope.launch {
            dspPreferences.dspConfig.collectLatest { config ->
                engine.updateDspConfig(config)
            }
        }
        
        serviceScope.launch {
            engine.onCompletion.collect {
                handleCompletion()
            }
        }

        serviceScope.launch {
            lastFmRepository.sessionKey.collect { key ->
                Log.d("AudioPlaybackService", "Last.fm Session Key updated: $key")
                lastFmSessionKey = key
            }
        }

        serviceScope.launch {
            val prefs = getSharedPreferences("beatraxus", Context.MODE_PRIVATE)
            // Use a flow to listen to preference changes or just poll/initialize
            scrobblingEnabled = prefs.getBoolean("scrobbling_enabled", true)
            Log.d("AudioPlaybackService", "Scrobbling enabled: $scrobblingEnabled")
        }

        serviceScope.launch {
            engine.playbackStateFlow
                .collectLatest { state ->
                    val songChanged = state.currentSong?.id != lastSongId
                    
                    if (songChanged) {
                        // Scrobble previous song if needed before resetting
                        handleScrobble(lastSongId, state.currentSong?.id)

                        lastSongId = state.currentSong?.id
                        currentAlbumArt = null
                        currentAlbumArtSongId = null
                        
                        currentSongStartTime = System.currentTimeMillis() / 1000
                        currentSongPlaybackTimeMs = 0
                        lastProgressUpdateTime = System.currentTimeMillis()
                        isScrobbled = false
                        
                        // Preload next song for gapless playback
                        getNextSong()?.let { engine.preloadNext(it) }

                        albumArtLoadJob?.cancel()
                        albumArtLoadJob = serviceScope.launch {
                            loadAlbumArt(state.currentSong)
                            updateNotification()
                        }

                        // Update Now Playing on Last.fm
                        val sessionKey = lastFmSessionKey
                        if (scrobblingEnabled && sessionKey != null && state.currentSong != null) {
                            serviceScope.launch(Dispatchers.IO) {
                                lastFmRepository.updateNowPlaying(
                                    artist = state.currentSong.artist,
                                    track = state.currentSong.title,
                                    album = state.currentSong.album,
                                    durationMs = state.currentSong.durationMs,
                                    sessionKey = sessionKey
                                )
                            }
                        }
                    }

                    if (state.isPlaying) {
                        val now = System.currentTimeMillis()
                        if (lastProgressUpdateTime > 0) {
                            currentSongPlaybackTimeMs += (now - lastProgressUpdateTime)
                        }
                        lastProgressUpdateTime = now
                        
                        // Check for scrobble threshold (50% or 4 mins)
                        val song = state.currentSong
                        if (scrobblingEnabled && !isScrobbled && lastFmSessionKey != null && song != null && song.durationMs > 30000) {
                            val threshold = minOf(song.durationMs / 2, 240000L)
                            if (currentSongPlaybackTimeMs >= threshold) {
                                isScrobbled = true
                                val sessionKey = lastFmSessionKey!!
                                serviceScope.launch(Dispatchers.IO) {
                                    lastFmRepository.scrobble(
                                        artist = song.artist,
                                        track = song.title,
                                        album = song.album,
                                        timestamp = currentSongStartTime,
                                        durationMs = song.durationMs,
                                        sessionKey = sessionKey
                                    )
                                }
                            }
                        }
                    } else {
                        lastProgressUpdateTime = 0
                    }

                    updateAllWidgets(state)
                    updateNotification()
                }
        }
    }

    private fun handleScrobble(oldSongId: String?, newSongId: String?) {
        // Implementation can be expanded if we want to scrobble on track end specifically,
        // but we already do it at 50% threshold above which is Last.fm standard.
    }

    private suspend fun loadAlbumArt(song: Song?) {
        if (song?.id == currentAlbumArtSongId && currentAlbumArt != null) return
        val loaded = withContext(Dispatchers.IO) {
            val uri = song?.albumArtUri ?: return@withContext null
            try {
                if (uri.scheme?.startsWith("http") == true) {
                    val loader = ImageLoader(this@AudioPlaybackService)
                    val request = ImageRequest.Builder(this@AudioPlaybackService)
                        .data(uri)
                        .size(500)
                        .allowHardware(false)
                        .build()
                    val result = loader.execute(request)
                    if (result is SuccessResult) {
                        (result.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
                    } else null
                } else {
                    contentResolver.openInputStream(uri)?.use { input ->
                        val original = BitmapFactory.decodeStream(input)
                        if (original != null) {
                            val size = 500
                            val ratio = original.width.toFloat() / original.height.toFloat()
                            val w = if (ratio > 1) size else (size * ratio).toInt()
                            val h = if (ratio > 1) (size / ratio).toInt() else size
                            Bitmap.createScaledBitmap(original, w, h, true)
                        } else null
                    }
                }
            } catch (e: Exception) {
                null
            }
        }
        if (loaded != null) {
            currentAlbumArt = loaded
            currentAlbumArtSongId = song?.id
        } else {
            currentAlbumArt = null
            currentAlbumArtSongId = song?.id
        }
    }

    private fun preloadArtwork(song: Song) {
        val uri = song.albumArtUri ?: return
        serviceScope.launch(Dispatchers.IO) {
            try {
                // Preload using Coil for remote/all uris to ensure it's in cache for UI
                val request = ImageRequest.Builder(this@AudioPlaybackService)
                    .data(uri)
                    .diskCachePolicy(CachePolicy.ENABLED)
                    .memoryCachePolicy(CachePolicy.ENABLED)
                    .build()
                ImageLoader(this@AudioPlaybackService).enqueue(request)
            } catch (e: Exception) {
                // Ignore preloading errors
            }
        }
    }

    private fun handleCompletion() {
        val currentRepeatMode = engine.playbackStateFlow.value.repeatMode
        val engineCurrentSong = engine.playbackStateFlow.value.currentSong
        
        // Check if engine already transitioned gaplessly
        if (engineCurrentSong != null && engineCurrentSong.id != playlist.getOrNull(currentIndex)?.id) {
            val newIndex = playlist.indexOfFirst { it.id == engineCurrentSong.id }
            if (newIndex != -1) {
                currentIndex = newIndex
                updateUpcomingSongs()
                // Already playing the new song, no need to call play()
                return
            }
        }

        when (currentRepeatMode) {
            RepeatMode.ONE -> {
                val song = engine.playbackStateFlow.value.currentSong
                if (song != null) engine.play(song)
            }
            RepeatMode.ALL -> next()
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
                        }
                    }
                }
            } catch (e: Exception) {
            }
        }
    }

    override fun onBind(intent: Intent): IBinder = binder

    private val driveAccountRepository by lazy { DriveAccountRepository(this) }
    private val lastFmRepository by lazy { com.beatflowy.app.repository.lastfm.LastFmRepository(this) }

    private var currentSongStartTime: Long = 0
    private var currentSongPlaybackTimeMs: Long = 0
    private var lastProgressUpdateTime: Long = 0
    private var isScrobbled = false
    private var lastFmSessionKey: String? = null
    private var scrobblingEnabled = true

    private val prefListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        if (key == "scrobbling_enabled") {
            scrobblingEnabled = prefs.getBoolean(key, true)
        }
    }



    private val _upcomingSongs = MutableStateFlow<List<Song>>(emptyList())
    val upcomingSongs: StateFlow<List<Song>> = _upcomingSongs.asStateFlow()

    private fun updateUpcomingSongs() {
        val upcoming = if (playlist.isEmpty() || currentIndex >= playlist.size - 1) {
            emptyList()
        } else {
            playlist.subList(currentIndex + 1, playlist.size)
        }
        _upcomingSongs.value = upcoming
        
        serviceScope.launch {
            cloudCacheManager.prepareCache(playlist.getOrNull(currentIndex), upcoming)
        }

        // Enable gapless transition in engine
        upcoming.firstOrNull()?.let { nextSong ->
            engine.preloadNext(nextSong)
            preloadArtwork(nextSong)
        }
    }

    private var resumeOnFocusGain = false

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                if (engine.playbackStateFlow.value.isPlaying) {
                    resumeOnFocusGain = false
                    togglePlayPause()
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                if (engine.playbackStateFlow.value.isPlaying) {
                    resumeOnFocusGain = true
                    engine.pause()
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {}
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (resumeOnFocusGain && !engine.playbackStateFlow.value.isPlaying && playlist.isNotEmpty()) {
                    engine.resume()
                    resumeOnFocusGain = false
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
        playbackJob?.cancel()

        if (engine.playbackStateFlow.value.isPlaying) {
            engine.pause()
            abandonAudioFocus()
            resumeOnFocusGain = false
            saveState()
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
        performTrackChange(1)
    }

    fun previous() {
        performTrackChange(-1)
    }
    
    private fun performTrackChange(delta: Int) {
        if (playlist.isEmpty()) return
        
        playbackJob?.cancel()
        
        currentIndex = (currentIndex + delta) % playlist.size
        if (currentIndex < 0) currentIndex += playlist.size
        
        val nextSong = playlist[currentIndex]
        
        engine.prepare(nextSong)
        saveState()
        
        playbackJob = serviceScope.launch {
            delay(150) // Debounce for rapid presses
            if (isActive && requestAudioFocus()) {
                engine.play(nextSong)
                updateUpcomingSongs()
            }
        }
    }

    fun seekTo(pos: Long) {
        engine.seekTo(pos)
        updateNotification()
    }

    fun updateDspConfig(config: DspConfig) {
        engine.updateDspConfig(config)
    }

    fun setOutputMode(mode: OutputMode) {
        if (_outputRouteStateFlow.value.selectedMode == mode) return
        audioOutput.setOutputMode(mode)
        refreshOutputRoute(reconfigure = true)
    }

    fun setShuffleMode(enabled: Boolean) {
        if (engine.playbackStateFlow.value.shuffleMode == enabled) return
        
        val currentSong = engine.playbackStateFlow.value.currentSong
        if (enabled) {
            val shuffled = playlist.shuffled().toMutableList()
            if (currentSong != null) {
                val idx = shuffled.indexOfFirst { it.id == currentSong.id }
                if (idx != -1) {
                    val removed = shuffled.removeAt(idx)
                    shuffled.add(0, removed)
                }
            }
            playlist = shuffled
            currentIndex = 0
        } else {
            playlist = originalPlaylist
            currentIndex = playlist.indexOfFirst { it.id == currentSong?.id }.coerceAtLeast(0)
        }
        updateUpcomingSongs()
        engine.setShuffleMode(enabled)
        saveState()
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
                PendingIntent.FLAG_IMMUTABLE)

            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Syncing Music...")
                .setContentText("Found $count songs so far")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setProgress(100, (progress * 100).toInt(), false)
                .setOngoing(true)
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOnlyAlertOnce(true)
                .build()
            
            notificationManager.notify(SCAN_NOTIFICATION_ID, notification)
        }
    }

    fun updateEnrichingProgress(progress: Float, current: Int, total: Int) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (progress >= 1.0f) {
            notificationManager.cancel(ENRICH_NOTIFICATION_ID)
        } else {
            val intent = Intent(this, MainActivity::class.java)
            val pendingIntent = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_IMMUTABLE)

            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Enriching Metadata...")
                .setContentText("Processed $current of $total songs")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setProgress(100, (progress * 100).toInt(), false)
                .setOngoing(true)
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOnlyAlertOnce(true)
                .build()
            
            notificationManager.notify(ENRICH_NOTIFICATION_ID, notification)
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
            saveState()
        }
    }
    
    fun moveInQueue(from: Int, to: Int) {
        if (from !in playlist.indices || to !in playlist.indices) return
        val mutable = playlist.toMutableList()
        val item = mutable.removeAt(from)
        mutable.add(to, item)
        playlist = mutable
        
        if (from == currentIndex) {
            currentIndex = to
        } else if (from < currentIndex && to >= currentIndex) {
            currentIndex--
        } else if (from > currentIndex && to <= currentIndex) {
            currentIndex++
        }
        updateUpcomingSongs()
        saveState()
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
        saveState()
    }

    fun addToQueue(song: Song) {
        if (playlist.any { it.id == song.id }) return
        val newList = playlist.toMutableList()
        newList.add(song)
        playlist = newList
        updateUpcomingSongs()
        saveState()
    }
    
    fun playList(songs: List<Song>, startIndex: Int) {
        if (requestAudioFocus()) {
            originalPlaylist = songs
            if (engine.playbackStateFlow.value.shuffleMode) {
                val shuffled = songs.shuffled().toMutableList()
                val selectedSong = songs.getOrNull(startIndex)
                if (selectedSong != null) {
                    val idx = shuffled.indexOfFirst { it.id == selectedSong.id }
                    if (idx != -1) {
                        val removed = shuffled.removeAt(idx)
                        shuffled.add(0, removed)
                    }
                }
                playlist = shuffled
                currentIndex = 0
            } else {
                playlist = songs
                currentIndex = if (startIndex in songs.indices) startIndex else 0
            }

            updateUpcomingSongs()

            if (playlist.isNotEmpty()) {
                engine.play(playlist[currentIndex])
            }
            saveState()
        }
    }

    private val _playbackStateFlow = MutableStateFlow(PlaybackState())
    val playbackStateFlow: StateFlow<PlaybackState> = _playbackStateFlow.asStateFlow()

    private val _audioStateFlow = MutableStateFlow(AudioState())
    val audioStateFlow: StateFlow<AudioState> = _audioStateFlow.asStateFlow()

    init {
        serviceScope.launch {
            engine.playbackStateFlow.collect { state ->
                _playbackStateFlow.value = state
            }
        }
        serviceScope.launch {
            engine.audioStateFlow.collect { state ->
                _audioStateFlow.value = state
            }
        }
    }
    val currentPositionMs get() = engine.currentPositionMs()

    fun playSong(song: Song) {
        if (requestAudioFocus()) {
            originalPlaylist = listOf(song)
            playlist = listOf(song)
            currentIndex = 0
            updateUpcomingSongs()
            engine.play(song)
        }
    }

    fun prepareSong(song: Song, position: Long) {
        originalPlaylist = listOf(song)
        playlist = listOf(song)
        currentIndex = 0
        updateUpcomingSongs()
        engine.prepare(song, position)
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
        val state = _playbackStateFlow.value
        val song = state.currentSong
        
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra("open_now_playing", true)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
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
            .setSmallIcon(R.mipmap.ic_launcher)
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
                builder.setLargeIcon(BitmapFactory.decodeResource(resources, R.drawable.ic_album_default))
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
        val state = _playbackStateFlow.value
        val song = state.currentSong

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
                currentPositionMs,
                if (state.isPlaying) 1.0f else 0.0f,
                SystemClock.elapsedRealtime()
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
        private const val ENRICH_NOTIFICATION_ID = 102
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

    private fun saveState() {
        val prefs = getSharedPreferences("beatraxus", Context.MODE_PRIVATE)
        val currentSong = playlist.getOrNull(currentIndex)
        prefs.edit().apply {
            if (playlist.isNotEmpty()) {
                putString("last_queue_ids", playlist.joinToString(",") { it.id })
                putString("last_original_queue_ids", originalPlaylist.joinToString(",") { it.id })
                putInt("last_queue_index", currentIndex)
            }
            if (currentSong != null) {
                putString("last_song_id", currentSong.id)
                putLong("last_song_pos", engine.currentPositionMs())
            }
            apply()
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        saveState()
        // If we are not playing, we can clear the cache when the task is removed (app swiped away)
        if (!engine.playbackStateFlow.value.isPlaying) {
            cloudCacheManager.clearFullCache()
            stopSelf()
        }
    }

    override fun onDestroy() {
        val prefs = getSharedPreferences("beatraxus", Context.MODE_PRIVATE)
        prefs.unregisterOnSharedPreferenceChangeListener(prefListener)

        val finalState = engine.playbackStateFlow.value.copy(isPlaying = false)
        serviceScope.launch {
            updateAllWidgets(finalState)
        }

        // Ensure cloud cache is cleared on destroy if we are not just restarting
        if (!engine.playbackStateFlow.value.isPlaying) {
            cloudCacheManager.clearFullCache()
        }

        serviceScope.cancel()
        audioManager.unregisterAudioDeviceCallback(audioDeviceCallback)
        try {
            unregisterReceiver(noisyReceiver)
        } catch (e: Exception) {}
        engine.release()
        mediaSession.release()
        abandonAudioFocus()
        super.onDestroy()
    }
}