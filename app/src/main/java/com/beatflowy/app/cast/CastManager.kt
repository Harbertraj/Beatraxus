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
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.common.images.WebImage

object CastManager {
    private const val TAG = "CastManager"
    var castContext: CastContext? = null
    val availableDevices = mutableStateListOf<MediaRouter.RouteInfo>()
    var isConnected by mutableStateOf(false)
    var connectedDeviceName by mutableStateOf<String?>(null)

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
                override fun onSessionStarting(session: CastSession) {}
                override fun onSessionStarted(session: CastSession, sessionId: String) {
                    isConnected = true
                    connectedDeviceName = session.castDevice?.friendlyName
                }
                override fun onSessionStartFailed(session: CastSession, error: Int) {
                    isConnected = false
                }
                override fun onSessionEnding(session: CastSession) {}
                override fun onSessionEnded(session: CastSession, error: Int) {
                    isConnected = false
                    connectedDeviceName = null
                }
                override fun onSessionResuming(session: CastSession, sessionId: String) {}
                override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {
                    isConnected = true
                    connectedDeviceName = session.castDevice?.friendlyName
                }
                override fun onSessionResumeFailed(session: CastSession, error: Int) {
                    isConnected = false
                }
                override fun onSessionSuspended(session: CastSession, reason: Int) {
                    isConnected = false
                }
            }, CastSession::class.java)
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing CastManager", e)
        }
    }

    fun castSong(context: Context, route: MediaRouter.RouteInfo, song: Song, streamUrl: String) {
        MediaRouter.getInstance(context).selectRoute(route)
        val castSession = castContext?.sessionManager?.currentCastSession
        val remoteMediaClient = castSession?.remoteMediaClient ?: return

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
    }

    fun stopCast() {
        castContext?.sessionManager?.endCurrentSession(true)
    }
}
