package com.beatraxus.app.features

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelFileDescriptor
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
 *   receiver -> controller   {"req":"<song sig>"}
 *   controller -> receiver   {"size":<bytes>,"ext":"mp3"}   (or {"none":true})
 *   controller -> receiver   FILE payload
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

    /** What the controller is currently able to hand out. */
    class Source(val sig: String, val ext: String, val size: Long, val open: () -> ParcelFileDescriptor?)

    @Volatile private var source: Source? = null
    @Volatile private var serviceId: String? = null
    @Volatile private var advertising = false

    /** Controller side: phones currently connected to us. */
    private val hostEndpoints: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    // ---- receiver state: one standing link to the controller + one song request at a time ----
    private class Request(val sig: String, val sid: String, val target: (String) -> File) {
        @Volatile var sent = false
        @Volatile var expected = 0L
        @Volatile var ext = "mp3"
        @Volatile var filePayload: Payload? = null
        @Volatile var finished = false
        var timeout: Job? = null
    }
    @Volatile private var request: Request? = null

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
        source = null
        hostEndpoints.forEach { try { client.disconnectFromEndpoint(it) } catch (_: Exception) { } }
        hostEndpoints.clear()
        publishPeers()
    }

    /** Controller: the file that can be requested right now (null = nothing shareable). */
    fun setSource(s: Source?) { source = s }

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
            val sig = try { JSONObject(String(payload.asBytes() ?: return)).optString("req") } catch (_: Exception) { return }
            val src = source
            if (src == null || src.sig != sig) {
                send(endpointId, JSONObject().put("none", true).toString())
                return
            }
            val pfd = try { src.open() } catch (_: Exception) { null }
            if (pfd == null) { send(endpointId, JSONObject().put("none", true).toString()); return }
            send(endpointId, JSONObject().put("size", src.size).put("ext", src.ext).toString())
            client.sendPayload(endpointId, Payload.fromFile(pfd))
        }
        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {}
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
                request?.let { sendRequest(it) }
            } else linkFailed()
        }
        override fun onDisconnected(endpointId: String) {
            if (endpointId != linkEndpoint) return
            linkEndpoint = null; linkConnecting = false
            publishPeers()
            request?.let { giveUp(it) } // cut off mid-request: caller falls back to the internet route
            if (linkRoom != null) scheduleRetry(LINK_QUICK_RETRY_MS)
        }
    }

    /** Receiver: ask the controller of [room] for the song with signature [sig]. */
    fun requestSong(room: String, sig: String, sid: String, myName: String, target: (ext: String) -> File) {
        cancelRequest()
        val req = Request(sig, sid, target)
        request = req
        // The link is normally up already; if not, give it a short while before using the internet.
        req.timeout = scope.launch {
            delay(DISCOVERY_TIMEOUT_MS)
            if (request === req && !req.sent) giveUp(req)
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
        // nothing should take longer than this once the request is out
        r.timeout = scope.launch { delay(TRANSFER_TIMEOUT_MS); giveUp(r) }
        send(ep, JSONObject().put("req", r.sig).toString())
    }

    /** Forget the current request; the standing link stays up. */
    fun cancelRequest() {
        val r = request ?: return
        request = null
        r.finished = true
        r.timeout?.cancel()
        r.filePayload?.let { try { client.cancelPayload(it.id) } catch (_: Exception) { } }
    }

    fun stopAll() {
        cancelRequest()
        stopLink()
        stopAdvertising()
        try { client.stopAllEndpoints() } catch (_: Exception) { }
        hostEndpoints.clear()
        publishPeers()
    }

    private fun giveUp(r: Request) {
        if (r.finished) return
        r.finished = true
        if (request === r) cancelRequest()
        onUnavailable(r.sid)
    }

    private val linkPayloads = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            val r = request ?: return
            if (r.finished) return
            when (payload.type) {
                Payload.Type.BYTES -> {
                    val o = try { JSONObject(String(payload.asBytes() ?: return)) } catch (_: Exception) { return }
                    if (o.optBoolean("none")) { giveUp(r); return }
                    r.expected = o.optLong("size")
                    r.ext = o.optString("ext", "mp3")
                }
                Payload.Type.FILE -> r.filePayload = payload
                else -> {}
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, u: PayloadTransferUpdate) {
            val r = request ?: return
            if (r.finished) return
            val isFile = r.filePayload?.id == u.payloadId
            if (!isFile) return
            when (u.status) {
                PayloadTransferUpdate.Status.IN_PROGRESS -> {
                    if (u.totalBytes > 0) listener?.onProgress(r.sid, (u.bytesTransferred * 100 / u.totalBytes).toInt())
                }
                PayloadTransferUpdate.Status.SUCCESS -> finish(r)
                PayloadTransferUpdate.Status.FAILURE, PayloadTransferUpdate.Status.CANCELED -> giveUp(r)
                else -> {}
            }
        }
    }

    private fun finish(r: Request) {
        val payload = r.filePayload ?: return giveUp(r)
        scope.launch(Dispatchers.IO) {
            val out = r.target(r.ext)
            try {
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
                if (r.expected > 0 && out.length() != r.expected) throw IllegalStateException("incomplete file")
                if (r.finished) { out.delete(); return@launch }
                r.finished = true
                r.timeout?.cancel()
                if (request === r) request = null // done; the link to the controller stays up
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
