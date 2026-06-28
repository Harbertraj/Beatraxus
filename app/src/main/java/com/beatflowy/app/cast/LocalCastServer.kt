package com.beatflowy.app.cast

import android.content.Context
import android.net.Uri
import android.util.Log
import com.beatflowy.app.model.Song
import com.beatflowy.app.model.SongSource
import com.beatflowy.app.telegram.TdLibManager
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

    var currentSong: Song? = null

    fun start(context: Context): String? {
        if (isRunning) return getUrl()
        
        try {
            serverSocket = ServerSocket(0) // Let system pick an available port
            port = serverSocket!!.localPort
            isRunning = true
            
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
            
            val url = getUrl()
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
    }

    private fun getUrl(): String? {
        return try {
            val ip = getLocalIpAddress()
            if (ip != null) "http://$ip:$port/stream" else null
        } catch (e: Exception) {
            null
        }
    }

    private fun getLocalIpAddress(): String? {
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

            // 2. Fallback to any non-loopback IPv4 address, preferring 192.168.43.1 (default Android hotspot)
            var fallbackIp: String? = null
            for (iface in interfaces) {
                if (!iface.isUp || iface.isLoopback) continue
                
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    val hostAddress = addr.hostAddress
                    if (addr is java.net.Inet4Address && hostAddress != null) {
                        Log.d(TAG, "Found interface: ${iface.name} -> $hostAddress")
                        if (hostAddress == "192.168.43.1") {
                            Log.d(TAG, "Detected default Android hotspot IP, using it.")
                            return hostAddress
                        }
                        if (fallbackIp == null) fallbackIp = hostAddress
                    }
                }
            }
            return fallbackIp
        } catch (e: Exception) {
            Log.e(TAG, "Error getting local IP", e)
        }
        return null
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

                // Parse Range header
                var rangeHeader: String? = null
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    if (line.startsWith("Range: bytes=")) {
                        rangeHeader = line.substring("Range: bytes=".length)
                    }
                }

                val output = socket.getOutputStream()
                val song = currentSong
                
                if (song == null) {
                    sendError(output, 404, "Not Found")
                    return@execute
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
                                val repo = com.beatflowy.app.repository.DriveAccountRepository(context)
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
                            val application = context.applicationContext as com.beatflowy.app.BeatraxusApplication
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

                val mimeType = "audio/${song.format.lowercase().ifEmpty { "mpeg" }}"
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

    private fun sendError(output: OutputStream, code: Int, message: String) {
        try {
            output.write("HTTP/1.1 $code $message\r\n".toByteArray())
            output.write("Content-Type: text/plain\r\n".toByteArray())
            output.write("Connection: close\r\n\r\n".toByteArray())
            output.write(message.toByteArray())
            output.flush()
        } catch (e: Exception) {}
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
                    Thread.sleep(50)
                } catch (e: Exception) {}
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
