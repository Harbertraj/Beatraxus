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
 * The controller advertises the room; a phone that is missing the song discovers it, asks for the
 * song signature and receives the file as a Nearby FILE payload.  If no controller is found within
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
    @Volatile var connectedToController = false
        private set

    // ---- receiver state (one request at a time) ----
    private class Request(val sig: String, val sid: String, val target: (String) -> File) {
        @Volatile var endpointId: String? = null
        @Volatile var expected = 0L
        @Volatile var ext = "mp3"
        @Volatile var filePayload: Payload? = null
        @Volatile var finished = false
        var timeout: Job? = null
    }
    @Volatile private var request: Request? = null

    fun isBusy() = connectedToController

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

    fun stopAdvertising() {
        if (advertising) try { client.stopAdvertising() } catch (_: Exception) { }
        advertising = false
        source = null
        if (request == null) try { client.stopAllEndpoints() } catch (_: Exception) { }
    }

    /** Controller: the file that can be requested right now (null = nothing shareable). */
    fun setSource(s: Source?) { source = s }

    private val hostLifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            client.acceptConnection(endpointId, hostPayloads)
        }
        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {}
        override fun onDisconnected(endpointId: String) {}
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

    /** Receiver: look for the controller of [room] and ask it for the song with signature [sig]. */
    fun requestSong(room: String, sig: String, sid: String, myName: String, target: (ext: String) -> File) {
        cancelRequest()
        val req = Request(sig, sid, target)
        request = req
        val id = SERVICE_PREFIX + room
        req.timeout = scope.launch {
            delay(DISCOVERY_TIMEOUT_MS)
            if (request === req && req.endpointId == null) giveUp(req)
        }
        client.startDiscovery(
            id,
            object : EndpointDiscoveryCallback() {
                override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
                    if (request !== req || req.endpointId != null) return
                    req.endpointId = endpointId
                    try { client.stopDiscovery() } catch (_: Exception) { }
                    client.requestConnection(myName.take(40), endpointId, clientLifecycle(req))
                        .addOnFailureListener { giveUp(req) }
                }
                override fun onEndpointLost(endpointId: String) {}
            },
            DiscoveryOptions.Builder().setStrategy(Strategy.P2P_STAR).build()
        ).addOnFailureListener { giveUp(req) }
    }

    fun cancelRequest() {
        val r = request ?: return
        request = null
        r.finished = true
        r.timeout?.cancel()
        connectedToController = false
        try { client.stopDiscovery() } catch (_: Exception) { }
        r.endpointId?.let { try { client.disconnectFromEndpoint(it) } catch (_: Exception) { } }
    }

    fun stopAll() {
        cancelRequest()
        stopAdvertising()
        try { client.stopAllEndpoints() } catch (_: Exception) { }
    }

    private fun giveUp(r: Request) {
        if (r.finished) return
        r.finished = true
        if (request === r) cancelRequest()
        onUnavailable(r.sid)
    }

    private fun clientLifecycle(r: Request) = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            client.acceptConnection(endpointId, clientPayloads(r))
        }
        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (r.finished) return
            if (result.status.isSuccess) {
                connectedToController = true
                r.timeout?.cancel()
                // nothing should take longer than this once connected
                r.timeout = scope.launch { delay(TRANSFER_TIMEOUT_MS); giveUp(r) }
                send(endpointId, JSONObject().put("req", r.sig).toString())
            } else giveUp(r)
        }
        override fun onDisconnected(endpointId: String) {
            if (!r.finished) giveUp(r)
        }
    }

    private fun clientPayloads(r: Request) = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
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
                if (request === r) cancelRequest()
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
