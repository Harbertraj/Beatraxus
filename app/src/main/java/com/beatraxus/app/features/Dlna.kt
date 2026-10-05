package com.beatraxus.app.features

import android.content.Context
import android.net.Uri
import android.net.wifi.WifiManager
import android.provider.OpenableColumns
import android.util.Xml
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import com.beatraxus.app.model.Song
import com.beatraxus.app.model.SongSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedOutputStream
import java.io.InputStream
import java.io.StringReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** A DLNA/UPnP device found on the network. A device can be a renderer (player), a server (library) or both. */
data class DlnaDevice(
    val name: String,
    val udn: String,
    val avTransportUrl: String? = null,
    val avTransportType: String? = null,
    val renderingControlUrl: String? = null,
    val renderingControlType: String? = null,
    val contentDirectoryUrl: String? = null,
    val contentDirectoryType: String? = null
) {
    val isRenderer: Boolean get() = avTransportUrl != null
    val isServer: Boolean get() = contentDirectoryUrl != null
}

data class DlnaEntry(
    val id: String,
    val title: String,
    val isContainer: Boolean,
    val artist: String = "",
    val album: String = "",
    val resUrl: String? = null,
    val mime: String = "",
    val durationMs: Long = 0L
)

data class DlnaPosition(val positionMs: Long, val durationMs: Long)

object DlnaManager {

    private val http = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()

    // ---------------------------------------------------------------- discovery

    suspend fun discover(context: Context, listenMs: Long = 3000L): List<DlnaDevice> = withContext(Dispatchers.IO) {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wifi.createMulticastLock("beatraxus_dlna").apply { setReferenceCounted(false); acquire() }
        val locations = LinkedHashSet<String>()
        try {
            DatagramSocket().use { socket ->
                socket.soTimeout = 600
                val group = InetAddress.getByName("239.255.255.250")
                val targets = listOf(
                    "urn:schemas-upnp-org:device:MediaRenderer:1",
                    "urn:schemas-upnp-org:device:MediaServer:1"
                )
                repeat(2) {
                    targets.forEach { st ->
                        val msg = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\n" +
                            "MAN: \"ssdp:discover\"\r\nMX: 2\r\nST: $st\r\n\r\n"
                        val data = msg.toByteArray()
                        socket.send(DatagramPacket(data, data.size, group, 1900))
                    }
                }
                val end = System.currentTimeMillis() + listenMs
                val buf = ByteArray(2048)
                while (System.currentTimeMillis() < end) {
                    try {
                        val packet = DatagramPacket(buf, buf.size)
                        socket.receive(packet)
                        val text = String(packet.data, 0, packet.length)
                        text.split("\r\n").firstOrNull { it.startsWith("location:", ignoreCase = true) }
                            ?.substringAfter(":")?.trim()?.let { locations.add(it) }
                    } catch (_: SocketTimeoutException) { /* keep listening */ }
                }
            }
        } catch (_: Exception) {
        } finally {
            try { lock.release() } catch (_: Exception) {}
        }
        locations.mapNotNull { fetchDescription(it) }.distinctBy { it.udn }
    }

    private fun fetchDescription(location: String): DlnaDevice? {
        return try {
            val xml = http.newCall(Request.Builder().url(location).build()).execute().use { it.body?.string() } ?: return null
            val parser = Xml.newPullParser()
            parser.setInput(StringReader(xml))
            var name = ""; var udn = ""
            var tag = ""; var inService = false
            var sType = ""; var sCtl = ""
            var avUrl: String? = null; var avType: String? = null
            var rcUrl: String? = null; var rcType: String? = null
            var cdUrl: String? = null; var cdType: String? = null
            val base = URL(location)
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        tag = parser.name
                        if (tag == "service") { inService = true; sType = ""; sCtl = "" }
                    }
                    XmlPullParser.TEXT -> {
                        val t = parser.text.trim()
                        if (t.isNotEmpty()) when {
                            inService && tag == "serviceType" -> sType = t
                            inService && tag == "controlURL" -> sCtl = t
                            !inService && tag == "friendlyName" && name.isEmpty() -> name = t
                            !inService && tag == "UDN" && udn.isEmpty() -> udn = t
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.name == "service") {
                            val full = if (sCtl.isNotEmpty()) URL(base, sCtl).toString() else null
                            when {
                                sType.contains("AVTransport") -> { avUrl = full; avType = sType }
                                sType.contains("RenderingControl") -> { rcUrl = full; rcType = sType }
                                sType.contains("ContentDirectory") -> { cdUrl = full; cdType = sType }
                            }
                            inService = false
                        }
                        tag = ""
                    }
                }
                event = parser.next()
            }
            if (udn.isEmpty()) udn = location
            if (avUrl == null && cdUrl == null) null
            else DlnaDevice(name.ifBlank { "Unknown device" }, udn, avUrl, avType, rcUrl, rcType, cdUrl, cdType)
        } catch (_: Exception) { null }
    }

    // ---------------------------------------------------------------- SOAP

    private fun xmlEscape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")

    private fun xmlUnescape(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&apos;", "'").replace("&amp;", "&")

    private fun soap(url: String, serviceType: String, action: String, args: List<Pair<String, String>>): String? {
        val body = buildString {
            append("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
            append("<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" ")
            append("s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>")
            append("<u:$action xmlns:u=\"$serviceType\">")
            args.forEach { (k, v) -> append("<$k>${xmlEscape(v)}</$k>") }
            append("</u:$action></s:Body></s:Envelope>")
        }
        val req = Request.Builder().url(url)
            .header("SOAPACTION", "\"$serviceType#$action\"")
            .post(body.toRequestBody("text/xml; charset=\"utf-8\"".toMediaType()))
            .build()
        return try {
            http.newCall(req).execute().use { if (it.isSuccessful) it.body?.string() ?: "" else null }
        } catch (_: Exception) { null }
    }

    // ---------------------------------------------------------------- renderer control

    private suspend fun av(d: DlnaDevice, action: String, args: List<Pair<String, String>>): String? =
        withContext(Dispatchers.IO) {
            val url = d.avTransportUrl ?: return@withContext null
            soap(url, d.avTransportType ?: "urn:schemas-upnp-org:service:AVTransport:1", action,
                listOf("InstanceID" to "0") + args)
        }

    suspend fun play(d: DlnaDevice) = av(d, "Play", listOf("Speed" to "1")) != null
    suspend fun pause(d: DlnaDevice) = av(d, "Pause", emptyList()) != null
    suspend fun stop(d: DlnaDevice) = av(d, "Stop", emptyList()) != null
    suspend fun seek(d: DlnaDevice, ms: Long) =
        av(d, "Seek", listOf("Unit" to "REL_TIME", "Target" to formatTime(ms))) != null

    suspend fun position(d: DlnaDevice): DlnaPosition? {
        val r = av(d, "GetPositionInfo", emptyList()) ?: return null
        val rel = Regex("<RelTime>(.*?)</RelTime>").find(r)?.groupValues?.get(1)
        val dur = Regex("<TrackDuration>(.*?)</TrackDuration>").find(r)?.groupValues?.get(1)
        return DlnaPosition(parseTime(rel), parseTime(dur))
    }

    suspend fun setVolume(d: DlnaDevice, percent: Int): Boolean = withContext(Dispatchers.IO) {
        val url = d.renderingControlUrl ?: return@withContext false
        soap(
            url, d.renderingControlType ?: "urn:schemas-upnp-org:service:RenderingControl:1", "SetVolume",
            listOf("InstanceID" to "0", "Channel" to "Master", "DesiredVolume" to percent.coerceIn(0, 100).toString())
        ) != null
    }

    /** Sends [song] to [d] and starts playing. Returns an error message, or null on success. */
    suspend fun castSong(context: Context, d: DlnaDevice, song: Song, startMs: Long): String? {
        val scheme = song.uri.scheme
        val mime = mimeFor(song.format)
        val url = when {
            scheme == "http" || scheme == "https" -> song.uri.toString()
            song.isCloud() -> return "Cloud songs can't be sent to a DLNA device. Download the song first."
            else -> LocalMediaServer.urlFor(context, song.uri, mime, song.format)
                ?: return "Not connected to Wi-Fi."
        }
        val meta = didl(song.title, song.artist, song.album, url, mime)
        if (av(d, "SetAVTransportURI", listOf("CurrentURI" to url, "CurrentURIMetaData" to meta)) == null) {
            return "The device refused the song."
        }
        if (!play(d)) return "The device could not start playback."
        if (startMs > 1500) {
            delay(800)
            seek(d, startMs)
        }
        return null
    }

    private fun didl(title: String, artist: String, album: String, url: String, mime: String): String =
        "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" " +
            "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">" +
            "<item id=\"0\" parentID=\"-1\" restricted=\"1\"><dc:title>${xmlEscape(title)}</dc:title>" +
            "<upnp:artist>${xmlEscape(artist)}</upnp:artist><upnp:album>${xmlEscape(album)}</upnp:album>" +
            "<upnp:class>object.item.audioItem.musicTrack</upnp:class>" +
            "<res protocolInfo=\"http-get:*:$mime:*\">${xmlEscape(url)}</res></item></DIDL-Lite>"

    fun mimeFor(format: String): String = when (format.lowercase().removePrefix(".")) {
        "mp3" -> "audio/mpeg"
        "flac" -> "audio/flac"
        "wav", "wave" -> "audio/wav"
        "m4a", "alac", "aac" -> "audio/mp4"
        "ogg", "oga" -> "audio/ogg"
        "opus" -> "audio/opus"
        "aiff", "aif" -> "audio/aiff"
        else -> "audio/mpeg"
    }

    private fun formatTime(ms: Long): String {
        val s = ms / 1000
        return "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
    }

    private fun parseTime(t: String?): Long {
        if (t.isNullOrBlank() || t == "NOT_IMPLEMENTED") return 0L
        val parts = t.substringBefore('.').split(":").mapNotNull { it.toLongOrNull() }
        if (parts.size != 3) return 0L
        return (parts[0] * 3600 + parts[1] * 60 + parts[2]) * 1000
    }

    // ---------------------------------------------------------------- server browsing

    suspend fun browse(server: DlnaDevice, objectId: String): List<DlnaEntry> = withContext(Dispatchers.IO) {
        val url = server.contentDirectoryUrl ?: return@withContext emptyList()
        val resp = soap(
            url, server.contentDirectoryType ?: "urn:schemas-upnp-org:service:ContentDirectory:1", "Browse",
            listOf(
                "ObjectID" to objectId, "BrowseFlag" to "BrowseDirectChildren", "Filter" to "*",
                "StartingIndex" to "0", "RequestedCount" to "500", "SortCriteria" to ""
            )
        ) ?: return@withContext emptyList()
        val result = Regex("<Result>(.*?)</Result>", RegexOption.DOT_MATCHES_ALL).find(resp)?.groupValues?.get(1)
            ?: return@withContext emptyList()
        parseDidl(xmlUnescape(result))
    }

    private fun parseDidl(didl: String): List<DlnaEntry> {
        val out = ArrayList<DlnaEntry>()
        try {
            val p = Xml.newPullParser()
            p.setInput(StringReader(didl))
            var id = ""; var isContainer = false; var inEntry = false
            var title = ""; var artist = ""; var album = ""; var cls = ""
            var resUrl: String? = null; var mime = ""; var dur = 0L
            var tag = ""; var resIsAudio = false
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                when (ev) {
                    XmlPullParser.START_TAG -> {
                        tag = p.name
                        if (tag == "container" || tag == "item") {
                            inEntry = true; isContainer = tag == "container"
                            id = p.getAttributeValue(null, "id") ?: ""
                            title = ""; artist = ""; album = ""; cls = ""; resUrl = null; mime = ""; dur = 0L; resIsAudio = false
                        } else if (tag == "res" && inEntry) {
                            val proto = p.getAttributeValue(null, "protocolInfo") ?: ""
                            val isAudio = proto.contains("audio", true)
                            if (resUrl == null || (isAudio && !resIsAudio)) {
                                resIsAudio = isAudio
                                mime = proto.split(":").getOrNull(2) ?: ""
                                dur = parseTime(p.getAttributeValue(null, "duration"))
                                resUrl = "" // filled by the TEXT event below
                            }
                        }
                    }
                    XmlPullParser.TEXT -> if (inEntry) {
                        val t = p.text
                        when (tag) {
                            "dc:title" -> title = t
                            "upnp:artist", "dc:creator" -> if (artist.isEmpty()) artist = t
                            "upnp:album" -> album = t
                            "upnp:class" -> cls = t
                            "res" -> if (resUrl != null && resUrl!!.isEmpty()) resUrl = t.trim()
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (p.name == "container" || p.name == "item") {
                            val keep = isContainer || cls.startsWith("object.item.audioItem") || mime.startsWith("audio")
                            if (keep) out.add(DlnaEntry(id, title.ifBlank { "Untitled" }, isContainer, artist, album,
                                resUrl?.ifBlank { null }, mime, dur))
                            inEntry = false
                        }
                        tag = ""
                    }
                }
                ev = p.next()
            }
        } catch (_: Exception) {}
        return out
    }

    /** Converts an audio entry from a DLNA server into a playable Song (streamed over HTTP). */
    fun entryToSong(server: DlnaDevice, e: DlnaEntry): Song? {
        val url = e.resUrl ?: return null
        val ext = url.substringAfterLast('.', "").substringBefore('?').take(5).ifBlank {
            e.mime.substringAfter('/', "mp3")
        }
        return Song(
            id = "dlna:${server.udn}:${e.id}",
            uri = Uri.parse(url),
            title = e.title,
            artist = e.artist.ifBlank { "Unknown Artist" },
            album = e.album.ifBlank { server.name },
            durationMs = e.durationMs,
            format = ext,
            sampleRateHz = 0,
            source = SongSource.WEB
        )
    }
}

/** Tiny HTTP server that lets a DLNA renderer stream a local file (with Range support). */
object LocalMediaServer {
    private var server: ServerSocket? = null
    private val files = ConcurrentHashMap<String, Pair<Uri, String>>()
    private lateinit var appContext: Context

    @Synchronized
    fun urlFor(context: Context, uri: Uri, mime: String, format: String): String? {
        appContext = context.applicationContext
        val ip = localIp() ?: return null
        if (server == null || server!!.isClosed) startServer()
        val token = UUID.randomUUID().toString().replace("-", "").take(16)
        files[token] = uri to mime
        val ext = format.lowercase().removePrefix(".").ifBlank { "mp3" }
        return "http://$ip:${server!!.localPort}/m/$token.$ext"
    }

    private fun startServer() {
        val s = ServerSocket(0)
        server = s
        thread(isDaemon = true, name = "beatraxus-dlna-http") {
            while (!s.isClosed) {
                try {
                    val client = s.accept()
                    thread(isDaemon = true) { handle(client) }
                } catch (_: Exception) { break }
            }
        }
    }

    fun stop() {
        try { server?.close() } catch (_: Exception) {}
        server = null
        files.clear()
    }

    private fun localIp(): String? = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress
    } catch (_: Exception) { null }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c == -1) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
        }
    }

    private fun handle(socket: Socket) {
        socket.use { s ->
            try {
                s.soTimeout = 30_000
                val input = s.getInputStream()
                val request = readLine(input) ?: return
                val headers = HashMap<String, String>()
                while (true) {
                    val line = readLine(input) ?: break
                    if (line.isEmpty()) break
                    val i = line.indexOf(':')
                    if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
                }
                val parts = request.split(" ")
                val method = parts.getOrNull(0) ?: return
                val path = parts.getOrNull(1) ?: return
                val token = path.removePrefix("/m/").substringBefore('.').substringBefore('?')
                val out = BufferedOutputStream(s.getOutputStream())
                val (uri, mime) = files[token] ?: run {
                    out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); out.flush(); return
                }
                val resolver = appContext.contentResolver
                var length = try { resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L } catch (_: Exception) { -1L }
                if (length <= 0) {
                    length = try {
                        resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                            if (c.moveToFirst()) c.getLong(0) else -1L
                        } ?: -1L
                    } catch (_: Exception) { -1L }
                }
                if (length <= 0) {
                    out.write("HTTP/1.1 500 Error\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); out.flush(); return
                }
                var start = 0L
                var end = length - 1
                val range = headers["range"]
                if (range != null && range.startsWith("bytes=")) {
                    val r = range.removePrefix("bytes=").split("-")
                    r.getOrNull(0)?.toLongOrNull()?.let { start = it }
                    r.getOrNull(1)?.toLongOrNull()?.let { end = it.coerceAtMost(length - 1) }
                }
                if (start >= length) {
                    out.write("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */$length\r\nContent-Length: 0\r\n\r\n".toByteArray())
                    out.flush(); return
                }
                val status = if (range != null) "206 Partial Content" else "200 OK"
                val sb = StringBuilder("HTTP/1.1 $status\r\n")
                sb.append("Content-Type: $mime\r\nAccept-Ranges: bytes\r\n")
                sb.append("Content-Length: ${end - start + 1}\r\n")
                if (range != null) sb.append("Content-Range: bytes $start-$end/$length\r\n")
                sb.append("transferMode.dlna.org: Streaming\r\n")
                sb.append("contentFeatures.dlna.org: DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000\r\n")
                sb.append("Connection: close\r\n\r\n")
                out.write(sb.toString().toByteArray())
                if (method == "GET") {
                    resolver.openInputStream(uri)?.use { stream ->
                        var skipped = 0L
                        while (skipped < start) {
                            val n = stream.skip(start - skipped)
                            if (n <= 0) break
                            skipped += n
                        }
                        var remaining = end - start + 1
                        val buf = ByteArray(64 * 1024)
                        while (remaining > 0) {
                            val n = stream.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                            if (n <= 0) break
                            out.write(buf, 0, n)
                            remaining -= n
                        }
                    }
                }
                out.flush()
            } catch (_: Exception) { /* renderers drop connections often */ }
        }
    }
}
