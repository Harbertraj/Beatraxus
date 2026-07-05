package com.beatflowy.app.cast

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.beatflowy.app.model.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaSeekOptions
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.google.android.gms.common.images.WebImage

object CastManager {
    private const val TAG = "CastManager"
    var castContext: CastContext? = null
    val availableDevices = mutableStateListOf<MediaRouter.RouteInfo>()
    var isConnected by mutableStateOf(false)
    var connectedDeviceName by mutableStateOf<String?>(null)

    private val _castMediaStatus = MutableStateFlow<MediaStatus?>(null)
    val castMediaStatus = _castMediaStatus.asStateFlow()

    private val mediaClientCallback = object : RemoteMediaClient.Callback() {
        override fun onStatusUpdated() {
            _castMediaStatus.value = castContext?.sessionManager?.currentCastSession?.remoteMediaClient?.mediaStatus
        }
    }

    private var pendingSongToCast: Pair<Song, String>? = null

    private val selector = MediaRouteSelector.Builder()
        .addControlCategory(CastMediaControlIntent.categoryForCast(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID))
        .build()

    fun initialize(context: Context) {
        try {
            castContext = CastContext.getSharedInstance(context)
            val mediaRouter = MediaRouter.getInstance(context)
            
            mediaRouter.addCallback(selector, object : MediaRouter.Callback() {
                override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) {
                    if (route.matchesSelector(selector)) {
                        if (availableDevices.none { it.id == route.id }) {
                            availableDevices.add(route)
                        }
                    }
                }

                override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) {
                    availableDevices.removeAll { it.id == route.id }
                }

                override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) {
                    val index = availableDevices.indexOfFirst { it.id == route.id }
                    if (index != -1) {
                        availableDevices[index] = route
                    }
                }
            }, MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN)

            castContext?.sessionManager?.addSessionManagerListener(object : SessionManagerListener<CastSession> {
                override fun onSessionStarting(session: CastSession) {
                    Log.d(TAG, "onSessionStarting: ${session.castDevice?.friendlyName}")
                }
                override fun onSessionStarted(session: CastSession, sessionId: String) {
                    Log.d(TAG, "onSessionStarted: sessionId=$sessionId, device=${session.castDevice?.friendlyName}")
                    isConnected = true
                    connectedDeviceName = session.castDevice?.friendlyName
                    
                    session.remoteMediaClient?.registerCallback(mediaClientCallback)
                    _castMediaStatus.value = session.remoteMediaClient?.mediaStatus

                    LocalCastServer.start(context)

                    pendingSongToCast?.let { (song, _) ->
                        Log.d(TAG, "Casting pending song: ${song.title}")
                        LocalCastServer.currentSong = song
                        val urlToCast = LocalCastServer.start(context) ?: song.uri.toString()
                        performLoad(session, song, urlToCast)
                    }
                }
                override fun onSessionStartFailed(session: CastSession, error: Int) {
                    val errorReason = when(error) {
                        0 -> "SUCCESS"
                        2000 -> "AUTHENTICATION_FAILED"
                        2002 -> "CANCELED"
                        2005 -> "INTERNAL_ERROR"
                        2100 -> "NETWORK_ERROR"
                        2101 -> "TCP_PROBER_TIMEOUT"
                        2102 -> "NOT_ALLOWED_BY_USER"
                        2103 -> "TIMEOUT"
                        2150 -> "CAST_CANCELLED"
                        2151 -> "CAST_NOT_AVAILABLE"
                        else -> "UNKNOWN ($error)"
                    }
                    Log.e(TAG, "onSessionStartFailed: $errorReason ($error)")
                    isConnected = false
                    pendingSongToCast = null
                    LocalCastServer.stop()
                }
                override fun onSessionEnding(session: CastSession) {
                    Log.d(TAG, "onSessionEnding")
                }
                override fun onSessionEnded(session: CastSession, error: Int) {
                    Log.d(TAG, "onSessionEnded: error code $error")
                    isConnected = false
                    connectedDeviceName = null
                    session.remoteMediaClient?.unregisterCallback(mediaClientCallback)
                    _castMediaStatus.value = null
                    LocalCastServer.stop()
                }
                override fun onSessionResuming(session: CastSession, sessionId: String) {
                    Log.d(TAG, "onSessionResuming: $sessionId")
                }
                override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {
                    Log.d(TAG, "onSessionResumed: wasSuspended=$wasSuspended, device=${session.castDevice?.friendlyName}")
                    isConnected = true
                    connectedDeviceName = session.castDevice?.friendlyName
                    
                    LocalCastServer.start(context)

                    pendingSongToCast?.let { (song, _) ->
                        LocalCastServer.currentSong = song
                        val urlToCast = LocalCastServer.start(context) ?: song.uri.toString()
                        performLoad(session, song, urlToCast)
                    }
                }
                override fun onSessionResumeFailed(session: CastSession, error: Int) {
                    Log.e(TAG, "onSessionResumeFailed: error code $error")
                    isConnected = false
                }
                override fun onSessionSuspended(session: CastSession, reason: Int) {
                    Log.d(TAG, "onSessionSuspended: $reason")
                    isConnected = false
                }
            }, CastSession::class.java)
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing CastManager", e)
        }
    }

    fun castSong(context: Context, route: MediaRouter.RouteInfo, song: Song, streamUrl: String) {
        val mediaRouter = MediaRouter.getInstance(context)
        val currentSession = castContext?.sessionManager?.currentCastSession
        
        if (currentSession?.isConnected == true && mediaRouter.selectedRoute.id == route.id) {
            LocalCastServer.currentSong = song
            val urlToCast = LocalCastServer.start(context) ?: song.uri.toString()
            performLoad(currentSession, song, urlToCast)
        } else {
            pendingSongToCast = Pair(song, streamUrl)
            mediaRouter.selectRoute(route)
        }
    }

    private fun performLoad(session: CastSession, song: Song, streamUrl: String) {
        val remoteMediaClient = session.remoteMediaClient ?: run {
            Log.e(TAG, "RemoteMediaClient is null")
            return
        }

        Log.d(TAG, "Attempting to load media: $streamUrl")

        val musicMetadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MUSIC_TRACK).apply {
            putString(MediaMetadata.KEY_TITLE, song.title)
            putString(MediaMetadata.KEY_ARTIST, song.artist)
            putString(MediaMetadata.KEY_ALBUM_TITLE, song.album)
            song.albumArtUri?.let {
                addImage(WebImage(it))
            }
        }

        val mediaInfo = MediaInfo.Builder(streamUrl)
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType("audio/*")
            .setMetadata(musicMetadata)
            .build()

        remoteMediaClient.load(MediaLoadRequestData.Builder().setMediaInfo(mediaInfo).build())
            .setResultCallback { result ->
                if (result.status.isSuccess) {
                    Log.d(TAG, "Media load command sent successfully")
                } else {
                    Log.e(TAG, "Failed to load media: ${result.status.statusMessage} (Code: ${result.status.statusCode})")
                }
            }
        pendingSongToCast = null
    }

    fun stopCast() {
        castContext?.sessionManager?.endCurrentSession(true)
        LocalCastServer.stop()
    }

    fun play() {
        castContext?.sessionManager?.currentCastSession?.remoteMediaClient?.play()
    }

    fun pause() {
        castContext?.sessionManager?.currentCastSession?.remoteMediaClient?.pause()
    }

    fun seek(position: Long) {
        val options = MediaSeekOptions.Builder()
            .setPosition(position)
            .build()
        castContext?.sessionManager?.currentCastSession?.remoteMediaClient?.seek(options)
    }

    fun next() {
        // Cast SDK doesn't have a direct "next" on RemoteMediaClient if we are just loading single items
        // We'll need to handle this in AudioPlaybackService by calling castSong with the next song
    }
}
