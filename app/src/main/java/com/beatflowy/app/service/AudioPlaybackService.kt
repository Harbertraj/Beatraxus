package com.beatflowy.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import com.beatflowy.app.MainActivity
import com.beatflowy.app.engine.AudioEngine
import com.beatflowy.app.engine.AudioState
import com.beatflowy.app.engine.PlaybackState
import com.beatflowy.app.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class AudioPlaybackService : Service() {

    companion object {
        private const val CHANNEL_ID = "beatflowy_playback"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_PLAY_PAUSE = "com.beatflowy.PLAY_PAUSE"
        private const val ACTION_NEXT = "com.beatflowy.NEXT"
        private const val ACTION_PREVIOUS = "com.beatflowy.PREVIOUS"
    }

    private lateinit var engine: AudioEngine
    private lateinit var mediaSession: MediaSessionCompat
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): AudioPlaybackService = this@AudioPlaybackService
    }

    val audioStateFlow: StateFlow<AudioState> get() = engine.audioStateFlow
    val playbackStateFlow: StateFlow<PlaybackState> get() = engine.playbackStateFlow
    val currentPositionMs: Long get() = engine.currentPositionMs

    override fun onCreate() {
        super.onCreate()
        engine = AudioEngine(applicationContext)
        engine.prepare()
        createNotificationChannel()
        setupMediaSession()
        startForeground(NOTIFICATION_ID, buildNotification(null, false))
        serviceScope.launch {
            engine.playbackStateFlow.collect { state ->
                updateMediaSession(state)
                updateNotification(state)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY_PAUSE -> engine.togglePlayPause()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onDestroy() {
        serviceScope.cancel()
        engine.release()
        mediaSession.release()
        super.onDestroy()
    }

    fun playSong(song: Song) = engine.play(song)
    fun togglePlayPause() = engine.togglePlayPause()
    fun seekTo(positionMs: Long) = engine.seekTo(positionMs)
    fun setResamplingEnabled(enabled: Boolean) = engine.setResamplingEnabled(enabled)
    fun setEqualizerEnabled(enabled: Boolean) = engine.setEqualizerEnabled(enabled)
    fun setEqBandGain(band: Int, gain: Float) = engine.setEqGain(band, gain)

    private fun setupMediaSession() {
        mediaSession = MediaSessionCompat(this, "BeatflowySession").apply {
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = engine.resume()
                override fun onPause() = engine.pause()
                override fun onSeekTo(pos: Long) = engine.seekTo(pos)
            })
            isActive = true
        }
    }

    private fun updateMediaSession(state: PlaybackState) {
        val pbStateInt = if (state.isPlaying) PlaybackStateCompat.STATE_PLAYING
                         else PlaybackStateCompat.STATE_PAUSED
        mediaSession.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                    PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_PLAY_PAUSE or
                    PlaybackStateCompat.ACTION_SEEK_TO
                )
                .setState(pbStateInt, state.positionMs, 1.0f)
                .build()
        )
        state.currentSong?.let { song ->
            mediaSession.setMetadata(
                MediaMetadataCompat.Builder()
                    .putString(MediaMetadataCompat.METADATA_KEY_TITLE, song.title)
                    .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, song.artist)
                    .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, song.album)
                    .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, song.durationMs)
                    .build()
            )
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Beatflowy Playback", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Music playback controls"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun updateNotification(state: PlaybackState) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(state.currentSong, state.isPlaying))
    }

    private fun buildNotification(song: Song?, isPlaying: Boolean): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val playPauseIntent = PendingIntent.getService(
            this, 1,
            Intent(this, AudioPlaybackService::class.java).apply { action = ACTION_PLAY_PAUSE },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val playPauseAction = NotificationCompat.Action(
            if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
            if (isPlaying) "Pause" else "Play",
            playPauseIntent
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(song?.title ?: "Beatflowy")
            .setContentText(if (song != null) "${song.artist} — ${song.album}" else "Ready to play")
            .setContentIntent(contentIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(isPlaying)
            .setShowWhen(false)
            .addAction(playPauseAction)
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0)
            )
            .build()
    }
}
