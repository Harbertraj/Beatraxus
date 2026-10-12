package com.beatraxus.app.features

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Same-place song transfer for Play Together using Google Nearby Connections
 * (Bluetooth + Wi-Fi Direct / hotspot, no internet and no TURN server needed).
 *
 * Star topology: the controller advertises the room and every other phone in the room keeps one
 * standing connection to it ([startLink]), so all nearby phones are connected before anybody needs
 * a song.  A phone that is missing the song then asks over that link for the song signature and
 * receives the file as a Nearby FILE payload.  If no controller is reachable within
 * [DISCOVERY_TIMEOUT_MS], or anything fails, [onUnavailable] fires and the caller falls back to the
 * WebRTC path in [PtFileTransfer].
 *
 * Protocol per connection (all BYTES payloads are small JSON):
 *   receiver -> controller   {"req":"<song sig>","v":2}     ("v":2 = I can also take the cover)
 *   controller -> receiver   {"size":<bytes>,"ext":"mp3","sid":<song payload id>,
 *                             "art":<cover payload id>,"artSize":<bytes>}   (or {"none":true})
 *   controller -> receiver   FILE payload with the cover (only if the song has one)
 *   controller -> receiver   FILE payload with the song
 * The controller can hand out the current song and the ones queued after it (so phones can load
 * them ahead of time); a request without "v" gets the old reply (no cover) so older apps still work.
 */
class PtNearbyTransfer(
    context: Context,
    private val scope: CoroutineScope,
    /** Called whenever the number of connected nearby phones changes (controller: spokes, others: 0/1). */
    private val onPeers: (Int) -> Unit = {},
    /** Called when the Nearby route cannot deliver [sid]; caller should use WebRTC instead. */
    private val onUnavailable: (sid: String) -> Unit
) {
    private val app = context.applicationContext
    private val client: ConnectionsClient by lazy { Nearby.getConnectionsClient(app) }

    @Volatile var listener: PtFileTransfer.Listener? = null

    /** A song the controller can hand out. [cover] returns a small JPEG of its album art, if it has one. */
    class Source(
        val sig: String,
        val ext: String,
        val size: Long,
        val open: () -> ParcelFileDescriptor?,
        val cover: () -> File? = { null }
    )

    @Volatile private var sources: Map<String, Source> = emptyMap()
    @Volatile private var serviceId: String? = null
    @Volatile private var advertising = false

    /** Controller side: phones currently connected to us. */
    private val hostEndpoints: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    // ---- receiver state: one standing link to the controller + several song requests at once ----
    private class Request(
        val sig: String,
        val sid: String,
        /** Where the cover is saved (null = the caller does not want one). */
        val artTarget: File?,
        val target: (String) -> File
    ) {
        @Volatile var sent = false
        @Volatile var expected = 0L
        @Volatile var ext = "mp3"
        @Volatile var headerSeen = false
        @Volatile var artId = 0L
        /** Payload id of the song file (from the controller's header; 0 for older controllers). */
        @Volatile var songId = 0L
        /** Last time bytes of this request moved (the stall timeout counts from here). */
        @Volatile var lastProgressAt = 0L
        @Volatile var filePayload: Payload? = null
        @Volatile var artPayload: Payload? = null
        /** 0 = still coming (or none), 1 = received, -1 = failed. */
        @Volatile var artState = 0
        @Volatile var finished = false
        var timeout: Job? = null
        /** Payload ids whose transfer is complete. */
        val done: MutableSet<Long> = ConcurrentHashMap.newKeySet()
    }
    /** Open requests by session id: the song playing now and the ones loaded ahead run side by side. */
    private val requests = ConcurrentHashMap<String, Request>()
    /** Payload id -> the request it belongs to (known once the controller's header arrived). */
    private val payloadOwner = ConcurrentHashMap<Long, Request>()
    /** FILE payloads / completions that arrived before their header said whose they are. */
    private val strayFiles = ConcurrentHashMap<Long, Payload>()
    private val strayDone: MutableSet<Long> = ConcurrentHashMap.newKeySet()

    @Volatile private var linkRoom: String? = null
    @Volatile private var linkName = "Guest"
    @Volatile private var linkEndpoint: String? = null
    @Volatile private var linkConnecting = false
    @Volatile private var linkDiscovering = false
    private var windowJob: Job? = null
    private var retryJob: Job? = null

    val connectedToController: Boolean get() = linkEndpoint != null

    fun isBusy() = connectedToController

    private fun publishPeers() {
        onPeers(if (advertising) hostEndpoints.size else if (linkEndpoint != null) 1 else 0)
    }

    // ---- controller ----------------------------------------------------------------------

    /** Controller: start (or keep) advertising room [room]. */
    fun startAdvertising(room: String, myName: String) {
        val id = SERVICE_PREFIX + room
        if (advertising && serviceId == id) return
        stopAdvertising()
        serviceId = id
        advertising = true
        client.startAdvertising(
            myName.take(40), id, hostLifecycle,
            AdvertisingOptions.Builder().setStrategy(Strategy.P2P_STAR).build()
        ).addOnFailureListener { advertising = false }
    }

    /** Controller: stop advertising and drop the phones that were connected to us. */
    fun stopAdvertising() {
        if (!advertising) return
        try { client.stopAdvertising() } catch (_: Exception) { }
        advertising = false
        sources = emptyMap()
        hostEndpoints.forEach { try { client.disconnectFromEndpoint(it) } catch (_: Exception) { } }
        hostEndpoints.clear()
        publishPeers()
    }

    /** Controller: the songs that can be requested right now (empty = nothing shareable). */
    fun setSources(list: List<Source>) { sources = list.associateBy { it.sig } }

    private val hostLifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            client.acceptConnection(endpointId, hostPayloads)
        }
        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) { hostEndpoints.add(endpointId); publishPeers() }
        }
        override fun onDisconnected(endpointId: String) {
            if (hostEndpoints.remove(endpointId)) publishPeers()
        }
    }

    private val hostPayloads = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type != Payload.Type.BYTES) return
            val req = try { JSONObject(String(payload.asBytes() ?: return)) } catch (_: Exception) { return }
            val src = sources[req.optString("req")]
            if (src == null) {
                send(endpointId, JSONObject().put("none", true).toString())
                return
            }
            // Building the cover reads and decodes an image: not on the thread Nearby calls us on.
            val wantsCover = req.optInt("v", 1) >= 2
            scope.launch(Dispatchers.IO) { serve(endpointId, src, wantsCover) }
        }
        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {}
    }

    private fun serve(endpointId: String, src: Source, wantsCover: Boolean) {
        val pfd = try { src.open() } catch (_: Exception) { null }
        if (pfd == null) { send(endpointId, JSONObject().put("none", true).toString()); return }
        val songPayload = Payload.fromFile(pfd)
        var artPayload: Payload? = null
        var artSize = 0L
        if (wantsCover) {
            try {
                src.cover()?.let { f -> artPayload = Payload.fromFile(f); artSize = f.length() }
            } catch (_: Exception) { artPayload = null }
        }
        // "req" echoes the signature so a phone with several open requests knows which song this header is for
        val header = JSONObject().put("size", src.size).put("ext", src.ext).put("req", src.sig)
        if (wantsCover) {
            header.put("sid", songPayload.id)
            artPayload?.let { header.put("art", it.id).put("artSize", artSize) }
        }
        send(endpointId, header.toString())
        artPayload?.let { client.sendPayload(endpointId, it) } // the small cover goes first
        client.sendPayload(endpointId, songPayload)
    }

    private fun send(endpointId: String, json: String) {
        client.sendPayload(endpointId, Payload.fromBytes(json.toByteArray()))
    }

    // ---- receiver ------------------------------------------------------------------------

    /**
     * Receiver: keep a standing connection to the controller of [room]. Safe to call on every room
     * update; it only does work when no link is running yet. Discovery runs in short windows and
     * retries, so it finds the controller whenever it appears and does not drain the battery when
     * nobody is nearby.
     */
    fun startLink(room: String, myName: String) {
        linkName = myName
        if (linkRoom == room) return
        stopLink()
        linkRoom = room
        beginDiscovery()
    }

    /** Receiver: drop the standing connection (this phone became the controller, left, or turned Nearby off). */
    fun stopLink() {
        if (linkRoom == null && linkEndpoint == null) return
        linkRoom = null
        windowJob?.cancel(); retryJob?.cancel()
        linkConnecting = false; linkDiscovering = false
        try { client.stopDiscovery() } catch (_: Exception) { }
        linkEndpoint?.let { try { client.disconnectFromEndpoint(it) } catch (_: Exception) { } }
        linkEndpoint = null
        publishPeers()
    }

    private fun beginDiscovery() {
        val room = linkRoom ?: return
        if (linkEndpoint != null || linkConnecting) return
        try { client.stopDiscovery() } catch (_: Exception) { }
        linkDiscovering = true
        windowJob?.cancel()
        windowJob = scope.launch {
            delay(DISCOVERY_WINDOW_MS)
            if (linkRoom == room && linkEndpoint == null && !linkConnecting) {
                try { client.stopDiscovery() } catch (_: Exception) { }
                linkDiscovering = false
                scheduleRetry(LINK_IDLE_MS)
            }
        }
        client.startDiscovery(
            SERVICE_PREFIX + room,
            object : EndpointDiscoveryCallback() {
                override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
                    if (linkRoom != room || linkEndpoint != null || linkConnecting) return
                    linkConnecting = true
                    linkDiscovering = false
                    windowJob?.cancel()
                    try { client.stopDiscovery() } catch (_: Exception) { }
                    client.requestConnection(linkName.take(40), endpointId, linkLifecycle)
                        .addOnFailureListener { linkFailed() }
                }
                override fun onEndpointLost(endpointId: String) {}
            },
            DiscoveryOptions.Builder().setStrategy(Strategy.P2P_STAR).build()
        ).addOnFailureListener { linkFailed() }
    }

    private fun scheduleRetry(delayMs: Long) {
        retryJob?.cancel()
        retryJob = scope.launch {
            delay(delayMs)
            if (linkRoom != null && linkEndpoint == null && !linkConnecting) beginDiscovery()
        }
    }

    private fun linkFailed() {
        windowJob?.cancel()
        linkConnecting = false; linkDiscovering = false; linkEndpoint = null
        try { client.stopDiscovery() } catch (_: Exception) { }
        publishPeers()
        if (linkRoom != null) scheduleRetry(LINK_RETRY_MS)
    }

    private val linkLifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            client.acceptConnection(endpointId, linkPayloads)
        }
        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (linkRoom == null) { try { client.disconnectFromEndpoint(endpointId) } catch (_: Exception) { }; return }
            if (result.status.isSuccess) {
                linkConnecting = false
                linkEndpoint = endpointId
                retryJob?.cancel(); windowJob?.cancel()
                publishPeers()
                requests.values.forEach { sendRequest(it) }
            } else linkFailed()
        }
        override fun onDisconnected(endpointId: String) {
            if (endpointId != linkEndpoint) return
            linkEndpoint = null; linkConnecting = false
            publishPeers()
            requests.values.toList().forEach { giveUp(it) } // cut off mid-request: caller falls back to the internet route
            if (linkRoom != null) scheduleRetry(LINK_QUICK_RETRY_MS)
        }
    }

    /** Receiver: ask the controller of [room] for the song with signature [sig]. Several can be open at once. */
    fun requestSong(
        room: String, sig: String, sid: String, myName: String,
        artTarget: File?, target: (ext: String) -> File
    ) {
        val req = Request(sig, sid, artTarget, target)
        requests[sid] = req
        // The link is normally up already; if not, give it a short while before using the internet.
        req.timeout = scope.launch {
            delay(DISCOVERY_TIMEOUT_MS)
            if (requests[sid] === req && !req.sent) giveUp(req)
        }
        startLink(room, myName)
        if (linkEndpoint != null) sendRequest(req)
        else if (!linkConnecting && !linkDiscovering) { retryJob?.cancel(); beginDiscovery() }
    }

    private fun sendRequest(r: Request) {
        val ep = linkEndpoint ?: return
        synchronized(r) {
            if (r.sent || r.finished) return
            r.sent = true
        }
        r.timeout?.cancel()
        r.lastProgressAt = SystemClock.elapsedRealtime()
        // With several songs sharing the link a transfer can take long; it only counts as dead when nothing moves.
        r.timeout = scope.launch {
            while (!r.finished) {
                delay(STALL_CHECK_MS)
                if (SystemClock.elapsedRealtime() - r.lastProgressAt > TRANSFER_TIMEOUT_MS) { giveUp(r); break }
            }
        }
        send(ep, JSONObject().put("req", r.sig).put("v", 2).toString())
    }

    /** Forget the request [sid]; the standing link stays up. */
    fun cancelRequest(sid: String) {
        val r = requests.remove(sid) ?: return
        r.finished = true
        r.timeout?.cancel()
        r.filePayload?.let { try { client.cancelPayload(it.id) } catch (_: Exception) { } }
        r.artPayload?.let { try { client.cancelPayload(it.id) } catch (_: Exception) { } }
        forget(r)
    }

    fun cancelAllRequests() {
        requests.keys.toList().forEach { cancelRequest(it) }
        strayFiles.clear(); strayDone.clear()
    }

    /** Drops the payload bookkeeping of a request that is over. */
    private fun forget(r: Request) {
        if (r.songId != 0L) payloadOwner.remove(r.songId, r)
        if (r.artId != 0L) payloadOwner.remove(r.artId, r)
        r.filePayload?.let { payloadOwner.remove(it.id, r); strayDone.remove(it.id) }
        r.artPayload?.let { payloadOwner.remove(it.id, r); strayDone.remove(it.id) }
    }

    fun stopAll() {
        cancelAllRequests()
        stopLink()
        stopAdvertising()
        try { client.stopAllEndpoints() } catch (_: Exception) { }
        hostEndpoints.clear()
        publishPeers()
    }

    private fun giveUp(r: Request) {
        if (r.finished) return
        r.finished = true
        cancelRequest(r.sid)
        onUnavailable(r.sid)
    }

    /** The request a header belongs to: the one it names, else (older controller) the only one still waiting. */
    private fun requestForHeader(sig: String): Request? {
        if (sig.isNotEmpty()) return requests.values.firstOrNull { it.sig == sig && !it.headerSeen }
        val waiting = requests.values.filter { it.sent && !it.headerSeen }
        return if (waiting.size == 1) waiting[0] else null
    }

    /** Hands files that arrived early to the request they belong to. */
    private fun claimStrays() {
        for (r in requests.values) {
            if (!r.headerSeen || r.finished) continue
            if (r.artId != 0L && r.artPayload == null) {
                strayFiles.remove(r.artId)?.let { r.artPayload = it; payloadOwner[it.id] = r }
            }
            if (r.filePayload == null) {
                val song = if (r.songId != 0L) strayFiles.remove(r.songId)
                else strayFiles.keys.firstOrNull { it != r.artId }?.let { strayFiles.remove(it) } // older controller
                if (song != null) { r.filePayload = song; r.songId = song.id; payloadOwner[song.id] = r }
            }
            if (r.artPayload?.id?.let { it in strayDone } == true) { r.artState = 1; r.done.add(r.artPayload!!.id) }
            val f = r.filePayload
            if (f != null && f.id in strayDone) { r.done.add(f.id); finish(r) }
        }
    }

    private val linkPayloads = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            when (payload.type) {
                Payload.Type.BYTES -> {
                    val o = try { JSONObject(String(payload.asBytes() ?: return)) } catch (_: Exception) { return }
                    val r = requestForHeader(o.optString("req")) ?: return
                    if (r.finished) return
                    if (o.optBoolean("none")) { giveUp(r); return }
                    r.expected = o.optLong("size")
                    r.ext = ptSafeExt(o.optString("ext", "mp3"))
                    r.artId = o.optLong("art", 0L)
                    r.songId = o.optLong("sid", 0L)
                    r.lastProgressAt = SystemClock.elapsedRealtime()
                    if (r.songId != 0L) payloadOwner[r.songId] = r
                    if (r.artId != 0L) payloadOwner[r.artId] = r
                    r.headerSeen = true
                    claimStrays()
                }
                Payload.Type.FILE -> {
                    val owner = payloadOwner[payload.id]
                    if (owner != null) {
                        if (payload.id == owner.artId) owner.artPayload = payload else owner.filePayload = payload
                        claimStrays()
                    } else {
                        strayFiles[payload.id] = payload
                        claimStrays()
                    }
                }
                else -> {}
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, u: PayloadTransferUpdate) {
            val r = payloadOwner[u.payloadId]
            if (r == null) {
                // header not seen yet: remember completions so they are not lost
                if (u.status == PayloadTransferUpdate.Status.SUCCESS) { strayDone.add(u.payloadId); claimStrays() }
                return
            }
            if (r.finished) return
            val isArt = r.artId != 0L && u.payloadId == r.artId
            when {
                isArt -> when (u.status) {
                    PayloadTransferUpdate.Status.SUCCESS -> { r.done.add(u.payloadId); r.artState = 1 }
                    PayloadTransferUpdate.Status.FAILURE, PayloadTransferUpdate.Status.CANCELED -> r.artState = -1
                    else -> {}
                }
                else -> when (u.status) {
                    PayloadTransferUpdate.Status.IN_PROGRESS -> {
                        r.lastProgressAt = SystemClock.elapsedRealtime()
                        if (u.totalBytes > 0) listener?.onProgress(r.sid, (u.bytesTransferred * 100 / u.totalBytes).toInt())
                    }
                    PayloadTransferUpdate.Status.SUCCESS -> { r.done.add(u.payloadId); finish(r) }
                    PayloadTransferUpdate.Status.FAILURE, PayloadTransferUpdate.Status.CANCELED -> giveUp(r)
                    else -> {}
                }
            }
        }
    }

    /** Copies a received FILE payload into [out]. */
    private fun copyPayload(payload: Payload, out: File) {
        out.parentFile?.mkdirs()
        if (Build.VERSION.SDK_INT >= 29) {
            val pfd = payload.asFile()?.asParcelFileDescriptor() ?: throw IllegalStateException("no file")
            ParcelFileDescriptor.AutoCloseInputStream(pfd).use { i -> out.outputStream().use { o -> i.copyTo(o) } }
        } else {
            @Suppress("DEPRECATION")
            val f = payload.asFile()?.asJavaFile() ?: throw IllegalStateException("no file")
            FileInputStream(f).use { i -> out.outputStream().use { o -> i.copyTo(o) } }
            f.delete()
        }
    }

    /** The cover is small and is sent first, so it is normally here already; give it a moment if not. */
    private suspend fun saveCover(r: Request) {
        val target = r.artTarget ?: return
        if (r.artId == 0L) return // the controller has no cover for this song (or an older app)
        var waited = 0L
        while (r.artState == 0 && waited < COVER_WAIT_MS) { delay(50); waited += 50 }
        val payload = r.artPayload
        if (r.artState != 1 || payload == null) return
        try { copyPayload(payload, target) } catch (_: Exception) { target.delete() }
    }

    private fun finish(r: Request) {
        val payload = r.filePayload ?: return giveUp(r)
        scope.launch(Dispatchers.IO) {
            val out = r.target(r.ext)
            try {
                copyPayload(payload, out)
                if (r.expected > 0 && out.length() != r.expected) throw IllegalStateException("incomplete file")
                if (r.finished) { out.delete(); return@launch }
                saveCover(r)
                if (r.finished) { out.delete(); return@launch }
                r.finished = true
                r.timeout?.cancel()
                requests.remove(r.sid) // done; the link to the controller stays up
                forget(r)
                listener?.onReceived(r.sid, out)
            } catch (_: Exception) {
                out.delete()
                giveUp(r)
            }
        }
    }

    companion object {
        private const val SERVICE_PREFIX = "com.beatraxus.pt."
        private const val DISCOVERY_TIMEOUT_MS = 8_000L
        /** How long one scan for the controller runs before pausing, and how long it pauses. */
        private const val DISCOVERY_WINDOW_MS = 25_000L
        private const val LINK_IDLE_MS = 15_000L
        private const val LINK_RETRY_MS = 4_000L
        private const val LINK_QUICK_RETRY_MS = 1_500L
        private const val TRANSFER_TIMEOUT_MS = 180_000L
        private const val COVER_WAIT_MS = 2_500L
        private const val STALL_CHECK_MS = 5_000L

        /** Runtime permissions Nearby Connections needs on this Android version. */
        fun requiredPermissions(): Array<String> = buildList {
            if (Build.VERSION.SDK_INT >= 31) {
                add(Manifest.permission.BLUETOOTH_ADVERTISE)
                add(Manifest.permission.BLUETOOTH_CONNECT)
                add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.NEARBY_WIFI_DEVICES)
            if (Build.VERSION.SDK_INT <= 32) add(Manifest.permission.ACCESS_FINE_LOCATION)
        }.toTypedArray()

        fun hasPermissions(context: Context) = requiredPermissions().all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }
}
