package com.beatraxus.app.features

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.net.wifi.SoftApConfiguration
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Dispatchers
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * "Own Wi-Fi network" route of Play Together, tried when Nearby (Bluetooth / Wi-Fi Direct) fails.
 *
 * The controller phone opens a local-only hotspot ([PtLocalWifiHost]) and serves the current song and
 * the next ones over a tiny HTTP server; a phone that is missing a song joins that hotspot
 * ([PtLocalWifiClient], Android 10+) and downloads the file. The room (Firebase) only carries the
 * hotspot's name, password, port and token (rooms/<code>/wifi), never the audio.
 */

/** What the controller publishes in rooms/<code>/wifi so another phone can join and download. */
internal data class PtWifiCreds(
    val ssid: String,
    val pass: String,
    val port: Int,
    val token: String,
    /** The hotspot only speaks WPA3 (SAE); otherwise WPA2 is used. */
    val sae: Boolean = false
) {
    val key: String get() = "$ssid|$pass|$port|$token"

    fun toJson(): JSONObject = JSONObject()
        .put("ssid", ssid).put("pass", pass).put("port", port).put("token", token)
        .also { if (sae) it.put("sec", "sae") }

    companion object {
        fun fromJson(o: JSONObject): PtWifiCreds? {
            val ssid = o.optString("ssid")
            val pass = o.optString("pass")
            val port = o.optInt("port", 0)
            val token = o.optString("token")
            if (ssid.isBlank() || pass.isBlank() || port !in 1..65535 || token.isBlank()) return null
            return PtWifiCreds(ssid, pass, port, token, o.optString("sec") == "sae")
        }
    }
}

/** A failure of the own-Wi-Fi route with a reason that can be shown to the person. */
internal class PtWifiException(val reason: String) : Exception(reason)

// =============================================================================================
// Controller: local-only hotspot + HTTP server
// =============================================================================================

internal class PtLocalWifiHost(context: Context, private val scope: CoroutineScope) {
    private val app = context.applicationContext
    private val lock = Any()
    private val startMutex = Mutex()

    /** Called once when the hotspot stopped (idle, left the room, or Android stopped it). */
    @Volatile var onStopped: (() -> Unit)? = null

    @Volatile private var sources: Map<String, PtNearbyTransfer.Source> = emptyMap()
    @Volatile private var creds: PtWifiCreds? = null
    @Volatile private var lastActivity = 0L

    private class Running(
        val reservation: WifiManager.LocalOnlyHotspotReservation,
        val server: ServerSocket,
        val pool: ExecutorService
    )
    private var running: Running? = null
    private var watchdog: Job? = null
    private val active = AtomicInteger(0)
    private val openSockets: MutableSet<Socket> = ConcurrentHashMap.newKeySet()

    val isRunning: Boolean get() = creds != null

    /** Counts as use: keeps the hotspot from being switched off for another two minutes. */
    fun touch() { lastActivity = SystemClock.elapsedRealtime() }

    /** The songs that may be downloaded right now (the current one and the next ones). */
    fun setSources(list: List<PtNearbyTransfer.Source>) { sources = list.associateBy { it.sig } }

    /** Starts the hotspot and the server if they are not running; returns what a phone needs to join. */
    suspend fun start(): PtWifiCreds {
        return startMutex.withLock {
            creds?.let { touch(); return@withLock it }
            if (!PtNearbyTransfer.hasPermissions(app)) {
                throw PtWifiException("Permission missing: allow Nearby devices / Location for Beatraxus")
            }
            if (PtRadios.locationOffForWifi(app)) {
                throw PtWifiException("Location is off (Android 12 and older need it to start a hotspot)")
            }
            val wm = app.getSystemService(WifiManager::class.java) ?: throw PtWifiException("This phone has no Wi-Fi")
            val reservation = openHotspot(wm)
            val cfg = try {
                readConfig(reservation)
            } catch (e: Exception) {
                try { reservation.close() } catch (_: Exception) { }
                throw if (e is PtWifiException) e else PtWifiException("Hotspot error: ${e.message ?: e.javaClass.simpleName}")
            }
            val server = try {
                ServerSocket(0)
            } catch (e: Exception) {
                try { reservation.close() } catch (_: Exception) { }
                throw PtWifiException("Hotspot error: could not start the transfer server")
            }
            val pool = Executors.newCachedThreadPool { r -> Thread(r, "pt-wifi").apply { isDaemon = true } }
            val c = PtWifiCreds(cfg.ssid, cfg.pass, server.localPort, PtRoutePlan.newToken(), cfg.sae)
            synchronized(lock) {
                running = Running(reservation, server, pool)
                creds = c
            }
            touch()
            pool.execute { acceptLoop(server, c.token) }
            watchdog = scope.launch {
                while (isActive) {
                    delay(WATCHDOG_MS)
                    if (PtRoutePlan.idleExpired(lastActivity, SystemClock.elapsedRealtime(), active.get())) {
                        stop()
                        break
                    }
                }
            }
            c
        }
    }

    /** Stops the server and the hotspot. Safe to call at any time, also when nothing is running. */
    fun stop() {
        val r: Running?
        val was: Boolean
        synchronized(lock) {
            was = creds != null
            r = running
            creds = null
            running = null
        }
        watchdog?.cancel()
        watchdog = null
        try { r?.server?.close() } catch (_: Exception) { }
        openSockets.toList().forEach { closeQuietly(it) }
        openSockets.clear()
        r?.pool?.shutdownNow()
        try { r?.reservation?.close() } catch (_: Exception) { }
        if (was) onStopped?.invoke()
    }

    // ---- hotspot ---------------------------------------------------------------------------------

    private suspend fun openHotspot(wm: WifiManager): WifiManager.LocalOnlyHotspotReservation {
        return withTimeoutOrNull(HOTSPOT_START_TIMEOUT_MS) {
            suspendCancellableCoroutine<WifiManager.LocalOnlyHotspotReservation> { cont ->
                val cb = object : WifiManager.LocalOnlyHotspotCallback() {
                    override fun onStarted(reservation: WifiManager.LocalOnlyHotspotReservation) {
                        if (cont.isActive) cont.resume(reservation) else try { reservation.close() } catch (_: Exception) { }
                    }
                    // Android switched it off (other hotspot, Location off, ...); closing sockets is done off the main thread
                    override fun onStopped() { scope.launch(Dispatchers.IO) { stop() } }
                    override fun onFailed(reason: Int) {
                        if (cont.isActive) cont.resumeWithException(PtWifiException(PtRoutePlan.hotspotFailure(reason)))
                    }
                }
                try {
                    wm.startLocalOnlyHotspot(cb, Handler(Looper.getMainLooper()))
                } catch (e: SecurityException) {
                    if (cont.isActive) cont.resumeWithException(
                        PtWifiException("Permission missing: allow Nearby devices / Location for Beatraxus")
                    )
                } catch (e: Exception) {
                    if (cont.isActive) cont.resumeWithException(
                        PtWifiException("Hotspot error: ${e.message ?: e.javaClass.simpleName}")
                    )
                }
            }
        } ?: throw PtWifiException("Hotspot error: it did not start in time")
    }

    private class HotspotConfig(val ssid: String, val pass: String, val sae: Boolean)

    @Suppress("DEPRECATION")
    private fun readConfig(reservation: WifiManager.LocalOnlyHotspotReservation): HotspotConfig {
        val ssid: String?
        val pass: String?
        var sae = false
        if (Build.VERSION.SDK_INT >= 30) {
            val c = reservation.softApConfiguration
            ssid = c.ssid
            pass = c.passphrase
            sae = c.securityType == SoftApConfiguration.SECURITY_TYPE_WPA3_SAE
        } else {
            val c = reservation.wifiConfiguration
            ssid = c?.SSID?.trim('"')
            pass = c?.preSharedKey?.trim('"')
        }
        if (ssid.isNullOrBlank()) throw PtWifiException("Hotspot error: Android did not report the network name")
        if (pass.isNullOrBlank()) throw PtWifiException("Hotspot error: the hotspot has no password")
        return HotspotConfig(ssid, pass, sae)
    }

    // ---- HTTP server -----------------------------------------------------------------------------

    private fun acceptLoop(server: ServerSocket, token: String) {
        while (!server.isClosed) {
            val socket = try { server.accept() } catch (_: Exception) { break }
            if (active.get() >= MAX_CONNECTIONS) {
                try { respond(socket.getOutputStream(), 503, "Busy") } catch (_: Exception) { }
                closeQuietly(socket)
                continue
            }
            active.incrementAndGet()
            openSockets.add(socket)
            val pool = synchronized(lock) { running?.pool }
            try {
                pool?.execute {
                    try { handle(socket, token) } catch (_: Exception) { } finally {
                        openSockets.remove(socket)
                        active.decrementAndGet()
                        touch()
                        closeQuietly(socket)
                    }
                } ?: throw IllegalStateException("stopped")
            } catch (_: Exception) {
                openSockets.remove(socket)
                active.decrementAndGet()
                closeQuietly(socket)
            }
        }
    }

    private fun handle(socket: Socket, token: String) {
        socket.soTimeout = READ_TIMEOUT_MS
        val input = socket.getInputStream().buffered()
        val out = socket.getOutputStream().buffered(64 * 1024)
        val line = readLine(input) ?: return
        var headers = 0
        while (true) {
            val h = readLine(input) ?: return
            if (h.isEmpty()) break
            if (++headers > 64) return
        }
        val parts = line.split(' ')
        if (parts.size < 2) { respond(out, 400, "Bad Request"); return }
        if (parts[0] != "GET") { respond(out, 405, "Method Not Allowed"); return }
        val target = PtRoutePlan.parseTarget(parts[1]) ?: run { respond(out, 404, "Not Found"); return }
        if (!PtRoutePlan.tokenOk(token, target.token)) { respond(out, 403, "Forbidden"); return }
        val src = sources[target.sig] ?: run { respond(out, 404, "Not Found"); return }
        touch()
        when (target.kind) {
            PtRoutePlan.Kind.SONG -> serveSong(out, src)
            PtRoutePlan.Kind.ART -> serveArt(out, src)
        }
    }

    private fun serveSong(out: OutputStream, src: PtNearbyTransfer.Source) {
        val pfd = try { src.open() } catch (_: Exception) { null }
        if (pfd == null) { respond(out, 404, "Not Found"); return }
        try {
            val length = pfd.statSize.takeIf { it > 0 } ?: src.size
            if (length <= 0) { respond(out, 404, "Not Found"); return }
            writeHead(out, "application/octet-stream", length, "X-Ext: ${ptSafeExt(src.ext)}\r\n")
            ParcelFileDescriptor.AutoCloseInputStream(pfd).use { copyExactly(it, out, length) }
            out.flush()
        } finally {
            try { pfd.close() } catch (_: Exception) { }
        }
    }

    private fun serveArt(out: OutputStream, src: PtNearbyTransfer.Source) {
        val cover = try { src.cover() } catch (_: Exception) { null }
        if (cover == null || !cover.exists() || cover.length() <= 0) { respond(out, 404, "Not Found"); return }
        writeHead(out, "image/jpeg", cover.length(), "")
        cover.inputStream().use { copyExactly(it, out, cover.length()) }
        out.flush()
    }

    private fun writeHead(out: OutputStream, type: String, length: Long, extra: String) {
        out.write(
            ("HTTP/1.1 200 OK\r\nContent-Type: $type\r\nContent-Length: $length\r\n$extra" +
                "Connection: close\r\n\r\n").toByteArray(Charsets.ISO_8859_1)
        )
    }

    private fun respond(out: OutputStream, code: Int, text: String) {
        out.write("HTTP/1.1 $code $text\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
        out.flush()
    }

    /** Copies [length] bytes (never more, so the Content-Length is always right); a shorter file just ends early. */
    private fun copyExactly(input: InputStream, out: OutputStream, length: Long) {
        val buf = ByteArray(64 * 1024)
        var left = length
        while (left > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) break
            out.write(buf, 0, n)
            left -= n
        }
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString()
            if (b != '\r'.code) {
                sb.append(b.toChar())
                if (sb.length > MAX_LINE) return null
            }
        }
    }

    private fun closeQuietly(s: Socket) { try { s.close() } catch (_: Exception) { } }

    private companion object {
        const val HOTSPOT_START_TIMEOUT_MS = 15_000L
        const val WATCHDOG_MS = 10_000L
        const val MAX_CONNECTIONS = 6
        const val READ_TIMEOUT_MS = 10_000
        const val MAX_LINE = 4096
    }
}

// =============================================================================================
// Follower: join the controller's hotspot and download
// =============================================================================================

internal class PtLocalWifiClient(context: Context, private val scope: CoroutineScope) {
    private val app = context.applicationContext
    private val joinMutex = Mutex()

    private class Link(val key: String, val callback: JoinCallback, val http: OkHttpClient, val host: String)

    @Volatile private var link: Link? = null
    private var releaseJob: Job? = null
    @Volatile private var activeCall: Call? = null
    @Volatile private var aborted = false

    /** True while this phone is joined to a controller's hotspot (a near link: loading ahead is fine). */
    val isJoined: Boolean get() = link != null

    /**
     * Joins the controller's Wi-Fi (reusing the connection if it is already up), downloads the cover
     * (best effort) and the song, and returns the song file. Throws [PtWifiException] with a reason.
     * The connection stays up for [PtRoutePlan.CLIENT_IDLE_MS] so the queue can be loaded through it.
     */
    suspend fun fetch(
        creds: PtWifiCreds,
        sig: String,
        artTarget: File?,
        target: (ext: String) -> File,
        onProgress: (Int) -> Unit,
        onPartial: ((PtPartialFiles.Entry) -> Unit)? = null
    ): File {
        if (Build.VERSION.SDK_INT < 29) throw PtWifiException("Joining another Wi-Fi needs Android 10 or newer")
        synchronized(this) { releaseJob?.cancel(); releaseJob = null }
        aborted = false
        try {
            val l = join(creds)
            val file = withContext(Dispatchers.IO) { download(l, creds, sig, artTarget, target, onProgress, onPartial) }
            scheduleRelease()
            return file
        } catch (e: CancellationException) {
            scheduleRelease()
            throw e
        } catch (e: Exception) {
            if (aborted) { scheduleRelease(); throw PtWifiException("Cancelled") }
            release()
            throw if (e is PtWifiException) e else PtWifiException(e.message ?: "Wi-Fi transfer failed")
        }
    }

    /** Stops the download that is running (the Wi-Fi connection stays up for a while). */
    fun abort() {
        aborted = true
        activeCall?.cancel()
    }

    /** Leaves the controller's Wi-Fi. */
    fun release() {
        val l: Link?
        synchronized(this) {
            releaseJob?.cancel(); releaseJob = null
            l = link
            link = null
        }
        l?.let { unregister(it.callback) }
    }

    private fun scheduleRelease() {
        synchronized(this) {
            releaseJob?.cancel()
            releaseJob = scope.launch {
                delay(PtRoutePlan.CLIENT_IDLE_MS)
                release()
            }
        }
    }

    private fun unregister(cb: ConnectivityManager.NetworkCallback) {
        try { app.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) } catch (_: Exception) { }
    }

    // ---- joining -----------------------------------------------------------------------------------

    @RequiresApi(29)
    private suspend fun join(c: PtWifiCreds): Link {
        return joinMutex.withLock {
            link?.takeIf { it.key == c.key }?.let { return@withLock it }
            release()
            if (!PtRadios.wifiOn(app)) throw PtWifiException("Wi-Fi is off")
            val cm = app.getSystemService(ConnectivityManager::class.java)
                ?: throw PtWifiException("This phone has no connectivity service")
            val spec = WifiNetworkSpecifier.Builder().setSsid(c.ssid).apply {
                if (c.sae) setWpa3Passphrase(c.pass) else setWpa2Passphrase(c.pass)
            }.build()
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(spec)
                .build()
            val cb = JoinCallback(c, SystemClock.elapsedRealtime())
            try {
                cm.requestNetwork(request, cb, PtRoutePlan.JOIN_TIMEOUT_MS.toInt())
            } catch (e: SecurityException) {
                throw PtWifiException("Permission missing: Android did not allow joining the Wi-Fi")
            } catch (e: Exception) {
                throw PtWifiException("Could not join the Wi-Fi: ${e.message ?: e.javaClass.simpleName}")
            }
            val l = try {
                withTimeout(PtRoutePlan.JOIN_TIMEOUT_MS + 3_000) { cb.result.await() }
            } catch (e: TimeoutCancellationException) {
                unregister(cb)
                throw PtWifiException("Joining the controller's Wi-Fi timed out")
            } catch (e: CancellationException) {
                unregister(cb)
                throw e
            } catch (e: Exception) {
                unregister(cb)
                throw e
            }
            synchronized(this) { link = l }
            l
        }
    }

    /** Collects the network and its addresses; completes when the controller's address is known. */
    private inner class JoinCallback(private val creds: PtWifiCreds, private val started: Long) :
        ConnectivityManager.NetworkCallback() {
        val result = CompletableDeferred<Link>()
        private var network: Network? = null
        private var props: LinkProperties? = null

        override fun onAvailable(network: Network) { this.network = network; maybeDone() }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            this.network = this.network ?: network
            props = linkProperties
            maybeDone()
        }

        override fun onUnavailable() {
            // Android reports "rejected in the dialog" at once and "not found" only at the timeout
            val waited = SystemClock.elapsedRealtime() - started
            result.completeExceptionally(
                PtWifiException(
                    if (waited < PtRoutePlan.JOIN_TIMEOUT_MS - 3_000) "Joining the controller's Wi-Fi was declined"
                    else "Could not find the controller's Wi-Fi in time"
                )
            )
        }

        override fun onLost(network: Network) {
            if (network != this.network) return
            if (!result.isCompleted) {
                result.completeExceptionally(PtWifiException("Lost the controller's Wi-Fi"))
            } else {
                val l = link
                if (l != null && l.callback === this) {
                    synchronized(this@PtLocalWifiClient) { if (link === l) link = null }
                    unregister(this)
                }
            }
        }

        private fun maybeDone() {
            if (result.isCompleted) return
            val n = network ?: return
            val p = props ?: return
            val host = PtRoutePlan.chooseHost(dhcpServer(p), gateways(p)) ?: return
            val http = OkHttpClient.Builder()
                .socketFactory(n.socketFactory)
                .proxy(Proxy.NO_PROXY)
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .retryOnConnectionFailure(false)
                .build()
            result.complete(Link(creds.key, this, http, host))
        }
    }

    private fun dhcpServer(p: LinkProperties): String? =
        if (Build.VERSION.SDK_INT >= 30) p.dhcpServerAddress?.hostAddress else null

    private fun gateways(p: LinkProperties): List<String> {
        val routes = p.routes
        return routes.filter { it.isDefaultRoute }.mapNotNull { it.gateway?.hostAddress } +
            routes.mapNotNull { it.gateway?.hostAddress }
    }

    // ---- downloading -------------------------------------------------------------------------------

    private fun download(
        l: Link, c: PtWifiCreds, sig: String, artTarget: File?,
        target: (String) -> File, onProgress: (Int) -> Unit,
        onPartial: ((PtPartialFiles.Entry) -> Unit)? = null
    ): File {
        val base = "http://${l.host}:${c.port}"
        val query = "?t=${c.token}"
        if (artTarget != null) {
            // the cover is a bonus: any problem with it must not stop the song
            try {
                get(l, "$base/art/$sig$query").use { r ->
                    val body = r.body
                    if (r.isSuccessful && body != null) save(body, artTarget, null)
                }
            } catch (_: Exception) {
                artTarget.delete()
            }
        }
        if (aborted) throw PtWifiException("Cancelled")
        get(l, "$base/song/$sig$query").use { r ->
            when {
                r.code == 403 -> throw PtWifiException("The controller refused the link")
                r.code == 404 -> throw PtWifiException("The controller no longer shares this song")
                !r.isSuccessful -> throw PtWifiException("The controller answered HTTP ${r.code}")
            }
            val body = r.body ?: throw PtWifiException("The controller sent an empty answer")
            val out = target(ptSafeExt(r.header("X-Ext") ?: "mp3"))
            save(body, out, onProgress, onPartial)
            return out
        }
    }

    private fun get(l: Link, url: String): Response {
        val call = l.http.newCall(Request.Builder().url(url).build())
        activeCall = call
        return call.execute()
    }

    /**
     * Writes [body] to [out] through a ".part" file, so a half file never has the final name.
     * With [onPartial] the growing file is also registered (see [PtPartialFiles]) so the song can start
     * playing before the last byte is here; the final name is only taken at the end.
     */
    private fun save(
        body: ResponseBody, out: File, onProgress: ((Int) -> Unit)?,
        onPartial: ((PtPartialFiles.Entry) -> Unit)? = null
    ) {
        out.parentFile?.mkdirs()
        val part = File(out.parentFile, out.name + ".part")
        val expected = body.contentLength()
        var got = 0L
        val entry = if (onPartial != null && expected > 0) PtPartialFiles.begin(out, expected, part) else null
        try {
            body.byteStream().use { input ->
                part.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var lastPct = -1
                    var lastPartialAt = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        got += n
                        if (entry != null) {
                            entry.advance(n)
                            val now = SystemClock.elapsedRealtime()
                            if (now - lastPartialAt >= 300L) { lastPartialAt = now; onPartial?.invoke(entry) }
                        }
                        if (expected > 0 && onProgress != null) {
                            val pct = (got * 100 / expected).toInt()
                            if (pct != lastPct) { lastPct = pct; onProgress(pct) }
                        }
                    }
                }
            }
            if (got == 0L || (expected > 0 && got != expected)) throw PtWifiException("The file arrived incomplete")
            // (a reader that already has the .part file open keeps reading it through the rename)
            out.delete()
            if (!part.renameTo(out)) throw PtWifiException("Could not save the file")
            if (entry != null) PtPartialFiles.complete(out)
        } catch (e: Exception) {
            if (entry != null) PtPartialFiles.fail(out)
            part.delete()
            throw e
        }
    }
}
