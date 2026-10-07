package com.beatraxus.app.features

import android.content.Context
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

    private data class RoomState(
        val by: String,
        val byName: String,
        val playing: Boolean,
        val positionMs: Long,
        val updatedAt: Long,
        val song: Entry?,
        val queue: List<Entry>
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
        val base = PtUiState(displayName = name)
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
        stopJobs()
        room = null
        roomState = null
        _state.update {
            it.copy(
                status = PtStatus.IDLE, roomCode = null, members = emptyList(), nowPlayingTitle = null,
                nowPlayingArtist = null, controllerName = null, roomPlaying = false, songMissing = false, streaming = false,
                message = null, busy = false
            )
        }
        scope.launch(Dispatchers.IO + NonCancellable) {
            try {
                call("DELETE", "$code/members/$me")
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
                                    "put", "patch" -> refreshTrigger.trySend(Unit)
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
                delay(120) // let a burst of events collapse into one read
                try { refresh() } catch (e: CancellationException) { throw e } catch (_: Exception) { }
            }
        }
    }

    /** Presence + staleness check + clock re-sync. */
    private fun startHeartbeat(code: String) {
        heartbeatJob = scope.launch(Dispatchers.IO) {
            var n = 0
            while (isActive) {
                delay(20_000)
                try {
                    putMember(code)
                    if (++n % 9 == 0) measureClockOffset(code)
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
            while (isActive) {
                delay(700)
                val now = SystemClock.elapsedRealtime()
                val cur = host.currentSong()
                val playing = host.isPlaying()
                val pos = host.positionMs()

                if (now < ignoreLocalUntil) {
                    // we are applying a remote change: don't echo it back
                    lastId = cur?.id; lastPlaying = playing; lastPos = pos; lastT = now
                    continue
                }
                val streamEnded = cur != null && cur.id.startsWith(STREAM_PREFIX) &&
                    cur.durationMs > 0 && pos >= cur.durationMs - 1_500
                if (cur != null && !streamEnded) {
                    try {
                        when {
                            cur.id != lastId -> if (!matchesRoomSong(cur) || roomState?.playing != playing) publish(cur, playing, pos)
                            playing != lastPlaying -> publish(cur, playing, pos)
                            else -> {
                                val expected = if (lastPlaying) lastPos + (now - lastT) else lastPos
                                if (abs(pos - expected) > 2_200) publish(cur, playing, pos)
                                else if (++tick % 6 == 0) correctDrift(cur, playing, pos)
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) { /* next tick retries via the changed state */ }
                }
                lastId = cur?.id; lastPlaying = playing; lastPos = pos; lastT = now
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
                stopJobs(); room = null
                _state.update {
                    it.copy(status = PtStatus.IDLE, roomCode = null, members = emptyList(), message = "The room was closed.")
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
            shareForRoom(st, members.any { !it.isMe })
        }
    }

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
            queue = q
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
            if (entry.url.isNotBlank()) {
                // Not in this library: play the controller's copy online, at the room's position.
                val stream = streamSong(entry)
                val target = expectedPosition(st)
                ignoreLocalUntil = SystemClock.elapsedRealtime() + 2_500
                _state.update { it.copy(songMissing = false, streaming = true) }
                val cur = host.currentSong()
                if (cur?.id != stream.id) {
                    host.playQueue(listOf(stream), 0, target, st.playing)
                } else {
                    if (host.isPlaying() != st.playing) host.setPlaying(st.playing)
                    if (abs(host.positionMs() - target) > 1_500) host.seekTo(target)
                }
                return
            }
            // Nothing to stream yet: silence this phone and ask the controller to share the file.
            ignoreLocalUntil = SystemClock.elapsedRealtime() + 2_500
            if (host.isPlaying()) host.setPlaying(false)
            _state.update { it.copy(songMissing = true, streaming = false) }
            return
        }
        _state.update { it.copy(songMissing = false, streaming = false) }

        val target = expectedPosition(st)
        ignoreLocalUntil = SystemClock.elapsedRealtime() + 2_500
        val cur = host.currentSong()
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
            host.playQueue(songs, index, target, st.playing)
        } else {
            if (host.isPlaying() != st.playing) host.setPlaying(st.playing)
            if (abs(host.positionMs() - target) > 1_500) host.seekTo(target)
        }
    }

    private fun correctDrift(cur: Song, playing: Boolean, pos: Long) {
        val st = roomState ?: return
        if (st.by == uid || !st.playing || !playing || !matchesRoomSong(cur)) return
        val target = expectedPosition(st)
        if (abs(pos - target) > 1_800) {
            ignoreLocalUntil = SystemClock.elapsedRealtime() + 1_500
            host.seekTo(target)
        }
    }

    private suspend fun publish(song: Song, playing: Boolean, pos: Long) {
        val code = room ?: return
        val q = host.queue()
        val idx = q.indexOfFirst { it.id == song.id }
        val window = if (idx >= 0) q.subList(max(0, idx - 3), min(q.size, idx + 40)) else listOf(song)
        val body = JSONObject().apply {
            put("by", uid)
            put("byName", myName)
            put("playing", playing)
            put("positionMs", pos)
            put("updatedAt", serverValue())
            put("song", songJson(song))
            put("queue", JSONArray().also { arr -> window.forEach { arr.put(songJson(it)) } })
        }
        // optimistic local copy so the next tick doesn't see "something changed" again
        roomState = RoomState(
            uid, myName, playing, pos, serverNow(),
            Entry(song.title, song.artist, song.album, song.durationMs), emptyList()
        )
        call("PUT", "$code/state", body.toString())
        // others are listening: start uploading right away so nobody has to wait for a request
        if (_state.value.members.any { !it.isMe }) shareSong(song)
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
        repeat(3) {
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

    private companion object { const val STREAM_PREFIX = "pt_stream_" }

    private fun randomCode(): String {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        return (1..6).map { alphabet.random() }.joinToString("")
    }
}
