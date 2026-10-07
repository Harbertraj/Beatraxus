package com.beatraxus.app.features

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

/**
 * Peer-to-peer song transfer for Play Together (free: no Firebase Storage, no Blaze plan).
 *
 * Firebase Realtime Database only carries the small signaling messages (offer / answer / ICE).
 * The audio file itself goes straight from the controller phone to the phone that is missing
 * the song over a WebRTC data channel.
 *
 * Wire format on the data channel:
 *   1. text   {"size":<bytes>,"ext":"mp3"}
 *   2. binary 16 KB chunks
 *   3. text   "EOF"
 */
class PtFileTransfer(
    context: Context,
    private val scope: CoroutineScope,
    /** Sends a signaling message (kind = offer | answer | ice) for session [sid]. */
    private val sendSignal: suspend (sid: String, kind: String, json: String) -> Unit
) {
    private val app = context.applicationContext

    interface Listener {
        fun onProgress(sid: String, percent: Int)
        fun onReceived(sid: String, file: File)
        fun onFailed(sid: String, reason: String)
    }

    @Volatile var listener: Listener? = null

    private class Session(val sid: String, val pc: PeerConnection, val isSender: Boolean) {
        var channel: DataChannel? = null
        var job: Job? = null
        val appliedIce = HashSet<String>()
        var remoteSet = false
        val pendingIce = ArrayList<IceCandidate>()
        @Volatile var done = false
        var out: FileOutputStream? = null
        var outFile: File? = null
        var expected = 0L
        var got = 0L
        var ext = "mp3"
    }

    private val sessions = ConcurrentHashMap<String, Session>()

    private val factory: PeerConnectionFactory by lazy {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(app).createInitializationOptions()
        )
        PeerConnectionFactory.builder().createPeerConnectionFactory()
    }

    private val rtcConfig by lazy {
        PeerConnection.RTCConfiguration(
            listOf(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer())
        ).apply { sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN }
    }

    fun hasSession(sid: String) = sessions.containsKey(sid)
    fun activeSenders() = sessions.values.count { it.isSender && !it.done }

    // ---- sender (controller) -------------------------------------------------------------

    /** Controller side: open a connection to the phone behind [sid] and push the file. */
    fun startSending(sid: String, ext: String, size: Long, open: () -> InputStream?) {
        if (sessions.containsKey(sid)) return
        val s = newSession(sid, true) ?: return
        val dc = s.pc.createDataChannel("song", DataChannel.Init().apply { ordered = true })
        s.channel = dc
        dc.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(p: Long) {}
            override fun onMessage(b: DataChannel.Buffer) {}
            override fun onStateChange() {
                if (dc.state() == DataChannel.State.OPEN && s.job == null) {
                    s.job = scope.launch(Dispatchers.IO) { pump(s, dc, ext, size, open) }
                }
            }
        })
        s.pc.createOffer(object : SimpleSdp() {
            override fun onCreateSuccess(d: SessionDescription) {
                s.pc.setLocalDescription(SimpleSdp(), d)
                emit(sid, "offer", sdpJson(d))
            }
            override fun onCreateFailure(e: String?) = fail(s, "offer: $e")
        }, MediaConstraints())
        timeout(s, 180_000)
    }

    private suspend fun pump(s: Session, dc: DataChannel, ext: String, size: Long, open: () -> InputStream?) {
        try {
            val input = open() ?: throw IllegalStateException("cannot read file")
            dc.send(text(JSONObject().put("size", size).put("ext", ext).toString()))
            input.use {
                val buf = ByteArray(CHUNK)
                var sent = 0L
                var lastPct = -1
                while (true) {
                    val n = it.read(buf)
                    if (n <= 0) break
                    while (dc.bufferedAmount() > MAX_BUFFERED) delay(15)
                    if (s.done) return
                    dc.send(DataChannel.Buffer(ByteBuffer.wrap(buf.copyOf(n)), true))
                    sent += n
                    val pct = if (size > 0) (sent * 100 / size).toInt() else 0
                    if (pct != lastPct) { lastPct = pct; listener?.onProgress(s.sid, pct) }
                }
            }
            dc.send(text("EOF"))
            while (dc.bufferedAmount() > 0 && !s.done) delay(50)
            delay(500) // let the last bytes leave before tearing down
            close(s.sid)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fail(s, e.message ?: "send failed")
        }
    }

    // ---- receiver (phone without the song) -----------------------------------------------

    /** Receiver side: the controller's offer arrived, answer it and save the incoming file. */
    fun acceptOffer(sid: String, offerJson: String, targetFile: (ext: String) -> File) {
        if (sessions.containsKey(sid)) return
        val s = newSession(sid, false) ?: return
        timeout(s, 180_000)
        val o = JSONObject(offerJson)
        s.pc.setRemoteDescription(object : SimpleSdp() {
            override fun onSetSuccess() {
                markRemoteSet(s)
                s.pc.createAnswer(object : SimpleSdp() {
                    override fun onCreateSuccess(d: SessionDescription) {
                        s.pc.setLocalDescription(SimpleSdp(), d)
                        emit(sid, "answer", sdpJson(d))
                    }
                    override fun onCreateFailure(e: String?) = fail(s, "answer: $e")
                }, MediaConstraints())
            }
            override fun onSetFailure(e: String?) = fail(s, "offer rejected: $e")
        }, SessionDescription(SessionDescription.Type.OFFER, o.optString("sdp")))
        this.targetFile = targetFile
    }

    @Volatile private var targetFile: ((String) -> File)? = null

    private fun attachReceiver(s: Session, dc: DataChannel) {
        s.channel = dc
        dc.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(p: Long) {}
            override fun onStateChange() {}
            override fun onMessage(b: DataChannel.Buffer) {
                try {
                    val bytes = ByteArray(b.data.remaining()).also { b.data.get(it) }
                    if (!b.binary) {
                        val t = String(bytes)
                        if (t == "EOF") {
                            s.out?.close()
                            val f = s.outFile
                            s.done = true
                            if (f != null && (s.expected <= 0 || f.length() == s.expected)) {
                                listener?.onReceived(s.sid, f)
                            } else {
                                f?.delete()
                                listener?.onFailed(s.sid, "incomplete file")
                            }
                            close(s.sid)
                        } else {
                            val h = JSONObject(t)
                            s.expected = h.optLong("size")
                            s.ext = h.optString("ext", "mp3")
                            val f = (targetFile ?: return).invoke(s.ext)
                            f.parentFile?.mkdirs()
                            s.outFile = f
                            s.out = FileOutputStream(f)
                        }
                    } else {
                        s.out?.write(bytes)
                        s.got += bytes.size
                        if (s.expected > 0) listener?.onProgress(s.sid, (s.got * 100 / s.expected).toInt())
                    }
                } catch (e: Exception) {
                    fail(s, e.message ?: "receive failed")
                }
            }
        })
    }

    // ---- signaling input -----------------------------------------------------------------

    /** Controller: the receiver's answer arrived. */
    fun onAnswer(sid: String, answerJson: String) {
        val s = sessions[sid] ?: return
        if (s.remoteSet) return
        s.pc.setRemoteDescription(object : SimpleSdp() {
            override fun onSetSuccess() = markRemoteSet(s)
            override fun onSetFailure(e: String?) = fail(s, "answer rejected: $e")
        }, SessionDescription(SessionDescription.Type.ANSWER, JSONObject(answerJson).optString("sdp")))
    }

    /** Both sides: a remote ICE candidate arrived ([key] de-duplicates re-reads of the same one). */
    fun onRemoteIce(sid: String, key: String, json: String) {
        val s = sessions[sid] ?: return
        if (!s.appliedIce.add(key)) return
        val o = JSONObject(json)
        val c = IceCandidate(o.optString("mid"), o.optInt("idx"), o.optString("c"))
        synchronized(s) {
            if (s.remoteSet) s.pc.addIceCandidate(c) else s.pendingIce += c
        }
    }

    private fun markRemoteSet(s: Session) {
        synchronized(s) {
            s.remoteSet = true
            s.pendingIce.forEach { s.pc.addIceCandidate(it) }
            s.pendingIce.clear()
        }
    }

    // ---- plumbing ------------------------------------------------------------------------

    private fun newSession(sid: String, sender: Boolean): Session? {
        lateinit var holder: Session
        val pc = factory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(c: IceCandidate) {
                emit(sid, "ice", JSONObject().put("mid", c.sdpMid).put("idx", c.sdpMLineIndex).put("c", c.sdp).toString())
            }
            override fun onDataChannel(dc: DataChannel) { if (!sender) attachReceiver(holder, dc) }
            override fun onConnectionChange(st: PeerConnection.PeerConnectionState) {
                if (st == PeerConnection.PeerConnectionState.FAILED && !holder.done) fail(holder, "connection failed")
            }
            override fun onSignalingChange(p: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(p: PeerConnection.IceConnectionState?) {}
            override fun onIceConnectionReceivingChange(p: Boolean) {}
            override fun onIceGatheringChange(p: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidatesRemoved(p: Array<out IceCandidate>?) {}
            override fun onAddStream(p: org.webrtc.MediaStream?) {}
            override fun onRemoveStream(p: org.webrtc.MediaStream?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(r: org.webrtc.RtpReceiver?, m: Array<out org.webrtc.MediaStream>?) {}
        }) ?: return null
        holder = Session(sid, pc, sender)
        sessions[sid] = holder
        return holder
    }

    private fun emit(sid: String, kind: String, json: String) {
        scope.launch(Dispatchers.IO) {
            try { sendSignal(sid, kind, json) } catch (e: CancellationException) { throw e } catch (_: Exception) { }
        }
    }

    private fun timeout(s: Session, ms: Long) {
        scope.launch {
            delay(ms)
            if (!s.done && sessions[s.sid] === s) fail(s, "timed out")
        }
    }

    private fun fail(s: Session, why: String) {
        if (s.done) return
        s.done = true
        s.outFile?.delete()
        listener?.onFailed(s.sid, why)
        close(s.sid)
    }

    fun close(sid: String) {
        val s = sessions.remove(sid) ?: return
        s.done = true
        try { s.job?.cancel(); s.out?.close(); s.channel?.close(); s.pc.close() } catch (_: Exception) { }
    }

    fun closeAll() = sessions.keys.toList().forEach { close(it) }

    private fun text(t: String) = DataChannel.Buffer(ByteBuffer.wrap(t.toByteArray()), false)
    private fun sdpJson(d: SessionDescription) = JSONObject().put("sdp", d.description).toString()

    private open class SimpleSdp : SdpObserver {
        override fun onCreateSuccess(d: SessionDescription) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(e: String?) {}
        override fun onSetFailure(e: String?) {}
    }

    private companion object {
        const val CHUNK = 16 * 1024
        const val MAX_BUFFERED = 1L * 1024 * 1024
    }
}
