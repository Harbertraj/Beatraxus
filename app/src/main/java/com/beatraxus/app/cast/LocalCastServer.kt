package com.beatraxus.app.cast

import android.content.Context
import android.net.Uri
import android.net.wifi.WifiManager
import android.util.Log
import com.beatraxus.app.model.Song
import com.beatraxus.app.model.SongSource
import com.beatraxus.app.telegram.TdLibManager
import kotlinx.coroutines.runBlocking
import org.drinkless.tdlib.TdApi
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

object LocalCastServer {
    private const val TAG = "LocalCastServer"
    private var serverSocket: ServerSocket? = null
    private val threadPool = Executors.newCachedThreadPool()
    private var isRunning = false
    private var port = 8080
    private var multicastLock: WifiManager.MulticastLock? = null

    var currentSong: Song? = null

    fun start(context: Context): String? {
        if (isRunning) return getUrl(context)
        
        try {
            serverSocket = ServerSocket(0) // Let system pick an available port
            port = serverSocket!!.localPort
            isRunning = true

            // Acquire MulticastLock (Fix E)
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wifi.createMulticastLock("beatraxus_cast").apply {
                setReferenceCounted(true)
                acquire()
            }
            
            threadPool.execute {
                while (isRunning) {
                    try {
                        val socket = serverSocket?.accept() ?: break
                        handleRequest(context, socket)
                    } catch (e: Exception) {
                        if (isRunning) Log.e(TAG, "Error accepting connection", e)
                    }
                }
            }
            
            val url = getUrl(context)
            Log.d(TAG, "Server started on $url")
            return url
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start server", e)
            return null
        }
    }

    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing server socket", e)
        }
        serverSocket = null

        multicastLock?.let {
            if (it.isHeld) it.release()
        }
        multicastLock = null
    }

    private fun getUrl(context: Context): String? {
        return try {
            val ip = getLocalIpAddress(context)
            val sid = currentSong?.id
            if (ip != null) "http://$ip:$port/stream${if (sid != null) "?sid=$sid" else ""}" else null
        } catch (e: Exception) {
            null
        }
    }

    fun getArtUrl(context: Context): String? {
        val ip = getLocalIpAddress(context) ?: return null
        return "http://$ip:$port/art"
    }

    private fun getLocalIpAddress(context: Context): String? {
        try {
            // Fix A — Correct IP selection. Replace the hotspot‑first logic with 
            // "prefer the interface that owns the device's active Wi‑Fi network"
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val activeNetwork = cm.activeNetwork
            if (activeNetwork != null) {
                val linkProperties = cm.getLinkProperties(activeNetwork)
                val ip = linkProperties?.linkAddresses
                    ?.map { it.address }
                    ?.firstOrNull { it is java.net.Inet4Address && !it.isLoopbackAddress }
                    ?.hostAddress
                if (ip != null) return ip
            }

            return fallbackScan()
        } catch (e: Exception) {
            Log.e(TAG, "Error getting local IP", e)
        }
        return fallbackScan()
    }

    private fun fallbackScan(): String? {
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces().toList()
            
            // 1. Try to find hotspot/p2p interfaces first (often used for Cast in hotspot mode)
            val priorityInterfaces = listOf("ap0", "wlan1", "softap", "p2p-wlan0-0", "p2p-wlan0-1")
            for (ifName in priorityInterfaces) {
                val iface = interfaces.find { it.name.contains(ifName, ignoreCase = true) }
                if (iface != null && iface.isUp) {
                    val addr = iface.inetAddresses.asSequence().find {
                        !it.isLoopbackAddress && it is java.net.Inet4Address
                    }
                    if (addr != null) {
                        Log.d(TAG, "Found priority interface IP: ${iface.name} -> ${addr.hostAddress}")
                        return addr.hostAddress
                    }
                }
            }

            // 2. Fallback to any non-loopback IPv4 address
            var fallbackIp: String? = null
            for (iface in interfaces) {
                if (!iface.isUp || iface.isLoopback) continue
                
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    val hostAddress = addr.hostAddress
                    if (addr is java.net.Inet4Address && hostAddress != null) {
                        if (hostAddress == "192.168.43.1") return hostAddress
                        if (fallbackIp == null) fallbackIp = hostAddress
                    }
                }
            }
            return fallbackIp
        } catch (e: Exception) {
            return null
        }
    }

    private fun handleRequest(context: Context, socket: Socket) {
        threadPool.execute {
            try {
                val input = socket.getInputStream()
                val reader = input.bufferedReader()
                val requestLine = reader.readLine()
                
                if (requestLine == null || !requestLine.startsWith("GET")) {
                    socket.close()
                    return@execute
                }

                val fullPath = requestLine.substringAfter(' ').substringBefore(' ')
                val path = fullPath.substringBefore('?')
                val query = fullPath.substringAfter('?', "")
                val output = socket.getOutputStream()

                if (path.startsWith("/art")) {
                    serveArt(context, output)
                    return@execute
                }

                // Fix D — Tag requests to avoid the skip race.
                val requestedSid = if (query.contains("sid=")) {
                    query.substringAfter("sid=").substringBefore('&')
                } else null

                val song = currentSong
                
                if (song == null) {
                    sendError(output, 404, "Not Found")
                    return@execute
                }

                if (requestedSid != null && requestedSid != song.id) {
                    Log.w(TAG, "Rejecting request for stale SID: $requestedSid (Current: ${song.id})")
                    sendError(output, 403, "Stale Request")
                    return@execute
                }

                // Parse Range header
                var rangeHeader: String? = null
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    if (line.startsWith("Range: bytes=")) {
                        rangeHeader = line.substring("Range: bytes=".length)
                    }
                }

                val fileSize = song.fileSizeBytes
                var startByte = 0L
                var endByte = if (fileSize > 0) fileSize - 1 else -1L

                if (rangeHeader != null) {
                    val parts = rangeHeader.split("-")
                    startByte = parts[0].toLongOrNull() ?: 0L
                    if (parts.size > 1 && parts[1].isNotEmpty()) {
                        endByte = parts[1].toLong()
                    }
                }

                val inputStream: InputStream? = try {
                    when (song.source) {
                        SongSource.LOCAL -> {
                            context.contentResolver.openInputStream(song.uri)?.apply {
                                if (startByte > 0) skip(startByte)
                            }
                        }
                        SongSource.GDRIVE -> {
                            if (song.driveAccountEmail != null && song.driveFileId != null) {
                                val repo = com.beatraxus.app.repository.DriveAccountRepository(context)
                                val credential = repo.getCredential(song.driveAccountEmail)
                                val driveService = com.google.api.services.drive.Drive.Builder(
                                    com.google.api.client.extensions.android.http.AndroidHttp.newCompatibleTransport(),
                                    com.google.api.client.json.gson.GsonFactory.getDefaultInstance(),
                                    credential
                                ).setApplicationName("Beatraxus").build()
                                
                                val request = driveService.files().get(song.driveFileId)
                                if (rangeHeader != null) {
                                    request.requestHeaders.range = "bytes=$startByte-${if (endByte != -1L) endByte else ""}"
                                }
                                request.executeMediaAsInputStream()
                            } else null
                        }
                        SongSource.WEB -> {
                            val connection = java.net.URL(song.uri.toString()).openConnection()
                            if (rangeHeader != null) {
                                connection.setRequestProperty("Range", "bytes=$startByte-${if (endByte != -1L) endByte else ""}")
                            }
                            connection.inputStream
                        }
                        SongSource.TELEGRAM -> {
                            val application = context.applicationContext as com.beatraxus.app.BeatraxusApplication
                            val tdLib = application.tdLibManager
                            val fileId = song.telegramFileId ?: return@execute
                            
                            runBlocking {
                                tdLib.send(TdApi.DownloadFile(fileId, 32, 0, 0, false))
                            }
                            
                            TelegramInputStream(tdLib, fileId, startByte)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error opening stream for ${song.uri}", e)
                    null
                }

                if (inputStream == null) {
                    sendError(output, 500, "Internal Server Error")
                    return@execute
                }

                val mimeType = when (song.format.uppercase()) {
                    "M4A", "ALAC" -> "audio/mp4"
                    "" -> "audio/mpeg"
                    else -> "audio/${song.format.lowercase()}"
                }
                val contentLength = if (endByte != -1L) endByte - startByte + 1 else if (fileSize > 0) fileSize - startByte else -1L

                if (rangeHeader != null) {
                    output.write("HTTP/1.1 206 Partial Content\r\n".toByteArray())
                    output.write("Content-Range: bytes $startByte-${if (endByte != -1L) endByte else (fileSize - 1)}/$fileSize\r\n".toByteArray())
                } else {
                    output.write("HTTP/1.1 200 OK\r\n".toByteArray())
                }
                
                output.write("Content-Type: $mimeType\r\n".toByteArray())
                if (contentLength > 0) {
                    output.write("Content-Length: $contentLength\r\n".toByteArray())
                }
                output.write("Accept-Ranges: bytes\r\n".toByteArray())
                output.write("Connection: close\r\n\r\n".toByteArray())

                val buffer = ByteArray(64 * 1024)
                var bytesRead: Int
                var totalSent = 0L
                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    if (contentLength > 0 && totalSent + bytesRead > contentLength) {
                        output.write(buffer, 0, (contentLength - totalSent).toInt())
                        break
                    }
                    output.write(buffer, 0, bytesRead)
                    totalSent += bytesRead
                }
                output.flush()
                inputStream.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error handling request", e)
            } finally {
                try { socket.close() } catch (e: Exception) {}
            }
        }
    }

    private fun serveArt(context: Context, output: OutputStream) {
        val song = currentSong
        val uri = song?.albumArtUri
        if (uri == null) {
            sendError(output, 404, "Not Found")
            return
        }

        try {
            val inputStream = if (uri.scheme == "file") {
                java.io.File(uri.path!!).inputStream()
            } else {
                context.contentResolver.openInputStream(uri)
            }

            if (inputStream == null) {
                sendError(output, 404, "Not Found")
                return
            }

            output.write("HTTP/1.1 200 OK\r\n".toByteArray())
            output.write("Content-Type: image/jpeg\r\n".toByteArray())
            output.write("Connection: close\r\n\r\n".toByteArray())

            val buffer = ByteArray(64 * 1024)
            var bytesRead: Int
            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                output.write(buffer, 0, bytesRead)
            }
            output.flush()
            inputStream.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error serving art", e)
            sendError(output, 500, "Internal Server Error")
        }
    }

    private fun sendError(output: OutputStream, code: Int, message: String) {
        try {
            output.write("HTTP/1.1 $code $message\r\n".toByteArray())
            output.write("Content-Type: text/plain\r\n".toByteArray())
            output.write("Connection: close\r\n\r\n".toByteArray())
            output.write(message.toByteArray())
            output.flush()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send error response: $message", e)
        }
    }

    private class TelegramInputStream(
        private val tdLib: TdLibManager,
        private val fileId: Int,
        private var position: Long
    ) : InputStream() {
        private var localPath: String? = null
        private var downloadedSize: Long = 0L

        override fun read(): Int {
            val b = ByteArray(1)
            val result = read(b, 0, 1)
            return if (result == -1) -1 else b[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            var attempts = 0
            while (attempts < 100) {
                val file = tdLib.getFileFlow(fileId).value
                localPath = file?.local?.path
                downloadedSize = file?.local?.downloadedPrefixSize?.toLong() ?: 0L

                if (localPath != null && downloadedSize >= position + len) {
                    break
                }
                
                // If the file is already fully downloaded, we don't need to wait more than what it has
                if (localPath != null && file?.local?.isDownloadingCompleted == true) {
                    break
                }

                try {
                    Thread.sleep(50) // intentional: raw thread, not a coroutine
                } catch (e: Exception) {
                    Log.w(TAG, "Wait interrupted", e)
                }
                attempts++
            }

            val path = localPath ?: return -1
            val available = (downloadedSize - position).toInt()
            if (available <= 0) return -1
            
            val toRead = minOf(len, available)

            return try {
                RandomAccessFile(path, "r").use { raf ->
                    raf.seek(position)
                    val read = raf.read(b, off, toRead)
                    if (read > 0) position += read
                    read
                }
            } catch (e: Exception) {
                -1
            }
        }
    }
}
