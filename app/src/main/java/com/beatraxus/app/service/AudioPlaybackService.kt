package com.beatraxus.app.service

import com.beatraxus.app.utils.ImageUtils
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
import android.os.PowerManager
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
import com.beatraxus.app.MainActivity
import com.beatraxus.app.R
import com.beatraxus.app.engine.AudioEngine
import com.beatraxus.app.engine.AudioState
import com.beatraxus.app.model.OutputMode
import com.beatraxus.app.engine.OutputRouteState
import com.beatraxus.app.engine.AudioTrackOutput
import com.beatraxus.app.engine.PlaybackState
import com.beatraxus.app.engine.RepeatMode
import com.beatraxus.app.model.DspConfig
import com.beatraxus.app.model.Song
import com.beatraxus.app.model.toEntity
import com.beatraxus.app.widget.MusicWidgetKeys
import com.beatraxus.app.widget.MusicWidgetLarge
import com.beatraxus.app.widget.MusicWidgetMedium
import com.beatraxus.app.widget.MusicWidgetSmall
import com.beatraxus.app.repository.DspPreferences
import coil.imageLoader
import coil.size.Precision
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import com.beatraxus.app.drive.DrivePlaybackHelper
import com.beatraxus.app.drive.DriveLibraryScanner
import com.beatraxus.app.drive.DropboxLibraryScanner
import com.beatraxus.app.drive.OneDriveLibraryScanner
import com.beatraxus.app.drive.BoxLibraryScanner
import com.beatraxus.app.drive.NextcloudLibraryScanner
import com.beatraxus.app.model.SongSource
import com.beatraxus.app.repository.DriveAccountRepository
import android.net.Uri
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.request.CachePolicy
import com.beatraxus.app.repository.MusicRepository
import com.google.android.gms.cast.MediaStatus
import com.beatraxus.app.model.AppDatabase
import com.google.api.client.googleapis.extensions.android.gms.auth.UserRecoverableAuthIOException
import java.io.File


class AudioPlaybackService : Service() {
    private val binder = LocalBinder()
    private lateinit var engine: AudioEngine
    private lateinit var audioOutput: AudioTrackOutput
    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var audioManager: AudioManager
    private lateinit var dspPreferences: DspPreferences
    private lateinit var cloudCacheManager: com.beatraxus.app.drive.CloudCacheManager
    private var audioFocusRequest: AudioFocusRequest? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val _outputRouteStateFlow = MutableStateFlow(OutputRouteState())
    val outputRouteStateFlow: StateFlow<OutputRouteState> = _outputRouteStateFlow.asStateFlow()
    
    // Playback control state
    private var playbackJob: Job? = null
    private var originalPlaylist: List<Song> = emptyList()
    private var playlist: List<Song> = emptyList()
    private var currentIndex: Int = -1
    private var hasRestoredFromDisk: Boolean = false

    fun getPlaylist(): List<Song> = playlist
    fun getOriginalPlaylist(): List<Song> = originalPlaylist
    fun getCurrentIndex(): Int = currentIndex

    fun restorePlaylist(playlist: List<Song>, originalPlaylist: List<Song>, currentIndex: Int, positionMs: Long) {
        if (hasRestoredFromDisk) return
        
        this.playlist = playlist
        this.originalPlaylist = originalPlaylist
        this.currentIndex = currentIndex
        if (currentIndex in playlist.indices) {
            engine.prepare(playlist[currentIndex], positionMs)
        }
        updateUpcomingSongs()
        updateNotification()
        
        // Use sync=true here to ensure that as soon as the app successfully restores the queue,
        // it's persisted. This helps prevent "losing" the restored state if the app crashes 
        // or is killed shortly after launch.
        saveState(sync = true)

        hasRestoredFromDisk = true
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
    private var lastSessionId: Long = 0L
    private var currentAlbumArt: Bitmap? = null
    private var currentAlbumArtSongId: String? = null
    private var albumArtLoadJob: Job? = null

    inner class LocalBinder : Binder() {
        fun getService(): AudioPlaybackService = this@AudioPlaybackService
    }

    /** Current AudioTrack session id for live-meter taps (Music Detail Inspector, Phase 6),
     *  or 0 if unavailable (e.g. MMAP-exclusive output, or no active track yet). */
    fun getAudioSessionId(): Int = if (::audioOutput.isInitialized) audioOutput.getAudioSessionId() else 0

    override fun onCreate() {
        super.onCreate()
        val prefs = getSharedPreferences("beatraxus", Context.MODE_PRIVATE)
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
        scrobblingEnabled = prefs.getBoolean("scrobbling_enabled", true)

        dspPreferences = DspPreferences(this)
        cloudCacheManager = com.beatraxus.app.drive.CloudCacheManager(
            this,
            driveAccountRepository,
            dropboxAccountRepository,
            onedriveAccountRepository,
            boxAccountRepository,
            nextcloudAccountRepository,
            smbConnectionRepository,
            ftpConnectionRepository,
            smbFolderBrowser,
            ftpFolderBrowser
        )
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioOutput = AudioTrackOutput(this)
        val application = (application as com.beatraxus.app.BeatraxusApplication)
        val database = application.database
        val tdLibManager = application.tdLibManager
        engine = AudioEngine(this, audioOutput, cloudCacheManager, database, tdLibManager)
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
            androidx.compose.runtime.snapshotFlow { com.beatraxus.app.cast.CastManager.isConnected }
                .collect { connected ->
                    if (connected && engine.playbackStateFlow.value.isPlaying) {
                        engine.pause()
                    }
                }
        }

        serviceScope.launch {
            com.beatraxus.app.cast.CastManager.castMediaStatus.collect { status ->
                if (status != null && com.beatraxus.app.cast.CastManager.isConnected) {
                    val isPlaying = status.playerState == MediaStatus.PLAYER_STATE_PLAYING ||
                                    status.playerState == MediaStatus.PLAYER_STATE_BUFFERING
                    if (_playbackStateFlow.value.isPlaying != isPlaying) {
                        _playbackStateFlow.update { it.copy(isPlaying = isPlaying) }
                    }
                }
            }
        }

        serviceScope.launch {
            engine.playbackStateFlow
                .collectLatest { state ->
                    val songChanged = state.currentSong?.id != lastSongId || state.sessionId != lastSessionId
                    
                    if (songChanged) {
                        cloudCacheManager.setCurrentlyPlayingId(state.currentSong?.id)
                        // Scrobble previous song if needed before resetting
                        if (state.currentSong?.id != lastSongId) {
                            handleScrobble(lastSongId, state.currentSong?.id)
                        }

                        lastSongId = state.currentSong?.id
                        lastSessionId = state.sessionId
                        currentAlbumArt = null
                        currentAlbumArtSongId = null
                        
                        currentSongStartTime = System.currentTimeMillis() / 1000
                        currentSongPlaybackTimeMs = 0
                        lastProgressUpdateTime = System.currentTimeMillis()
                        isScrobbled = false
                        
                        // Update current index if the song changed (gapless transition)
                        state.currentSong?.let { song ->
                            val newIndex = playlist.indexOfFirst { it.id == song.id }
                            if (newIndex != -1 && newIndex != currentIndex) {
                                currentIndex = newIndex
                            }
                        }

                        // Preload next song for gapless playback
                        updateUpcomingSongs()

                        albumArtLoadJob?.cancel()
                        currentAlbumArt = null
                        currentAlbumArtSongId = null
                        updateNotification()
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

                    serviceScope.launch { updateAllWidgets(state) }
                    updateNotification()
                }
        }
    }

    private fun handleScrobble(oldSongId: String?, newSongId: String?) {
        // Implementation can be expanded if we want to scrobble on track end specifically,
        // but we already do it at 50% threshold above which is Last.fm standard.
    }

    private val defaultArtCache = mutableMapOf<Int, Bitmap>()
    private fun getDefaultAlbumArt(): Bitmap {
        val res = ImageUtils.getDefaultAlbumArtRes()
        return defaultArtCache.getOrPut(res) {
            BitmapFactory.decodeResource(resources, res)
        }
    }

    private suspend fun loadAlbumArt(song: Song?) {
        if (song?.id == currentAlbumArtSongId && currentAlbumArt != null) return
        val loaded = withContext(Dispatchers.IO) {
            val uri = song?.albumArtUri ?: return@withContext null
            try {
                val request = ImageRequest.Builder(this@AudioPlaybackService)
                    .data(uri)
                    .size(500)
                    .precision(Precision.INEXACT)
                    .allowHardware(false)
                    .build()
                val result = this@AudioPlaybackService.imageLoader.execute(request)
                if (result is SuccessResult) {
                    (result.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
                } else null
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
                    .size(800) // Match UI size for memory cache hit
                    .precision(Precision.INEXACT)
                    .diskCachePolicy(CachePolicy.ENABLED)
                    .memoryCachePolicy(CachePolicy.ENABLED)
                    .build()
                this@AudioPlaybackService.imageLoader.enqueue(request)
            } catch (e: Exception) {
                // Ignore preloading errors
            }
        }
    }

    private fun handleCompletion() {
        val state = engine.playbackStateFlow.value
        val currentRepeatMode = state.repeatMode
        val engineSessionId = state.sessionId
        
        // If engine already transitioned (e.g. gapless playback), it will already be playing 
        // the next song. We just need to sync our internal currentIndex and return to avoid double-skipping.
        if (state.isPlaying && state.currentSong != null) {
            val engineCurrentSong = state.currentSong
            val newIndex = playlist.indexOfFirst { it.id == engineCurrentSong.id }
            if (newIndex != -1) {
                currentIndex = newIndex
                updateUpcomingSongs()
            }
            lastSessionId = engineSessionId
            return
        }

        when (currentRepeatMode) {
            RepeatMode.ONE -> {
                val song = playlist.getOrNull(currentIndex)
                if (song != null) engine.play(song)
            }
            RepeatMode.ALL -> next(isAutoAdvance = true)
            RepeatMode.OFF -> {
                if (playlist.isNotEmpty() && currentIndex < playlist.size - 1) {
                    next(isAutoAdvance = true)
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

    private suspend fun updateAllWidgets(state: PlaybackState) = withContext(Dispatchers.Default) {
        val song = state.currentSong
        val title = song?.title ?: "Not Playing"
        val artist = song?.artist ?: "Beatraxus"
        val isPlaying = state.isPlaying
        val albumArtUri = song?.albumArtUri?.toString() ?: ""
        val shuffleOn = state.shuffleMode
        val repeatMode = state.repeatMode.name

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

    override fun onBind(intent: Intent): IBinder = binder

    private val driveAccountRepository by lazy { DriveAccountRepository(this) }
    private val dropboxAccountRepository by lazy { com.beatraxus.app.repository.DropboxAccountRepository(this) }
    private val onedriveAccountRepository by lazy { com.beatraxus.app.repository.OneDriveAccountRepository(this) }
    private val boxAccountRepository by lazy { com.beatraxus.app.repository.BoxAccountRepository(this) }
    private val nextcloudAccountRepository by lazy { com.beatraxus.app.repository.NextcloudAccountRepository(this) }
    private val smbConnectionRepository by lazy { com.beatraxus.app.repository.SmbConnectionRepository(this) }
    private val ftpConnectionRepository by lazy { com.beatraxus.app.repository.FtpConnectionRepository(this) }
    private val smbFolderBrowser by lazy { com.beatraxus.app.network.SmbFolderBrowser() }
    private val ftpFolderBrowser by lazy { com.beatraxus.app.network.FtpFolderBrowser() }
    private val musicRepository by lazy { com.beatraxus.app.repository.MusicRepository(this) }
    private val songDao by lazy { (application as com.beatraxus.app.BeatraxusApplication).database.songDao() }
    private val lastFmRepository by lazy { com.beatraxus.app.repository.lastfm.LastFmRepository(this) }
    private var libraryScanJob: Job? = null

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

    private val _previousSongs = MutableStateFlow<List<Song>>(emptyList())
    val previousSongs: StateFlow<List<Song>> = _previousSongs.asStateFlow()

    private fun updateUpcomingSongs() {
        val current = playlist.getOrNull(currentIndex)
        val upcoming = getUpcomingSongs()
        _upcomingSongs.value = upcoming

        val previous = if (playlist.isEmpty() || currentIndex <= 0) {
            emptyList()
        } else {
            playlist.subList(0, currentIndex)
        }
        _previousSongs.value = previous
        
        serviceScope.launch {
            val tdLib = (application as com.beatraxus.app.BeatraxusApplication).tdLibManager
            cloudCacheManager.prepareCache(current, upcoming, tdLib)
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

        if (com.beatraxus.app.cast.CastManager.isConnected) {
            // NOTE: engine.playbackStateFlow reflects the *local* player, which is deliberately
            // paused while casting — it's always false here, so this must not be used to decide
            // play vs pause. _playbackStateFlow is kept in sync with the TV's real state via
            // CastManager.castMediaStatus (see the collector above), so use that instead.
            if (_playbackStateFlow.value.isPlaying) {
                com.beatraxus.app.cast.CastManager.pause()
            } else {
                com.beatraxus.app.cast.CastManager.play()
            }
            return
        }

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

    fun next(isAutoAdvance: Boolean = false) {
        performTrackChange(1, isAutoAdvance)
    }

    fun previous(isAutoAdvance: Boolean = false) {
        performTrackChange(-1, isAutoAdvance)
    }
    
    private fun performTrackChange(delta: Int, isAutoAdvance: Boolean = false) {
        if (playlist.isEmpty()) return
        
        playbackJob?.cancel()
        
        currentIndex = (currentIndex + delta) % playlist.size
        if (currentIndex < 0) currentIndex += playlist.size
        
        val nextSong = playlist[currentIndex]
        
        if (com.beatraxus.app.cast.CastManager.isConnected) {
            val route = androidx.mediarouter.media.MediaRouter.getInstance(this).selectedRoute
            com.beatraxus.app.cast.CastManager.castSong(this, route, nextSong, nextSong.uri.toString())
            _playbackStateFlow.update { it.copy(currentSong = nextSong, isPlaying = true) }
            return
        }

        engine.prepare(nextSong)
        saveState()
        
        playbackJob = serviceScope.launch {
            if (!isAutoAdvance) {
                delay(150)
            }
            if (isActive && requestAudioFocus()) {
                engine.play(nextSong)
            }
        }
    }

    fun seekTo(pos: Long) {
        if (com.beatraxus.app.cast.CastManager.isConnected) {
            com.beatraxus.app.cast.CastManager.seek(pos)
            return
        }
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
        updateUpcomingSongs()
        updateMediaSessionState()
        saveState()
    }

    fun runLocalScan(
        fullScan: Boolean,
        currentSongs: List<Song>,
        onProgress: (Float, Int, Int, Int) -> Unit,
        onComplete: (List<Song>, List<Song>, List<String>, String, Boolean) -> Unit,
        onError: (String) -> Unit
    ) {
        if (libraryScanJob?.isActive == true) return
        libraryScanJob = serviceScope.launch(Dispatchers.Default) {
            acquireScanWakeLock()
            try {
                val blocked = musicRepository.getBlockedFolders()
                val currentLocalSongsMap = currentSongs.filter { it.source == SongSource.LOCAL }.associateBy { it.id }
                
                val resultsFromMediaStore = musicRepository.scanAudioFiles(fullScan = fullScan, excludedPaths = blocked) { count, albums, artists, progress ->
                    onProgress(progress, count, albums, artists)
                    updateScanningProgress(progress, count, false)
                }
                
                val results = resultsFromMediaStore.map { scanned ->
                    currentLocalSongsMap[scanned.id] ?: scanned
                }

                val currentLocalIds = currentLocalSongsMap.keys
                val resultIds = results.map { it.id }.toSet()
                val newSongs = results.filter { it.id !in currentLocalIds }
                val removedLocalIds = currentLocalIds - resultIds
                
                val hasChanges = fullScan || currentLocalSongsMap.size != results.size || newSongs.isNotEmpty() || removedLocalIds.isNotEmpty()

                if (hasChanges) {
                    val entities = results.map { it.toEntity() }
                    withContext(Dispatchers.IO) {
                        if (fullScan) {
                            songDao.deleteLocalSongs()
                        } else if (removedLocalIds.isNotEmpty()) {
                            songDao.deleteSongsByIds(removedLocalIds.toList())
                        }
                        entities.chunked(200).forEach { chunk ->
                            songDao.insertSongs(chunk)
                        }
                    }
                }

                val allFolders = results.map { it.folder }.filter { it != "Unknown" }.toSet()
                val sortedFolders = allFolders.sortedBy { it.length }
                val minimalFolders = mutableListOf<String>()
                val blockedSet = blocked.toSet()
                for (folder in sortedFolders) {
                    if (blockedSet.any { folder.startsWith(it + "/") || folder == it }) continue
                    if (minimalFolders.none { folder.startsWith(it + "/") || folder == it }) {
                        minimalFolders.add(folder)
                    }
                }
                musicRepository.addMusicFolders(minimalFolders)

                val message = when {
                    fullScan -> "Full scan complete"
                    newSongs.isNotEmpty() && removedLocalIds.isNotEmpty() -> "Added ${newSongs.size} songs, removed ${removedLocalIds.size}"
                    newSongs.isNotEmpty() -> "Added ${newSongs.size} new songs"
                    removedLocalIds.isNotEmpty() -> "Removed ${removedLocalIds.size} missing songs"
                    hasChanges -> "Library updated"
                    else -> "No changes found"
                }

                onComplete(results, newSongs, removedLocalIds.toList(), message, hasChanges)
                updateScanningProgress(1.0f, results.size, true)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                onError(e.message ?: "Unknown error")
                updateScanningProgress(1.0f, 0, true)
            } finally {
                releaseScanWakeLock()
            }
        }
    }

    fun runDriveScan(
        email: String,
        allowedFormats: Set<String>,
        onProgress: (Float) -> Unit,
        onDiscoveryComplete: (List<Song>) -> Unit,
        onEnrichmentProgress: (Float, Int, Int) -> Unit,
        onStatusUpdate: (String?) -> Unit,
        onSongUpdated: (Song) -> Unit,
        onComplete: (String) -> Unit,
        onError: (String, Intent?) -> Unit
    ) {
        if (libraryScanJob?.isActive == true) return
        libraryScanJob = serviceScope.launch(Dispatchers.Default) {
            acquireScanWakeLock()
            try {
                val credential = driveAccountRepository.getCredential(email)
                val scanner = com.beatraxus.app.drive.DriveLibraryScanner(application)
                val newSongs = scanner.scanAccount(credential, allowedFormats)
                
                val existingSongs = withContext(Dispatchers.IO) {
                    songDao.getSongsByAccount(email.lowercase()).associateBy { it.id }
                }

                val newSongIds = newSongs.map { it.id }.toSet()
                val songsToDelete = existingSongs.filterKeys { it !in newSongIds }.keys.toList()
                if (songsToDelete.isNotEmpty()) {
                    withContext(Dispatchers.IO) {
                        songDao.deleteSongsByIds(songsToDelete)
                    }
                }

                if (newSongs.isNotEmpty()) {
                    val updatedNewSongs = newSongs.map { song ->
                        val existing = existingSongs[song.id]
                        if (existing != null && (existing.isEnriched || existing.durationMs > 0)) {
                            song.copy(
                                durationMs = existing.durationMs,
                                bitrate = existing.bitrate,
                                sampleRateHz = existing.sampleRateHz,
                                bitDepth = existing.bitDepth,
                                albumArtUri = existing.albumArtUriString?.let { Uri.parse(it) } ?: song.albumArtUri,
                                format = existing.format,
                                album = existing.album,
                                artist = existing.artist,
                                genre = existing.genre,
                                year = existing.year,
                                lyrics = existing.lyrics,
                                replayGainTrackDb = existing.replayGainTrackDb,
                                replayGainAlbumDb = existing.replayGainAlbumDb,
                                replayGainTrackPeak = existing.replayGainTrackPeak,
                                replayGainAlbumPeak = existing.replayGainAlbumPeak,
                                isEnriched = existing.isEnriched,
                                lastSyncTimestamp = existing.lastSyncTimestamp
                            )
                        } else {
                            song
                        }
                    }

                    songDao.insertSongs(updatedNewSongs.map { it.toEntity() })
                    onDiscoveryComplete(updatedNewSongs)
                    
                    val toEnrich = updatedNewSongs.filter {
                        !it.isEnriched || (it.albumArtUri == null && !it.albumArtFetchAttempted)
                    }
                    if (toEnrich.isNotEmpty()) {
                        val extractor = com.beatraxus.app.repository.MetadataExtractor(application)
                        var processed = 0
                        val total = toEnrich.size
                        
                        onStatusUpdate("Enriching $total new songs...")

                        extractor.extractCloudMetadataBatch(toEnrich, credential) { updatedSong ->
                            processed++
                            val progress = processed.toFloat() / total.toFloat()
                            onProgress(progress)
                            onEnrichmentProgress(progress, processed, total)
                            updateEnrichingProgress(progress, processed, total)
                            
                            songDao.insertSong(updatedSong.toEntity())
                            onSongUpdated(updatedSong)
                        }
                        onStatusUpdate(null)
                        updateEnrichingProgress(1.0f, total, total)
                    }
                    onComplete("Synced ${newSongs.size} songs from $email")
                } else {
                    onComplete("No songs found for $email")
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (e is UserRecoverableAuthIOException) {
                    onError(e.message ?: "Auth error", e.intent)
                } else {
                    onError(e.message ?: "Drive scan failed: ${e.message}", null)
                }
            } finally {
                releaseScanWakeLock()
            }
        }
    }

    fun runDropboxScan(
        account: com.beatraxus.app.repository.DropboxAccount,
        allowedFormats: Set<String>,
        onProgress: (Float) -> Unit,
        onDiscoveryComplete: (List<Song>) -> Unit,
        onSongUpdated: (Song) -> Unit,
        onComplete: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        if (libraryScanJob?.isActive == true) return
        libraryScanJob = serviceScope.launch(Dispatchers.Default) {
            acquireScanWakeLock()
            try {
                val scanner = com.beatraxus.app.drive.DropboxLibraryScanner(application)
                val discovered = mutableListOf<Song>()
                val token = dropboxAccountRepository.getAccessToken(account.email) ?: throw Exception("Failed to get access token")
                scanner.scanAccountFlow(token, account.email, allowedFormats).collect { page ->
                    discovered.addAll(page)
                    withContext(Dispatchers.IO) {
                        songDao.insertSongs(page.map { it.toEntity() })
                    }
                    onDiscoveryComplete(page)
                }

                if (discovered.isNotEmpty()) {
                    val toEnrich = discovered.filter { !it.isEnriched }
                    if (toEnrich.isNotEmpty()) {
                        val extractor = com.beatraxus.app.repository.MetadataExtractor(application)
                        var processed = 0
                        val total = toEnrich.size
                        
                        extractor.extractCloudMetadataBatch(toEnrich, null) { updatedSong ->
                            processed++
                            val progress = processed.toFloat() / total.toFloat()
                            onProgress(progress)
                            updateEnrichingProgress(progress, processed, total)
                            
                            songDao.insertSong(updatedSong.toEntity())
                            onSongUpdated(updatedSong)
                        }
                        updateEnrichingProgress(1.0f, total, total)
                    }
                }
                onComplete("Dropbox sync complete. Found ${discovered.size} songs.")
            } catch (e: Exception) {
                onError(e.message ?: "Dropbox scan failed")
            } finally {
                releaseScanWakeLock()
            }
        }
    }

    fun runOneDriveScan(
        account: com.beatraxus.app.repository.OneDriveAccount,
        allowedFormats: Set<String>,
        onProgress: (Float) -> Unit,
        onDiscoveryComplete: (List<Song>) -> Unit,
        onSongUpdated: (Song) -> Unit,
        onComplete: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        if (libraryScanJob?.isActive == true) return
        libraryScanJob = serviceScope.launch(Dispatchers.Default) {
            acquireScanWakeLock()
            try {
                val token = onedriveAccountRepository.getAccessToken(account.email)
                if (token == null) {
                    onError("Failed to get OneDrive access token")
                    return@launch
                }

                val scanner = com.beatraxus.app.drive.OneDriveLibraryScanner(application)
                val graphClient = com.microsoft.graph.requests.GraphServiceClient.builder()
                    .authenticationProvider { _ ->
                        java.util.concurrent.CompletableFuture.completedFuture(token)
                    }
                    .buildClient()

                var totalFound = 0
                scanner.scanAccountFlow(graphClient, account.email, allowedFormats).collect { page ->
                    totalFound += page.size
                    withContext(Dispatchers.IO) {
                        songDao.insertSongs(page.map { it.toEntity() })
                    }
                    onDiscoveryComplete(page)
                }

                if (totalFound > 0) {
                    // Similar enrichment logic as GDrive/Dropbox could be added here
                }
                
                onComplete("OneDrive sync complete. Found $totalFound songs.")
            } catch (e: Exception) {
                onError(e.message ?: "OneDrive scan failed")
            } finally {
                releaseScanWakeLock()
            }
        }
    }

    fun runBoxScan(
        account: com.beatraxus.app.repository.BoxAccount,
        allowedFormats: Set<String>,
        onProgress: (Float) -> Unit,
        onDiscoveryComplete: (List<Song>) -> Unit,
        onSongUpdated: (Song) -> Unit,
        onComplete: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        if (libraryScanJob?.isActive == true) return
        libraryScanJob = serviceScope.launch(Dispatchers.Default) {
            acquireScanWakeLock()
            try {
                val session = com.box.androidsdk.content.models.BoxSession(application)
                // session.setUserId(account.userId)
                val scanner = com.beatraxus.app.drive.BoxLibraryScanner(application)
                var totalFound = 0
                scanner.scanAccountFlow(session, account.email, allowedFormats).collect { discovered ->
                    totalFound += discovered.size
                    withContext(Dispatchers.IO) {
                        songDao.insertSongs(discovered.map { it.toEntity() })
                    }
                    onDiscoveryComplete(discovered)
                }
                onComplete("Box sync complete. Found $totalFound songs.")
            } catch (e: Exception) {
                onError(e.message ?: "Box scan failed")
            } finally {
                releaseScanWakeLock()
            }
        }
    }

    fun runNextcloudScan(
        account: com.beatraxus.app.repository.NextcloudAccount,
        allowedFormats: Set<String>,
        onProgress: (Float) -> Unit,
        onDiscoveryComplete: (List<Song>) -> Unit,
        onSongUpdated: (Song) -> Unit,
        onComplete: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        if (libraryScanJob?.isActive == true) return
        libraryScanJob = serviceScope.launch(Dispatchers.Default) {
            acquireScanWakeLock()
            try {
                val scanner = com.beatraxus.app.drive.NextcloudLibraryScanner(application)
                var totalFound = 0
                // Nextcloud scanner needs credentials
                scanner.scanAccountFlow(account.serverUrl, account.username, account.appPassword, allowedFormats).collect { discovered ->
                    totalFound += discovered.size
                    withContext(Dispatchers.IO) {
                        songDao.insertSongs(discovered.map { it.toEntity() })
                    }
                    onDiscoveryComplete(discovered)
                }
                onComplete("Nextcloud sync complete. Found $totalFound songs.")
            } catch (e: Exception) {
                onError(e.message ?: "Nextcloud scan failed")
            } finally {
                releaseScanWakeLock()
            }
        }
    }

    fun runFolderScan(
        folders: List<String>,
        onProgress: (Float, Int, Int, Int) -> Unit,
        onComplete: (List<Song>, String) -> Unit,
        onError: (String) -> Unit
    ) {
        if (libraryScanJob?.isActive == true) return
        libraryScanJob = serviceScope.launch(Dispatchers.Default) {
            acquireScanWakeLock()
            try {
                val allResults = mutableListOf<Song>()
                var totalProcessed = 0
                val allAlbums = mutableSetOf<String>()
                val allArtists = mutableSetOf<String>()
                
                for ((index, folder) in folders.withIndex()) {
                    val results = musicRepository.scanAudioFiles(fullScan = false, targetPath = folder) { count, albums, artists, progress ->
                        // Partial progress
                        val overallProgress = (index.toFloat() + progress) / folders.size.toFloat()
                        onProgress(overallProgress, totalProcessed + count, allAlbums.size + albums, allArtists.size + artists)
                        updateScanningProgress(overallProgress, totalProcessed + count, false)
                    }
                    allResults.addAll(results)
                    totalProcessed += results.size
                    allAlbums.addAll(results.map { it.album })
                    allArtists.addAll(results.map { it.artist })
                }
                
                // Save to DB
                withContext(Dispatchers.IO) {
                    val entities = allResults.map { it.toEntity() }
                    entities.chunked(200).forEach { chunk ->
                        songDao.insertSongs(chunk)
                    }
                }
                
                onComplete(allResults, "Added ${allResults.size} songs from folders")
                updateScanningProgress(1.0f, allResults.size, true)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                onError(e.message ?: "Folder scan failed")
                updateScanningProgress(1.0f, 0, true)
            } finally {
                releaseScanWakeLock()
            }
        }
    }

    fun cancelLibraryScan() {
        libraryScanJob?.cancel()
        libraryScanJob = null
    }

    private var scanWakeLock: PowerManager.WakeLock? = null


    private fun acquireScanWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        scanWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Beatraxus:LibraryScan").apply {
            setReferenceCounted(false)
            acquire(10 * 60 * 1000L) // 10 min safety timeout, don't hold forever
        }
    }

    private fun releaseScanWakeLock() {
        scanWakeLock?.let { if (it.isHeld) it.release() }
        scanWakeLock = null
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
                .setSmallIcon(R.drawable.ic_search_notification)
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
                .setSmallIcon(R.drawable.ic_search_notification)
                .setProgress(100, (progress * 100).toInt(), false)
                .setOngoing(true)
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOnlyAlertOnce(true)
                .build()
            
            notificationManager.notify(ENRICH_NOTIFICATION_ID, notification)
        }
    }

    fun getNextSong(): Song? {
        if (playlist.isEmpty()) return null
        val repeatMode = engine.playbackStateFlow.value.repeatMode
        return when (repeatMode) {
            RepeatMode.ONE -> playlist.getOrNull(currentIndex)
            RepeatMode.ALL -> if (currentIndex < playlist.size - 1) playlist[currentIndex + 1] else playlist[0]
            RepeatMode.OFF -> if (currentIndex < playlist.size - 1) playlist[currentIndex + 1] else null
        }
    }
    
    fun getUpcomingSongs(): List<Song> {
        if (playlist.isEmpty()) return emptyList()
        val repeatMode = engine.playbackStateFlow.value.repeatMode
        return when (repeatMode) {
            RepeatMode.ONE -> {
                val current = playlist.getOrNull(currentIndex)
                if (current != null) {
                    val remaining = if (currentIndex < playlist.size - 1) playlist.subList(currentIndex + 1, playlist.size) else emptyList()
                    listOf(current) + remaining
                } else emptyList()
            }
            RepeatMode.ALL -> {
                if (currentIndex < playlist.size - 1) {
                    playlist.subList(currentIndex + 1, playlist.size) + playlist.subList(0, currentIndex + 1)
                } else {
                    playlist
                }
            }
            RepeatMode.OFF -> {
                if (currentIndex < playlist.size - 1) playlist.subList(currentIndex + 1, playlist.size)
                else emptyList()
            }
        }
    }

    fun getPreviousSongs(): List<Song> {
        if (playlist.isEmpty() || currentIndex <= 0) return emptyList()
        return playlist.subList(0, currentIndex)
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
            playbackJob?.cancel()
            currentIndex = index
            val song = playlist[currentIndex]
            
            engine.prepare(song)
            saveState()
            
            playbackJob = serviceScope.launch {
                delay(150)
                if (isActive && requestAudioFocus()) {
                    engine.play(song)
                    // We don't need to call updateUpcomingSongs() here explicitly anymore,
                    // as engine.play(song) will trigger the state collector in onCreate,
                    // which will call updateUpcomingSongs() automatically.
                }
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
        playbackJob?.cancel()

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

        if (playlist.isNotEmpty()) {
            val song = playlist[currentIndex]

            if (com.beatraxus.app.cast.CastManager.isConnected) {
                val route = androidx.mediarouter.media.MediaRouter.getInstance(this).selectedRoute
                com.beatraxus.app.cast.CastManager.castSong(this, route, song, song.uri.toString())
                _playbackStateFlow.update { it.copy(currentSong = song, isPlaying = true) }
                return
            }

            engine.prepare(song)
            saveState()

            playbackJob = serviceScope.launch {
                delay(150)
                if (isActive && requestAudioFocus()) {
                    engine.play(song)
                    // We don't need to call updateUpcomingSongs() here explicitly anymore,
                    // as engine.play(song) will trigger the state collector in onCreate,
                    // which will call updateUpcomingSongs() automatically.
                }
            }
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

        // Lets CastManager auto-load "whatever is currently playing" if the user connects to a
        // TV through the system Cast icon rather than an explicit "cast this song" action.
        com.beatraxus.app.cast.CastManager.nowPlayingProvider = {
            engine.playbackStateFlow.value.currentSong?.let { song ->
                song to engine.currentPositionMs()
            }
        }
    }

    val currentPositionMs: Long
        get() = if (com.beatraxus.app.cast.CastManager.isConnected) {
            com.beatraxus.app.cast.CastManager.currentPositionMs()
        } else {
            engine.currentPositionMs()
        }

    fun playSong(song: Song) {
        playbackJob?.cancel()

        originalPlaylist = listOf(song)
        playlist = listOf(song)
        currentIndex = 0

        if (com.beatraxus.app.cast.CastManager.isConnected) {
            val route = androidx.mediarouter.media.MediaRouter.getInstance(this).selectedRoute
            com.beatraxus.app.cast.CastManager.castSong(this, route, song, song.uri.toString())
            _playbackStateFlow.update { it.copy(currentSong = song, isPlaying = true) }
            return
        }

        engine.prepare(song)
        saveState()

        playbackJob = serviceScope.launch {
            delay(150)
            if (isActive && requestAudioFocus()) {
                engine.play(song)
            }
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
            .setSmallIcon(R.drawable.ic_search_notification)
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
                builder.setLargeIcon(getDefaultAlbumArt())
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
        const val ACTION_PLAY_PAUSE = "com.beatraxus.app.ACTION_PLAY_PAUSE"
        const val ACTION_NEXT = "com.beatraxus.app.ACTION_NEXT"
        const val ACTION_PREVIOUS = "com.beatraxus.app.ACTION_PREVIOUS"
        const val ACTION_TOGGLE_SHUFFLE = "com.beatraxus.app.ACTION_TOGGLE_SHUFFLE"
        const val ACTION_TOGGLE_REPEAT = "com.beatraxus.app.ACTION_TOGGLE_REPEAT"
    }

    private fun refreshOutputRoute(reconfigure: Boolean = false) {
        val routeState = audioOutput.refreshRouteState()
        _outputRouteStateFlow.value = routeState
        if (reconfigure) {
            engine.reconfigureOutput()
        }
    }

    private fun saveState(sync: Boolean = false) {
        val prefs = getSharedPreferences("beatraxus", Context.MODE_PRIVATE)
        val currentSong = playlist.getOrNull(currentIndex)
        val editor = prefs.edit()
        
        // Ensure we only save the queue if we have valid data and a valid index.
        // This prevents overwriting a good saved state with an incomplete/initial one.
        if (playlist.isNotEmpty() && currentIndex in playlist.indices) {
            editor.putString("last_queue_ids", playlist.joinToString(",") { it.id })
            editor.putString("last_original_queue_ids", originalPlaylist.joinToString(",") { it.id })
            editor.putInt("last_queue_index", currentIndex)
            
            if (currentSong != null) {
                editor.putString("last_song_id", currentSong.id)
                editor.putLong("last_song_pos", engine.currentPositionMs())
            }
        }
        
        if (sync) {
            editor.commit()
        } else {
            editor.apply()
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val currentSong = engine.playbackStateFlow.value.currentSong
        val isPlaying = engine.playbackStateFlow.value.isPlaying

        // Save only the minimal state synchronously (fast, non-blocking on main thread
        // since SharedPreferences.commit() on a small payload is cheap).
        saveState(sync = true)

        // Reset internal state for potential service reuse/restart
        hasRestoredFromDisk = false
        playlist = emptyList()
        originalPlaylist = emptyList()
        currentIndex = -1

        stopForeground(STOP_FOREGROUND_REMOVE)

        // Do the heavier cleanup (engine stop/release, cache clearing) off the main thread,
        // in a NonCancellable coroutine so it survives the service stopping.
        serviceScope.launch(NonCancellable) {
            engine.stopSync()
            engine.release()

            // Clear Telegram cache when app is fully closed (swiped away)
            (application as? com.beatraxus.app.BeatraxusApplication)?.clearTelegramCache()

            if (isPlaying && currentSong != null) {
                cloudCacheManager.clearFullCache(excludeId = currentSong.id)
            } else {
                cloudCacheManager.clearFullCache()
            }
            stopSelf()
        }

        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        // Safety reset in case onTaskRemoved was skipped
        // (engine.release() below is now idempotent — see AudioEngine.release())
        hasRestoredFromDisk = false
        playlist = emptyList()
        originalPlaylist = emptyList()
        currentIndex = -1

        val prefs = getSharedPreferences("beatraxus", Context.MODE_PRIVATE)
        prefs.unregisterOnSharedPreferenceChangeListener(prefListener)

        val finalState = engine.playbackStateFlow.value.copy(isPlaying = false)
        runBlocking { updateAllWidgets(finalState) }

        // Ensure cloud cache is cleared on destroy if we are not just restarting
        val isPlayingLocal = engine.playbackStateFlow.value.isPlaying
        val currentSongLocal = engine.playbackStateFlow.value.currentSong
        if (!isPlayingLocal) {
            cloudCacheManager.clearFullCache()
        } else if (currentSongLocal != null) {
            cloudCacheManager.clearFullCache(excludeId = currentSongLocal.id)
        }

        cloudCacheManager.release()

        serviceScope.cancel()
        audioManager.unregisterAudioDeviceCallback(audioDeviceCallback)
        try {
            unregisterReceiver(noisyReceiver)
        } catch (e: Exception) {}
        engine.release()
        mediaSession.release()
        abandonAudioFocus()
        
        // Close TDLib when the service is destroyed to prevent background processing
        (application as com.beatraxus.app.BeatraxusApplication).tdLibManager.close()

        super.onDestroy()
    }
}

