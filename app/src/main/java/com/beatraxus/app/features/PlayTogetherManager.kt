package com.beatraxus.app.features

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.os.SystemClock
import android.net.Uri
import com.beatraxus.app.model.Song
import com.beatraxus.app.model.SongSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.security.MessageDigest
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class PtMember(val uid: String, val name: String, val isMe: Boolean, val isController: Boolean)

enum class PtStatus { IDLE, CONNECTING, CONNECTED }

data class PtUiState(
    val configured: Boolean = false,
    /** Why google-services.json could not be used (shown on the screen). */
    val configProblem: String? = null,
    val apiKey: String = "",
    val databaseUrl: String = "",
    val displayName: String = "",
    val status: PtStatus = PtStatus.IDLE,
    val roomCode: String? = null,
    val members: List<PtMember> = emptyList(),
    val nowPlayingTitle: String? = null,
    val nowPlayingArtist: String? = null,
    val controllerName: String? = null,
    val roomPlaying: Boolean = false,
    /** The song the room is playing does not exist in this phone's library. */
    val songMissing: Boolean = false,
    /** The room song is not in this phone's library and is being streamed from the controller. */
    val streaming: Boolean = false,
    /** 0..100 while the song is being received from the controller over WebRTC, -1 otherwise. */
    val transferPercent: Int = -1,
    /** Same-place transfer over Nearby Connections (Bluetooth / Wi-Fi Direct) is switched on. */
    val nearbyEnabled: Boolean = false,
    /** Phones currently connected over Nearby (controller: everyone who joined it; others: 0 or 1). */
    val nearbyPeers: Int = 0,
    /** The route the song transfer in progress (or the last one received) uses. */
    val route: PtRoute? = null,
    /** Why a route failed and what is tried next, e.g. "Nearby: Bluetooth is off. Trying Own Wi-Fi…". */
    val routeProblem: String? = null,
    val message: String? = null,
    /** Positive notice (e.g. "Database connected"), shown in green. */
    val info: String? = null,
    val busy: Boolean = false
)

/**
 * "Play Together": everybody in a room hears the same song at the same time.
 *
 * Whoever last picks a song / presses play / pause / seeks becomes the controller and
 * everyone else follows (their current song is paused and replaced). The sync state lives in
 * Firebase Realtime Database and is read / written over its REST API (+ server-sent events for
 * live updates), so no Firebase SDK or google-services plugin is needed - only the project's
 * Web API key and Realtime Database URL, which are entered once on the Play Together screen.
 *
 * Audio is NOT streamed: every phone plays the matching song from its own library (matched by
 * title + artist + duration), and the room only keeps them in sync.
 */
class PlayTogetherManager(
    context: Context,
    private val scope: CoroutineScope,
    private val host: Host
) {

    /** What the manager needs from the player. Mutating calls may be made from any thread. */
    interface Host {
        fun librarySongs(): List<Song>
        fun currentSong(): Song?
        fun isPlaying(): Boolean
        fun positionMs(): Long
        fun queue(): List<Song>
        fun playQueue(songs: List<Song>, index: Int, positionMs: Long, playing: Boolean)
        fun setPlaying(play: Boolean)
        fun seekTo(ms: Long)
    }

    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("play_together", Context.MODE_PRIVATE)
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val http = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build()
    private val uploadHttp = OkHttpClient.Builder()
        .callTimeout(0, TimeUnit.MILLISECONDS).writeTimeout(120, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).build()
    private val streamHttp = OkHttpClient.Builder().readTimeout(75, TimeUnit.SECONDS).build()

    // Database URL discovery (declared before _state because loadConfig() fills them in)
    private var cfgProjectId: String = ""
    private var cfgDbUrlFromFile: String = ""
    private var cfgBucket: String = ""
    @Volatile private var dbResolved = false
    private val dbMutex = Mutex()

    private val _state = MutableStateFlow(loadConfig())
    val state: StateFlow<PtUiState> = _state.asStateFlow()

    // ---- identity / auth -----------------------------------------------------------------
    private var uid: String = prefs.getString("uid", null)
        ?: UUID.randomUUID().toString().replace("-", "").take(12).also { prefs.edit().putString("uid", it).apply() }
    private var idToken: String? = null
    private var refreshToken: String? = prefs.getString("refresh", null)
    private var tokenExpiryMs = 0L

    // ---- room runtime --------------------------------------------------------------------
    private var room: String? = null
    private var myName: String = _state.value.displayName
    private var clockOffsetMs = 0L
    private var roomState: RoomState? = null
    private var lastAppliedAt = 0L
    @Volatile private var ignoreLocalUntil = 0L
    /** The newest `state` node pushed by the live stream; applied at once, before the full room re-read. */
    private val fastState = java.util.concurrent.atomic.AtomicReference<JSONObject?>(null)
    private var resyncJob: Job? = null

    private var streamJob: Job? = null
    private var heartbeatJob: Job? = null
    private var tickerJob: Job? = null
    private var refreshJob: Job? = null
    private val refreshTrigger = Channel<Unit>(Channel.CONFLATED)

    /** [url] = where other phones can stream this song from when they do not own it. */
    private data class Entry(
        val title: String, val artist: String, val album: String, val durationMs: Long,
        val url: String = "", val ext: String = ""
    )

    // ---- streaming (controller -> phones that do not have the song) -----------------------
    private val streamUrls = java.util.concurrent.ConcurrentHashMap<String, String>() // local song id -> url
    private val uploadedNames = java.util.concurrent.CopyOnWriteArrayList<String>()
    private val uploading = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val uploadMutex = Mutex()

    // ---- peer-to-peer transfer (controller -> phones that do not have the song) ----------------
    /** Receiver: song signature -> file received from the controller. */
    private val receivedFiles = java.util.concurrent.ConcurrentHashMap<String, java.io.File>()
    /** Receiver: the session id of the transfer I asked for (null = none in flight). */
    @Volatile private var mySid: String? = null
    @Volatile private var mySig: String? = null
    @Volatile private var lastNeedAt = 0L
    /** Last time the running song transfer reported progress; a live transfer is never restarted. */
    @Volatile private var lastProgressAt = 0L
    /** Request (session id) -> signature of the song it was made for, so a file is never stored under another song. */
    private val sidSig = java.util.concurrent.ConcurrentHashMap<String, String>()
    /** Controller: sessions already started, so the same request is not served twice. */
    private val servedSids = java.util.concurrent.CopyOnWriteArraySet<String>()

    // ---- loading the next songs ahead of time (receiver) ----------------------------------------
    /** Signature of the song being fetched ahead of time (null = the running transfer, if any, is for the current song). */
    @Volatile private var preloadSig: String? = null
    /**
     * Songs being fetched ahead of time, by session id. Up to [MAX_PARALLEL_PRELOADS] run side by side
     * (Nearby and/or internet), independent of the single request for the song playing right now.
     */
    private class Preload(val sig: String, val sid: String, @Volatile var route: PtRoute, val startedAt: Long) {
        @Volatile var lastProgressAt: Long = startedAt
    }
    private val preloads = java.util.concurrent.ConcurrentHashMap<String, Preload>()
    @Volatile private var lastPreloadAt = 0L
    /** Song signature -> when its preload last failed (not retried for [PRELOAD_RETRY_MS]). */
    private val preloadFailedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    /** Songs whose file was already searched for an embedded cover. */
    private val artTried = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    // ---- own Wi-Fi route: the controller opens a local-only hotspot, phones join it and download ----------
    private val wifiHost = PtLocalWifiHost(app, scope).also { it.onStopped = { onWifiHostStopped() } }
    private val wifiClient = PtLocalWifiClient(app, scope)
    /** Receiver: the routes tried for the song being fetched, and the one running now. */
    @Volatile private var myOrder: List<PtRoute> = emptyList()
    @Volatile private var myRoute: PtRoute? = null
    /** Receiver: the request (sid) whose Wi-Fi credentials were already used, and when its need was posted (server time). */
    @Volatile private var wifiAttemptSid: String? = null
    @Volatile private var wifiNeedAt = 0L
    /** Receiver: when the own-Wi-Fi route last failed; it is skipped for [WIFI_SKIP_MS] so every song does not wait for it again. */
    @Volatile private var ownWifiFailedAt = -WIFI_SKIP_MS
    private var wifiWaitJob: Job? = null
    private var wifiFetchJob: Job? = null
    /** Controller: Wi-Fi requests already looked at, the room whose credentials are published, and the last hotspot error. */
    private val wifiHandledSids = java.util.concurrent.CopyOnWriteArraySet<String>()
    @Volatile private var wifiCredsRoom: String? = null
    private val wifiBusy = AtomicBoolean(false)
    @Volatile private var wifiFailure: String? = null
    @Volatile private var wifiFailedAt = 0L

    private val transfer: PtFileTransfer by lazy {
        PtFileTransfer(app, scope) { sid, kind, json ->
            val code = room ?: return@PtFileTransfer
            // c = controller side, r = receiver side
            val isSender = servedSids.contains(sid)
            when (kind) {
                "offer" -> call("PUT", "$code/signal/$sid/offer", json)
                "answer" -> call("PUT", "$code/signal/$sid/answer", json)
                else -> call("POST", "$code/signal/$sid/ice_${if (isSender) "c" else "r"}", json)
            }
        }.also { it.listener = transferListener }
    }

    /** Same-place route: tried first; when it cannot deliver, own Wi-Fi and then WebRTC (above) take over. */
    private val nearby: PtNearbyTransfer by lazy {
        PtNearbyTransfer(app, scope, onPeers = { n -> _state.update { it.copy(nearbyPeers = n) } }) { sid ->
            onRouteFailed(sid, PtRoute.NEARBY, PtRadios.problem(app) ?: "No phone nearby answered")
        }.also { it.listener = transferListener }
    }

    private fun nearbyReady() = _state.value.nearbyEnabled && PtNearbyTransfer.hasPermissions(app)

    /** True once the person switched Nearby off by hand; stops it being switched on automatically. */
    val nearbyOptedOut: Boolean get() = prefs.getBoolean("nearby_opt_out", false)

    /**
     * Switch same-place transfer on/off (the screen asks for the permissions and radios first).
     * [byUser] is true when this comes from the person's own switch tap, which is remembered so a
     * manual "off" is not undone the next time a room is created or joined.
     */
    fun setNearbyEnabled(on: Boolean, byUser: Boolean = false) {
        if (byUser) prefs.edit().putBoolean("nearby_opt_out", !on).apply()
        val enabled = on && PtNearbyTransfer.hasPermissions(app)
        prefs.edit().putBoolean(PREF_NEARBY_ON, enabled).apply()
        _state.update { it.copy(nearbyEnabled = enabled) }
        if (!enabled) { nearby.stopAll(); linkedController = null }
        else roomState?.let { st ->
            // connect right away instead of waiting for the next room update
            scope.launch(Dispatchers.IO) { syncNearbyHost(st); syncNearbyLink(st) }
        }
    }

    private val transferListener = object : PtFileTransfer.Listener {
        override fun onProgress(sid: String, percent: Int) {
            preloads[sid]?.let { p ->
                p.lastProgressAt = SystemClock.elapsedRealtime()
                // the room just reached this song: show its progress like a normal request
                if (_state.value.songMissing && isRoomSong(p.sig)) _state.update { it.copy(transferPercent = percent) }
                return
            }
            if (sid == mySid) {
                lastProgressAt = SystemClock.elapsedRealtime()
                // a song that is only being loaded ahead of time does not show as "receiving"
                if (preloadSig == null) {
                    _state.update { it.copy(transferPercent = percent) }
                }
            }
        }

        override fun onReceived(sid: String, file: java.io.File) {
            preloads.remove(sid)?.let { p ->
                preloadFailedAt.remove(p.sig)
                transfer.close(sid)
                nearby.cancelRequest(sid)
                receivedFiles[p.sig] = file
                val wasWaiting = _state.value.songMissing && isRoomSong(p.sig)
                if (wasWaiting) _state.update { it.copy(transferPercent = -1, message = null, route = p.route, routeProblem = null) }
                scope.launch(Dispatchers.IO) {
                    try { room?.let { c -> call("DELETE", "$c/needs/$sid"); call("DELETE", "$c/signal/$sid") } } catch (_: Exception) { }
                    if (wasWaiting) { lastAppliedAt = 0L; roomState?.let { applyRemote(it) } } // the room is waiting for it: play now
                    roomState?.let { maybePreload(it) } // a slot is free: start the next song
                }
                return
            }
            if (sid != mySid) {
                // A transfer for a song this phone no longer waits for: keep the file only under ITS OWN song.
                val own = sidSig.remove(sid)
                nearby.cancelRequest(sid)
                transfer.close(sid)
                if (own != null) receivedFiles[own] = file else file.delete()
                scope.launch(Dispatchers.IO) { roomState?.let { applyRemote(it); maybePreload(it) } }
                return
            }
            sidSig.remove(sid)
            val sig = mySig ?: return
            val via = myRoute
            val wasPreload = preloadSig != null
            mySid = null
            preloadSig = null
            preloadFailedAt.remove(sig)
            nearby.cancelRequest(sid)
            transfer.close(sid)
            wifiWaitJob?.cancel()
            wifiAttemptSid = null
            myRoute = null
            receivedFiles[sig] = file
            _state.update {
                if (wasPreload) it.copy(transferPercent = -1, message = null)
                else it.copy(transferPercent = -1, message = null, route = via ?: it.route, routeProblem = null)
            }
            scope.launch(Dispatchers.IO) {
                try { room?.let { c -> call("DELETE", "$c/needs/$uid"); call("DELETE", "$c/signal/$sid") } } catch (_: Exception) { }
                // play it in sync with the room now
                lastAppliedAt = 0L
                roomState?.let { applyRemote(it) }
                // ...then go on with the next song of the queue
                roomState?.let { maybePreload(it) }
            }
        }

        override fun onFailed(sid: String, reason: String) {
            preloads[sid]?.let { failPreload(it); return }
            if (sid == mySid) {
                val sig = mySig
                val wasPreload = preloadSig != null
                mySid = null
                preloadSig = null
                if (wasPreload) {
                    // loading ahead is best effort: skip this song for a while, no message
                    if (sig != null) preloadFailedAt[sig] = SystemClock.elapsedRealtime()
                } else {
                    lastNeedAt = SystemClock.elapsedRealtime() // retry after the 8 s throttle
                    _state.update { it.copy(transferPercent = -1, message = "Song transfer failed ($reason). Retrying…") }
                }
            }
            scope.launch(Dispatchers.IO) {
                try { room?.let { c -> call("DELETE", "$c/signal/$sid") } } catch (_: Exception) { }
            }
        }
    }

    private data class RoomState(
        val by: String,
        val byName: String,
        val playing: Boolean,
        val positionMs: Long,
        val updatedAt: Long,
        val song: Entry?,
        val queue: List<Entry>,
        /** True for the controller's periodic position refresh (not a seek by the person): followers only nudge for big errors. */
        val anchor: Boolean = false
    )

    // =========================================================================================
    // Public API
    // =========================================================================================

    /**
     * Reads the Firebase project settings from google-services.json (copied into the app's
     * assets at build time from app/google-services.json): the Web API key and the Realtime
     * Database URL. Nothing is typed by the person.
     */
    private fun loadConfig(): PtUiState {
        val name = prefs.getString("name", null) ?: (Build.MODEL ?: "My phone")
        val base = PtUiState(displayName = name, nearbyEnabled = prefs.getBoolean(PREF_NEARBY_ON, false) && PtNearbyTransfer.hasPermissions(app))
        val text = try {
            app.assets.open("google-services.json").bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            return base.copy(
                configProblem = "google-services.json was not found in the app. Put it in the app folder " +
                    "(app/google-services.json) and rebuild."
            )
        }
        return try {
            val root = JSONObject(text)
            val info = root.optJSONObject("project_info")
            val projectId = info?.optString("project_id").orEmpty()
            cfgProjectId = projectId
            cfgBucket = info?.optString("storage_bucket").orEmpty().trim()
            cfgDbUrlFromFile = info?.optString("firebase_url").orEmpty().trim().trimEnd('/')
            var dbUrl = cfgDbUrlFromFile
            if (dbUrl.isBlank() && projectId.isNotBlank()) dbUrl = "https://$projectId-default-rtdb.firebaseio.com"
            // A URL the person pasted in (Database not found screen) always wins.
            val override = prefs.getString("dbUrlOverride", null)?.trim().orEmpty()
            if (override.isNotBlank()) { dbUrl = override; dbResolved = true }

            // pick the client entry for this app (fall back to the first one)
            val clients = root.optJSONArray("client")
            var chosen: JSONObject? = null
            if (clients != null) {
                for (i in 0 until clients.length()) {
                    val c = clients.optJSONObject(i) ?: continue
                    val pkg = c.optJSONObject("client_info")
                        ?.optJSONObject("android_client_info")?.optString("package_name")
                    if (pkg == app.packageName) { chosen = c; break }
                    if (chosen == null) chosen = c
                }
            }
            val key = chosen?.optJSONArray("api_key")?.optJSONObject(0)?.optString("current_key").orEmpty()
            when {
                key.isBlank() -> base.copy(configProblem = "No API key found in google-services.json.")
                dbUrl.isBlank() -> base.copy(
                    configProblem = "No Realtime Database URL in google-services.json. Create the Realtime " +
                        "Database in the Firebase console, download google-services.json again and rebuild."
                )
                else -> base.copy(configured = true, apiKey = key, databaseUrl = dbUrl.trimEnd('/'))
            }
        } catch (e: Exception) {
            base.copy(configProblem = "google-services.json could not be read.")
        }
    }

    fun setDisplayName(name: String) {
        val n = name.trim().take(24)
        prefs.edit().putString("name", n).apply()
        _state.update { it.copy(displayName = n) }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }

    /** Every address a database called [n] can live at (US = firebaseio.com, others = regional hosts). */
    private fun dbUrlsForName(n: String): List<String> =
        if (n.isBlank()) emptyList() else listOf(
            "https://$n.firebaseio.com",
            "https://$n.europe-west1.firebasedatabase.app",
            "https://$n.asia-southeast1.firebasedatabase.app",
            "https://$n.us-central1.firebasedatabase.app"
        )

    /**
     * Turns whatever the person pasted into a list of database URLs worth trying: a plain URL,
     * a URL with a path / query, a Firebase console address, or just the database name.
     */
    private fun databaseUrlCandidates(raw: String): List<String> {
        val s = raw.trim().trim('"', '\'', '<', '>').replace(Regex("\\s+"), "")
        if (s.isEmpty()) return emptyList()

        // Firebase console address: https://console.firebase.google.com/project/ID/database/NAME/data
        if (s.contains("console.firebase.google.com", ignoreCase = true)) {
            val name = Regex("/database/([^/?#]+)", RegexOption.IGNORE_CASE).find(s)?.groupValues?.get(1)
            return if (name != null) dbUrlsForName(name) else emptyList()
        }
        val withScheme = if (s.startsWith("http", ignoreCase = true)) s else "https://$s"
        val host = Regex("^https?://([^/?#:]+)", RegexOption.IGNORE_CASE)
            .find(withScheme)?.groupValues?.get(1)?.lowercase() ?: return emptyList()
        return when {
            host.endsWith(".firebaseio.com") ->
                (listOf("https://$host") + dbUrlsForName(host.removeSuffix(".firebaseio.com"))).distinct()
            host.endsWith(".firebasedatabase.app") ->
                (listOf("https://$host") + dbUrlsForName(host.substringBefore('.'))).distinct()
            !host.contains('.') -> dbUrlsForName(host) // just the database name
            else -> emptyList()
        }
    }

    /**
     * Lets the person paste the Realtime Database URL. The address is checked against Firebase
     * before it is kept, so a wrong paste is reported immediately instead of on "Create a room".
     */
    fun setDatabaseUrl(raw: String) {
        if (_state.value.busy) return
        val candidates = databaseUrlCandidates(raw)
        if (candidates.isEmpty()) {
            _state.update {
                it.copy(
                    info = null,
                    message = "Database not found. That does not look like a Firebase database URL - " +
                        "it should look like https://your-project-default-rtdb.firebaseio.com"
                )
            }
            return
        }
        _state.update { it.copy(busy = true, message = null, info = null) }
        scope.launch(Dispatchers.IO) {
            try {
                ensureAuth()
                var offline = false
                for (c in candidates) {
                    when (probeDatabase(c)) {
                        true -> {
                            prefs.edit().putString("dbUrlOverride", c).putString("dbUrlResolved", c).apply()
                            dbResolved = true
                            _state.update {
                                it.copy(
                                    databaseUrl = c, busy = false, message = null,
                                    info = "Database connected. You can create or join a room now."
                                )
                            }
                            return@launch
                        }
                        false -> Unit
                        null -> offline = true
                    }
                }
                _state.update {
                    it.copy(
                        busy = false,
                        message = if (offline) "Could not reach Firebase. Check your internet connection and try again."
                        else "Database not found at the address you pasted. Make sure the Realtime Database is created " +
                            "(Firebase console > Build > Realtime Database > Create database), then copy the URL shown " +
                            "at the top of its Data tab."
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, message = "Could not check the database URL, try again.") }
            }
        }
    }

    fun createRoom(name: String) {
        if (_state.value.busy || room != null) return
        setDisplayName(name)
        launchBusy {
            var code: String? = null
            repeat(6) {
                if (code != null) return@repeat
                val candidate = randomCode()
                if (call("GET", "$candidate/meta").trim() == "null") code = candidate
            }
            val c = code ?: throw IOException("Could not create a room, try again.")
            call(
                "PUT", "$c/meta",
                JSONObject().put("createdBy", uid).put("createdAt", serverValue()).toString()
            )
            enter(c)
        }
    }

    fun joinRoom(rawCode: String, name: String) {
        if (_state.value.busy || room != null) return
        val code = rawCode.trim().uppercase().filter { it.isLetterOrDigit() }
        if (code.length < 4) {
            _state.update { it.copy(message = "Enter the room code.") }
            return
        }
        setDisplayName(name)
        launchBusy {
            if (call("GET", "$code/meta").trim() == "null") throw IOException("Room \"$code\" was not found.")
            enter(code)
        }
    }

    fun leave() {
        val code = room ?: return
        val me = uid
        val others = _state.value.members.count { !it.isMe }
        val preloadSids = preloads.keys.toList() // their request nodes are removed below
        stopJobs()
        transfer.closeAll()
        nearby.stopAll()
        mySid = null; mySig = null; lastNeedAt = 0L; lastProgressAt = 0L; servedSids.clear()
        preloadSig = null; lastPreloadAt = 0L; preloads.clear(); preloadFailedAt.clear(); artTried.clear()
        resetWifi()
        room = null
        roomState = null
        _state.update {
            it.copy(
                transferPercent = -1, route = null, routeProblem = null,
                status = PtStatus.IDLE, roomCode = null, members = emptyList(), nowPlayingTitle = null,
                nowPlayingArtist = null, controllerName = null, roomPlaying = false, songMissing = false, streaming = false,
                message = null, busy = false
            )
        }
        scope.launch(Dispatchers.IO + NonCancellable) {
            try {
                call("DELETE", "$code/members/$me")
                call("DELETE", "$code/needs/$me")
                preloadSids.forEach { sid -> try { call("DELETE", "$code/needs/$sid") } catch (_: Exception) { } }
                java.io.File(app.cacheDir, "pt_recv").deleteRecursively(); receivedFiles.clear()
                if (others == 0) {
                    deleteUploads()
                    call("DELETE", code) // last one out closes the room
                }
            } catch (_: Exception) { /* best effort */ }
        }
    }

    // =========================================================================================
    // Joining / leaving
    // =========================================================================================

    private suspend fun enter(code: String) {
        myName = _state.value.displayName.ifBlank { "Guest" }
        room = code
        roomState = null
        lastAppliedAt = 0L
        _state.update { it.copy(status = PtStatus.CONNECTING, roomCode = code, message = null) }

        measureClockOffset(code)
        putMember(code)
        refresh() // first snapshot (also applies whatever the room is currently playing)

        _state.update { it.copy(status = PtStatus.CONNECTED, busy = false) }
        startStream(code)
        startHeartbeat(code)
        startTicker()
        startRefreshLoop()
    }

    private fun stopJobs() {
        streamJob?.cancel(); heartbeatJob?.cancel(); tickerJob?.cancel(); refreshJob?.cancel()
        streamJob = null; heartbeatJob = null; tickerJob = null; refreshJob = null
    }

    private fun launchBusy(block: suspend () -> Unit) {
        _state.update { it.copy(busy = true, message = null, info = null) }
        scope.launch(Dispatchers.IO) {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                stopJobs()
                room = null
                _state.update {
                    it.copy(
                        status = PtStatus.IDLE, roomCode = null, busy = false, members = emptyList(),
                        message = e.message ?: "Something went wrong."
                    )
                }
            }
        }
    }

    // =========================================================================================
    // Background loops
    // =========================================================================================

    /** Live updates: Firebase pushes an event on every change, we just re-read the room. */
    private fun startStream(code: String) {
        streamJob = scope.launch(Dispatchers.IO) {
            var backoff = 1_000L
            while (isActive) {
                try {
                    ensureAuth()
                    val req = Request.Builder()
                        .url(urlFor(code, ""))
                        .header("Accept", "text/event-stream")
                        .build()
                    streamHttp.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                        backoff = 1_000L
                        _state.update { if (it.message?.startsWith("Connection lost") == true) it.copy(message = null) else it }
                        val src = resp.body?.source() ?: throw IOException("empty stream")
                        var event = ""
                        while (isActive && !src.exhausted()) {
                            val line = src.readUtf8Line() ?: break
                            when {
                                line.startsWith("event:") -> event = line.substring(6).trim()
                                line.startsWith("data:") -> when (event) {
                                    "put", "patch" -> {
                                        // The controller writes the whole `state` node in one go, so the event
                                        // already carries everything needed: use it without another round trip.
                                        if (event == "put") {
                                            try {
                                                val msg = JSONObject(line.substring(5).trim())
                                                if (msg.optString("path") == "/state") {
                                                    msg.optJSONObject("data")?.let { fastState.set(it) }
                                                }
                                            } catch (_: Exception) { }
                                        }
                                        refreshTrigger.trySend(Unit)
                                    }
                                    "cancel", "auth_revoked" -> throw IOException("stream closed: $event")
                                }
                            }
                            // renew before the auth token expires
                            if (idToken != null && System.currentTimeMillis() > tokenExpiryMs - 60_000) break
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _state.update { it.copy(message = "Connection lost, retrying…") }
                }
                delay(backoff)
                backoff = min(backoff * 2, 15_000L)
            }
        }
    }

    private fun startRefreshLoop() {
        refreshJob = scope.launch(Dispatchers.IO) {
            for (u in refreshTrigger) {
                fastState.getAndSet(null)?.let { json ->
                    try { parseState(json)?.let { applyStateFast(it) } } catch (e: CancellationException) { throw e } catch (_: Exception) { }
                }
                delay(REFRESH_DEBOUNCE_MS) // let a burst of events collapse into one read
                try { refresh() } catch (e: CancellationException) { throw e } catch (_: Exception) { }
            }
        }
    }

    /** Playback part of [refresh], run straight from the live stream so followers react without a re-read. */
    private fun applyStateFast(st: RoomState) {
        _state.update {
            it.copy(
                nowPlayingTitle = st.song?.title,
                nowPlayingArtist = st.song?.artist,
                controllerName = st.byName,
                roomPlaying = st.playing
            )
        }
        roomState = st
        applyRemote(st)
    }

    /** Presence + staleness check + clock re-sync. */
    private fun startHeartbeat(code: String) {
        heartbeatJob = scope.launch(Dispatchers.IO) {
            var n = 0
            while (isActive) {
                delay(20_000)
                try {
                    putMember(code)
                    if (++n % 3 == 0) measureClockOffset(code)
                    refresh()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) { }
            }
        }
    }

    /**
     * Watches the local player. When the person does something (new song, play/pause, seek)
     * that is not what the room is already doing, it is published so everybody follows.
     */
    private fun startTicker() {
        tickerJob = scope.launch(Dispatchers.Default) {
            var lastId = host.currentSong()?.id
            var lastPlaying = host.isPlaying()
            var lastPos = host.positionMs()
            var lastT = SystemClock.elapsedRealtime()
            var tick = 0
            var lastDur = host.currentSong()?.durationMs ?: 0L
            var lastWasRoomSong = host.currentSong()?.let { matchesRoomSong(it) } == true
            while (isActive) {
                delay(TICK_MS)
                val now = SystemClock.elapsedRealtime()
                val cur = host.currentSong()
                val playing = host.isPlaying()
                val pos = host.positionMs()

                if (now < ignoreLocalUntil) {
                    // we are applying a remote change: don't echo it back
                    lastId = cur?.id; lastPlaying = playing; lastPos = pos; lastT = now
                    lastDur = cur?.durationMs ?: 0L
                    lastWasRoomSong = cur != null && matchesRoomSong(cur)
                    continue
                }
                val streamEnded = cur != null && cur.id.startsWith(STREAM_PREFIX) &&
                    cur.durationMs > 0 && pos >= cur.durationMs - 1_500
                var effPlaying = playing
                if (cur != null && !streamEnded) {
                    try {
                        when {
                            cur.id != lastId && roomState?.by != uid && roomState?.playing == true &&
                                lastWasRoomSong && lastDur > 0 && lastPos >= lastDur - 3_000 && !matchesRoomSong(cur) -> {
                                // This phone reached the end of the room's song a little before the controller and its own
                                // queue moved on by itself. Hold here: the next song must not start before the room's
                                // next song, and this phone must not change the room. The controller's change then
                                // lands on the already loaded song and only has to start it.
                                ignoreLocalUntil = SystemClock.elapsedRealtime() + 6_000
                                host.setPlaying(false)
                                effPlaying = false
                            }
                            cur.id != lastId -> {
                                // A new song is first reported as "not playing" for a moment (it is loaded, then started).
                                // Publishing that would pause everybody, so wait for it to start when it was playing before.
                                var p = playing
                                if (!p && (lastPlaying || roomState?.playing == true)) {
                                    var waited = 0
                                    while (!p && waited < SONG_CHANGE_SETTLE_MS) { delay(50); waited += 50; p = host.isPlaying() }
                                }
                                val now2 = host.currentSong() ?: cur
                                val applyingRemote = SystemClock.elapsedRealtime() < ignoreLocalUntil
                                // a follower still loading the room's song is not asking to pause the room
                                val stillLoading = !p && roomState?.by != uid && matchesRoomSong(now2) && roomState?.playing == true
                                if (!applyingRemote && !stillLoading && (!matchesRoomSong(now2) || roomState?.playing != p)) publish(now2, p, host.positionMs())
                                effPlaying = p
                            }
                            playing != lastPlaying -> publish(cur, playing, pos)
                            else -> {
                                val expected = if (lastPlaying) lastPos + (now - lastT) else lastPos
                                if (abs(pos - expected) > CONTROLLER_SEEK_MS) publish(cur, playing, pos)
                                else {
                                    tick++
                                    if (tick % DRIFT_EVERY_TICKS == 0) correctDrift(cur, playing, pos)
                                    // The controller re-sends its real position every few seconds, so followers
                                    // stay anchored to what is actually playing (stalls, buffering, output delay).
                                    else if (playing && roomState?.by == uid && matchesRoomSong(cur) && tick % REANCHOR_EVERY_TICKS == 0) publish(cur, true, pos, anchor = true)
                                }
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) { /* next tick retries via the changed state */ }
                }
                lastId = cur?.id; lastPlaying = effPlaying; lastPos = pos; lastT = now
                lastDur = cur?.durationMs ?: 0L
                lastWasRoomSong = cur != null && matchesRoomSong(cur)
            }
        }
    }

    // =========================================================================================
    // Reading the room
    // =========================================================================================

    private suspend fun refresh() {
        val code = room ?: return
        val text = call("GET", code).trim()
        if (text == "null") {
            if (room != null) {
                stopJobs()
                transfer.closeAll()
                nearby.stopAll()
                mySid = null; mySig = null; lastNeedAt = 0L; lastProgressAt = 0L; servedSids.clear()
                preloadSig = null; lastPreloadAt = 0L; preloads.clear(); preloadFailedAt.clear(); artTried.clear()
                resetWifi()
                room = null
                roomState = null
                java.io.File(app.cacheDir, "pt_recv").deleteRecursively(); receivedFiles.clear()
                _state.update {
                    it.copy(
                        route = null, routeProblem = null,
                        status = PtStatus.IDLE, roomCode = null, members = emptyList(), nowPlayingTitle = null,
                        nowPlayingArtist = null, controllerName = null, roomPlaying = false, songMissing = false,
                        streaming = false, transferPercent = -1, message = "The room was closed."
                    )
                }
            }
            return
        }
        val o = JSONObject(text)
        val now = serverNow()

        // members (drop anyone whose heartbeat is older than ~75 s)
        val mObj = o.optJSONObject("members")
        val st = parseState(o.optJSONObject("state"))
        val members = ArrayList<PtMember>()
        mObj?.keys()?.forEach { id ->
            val m = mObj.optJSONObject(id) ?: return@forEach
            val seen = m.optLong("seen", 0L)
            if (id == uid || now - seen < 75_000) {
                members += PtMember(id, m.optString("name", "Guest"), id == uid, st?.by == id)
            }
        }
        members.sortWith(compareByDescending<PtMember> { it.isMe }.thenBy { it.name.lowercase() })

        _state.update {
            it.copy(
                members = members,
                nowPlayingTitle = st?.song?.title,
                nowPlayingArtist = st?.song?.artist,
                controllerName = st?.byName,
                roomPlaying = st?.playing == true
            )
        }
        if (st != null) {
            roomState = st
            applyRemote(st)
            maybePreload(st)
            if (STORAGE_UPLOAD_ENABLED) shareForRoom(st, members.any { !it.isMe })
        }
        handleSignaling(o, now)
    }

    // =========================================================================================
    // Peer-to-peer song transfer (WebRTC data channel, Firebase RTDB = signaling only)
    // =========================================================================================

    private fun songSig(e: Entry) = sha(norm(e.title) + "|" + norm(e.artist) + "|" + (e.durationMs / 1000))

    /**
     * Receiver: tell the controller "I need this song". Re-sent at most every 25 s.
     * [preload] = it is not the song playing now but one of the next ones, fetched ahead of time;
     * such a request never blocks or delays the song the room is playing.
     */
    private suspend fun requestSong(e: Entry, preload: Boolean = false) {
        val code = room ?: return
        val now = SystemClock.elapsedRealtime()
        val sig = songSig(e)
        if (!preload) {
            // The room reached a song that is already on its way (loaded ahead of time): just show its progress.
            preloads.values.firstOrNull { it.sig == sig }?.let { running ->
                if (now - maxOf(running.startedAt, running.lastProgressAt) < PRELOAD_STALL_MS) {
                    _state.update { it.copy(transferPercent = max(it.transferPercent, 0), route = running.route) }
                    return
                }
                failPreload(running, remember = false) // stalled: ask again as the current song
            }
            // The room moved on to another song while the previous one was still arriving: the song playing now
            // goes first, at once, with the whole connection (the other one is fetched again later, in order).
            val busySid = mySid
            val busySig = mySig
            if (busySid != null && busySig != null && busySig != sig && preloadSig == null) {
                transfer.close(busySid)
                nearby.cancelRequest(busySid)
                cancelWifiAttempt()
                sidSig.remove(busySid)
                mySid = null; lastNeedAt = 0L
                try { call("DELETE", "$code/needs/$uid"); call("DELETE", "$code/signal/$busySid") } catch (e: CancellationException) { throw e } catch (_: Exception) { }
            }
            preloads.values.toList().forEach { failPreload(it, remember = false) }
            val loading = preloadSig
            if (mySid != null && loading != null) {
                if (loading == sig) {
                    // the room reached the song that was already on its way: just show its progress
                    preloadSig = null
                    _state.update { it.copy(transferPercent = max(it.transferPercent, 0)) }
                    return
                }
                // the room is now on a different song than the one loaded ahead: this one goes first
                transfer.close(mySid ?: "")
                nearby.cancelRequest(mySid ?: "")
                cancelWifiAttempt()
                mySid = null; preloadSig = null; lastNeedAt = 0L
            }
        }
        // Only re-issue when the transfer has stalled: no progress for 25 s since the request. The own
        // Wi-Fi route may legitimately wait longer (hotspot start, the system's join dialog).
        val stallMs = if (myRoute == PtRoute.OWN_WIFI) WIFI_STALL_MS else 25_000L
        if (mySid != null && now - maxOf(lastNeedAt, lastProgressAt) < stallMs) return
        if (!preload && now - lastNeedAt < 8_000) return
        lastNeedAt = now
        val sid = uid + "_" + UUID.randomUUID().toString().take(6)
        transfer.close(mySid ?: "")
        nearby.cancelRequest(mySid ?: "")
        cancelWifiAttempt()
        mySid = sid; mySig = sig
        sidSig[sid] = sig
        preloadSig = if (preload) sig else null
        if (!preload) _state.update { it.copy(transferPercent = 0, routeProblem = null) }
        // Routes, in order: Nearby (Bluetooth / Wi-Fi Direct), the controller's own Wi-Fi, the internet.
        // Each one hands over to the next when it fails; a song loaded ahead of time only uses the
        // own Wi-Fi while this phone is already joined to it, and the internet only when it is cheap.
        // (Already joined to the controller's Wi-Fi while Nearby is not connected: go straight over Wi-Fi
        // instead of searching for the controller again for every song of the queue.)
        val useNearby = nearbyReady() && (nearby.connectedToController || !wifiClient.isJoined)
        myOrder = PtRoutePlan.order(nearby = useNearby, ownWifi = ownWifiAllowed(preload), internet = true)
        startRoute(myOrder.first(), code, sig, sid)
    }

    private fun ownWifiAllowed(preload: Boolean) =
        Build.VERSION.SDK_INT >= 29 && _state.value.nearbyEnabled && (!preload || wifiClient.isJoined) &&
            SystemClock.elapsedRealtime() - ownWifiFailedAt >= WIFI_SKIP_MS

    /** Starts [route] for the song [sig] (request [sid]). The next route is started from [onRouteFailed]. */
    private suspend fun startRoute(route: PtRoute, code: String, sig: String, sid: String) {
        if (sid != mySid) return
        myRoute = route
        lastProgressAt = SystemClock.elapsedRealtime() // a new route gets its own time before the request counts as stalled
        val preload = preloadSig != null
        if (!preload) _state.update { it.copy(route = route) }
        when (route) {
            PtRoute.NEARBY -> {
                // Same place? PtNearbyTransfer reports back through onRouteFailed if no controller is found nearby.
                nearby.requestSong(code, sig, sid, myName.ifBlank { "Guest" }, artFile(sig)) { ext ->
                    java.io.File(app.cacheDir, "pt_recv/$sig.$ext")
                }
            }
            PtRoute.OWN_WIFI -> {
                // Ask the controller to open its Wi-Fi; its answer (rooms/<code>/wifi) is read in handleWifiCreds().
                wifiAttemptSid = null
                wifiNeedAt = serverNow()
                postNeed(sig, sid, wifi = true)
                wifiWaitJob?.cancel()
                wifiWaitJob = scope.launch {
                    delay(PtRoutePlan.CREDENTIALS_TIMEOUT_MS)
                    if (sid == mySid && myRoute == PtRoute.OWN_WIFI && wifiAttemptSid != sid) {
                        onRouteFailed(sid, PtRoute.OWN_WIFI, "The controller did not share its Wi-Fi in time")
                    }
                }
            }
            PtRoute.INTERNET -> {
                postNeed(sig, sid)
            }
        }
    }

    /** A route could not deliver the song: say why and go on with the next route. */
    private fun onRouteFailed(sid: String, route: PtRoute, reason: String) {
        preloads[sid]?.let { p ->
            if (p.route != route) return
            // Nearby did not deliver: the internet is the only other route for a song loaded ahead
            if (route == PtRoute.NEARBY && room != null) {
                p.route = PtRoute.INTERNET
                p.lastProgressAt = SystemClock.elapsedRealtime()
                scope.launch(Dispatchers.IO) {
                    try { postNeed(p.sig, p.sid) } catch (e: CancellationException) { throw e } catch (_: Exception) { failPreload(p) }
                }
            } else failPreload(p)
            return
        }
        if (sid != mySid || myRoute != route) return
        val sig = mySig ?: return
        val next = PtRoutePlan.after(myOrder, route)
        if (route == PtRoute.OWN_WIFI) ownWifiFailedAt = SystemClock.elapsedRealtime()
        wifiWaitJob?.cancel()
        stopWifiFetch()
        if (preloadSig == null) {
            val text = "${route.label}: $reason" + (next?.let { ". Trying ${it.label}…" } ?: "")
            _state.update { it.copy(routeProblem = text) }
        }
        if (next == null) {
            mySid = null; preloadSig = null; myRoute = null
            preloadFailedAt[sig] = SystemClock.elapsedRealtime()
            return
        }
        scope.launch(Dispatchers.IO) {
            try {
                startRoute(next, room ?: return@launch, sig, sid)
            } catch (e: CancellationException) { throw e } catch (_: Exception) { }
        }
    }

    private fun stopWifiFetch() {
        wifiFetchJob?.cancel(); wifiFetchJob = null
        wifiClient.abort()
    }

    /** Forget a running own-Wi-Fi attempt (the request was replaced or the room was left). */
    private fun cancelWifiAttempt() {
        wifiWaitJob?.cancel(); wifiWaitJob = null
        stopWifiFetch()
        wifiAttemptSid = null
        myRoute = null
    }

    /** Leaving / closing the room: drop the attempt, the controller's hotspot and this phone's join. */
    private fun resetWifi() {
        cancelWifiAttempt()
        wifiHandledSids.clear()
        wifiFailure = null; wifiFailedAt = 0L
        ownWifiFailedAt = -WIFI_SKIP_MS
        scope.launch(Dispatchers.IO) { wifiHost.stop(); wifiClient.release() }
    }

    // ---- loading the next songs ahead of time ------------------------------------------------------

    /** Cheap to move 8 songs? On Wi-Fi (not metered) or over a direct link to the controller. */
    private fun internetIsCheap(): Boolean {
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return false
        return try { !cm.isActiveNetworkMetered } catch (_: Exception) { false }
    }

    /** Loading ahead is wanted on any connection: waiting for a transfer at song change is what the person notices. */
    private fun preloadAllowed() = true

    /**
     * Receiver: keeps the next [PtPreloadPlan.COUNT] songs of the controller's queue on this phone,
     * up to [MAX_PARALLEL_PRELOADS] downloading at the same time, so skipping to the next song never
     * waits for a transfer. Songs this phone already owns are skipped, and files that are no longer
     * near the current song are deleted. The song playing now always goes first.
     */
    private fun maybePreload(st: RoomState) {
        if (st.by == uid) return
        val current = st.song ?: return
        val curSig = songSig(current)
        val entryBySig = LinkedHashMap<String, Entry>()
        st.queue.forEach { entryBySig.putIfAbsent(songSig(it), it) }
        val queueSigs = st.queue.map { songSig(it) }
        val keep = PtPreloadPlan.keep(queueSigs, curSig)
        trimReceived(keep)
        // downloads for songs that fell out of the window (queue changed / skipped far ahead) are dropped
        preloads.values.filter { it.sig !in keep }.forEach { failPreload(it, remember = false) }

        // Flow: the song playing now is received completely first; only then the next songs are loaded, one by one.
        if (mySid != null || _state.value.songMissing) return
        val now = SystemClock.elapsedRealtime()
        sweepStalledPreloads(now)
        if (!preloadAllowed()) return
        val library = host.librarySongs()
        val wanted = synchronized(preloads) {
            val free = MAX_PARALLEL_PRELOADS - preloads.size
            if (free <= 0) emptyList() else {
                val running = preloads.values.map { it.sig }.toSet()
                PtPreloadPlan.missing(PtPreloadPlan.upcoming(queueSigs, curSig), free) { sig ->
                    val entry = entryBySig[sig]
                    entry == null ||
                        sig in running ||
                        receivedFiles[sig]?.exists() == true ||
                        findLocal(entry, library) != null ||
                        (preloadFailedAt[sig]?.let { now - it < PRELOAD_RETRY_MS } == true)
                }.mapNotNull { sig -> entryBySig[sig]?.let { sig to it } }
            }
        }
        wanted.forEach { (sig, _) -> startPreload(sig) }
    }

    /** Starts fetching [sig] in the background: over Nearby when connected to the controller, else the internet (if cheap). */
    private fun startPreload(sig: String) {
        val route = when {
            nearbyReady() && nearby.connectedToController -> PtRoute.NEARBY
            else -> PtRoute.INTERNET
        }
        val sid = uid + "_" + UUID.randomUUID().toString().take(6)
        val p = Preload(sig, sid, route, SystemClock.elapsedRealtime())
        if (synchronized(preloads) { preloads.size >= MAX_PARALLEL_PRELOADS || preloads.values.any { it.sig == sig } }) return
        preloads[sid] = p
        scope.launch(Dispatchers.IO) {
            try {
                val code = room ?: run { failPreload(p); return@launch }
                if (route == PtRoute.NEARBY) {
                    nearby.requestSong(code, sig, sid, myName.ifBlank { "Guest" }, artFile(sig)) { ext ->
                        java.io.File(app.cacheDir, "pt_recv/$sig.$ext")
                    }
                } else {
                    postNeed(sig, sid) // one needs/<sid> node per song, so several can be open at once
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                failPreload(p)
            }
        }
    }

    /** Gives up on a preload: frees its slot, closes the session and (unless [remember] is false) skips the song for a while. */
    private fun failPreload(p: Preload, remember: Boolean = true) {
        if (preloads.remove(p.sid) == null) return
        if (remember) preloadFailedAt[p.sig] = SystemClock.elapsedRealtime()
        transfer.close(p.sid)
        nearby.cancelRequest(p.sid)
        scope.launch(Dispatchers.IO) {
            try { room?.let { c -> call("DELETE", "$c/needs/${p.sid}"); call("DELETE", "$c/signal/${p.sid}") } } catch (_: Exception) { }
        }
    }

    /** Preloads that made no progress for [PRELOAD_STALL_MS] are dropped; the next refresh starts them again. */
    private fun sweepStalledPreloads(now: Long) {
        preloads.values.filter { now - maxOf(it.startedAt, it.lastProgressAt) >= PRELOAD_STALL_MS }
            .forEach { failPreload(it, remember = false) }
    }

    private fun isRoomSong(sig: String): Boolean = roomState?.song?.let { songSig(it) == sig } == true

    /** Deletes received songs (and their covers) that are not in [keep] any more. */
    private fun trimReceived(keep: Set<String>) {
        val iter = receivedFiles.entries.iterator()
        while (iter.hasNext()) {
            val (sig, file) = iter.next()
            if (sig in keep) continue
            iter.remove()
            file.delete()
            artFile(sig).delete()
            artTried.remove(sig)
        }
    }

    private fun artFile(sig: String) = java.io.File(app.cacheDir, "pt_recv/$sig.jpg")

    /** Ask the controller for the song over the internet (WebRTC, signaled through the room). */
    private suspend fun postNeed(sig: String, sid: String, wifi: Boolean = false) {
        val code = room ?: return
        val need = JSONObject().put("sig", sig).put("sid", sid).put("v", 2).put("at", serverValue())
        if (wifi) need.put("route", "wifi") // the controller opens its own Wi-Fi instead of starting WebRTC
        // A request for a song loaded ahead of time gets its own node (named by its session id), so a phone
        // can have several open; the song playing now keeps the old per-phone node.
        val key = if (preloads.containsKey(sid)) sid else uid
        call("PUT", "$code/needs/$key", need.toString())
    }

    /** Both roles: look at `needs` (controller) and `signal` (everyone) in the room snapshot. */
    private fun handleSignaling(o: JSONObject, now: Long) {
        val signal = o.optJSONObject("signal")
        val st = roomState

        syncNearbyHost(st)
        syncNearbyLink(st)
        syncWifiHost(st)
        handleWifiCreds(o)

        // controller: serve fresh requests for the song that is playing now
        if (st != null && st.by == uid) {
            val needs = o.optJSONObject("needs")
            // the song playing now and the ones queued after it can be requested
            val servable = servables(st).associate { it.first to it.second }
            if (servable.isNotEmpty() && needs != null) {
                val order = servable.keys.toList()
                // play order: the song playing now is served before the ones queued after it
                val ordered = needs.keys().asSequence().toList()
                    .sortedBy { who -> order.indexOf(needs.optJSONObject(who)?.optString("sig")).let { if (it < 0) Int.MAX_VALUE else it } }
                ordered.forEach { who ->
                    val n = needs.optJSONObject(who) ?: return@forEach
                    val sid = n.optString("sid")
                    val song = servable[n.optString("sig")]
                    if (who == uid || sid.isBlank() || song == null) return@forEach
                    if (now - n.optLong("at", 0L) > 60_000) return@forEach
                    if (n.optString("route") == "wifi") {
                        // own Wi-Fi route: open the hotspot and publish it; no WebRTC for this request
                        if (wifiHandledSids.add(sid)) serveWifi(room ?: return@forEach)
                        return@forEach
                    }
                    if (servedSids.contains(sid)) return@forEach
                    // the last slots are kept for the song playing now; queued songs wait for a free one
                    val isCurrent = order.indexOf(n.optString("sig")) == 0
                    if (transfer.activeSenders() >= (if (isCurrent) MAX_PARALLEL_SENDS else MAX_PARALLEL_SENDS - 3)) return@forEach
                    servedSids.add(sid)
                    val size = song.fileSizeBytes.takeIf { it > 0 } ?: 0L
                    // the cover only goes to apps that said they can take it (v >= 2)
                    val cover = if (n.optInt("v", 1) >= 2) PtArt.coverFile(app, song) else null
                    transfer.startSending(sid, extOf(song), size, cover) { app.contentResolver.openInputStream(song.uri) }
                }
            }
        }
        if (signal == null) return
        signal.keys().forEach { sid ->
            val node = signal.optJSONObject(sid) ?: return@forEach
            val iAmSender = servedSids.contains(sid)
            val iAmReceiver = sid == mySid || preloads.containsKey(sid)
            if (iAmReceiver) {
                if (!transfer.hasSession(sid)) {
                    node.optJSONObject("offer")?.let { offer ->
                        val sig = (if (sid == mySid) mySig else preloads[sid]?.sig) ?: return@let
                        transfer.acceptOffer(sid, offer.toString(), artFile(sig)) { ext ->
                            java.io.File(app.cacheDir, "pt_recv/$sig.$ext")
                        }
                    }
                }
                node.optJSONObject("ice_c")?.let { feedIce(sid, it) }
            } else if (iAmSender) {
                node.optJSONObject("answer")?.let { transfer.onAnswer(sid, it.toString()) }
                node.optJSONObject("ice_r")?.let { feedIce(sid, it) }
            }
        }
    }

    /** Controller advertises the room and offers the current song to phones in the same place. */
    private fun syncNearbyHost(st: RoomState?) {
        val code = room
        if (code == null || st == null || st.by != uid || !nearbyReady()) { nearby.stopAdvertising(); return }
        nearby.startAdvertising(code, myName.ifBlank { "Guest" })
        nearby.setSources(buildSources(st))
    }

    /** The songs this phone hands out as controller (Nearby and own Wi-Fi share the same list). */
    private fun buildSources(st: RoomState): List<PtNearbyTransfer.Source> =
        servables(st).map { (sig, song) ->
            PtNearbyTransfer.Source(
                sig, extOf(song), song.fileSizeBytes.takeIf { it > 0 } ?: 0L,
                open = { app.contentResolver.openFileDescriptor(song.uri, "r") },
                cover = { PtArt.coverFile(app, song) }
            )
        }

    // ---- own Wi-Fi route -----------------------------------------------------------------------------

    /** Controller: keep the downloadable songs of the hotspot up to date; switch it off when no longer the controller. */
    private fun syncWifiHost(st: RoomState?) {
        if (st == null || st.by != uid) {
            if (wifiHost.isRunning) scope.launch(Dispatchers.IO) { wifiHost.stop() }
            return
        }
        if (wifiHost.isRunning) wifiHost.setSources(buildSources(st))
    }

    /**
     * Controller: a phone asked for the own-Wi-Fi route. Starts the hotspot if needed and publishes
     * {"ssid","pass","port","token","at"} in rooms/<code>/wifi (or {"err","at"} when it cannot).
     */
    private fun serveWifi(code: String) {
        wifiHost.touch()
        if (wifiHost.isRunning && wifiCredsRoom == code) return
        if (!wifiBusy.compareAndSet(false, true)) return
        scope.launch(Dispatchers.IO) {
            try {
                val known = wifiFailure
                if (known != null && !wifiHost.isRunning && SystemClock.elapsedRealtime() - wifiFailedAt < WIFI_RETRY_MS) {
                    putWifiError(code, known) // failed a moment ago: tell the phone again instead of retrying at once
                } else {
                    val creds = wifiHost.start()
                    wifiFailure = null
                    roomState?.let { wifiHost.setSources(buildSources(it)) }
                    call("PUT", "$code/wifi", creds.toJson().put("at", serverValue()).toString())
                    wifiCredsRoom = code
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val why = (e as? PtWifiException)?.reason ?: ("Hotspot error: " + (e.message ?: e.javaClass.simpleName))
                wifiFailure = why
                wifiFailedAt = SystemClock.elapsedRealtime()
                try { putWifiError(code, why) } catch (_: Exception) { }
            } finally {
                wifiBusy.set(false)
            }
        }
    }

    private suspend fun putWifiError(code: String, why: String) {
        call("PUT", "$code/wifi", JSONObject().put("err", why).put("at", serverValue()).toString())
    }

    /** The hotspot is off again (idle for 2 minutes, room left, or stopped by Android): remove its credentials. */
    private fun onWifiHostStopped() {
        val code = wifiCredsRoom ?: return
        wifiCredsRoom = null
        scope.launch(Dispatchers.IO + NonCancellable) {
            try { call("DELETE", "$code/wifi") } catch (_: Exception) { }
        }
    }

    /** Receiver: the controller published its Wi-Fi (or why it cannot): join it and download the song. */
    private fun handleWifiCreds(o: JSONObject) {
        val sid = mySid ?: return
        if (myRoute != PtRoute.OWN_WIFI || wifiAttemptSid == sid) return
        val sig = mySig ?: return
        val node = o.optJSONObject("wifi") ?: return
        val err = node.optString("err")
        if (err.isNotBlank()) {
            // only an answer given after this request counts (a few seconds of clock slack)
            if (node.optLong("at", 0L) >= wifiNeedAt - 10_000) onRouteFailed(sid, PtRoute.OWN_WIFI, err)
            return
        }
        val creds = PtWifiCreds.fromJson(node) ?: return
        wifiAttemptSid = sid
        wifiWaitJob?.cancel()
        wifiFetchJob = scope.launch(Dispatchers.IO) {
            try {
                val file = wifiClient.fetch(
                    creds, sig, artFile(sig),
                    target = { ext -> java.io.File(app.cacheDir, "pt_recv/$sig.$ext") },
                    onProgress = { pct -> transferListener.onProgress(sid, pct) }
                )
                if (sid == mySid) transferListener.onReceived(sid, file)
            } catch (e: CancellationException) {
                throw e
            } catch (e: PtWifiException) {
                onRouteFailed(sid, PtRoute.OWN_WIFI, e.reason)
            } catch (e: Exception) {
                onRouteFailed(sid, PtRoute.OWN_WIFI, e.message ?: "Wi-Fi transfer failed")
            }
        }
    }

    /**
     * Controller: the songs other phones may request, as (signature, song): the one playing now
     * (only while it really is the room's song) and the ones queued after it, so phones can load
     * them ahead of time.
     */
    private fun servables(st: RoomState): List<Pair<String, Song>> {
        val entry = st.song ?: return emptyList()
        val current = host.currentSong()?.takeIf { entry.matches(it) } ?: return emptyList()
        val queue = host.queue()
        val i = queue.indexOfFirst { it.id == current.id }
        val window = if (i >= 0) queue.subList(i, min(queue.size, i + 1 + PtPreloadPlan.COUNT)) else listOf(current)
        return window.filter { isUploadable(it) }
            .map { songSig(Entry(it.title, it.artist, it.album, it.durationMs)) to it }
            .distinctBy { it.first }
    }

    private var linkedController: String? = null

    /**
     * Everyone who is not the controller keeps a standing Nearby connection to it, so every phone
     * in the same place is connected as soon as someone starts playing, and songs can move over
     * Bluetooth / Wi-Fi Direct without waiting for a request. Follows the controller if it changes.
     */
    private fun syncNearbyLink(st: RoomState?) {
        val code = room
        if (code == null || st == null || st.by.isBlank() || st.by == uid || !nearbyReady()) {
            nearby.stopLink(); linkedController = null; return
        }
        if (linkedController != st.by) { nearby.stopLink(); linkedController = st.by }
        nearby.startLink(code, myName.ifBlank { "Guest" })
    }

    private fun feedIce(sid: String, list: JSONObject) =
        list.keys().forEach { k -> list.optJSONObject(k)?.let { transfer.onRemoteIce(sid, k, it.toString()) } }

    private fun parseState(o: JSONObject?): RoomState? {
        if (o == null) return null
        fun entry(j: JSONObject?): Entry? = j?.let {
            Entry(it.optString("t"), it.optString("a"), it.optString("al"), it.optLong("d"), it.optString("u"), it.optString("x"))
        }
        val q = ArrayList<Entry>()
        o.optJSONArray("queue")?.let { arr ->
            for (i in 0 until arr.length()) entry(arr.optJSONObject(i))?.let { q += it }
        }
        return RoomState(
            by = o.optString("by"),
            byName = o.optString("byName", "Someone"),
            playing = o.optBoolean("playing", false),
            positionMs = o.optLong("positionMs", 0L),
            updatedAt = o.optLong("updatedAt", 0L),
            song = entry(o.optJSONObject("song")),
            queue = q,
            anchor = o.optBoolean("anchor", false)
        )
    }

    // =========================================================================================
    // Applying / publishing playback
    // =========================================================================================

    private fun expectedPosition(st: RoomState): Long {
        val elapsed = if (st.playing) (serverNow() - st.updatedAt).coerceIn(0L, 10 * 60_000L) else 0L
        return max(0L, st.positionMs + elapsed)
    }

    /** Someone else changed what is playing: do the same here. */
    private fun applyRemote(st: RoomState) {
        if (st.by == uid) return
        val entry = st.song ?: return
        // a stream URL that shows up later (controller finished uploading) must still be applied
        val urlArrived = _state.value.songMissing && entry.url.isNotBlank()
        if (st.updatedAt != 0L && st.updatedAt <= lastAppliedAt && !urlArrived) return
        lastAppliedAt = st.updatedAt

        val library = host.librarySongs()
        val local = findLocal(entry, library)
        if (local == null) {
            // 1) a copy already received from the controller, 2) the controller's online URL
            val remote = receivedFiles[songSig(entry)]?.takeIf { it.exists() }?.let { receivedSong(entry, it) }
                ?: entry.url.takeIf { it.isNotBlank() }?.let { streamSong(entry) }
            if (remote != null) {
                val target = expectedPosition(st)
                ignoreLocalUntil = SystemClock.elapsedRealtime() + 2_500
                _state.update { it.copy(songMissing = false, streaming = true) }
                val cur = host.currentSong()
                var changed = true
                if (cur?.id != remote.id) {
                    host.playQueue(listOf(remote), 0, target + startLead(st.playing), st.playing)
                } else {
                    changed = false
                    if (host.isPlaying() != st.playing) { host.setPlaying(st.playing); changed = true }
                    if (syncSeekIfOff(st, target)) changed = true
                }
                if (st.playing && changed) scheduleResync()
                return
            }
            // Nothing to play yet: silence this phone and ask the controller to send the file.
            ignoreLocalUntil = SystemClock.elapsedRealtime() + 2_500
            if (host.isPlaying()) host.setPlaying(false)
            _state.update { it.copy(songMissing = true, streaming = false) }
            lastAppliedAt = 0L // re-evaluate on the next refresh until the file arrives
            scope.launch(Dispatchers.IO) {
                try { requestSong(entry) } catch (e: CancellationException) { throw e } catch (_: Exception) { }
            }
            return
        }
        _state.update { it.copy(songMissing = false, streaming = false) }

        val target = expectedPosition(st)
        ignoreLocalUntil = SystemClock.elapsedRealtime() + 2_500
        val cur = host.currentSong()
        var resyncNeeded = false
        if (cur?.id != local.id) {
            // build this phone's queue from the controller's queue (only songs we also have)
            val songs = ArrayList<Song>()
            var index = 0
            for (e in st.queue) {
                val s = findLocal(e, library) ?: continue
                if (songs.lastOrNull()?.id == s.id) continue
                if (s.id == local.id) index = songs.size
                songs += s
            }
            if (songs.none { it.id == local.id }) { songs.clear(); songs += local; index = 0 }
            host.playQueue(songs, index, target + startLead(st.playing), st.playing)
            resyncNeeded = true
        } else {
            if (host.isPlaying() != st.playing) { host.setPlaying(st.playing); resyncNeeded = true }
            if (syncSeekIfOff(st, target)) resyncNeeded = true
        }
        if (st.playing && resyncNeeded) scheduleResync()
    }

    /**
     * Starting a song takes the player a moment, during which the room clock keeps running. The follower
     * starts that much further into the song, so it lands on the controller's position instead of behind it.
     * The amount is learned from how far behind the first check after a start finds this phone.
     */
    @Volatile private var startLeadMs = 250L
    @Volatile private var leadSampleArmed = false

    private fun startLead(playing: Boolean): Long {
        if (!playing) return 0L
        leadSampleArmed = true
        lastSyncSeekAt = 0L
        return startLeadMs
    }

    /**
     * Loading a song (from the library, a received file, Nearby or an online stream) takes a moment, and
     * the position was worked out before that. Once this phone really plays, check it against the room
     * clock every few hundred ms and jump to the right place until it holds, so followers land on the
     * controller's position instead of staying 1-4 s behind.
     */
    private fun scheduleResync() {
        resyncJob?.cancel()
        resyncJob = scope.launch(Dispatchers.Default) {
            val start = SystemClock.elapsedRealtime()
            var steady = 0
            var lastSeekAt = 0L
            var lastSeekTarget = -1L
            var prevPos = -1L
            var pausedChecks = 0
            var kicks = 0
            while (isActive && SystemClock.elapsedRealtime() - start < RESYNC_WINDOW_MS && steady < 6) {
                delay(RESYNC_STEP_MS)
                val st = roomState ?: break
                if (st.by == uid || !st.playing) break
                val cur = host.currentSong() ?: continue
                if (!matchesRoomSong(cur)) { prevPos = -1L; continue } // still loading
                if (!host.isPlaying()) {
                    prevPos = -1L
                    // the room plays but this phone is not: start it (a failed/late start must not leave it silent)
                    if (++pausedChecks >= 4 && kicks < 3) { pausedChecks = 0; kicks++; ignoreLocalUntil = SystemClock.elapsedRealtime() + 1_500; host.setPlaying(true) }
                    continue
                }
                pausedChecks = 0
                // Only judge the position once audio is really running: right after a start or a seek the
                // player reports the requested position without moving, and seeking again then only restarts the load.
                val pos = host.positionMs()
                val advancing = prevPos >= 0 && pos - prevPos in 20L..1_000L && pos != lastSeekTarget
                prevPos = pos
                if (!advancing) continue
                val now = SystemClock.elapsedRealtime()
                if (now - lastSeekAt < RESYNC_SETTLE_MS) continue
                val behind = expectedPosition(st) - pos // + = this phone is late, - = early
                if (abs(behind) < 4_000) {
                    // Learn how much a start / seek costs, so the next one lands on the room position instead of behind it.
                    if (leadSampleArmed) {
                        leadSampleArmed = false
                        startLeadMs = (startLeadMs + (behind * 0.7f).toLong()).coerceIn(0L, 2_500L)
                    } else if (seekSamplePending) {
                        seekSamplePending = false
                        seekLeadMs = (seekLeadMs + (behind * 0.8f).toLong()).coerceIn(0L, 2_500L)
                    }
                }
                if (abs(behind) > RESYNC_TOLERANCE_MS) {
                    if (now - lastSeekAt >= RESYNC_SEEK_GAP_MS) {
                        lastSeekAt = now
                        lastSyncSeekAt = now
                        steady = 0
                        ignoreLocalUntil = now + 1_000
                        lastSeekTarget = expectedPosition(st) + seekLeadMs
                        seekSamplePending = true
                        host.seekTo(lastSeekTarget) // a seek takes a while to land: aim ahead by what it cost last time
                    }
                } else {
                    steady++
                }
            }
        }
    }

    /** How far ahead a correction seek must aim to land on the room position (learned from earlier seeks). */
    @Volatile private var seekLeadMs = 300L
    @Volatile private var seekSamplePending = false

    /**
     * A seek restarts the decoder and fades the audio back in, so seeking to correct small timing errors is
     * audible as a volume dip. Corrections are therefore rare: at most one per [MIN_SYNC_SEEK_GAP_MS], and the
     * controller's periodic refresh only moves a follower when it is clearly off.
     */
    @Volatile private var lastSyncSeekAt = 0L

    private fun canSyncSeek() = SystemClock.elapsedRealtime() - lastSyncSeekAt >= MIN_SYNC_SEEK_GAP_MS

    /** Applies the room position to a follower already on the right song. True when it seeked. */
    private fun syncSeekIfOff(st: RoomState, target: Long): Boolean {
        val err = abs(host.positionMs() - target)
        if (st.anchor) {
            if (err <= ANCHOR_SEEK_MS || !canSyncSeek()) return false
        } else if (err <= DRIFT_SEEK_MS) return false
        lastSyncSeekAt = SystemClock.elapsedRealtime()
        seekSamplePending = true
        host.seekTo(target + seekLeadMs)
        return true
    }

    private fun correctDrift(cur: Song, playing: Boolean, pos: Long) {
        val st = roomState ?: return
        if (st.by == uid || !st.playing || !playing || !matchesRoomSong(cur)) return
        val target = expectedPosition(st)
        if (abs(pos - target) > DRIFT_SEEK_MS && canSyncSeek()) {
            lastSyncSeekAt = SystemClock.elapsedRealtime()
            ignoreLocalUntil = SystemClock.elapsedRealtime() + 1_500
            seekSamplePending = true
            host.seekTo(target + seekLeadMs)
            scheduleResync() // watches where the seek lands and fine-tunes it
        }
    }

    /** Controller: estimated time a state update needs to reach the server (smoothed half round trip). */
    @Volatile private var uplinkMs = 80L

    private suspend fun publish(song: Song, playing: Boolean, pos0: Long, anchor: Boolean = false) {
        val code = room ?: return
        val q = host.queue()
        // The position was sampled a little while ago; read it again right before sending so the value
        // matches the moment the server stamps (followers add the time since that stamp).
        val sampled = if (playing && host.currentSong()?.id == song.id) host.positionMs() else pos0
        // the server stamps the state when it arrives, about half a round trip after this moment
        val pos = if (playing) sampled + uplinkMs else sampled
        val idx = q.indexOfFirst { it.id == song.id }
        val window = if (idx >= 0) q.subList(max(0, idx - 3), min(q.size, idx + 40)) else listOf(song)
        val body = JSONObject().apply {
            put("by", uid)
            put("byName", myName)
            put("playing", playing)
            put("positionMs", pos)
            put("updatedAt", serverValue())
            if (anchor) put("anchor", true)
            put("song", songJson(song))
            put("queue", JSONArray().also { arr -> window.forEach { arr.put(songJson(it)) } })
        }
        // optimistic local copy so the next tick doesn't see "something changed" again
        roomState = RoomState(
            uid, myName, playing, pos, serverNow(),
            Entry(song.title, song.artist, song.album, song.durationMs), emptyList()
        )
        val sentAt = SystemClock.elapsedRealtime()
        call("PUT", "$code/state", body.toString())
        uplinkMs = ((uplinkMs * 3 + (SystemClock.elapsedRealtime() - sentAt) / 2) / 4).coerceIn(0L, 600L)
        // others are listening: start uploading right away so nobody has to wait for a request
        if (STORAGE_UPLOAD_ENABLED && _state.value.members.any { !it.isMe }) shareSong(song)
        _state.update {
            it.copy(
                nowPlayingTitle = song.title, nowPlayingArtist = song.artist,
                controllerName = myName, roomPlaying = playing, songMissing = false
            )
        }
    }

    private fun songJson(s: Song): JSONObject {
        val j = JSONObject().put("t", s.title).put("a", s.artist).put("al", s.album).put("d", s.durationMs)
        val url = if (s.source == SongSource.WEB && s.uri.scheme?.startsWith("http") == true) s.uri.toString()
        else streamUrls[s.id]
        if (url != null) j.put("u", url).put("x", extOf(s))
        return j
    }

    // =========================================================================================
    // Streaming from the controller
    // =========================================================================================

    private fun extOf(s: Song): String =
        s.format.lowercase().filter { it.isLetterOrDigit() }.takeIf { it.length in 2..5 && it != "unknown" }
            ?: s.uri.lastPathSegment?.substringAfterLast('.', "")?.lowercase()?.filter { it.isLetterOrDigit() }
                ?.takeIf { it.length in 2..5 } ?: "mp3"

    private fun sha(text: String) = MessageDigest.getInstance("SHA-1").digest(text.toByteArray())
        .joinToString("") { "%02x".format(it) }.take(16)

    /** A song object other phones use to play the controller's copy online. */
    private fun streamSong(e: Entry) = Song(
        id = STREAM_PREFIX + sha(e.url),
        uri = Uri.parse(e.url),
        title = e.title, artist = e.artist, album = e.album, durationMs = e.durationMs,
        format = e.ext.uppercase().ifBlank { "STREAM" }, sampleRateHz = 44100,
        source = SongSource.WEB
    )

    /** A song object for a file received from the controller over WebRTC. */
    private fun receivedSong(e: Entry, f: java.io.File): Song {
        val sig = songSig(e)
        // the controller sent the cover with the song; if not, use a picture embedded in the file
        val art = artFile(sig)
        // (looked for once per song: re-reading the tags on every room update would be wasteful)
        val hasArt = (art.exists() && art.length() > 0) || (artTried.add(sig) && PtArt.extractEmbedded(app, f, art))
        return Song(
            id = STREAM_PREFIX + sig,
            uri = Uri.fromFile(f),
            title = e.title, artist = e.artist, album = e.album, durationMs = e.durationMs,
            format = f.extension.uppercase().ifBlank { "MP3" }, sampleRateHz = 44100,
            fileSizeBytes = f.length(), source = SongSource.WEB,
            albumArtUri = if (hasArt) Uri.fromFile(art) else null
        )
    }

    /**
     * Controller: as soon as somebody else is in the room, the current song is uploaded in the
     * background and its URL is attached to the room state, so phones without the song start
     * streaming it with no request / waiting step.
     */
    private fun shareForRoom(st: RoomState, othersPresent: Boolean) {
        if (st.by != uid || !othersPresent) return
        val entry = st.song ?: return
        val song = host.currentSong()?.takeIf { entry.matches(it) } ?: return
        if (entry.url.isNotBlank() || !isUploadable(song)) return
        shareSong(song)
    }

    /** Upload [song] (once), attach its URL to the room state, then get the next song ready. */
    private fun shareSong(song: Song) {
        val code = room ?: return
        if (!isUploadable(song) || !uploading.add(song.id)) return
        scope.launch(Dispatchers.IO) {
            try {
                val url = streamUrls[song.id] ?: uploadSong(code, song) ?: return@launch
                // only attach it if the room is still on this song
                if (room == code && roomState?.by == uid && matchesRoomSong(song)) {
                    call("PATCH", "$code/state/song", JSONObject().put("u", url).put("x", extOf(song)).toString())
                }
                // keep one song ahead so the next track starts without a gap
                val q = host.queue()
                val i = q.indexOfFirst { it.id == song.id }
                q.getOrNull(i + 1)?.takeIf { i >= 0 && isUploadable(it) && streamUrls[it.id] == null }?.let { next ->
                    if (uploading.add(next.id)) try { uploadSong(code, next) } finally { uploading.remove(next.id) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(message = "Could not share \"${song.title}\": ${e.message ?: "upload failed"}") }
            } finally {
                uploading.remove(song.id)
            }
        }
    }

    private fun isUploadable(s: Song) = s.source == SongSource.LOCAL &&
        (s.uri.scheme == "content" || s.uri.scheme == "file")

    private fun bucketCandidates(): List<String> = buildList {
        if (cfgBucket.isNotBlank()) add(cfgBucket)
        if (cfgProjectId.isNotBlank()) { add("$cfgProjectId.firebasestorage.app"); add("$cfgProjectId.appspot.com") }
    }.distinct()

    private fun storageUrl(bucket: String, name: String) =
        "https://firebasestorage.googleapis.com/v0/b/$bucket/o/" + URLEncoder.encode(name, "UTF-8")

    /** Uploads the song file to Firebase Storage (REST) and returns a tokenised download URL. */
    private suspend fun uploadSong(code: String, song: Song): String? = uploadMutex.withLock {
        uploadLocked(code, song)
    }

    private suspend fun uploadLocked(code: String, song: Song): String {
        streamUrls[song.id]?.let { return it }
        ensureAuth()
        val token = idToken ?: throw IOException("Not signed in to Firebase.")
        val ext = extOf(song)
        val name = "playTogether/$code/${sha(song.id)}.$ext"
        val resolver = app.contentResolver
        val length = try { resolver.openAssetFileDescriptor(song.uri, "r")?.use { it.length } ?: -1L } catch (_: Exception) { -1L }
            .let { if (it > 0) it else song.fileSizeBytes.takeIf { n -> n > 0 } ?: -1L }
        val mime = resolver.getType(song.uri)?.takeIf { it.startsWith("audio/") } ?: "audio/$ext"
        val body = object : RequestBody() {
            override fun contentType() = mime.toMediaType()
            override fun contentLength() = length
            override fun writeTo(sink: BufferedSink) {
                (resolver.openInputStream(song.uri) ?: throw IOException("Cannot read the song file.")).use { sink.writeAll(it.source()) }
            }
        }
        var lastErr: String? = null
        for (bucket in bucketCandidates()) {
            val req = Request.Builder()
                .url("https://firebasestorage.googleapis.com/v0/b/$bucket/o?name=" + URLEncoder.encode(name, "UTF-8"))
                .header("Authorization", "Firebase $token")
                .post(body).build()
            uploadHttp.newCall(req).execute().use { r ->
                val text = r.body?.string().orEmpty()
                if (r.isSuccessful) {
                    val tok = JSONObject(text).optString("downloadTokens").substringBefore(',')
                    val url = storageUrl(bucket, name) + "?alt=media" + if (tok.isNotBlank()) "&token=$tok" else ""
                    streamUrls[song.id] = url
                    uploadedNames += "$bucket|$name"
                    return url
                }
                lastErr = when (r.code) {
                    401, 403 -> "Firebase Storage denied the upload. Enable Storage and allow writes for signed-in users."
                    404 -> null // wrong bucket name, try the next one
                    else -> "Storage error (${r.code})."
                }
                if (lastErr != null) throw IOException(lastErr)
            }
        }
        throw IOException("Firebase Storage bucket not found. Enable Storage in the Firebase console and rebuild with the new google-services.json.")
    }

    private suspend fun deleteUploads() {
        val token = idToken
        for (entry in uploadedNames.toList()) {
            val (bucket, name) = entry.split('|', limit = 2)
            try {
                val rb = Request.Builder().url(storageUrl(bucket, name)).delete()
                if (token != null) rb.header("Authorization", "Firebase $token")
                http.newCall(rb.build()).execute().close()
            } catch (_: Exception) { }
        }
        uploadedNames.clear(); streamUrls.clear()
    }

    // ---- song matching ------------------------------------------------------------------

    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    private fun Entry.matches(s: Song): Boolean {
        if (norm(title) != norm(s.title)) return false
        if (durationMs > 0 && s.durationMs > 0 && abs(durationMs - s.durationMs) > 3_500) return false
        val a = norm(artist); val b = norm(s.artist)
        return a.isEmpty() || b.isEmpty() || a == b || a.contains(b) || b.contains(a)
    }

    private var indexedLibrary: List<Song>? = null
    private var titleIndex: Map<String, List<Song>> = emptyMap()

    private fun findLocal(e: Entry, library: List<Song>): Song? {
        if (indexedLibrary !== library) {
            titleIndex = library.groupBy { norm(it.title) }
            indexedLibrary = library
        }
        return titleIndex[norm(e.title)]?.firstOrNull { e.matches(it) }
    }

    private fun matchesRoomSong(s: Song): Boolean = roomState?.song?.matches(s) == true

    // =========================================================================================
    // Firebase REST plumbing
    // =========================================================================================

    private fun serverValue() = JSONObject().put(".sv", "timestamp")

    private fun serverNow() = System.currentTimeMillis() + clockOffsetMs

    private suspend fun putMember(code: String) {
        call(
            "PUT", "$code/members/$uid",
            JSONObject().put("name", myName).put("seen", serverValue()).toString()
        )
    }

    /** Offset between this phone's clock and Firebase's, from the lowest-latency of 3 samples. */
    private suspend fun measureClockOffset(code: String) {
        var best: Long? = null
        var bestRtt = Long.MAX_VALUE
        repeat(5) {
            val t0 = System.currentTimeMillis()
            call("PUT", "$code/clock/$uid", serverValue().toString())
            val t1 = System.currentTimeMillis()
            val server = call("GET", "$code/clock/$uid").trim().toLongOrNull() ?: return@repeat
            val rtt = t1 - t0
            if (rtt < bestRtt) { bestRtt = rtt; best = server - (t0 + t1) / 2 }
        }
        best?.let { clockOffsetMs = it }
    }

    private fun urlFor(code: String, sub: String, extra: String = ""): String {
        val base = _state.value.databaseUrl.trimEnd('/')
        val path = if (sub.isEmpty()) code else "$code/$sub"
        val q = buildList {
            idToken?.let { add("auth=$it") }
            if (extra.isNotEmpty()) add(extra)
        }.joinToString("&")
        return "$base/playTogether/rooms/$path.json" + if (q.isNotEmpty()) "?$q" else ""
    }

    /** [path] is "ROOM" or "ROOM/sub/path". */
    private suspend fun call(method: String, path: String, body: String? = null): String =
        withContext(Dispatchers.IO) {
            ensureAuth()
            resolveDatabaseUrl()
            val code = path.substringBefore('/')
            val sub = if (path.contains('/')) path.substringAfter('/') else ""
            val extra = if (method == "GET") "" else "print=silent"
            val rb = when (method) {
                "GET", "DELETE" -> null
                else -> (body ?: "{}").toRequestBody(jsonType)
            }
            val req = Request.Builder().url(urlFor(code, sub, extra)).method(method, rb).build()
            http.newCall(req).execute().use { r ->
                val text = r.body?.string().orEmpty()
                if (!r.isSuccessful) throw IOException(friendly(r.code))
                text
            }
        }

    /**
     * The Realtime Database URL depends on the region it was created in
     * (https://NAME.firebaseio.com for the US, https://NAME.REGION.firebasedatabase.app elsewhere),
     * and google-services.json only carries it when the database existed at download time.
     * Try the likely addresses once and keep the one that answers.
     */
    private suspend fun resolveDatabaseUrl() {
        if (dbResolved) return
        dbMutex.withLock {
            if (dbResolved) return
            val pid = cfgProjectId
            val candidates = buildList {
                prefs.getString("dbUrlResolved", null)?.let { add(it) }
                add(_state.value.databaseUrl)
                if (cfgDbUrlFromFile.isNotBlank()) add(cfgDbUrlFromFile)
                if (cfgDbUrlFromFile.isNotBlank()) {
                    val h = cfgDbUrlFromFile.removePrefix("https://").substringBefore('/')
                    addAll(dbUrlsForName(h.substringBefore('.')))
                }
                if (pid.isNotBlank()) {
                    addAll(dbUrlsForName("$pid-default-rtdb"))
                    add("https://$pid.firebaseio.com")
                }
            }.map { it.trim().trimEnd('/') }.filter { it.startsWith("https://") }.distinct()

            for (c in candidates) {
                when (probeDatabase(c)) {
                    true -> {
                        prefs.edit().putString("dbUrlResolved", c).apply()
                        _state.update { it.copy(databaseUrl = c) }
                        dbResolved = true
                        return
                    }
                    false -> Unit
                    null -> return // network trouble: let the real request report it, probe again next time
                }
            }
            // every address answered "no such database": stay unresolved so the next try (after the
            // database is created or its URL pasted) probes again; the real request reports the error.
        }
    }

    /** true = database exists at [base], false = it does not, null = could not tell (offline / server error). */
    private fun probeDatabase(base: String): Boolean? = try {
        val q = idToken?.let { "?auth=$it" }.orEmpty()
        val req = Request.Builder().url("$base/playTogether/rooms/__probe__.json$q").get().build()
        http.newCall(req).execute().use { r ->
            when {
                r.code == 200 || r.code == 401 || r.code == 403 -> true
                r.code >= 500 -> null
                else -> false
            }
        }
    } catch (_: Exception) { null }

    private fun friendly(code: Int) = when (code) {
        401, 403 -> "Permission denied. In Firebase enable Anonymous sign-in, publish the database rules shown above, and add this app\u2019s SHA-1 under Project settings > Your apps."
        404 -> "Database not found. Open Firebase console > Build > Realtime Database (create it if needed), copy the database URL shown at the top of the Data tab and paste it below."
        else -> "Server error ($code)."
    }

    /** Anonymous Firebase sign-in over REST. If it is unavailable we continue unauthenticated (open rules). */
    private fun ensureAuth() {
        val key = _state.value.apiKey
        if (key.isBlank()) return
        if (idToken != null && System.currentTimeMillis() < tokenExpiryMs - 120_000) return
        try {
            val rt = refreshToken
            var o: JSONObject? = null
            if (rt != null) {
                o = authCall(
                    "https://securetoken.googleapis.com/v1/token?key=$key",
                    FormBody.Builder().add("grant_type", "refresh_token").add("refresh_token", rt).build()
                )
            }
            if (o == null) {
                o = authCall(
                    "https://identitytoolkit.googleapis.com/v1/accounts:signUp?key=$key",
                    "{\"returnSecureToken\":true}".toRequestBody(jsonType)
                )
            }
            if (o != null) {
                idToken = o.optString("idToken", o.optString("id_token")).ifBlank { null }
                refreshToken = o.optString("refreshToken", o.optString("refresh_token")).ifBlank { refreshToken }
                val secs = (o.optString("expiresIn", o.optString("expires_in", "3600"))).toLongOrNull() ?: 3600L
                tokenExpiryMs = System.currentTimeMillis() + secs * 1000
                val id = o.optString("localId", o.optString("user_id"))
                if (id.isNotBlank()) uid = id
                prefs.edit().putString("refresh", refreshToken).putString("uid", uid).apply()
            }
        } catch (_: Exception) {
            idToken = null
        }
    }

    /** SHA-1 of the app's signing certificate (what Firebase's "Android restriction" on API keys checks). */
    private val certSha1: String? by lazy {
        try {
            val pm = app.packageManager
            val sig = if (Build.VERSION.SDK_INT >= 28) {
                pm.getPackageInfo(app.packageName, android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES)
                    .signingInfo?.apkContentsSigners?.firstOrNull()
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(app.packageName, android.content.pm.PackageManager.GET_SIGNATURES)
                    .signatures?.firstOrNull()
            }
            sig?.let {
                java.security.MessageDigest.getInstance("SHA-1").digest(it.toByteArray())
                    .joinToString("") { b -> "%02X".format(b) }
            }
        } catch (e: Exception) { null }
    }

    private fun authCall(url: String, body: okhttp3.RequestBody): JSONObject? = try {
        val rb = Request.Builder().url(url).post(body)
            // keys from google-services.json are normally restricted to this app: identify it like the Firebase SDK does
            .header("X-Android-Package", app.packageName)
        certSha1?.let { rb.header("X-Android-Cert", it) }
        http.newCall(rb.build()).execute().use { r ->
            if (r.isSuccessful) JSONObject(r.body?.string().orEmpty()) else null
        }
    } catch (_: Exception) { null }

    private companion object {
        const val STREAM_PREFIX = "pt_stream_"
        /** Old path: upload to Firebase Storage (needs the Blaze plan). WebRTC replaces it for free. */
        const val STORAGE_UPLOAD_ENABLED = false
        const val MAX_PARALLEL_SENDS = 16
        /** Pause between two songs loaded ahead of time, and how long a failed one is left alone. */
        const val PREF_NEARBY_ON = "nearby_on" // Nearby is off until the person switches it on
        /** Controller: how often the local player is checked for a change to publish. */
        const val TICK_MS = 100L
        /** Controller: a position jump bigger than this (beyond normal playback) counts as a seek and is published. */
        const val CONTROLLER_SEEK_MS = 800L
        /** Controller: gentle drift check for followers' reference every this many ticks (~2 s). */
        const val DRIFT_EVERY_TICKS = 20
        /** Controller: how often (in ticks, ~5 s) the real position is published again while playing. */
        const val REANCHOR_EVERY_TICKS = 50
        /** Longest wait for a freshly changed song to report "playing" before the change is published. */
        const val SONG_CHANGE_SETTLE_MS = 1_200
        /** Follower: a position error bigger than this is corrected with a seek. */
        const val DRIFT_SEEK_MS = 150L
        /** A periodic position refresh moves a follower only when it is off by more than this. */
        const val ANCHOR_SEEK_MS = 150L
        /** Minimum time between two timing-correction seeks on a follower (a seek is audible). */
        const val MIN_SYNC_SEEK_GAP_MS = 3_000L
        const val REFRESH_DEBOUNCE_MS = 25L
        const val RESYNC_STEP_MS = 150L
        /** After a correction seek, wait this long before judging the position again. */
        const val RESYNC_SETTLE_MS = 700L
        const val RESYNC_WINDOW_MS = 40_000L
        const val RESYNC_TOLERANCE_MS = 100L
        const val RESYNC_SEEK_GAP_MS = 1_500L
        /** Songs loaded ahead of time at the same time. */
        const val MAX_PARALLEL_PRELOADS = 1
        /** A preload with no progress for this long is dropped and started again. */
        const val PRELOAD_STALL_MS = 30_000L
        const val PRELOAD_GAP_MS = 1_500L
        const val PRELOAD_RETRY_MS = 60_000L
        /** After a hotspot error the controller does not retry for this long, it just repeats the error. */
        const val WIFI_RETRY_MS = 30_000L
        /** Receiver: after the own-Wi-Fi route failed it is not tried again for this long. */
        const val WIFI_SKIP_MS = 60_000L
        /** Receiver: how long a request on the own-Wi-Fi route may go without progress before it is re-issued. */
        const val WIFI_STALL_MS = PtRoutePlan.CREDENTIALS_TIMEOUT_MS + PtRoutePlan.JOIN_TIMEOUT_MS + 15_000L
    }

    private fun randomCode(): String {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        return (1..6).map { alphabet.random() }.joinToString("")
    }
}
