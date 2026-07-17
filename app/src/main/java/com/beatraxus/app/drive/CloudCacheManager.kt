package com.beatraxus.app.drive

import android.content.Context
import android.media.MediaDataSource
import android.util.Log
import com.beatraxus.app.model.Song
import com.beatraxus.app.model.SongSource
import com.beatraxus.app.repository.DriveAccountRepository
import com.beatraxus.app.telegram.AuthState
import com.beatraxus.app.telegram.TdLibManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import org.drinkless.tdlib.TdApi
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class CloudCacheManager(
    private val context: Context,
    private val driveAccountRepository: DriveAccountRepository,
    private val dropboxAccountRepository: com.beatraxus.app.repository.DropboxAccountRepository,
    private val onedriveAccountRepository: com.beatraxus.app.repository.OneDriveAccountRepository,
    private val boxAccountRepository: com.beatraxus.app.repository.BoxAccountRepository,
    private val nextcloudAccountRepository: com.beatraxus.app.repository.NextcloudAccountRepository,
    private val smbConnectionRepository: com.beatraxus.app.repository.SmbConnectionRepository,
    private val ftpConnectionRepository: com.beatraxus.app.repository.FtpConnectionRepository,
    private val smbFolderBrowser: com.beatraxus.app.network.SmbFolderBrowser,
    private val ftpFolderBrowser: com.beatraxus.app.network.FtpFolderBrowser
) {
    private val TAG = "CloudCacheManager"
    private val cacheDir = File(context.cacheDir, "cloud_cache").apply { mkdirs() }
    private val downloadScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val playbackLruCache = PlaybackLruCache.getInstance(context)
    
    // Use ConcurrentHashMap for thread-safe access from media threads
    private val activeDownloads = ConcurrentHashMap<String, Job>()
    private val mutex = Mutex()
    private var currentlyPlayingId: String? = null
    
    fun setCurrentlyPlayingId(id: String?) {
        currentlyPlayingId = id
    }

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .connectionPool(okhttp3.ConnectionPool(50, 5, TimeUnit.MINUTES))
        .dispatcher(okhttp3.Dispatcher().apply { 
            maxRequests = 100
            maxRequestsPerHost = 50 
        })
        .build()

    fun getCachedFile(song: Song): File? {
        if (!song.isCloud()) return null
        return playbackLruCache.getCachedFile(song)
    }

    fun getDataSource(
        song: Song,
        tdLib: TdLibManager,
        isSeekPending: () -> Boolean = { false }
    ): MediaDataSource? {
        if (song.source == SongSource.TELEGRAM) {
            return TelegramFileDataSource(song, tdLib)
        }
        if (song.source == SongSource.SMB || song.source == SongSource.FTP) {
            return NetworkFolderDataSource(song, isSeekPending)
        }
        if (!song.isCloud()) return null
        return StreamingCacheDataSource(song, isSeekPending)
    }

    /**
     * For Telegram songs, tries to get the local path from TDLib, refreshing fileId if needed.
     * This is useful for decoders like FFmpeg that need a real file path.
     */
    suspend fun getTelegramFilePath(song: Song, tdLib: TdLibManager): String? = withContext(Dispatchers.IO) {
        if (song.source != SongSource.TELEGRAM) return@withContext null

        tdLib.ensureClientStarted()
        if (!tdLib.awaitTdlibReady(20000)) {
            Log.w(TAG, "Telegram file path failed for ${song.title}: TDLib client is not active after timeout")
            return@withContext null
        }

        var currentFileId = song.telegramFileId
        if (currentFileId == null || currentFileId == 0) {
            currentFileId = refreshFileId(song, tdLib)
        }
        
        if (currentFileId == null || currentFileId == 0) {
            Log.e(TAG, "No fileId for Telegram song: ${song.title}")
            return@withContext null
        }

        // Trigger/check download
        var file = try {
            tdLib.send(TdApi.DownloadFile(currentFileId, 32, 0, 0, false))
        } catch (e: Exception) {
            Log.w(TAG, "DownloadFile failed for ${song.title}, refreshing ID: ${e.message}")
            currentFileId = refreshFileId(song, tdLib) ?: return@withContext null
            try { 
                tdLib.send(TdApi.DownloadFile(currentFileId, 32, 0, 0, false)) 
            } catch (e2: Exception) { 
                Log.e(TAG, "DownloadFile failed again after refresh: ${e2.message}")
                null 
            }
        }

        if (file?.local?.path?.isNotBlank() == true) {
            if (file.local.isDownloadingCompleted) {
                val f = File(file.local.path)
                if (f.exists()) {
                    val cachedFile = playbackLruCache.getOrCacheFile(song, f, true, currentlyPlayingId)
                    // Once safely in cloud_cache, remove from TDLib to free up space and ensure 15-song limit
                    try { tdLib.send(TdApi.DeleteFile(currentFileId)) } catch (_: Exception) {}
                    return@withContext cachedFile.absolutePath
                } else {
                    Log.w(TAG, "Telegram file reported as completed but missing from disk: ${file.local.path}. Re-downloading...")
                    try { tdLib.send(TdApi.DeleteFile(currentFileId)) } catch (_: Exception) {}
                    tdLib.send(TdApi.DownloadFile(currentFileId, 32, 0, 0, false))
                }
            }
            // M4A/MP4 files from Telegram (using FFmpeg mov demuxer) often have the 'moov' atom at the end,
            // causing FFmpeg to fail if opened as a partial local file. For these, we wait for the download to complete.
            val format = song.format.lowercase()
            val isMov = format == "m4a" || format == "mp4" || format.contains("alac") || song.title.contains("alac", ignoreCase = true)
            if (!isMov && File(file.local.path).exists()) return@withContext file.local.path
        }

        // Wait for path to become available
        Log.d(TAG, "Waiting for Telegram file path for ${song.title} (ID: $currentFileId)...")
        var attempts = 0
        while (attempts < 2400) { // 120 seconds (increased from 30s to allow for full download of lossless songs)
            file = try { tdLib.send(TdApi.GetFile(currentFileId)) } catch (e: Exception) { null }
            if (file?.local?.path?.isNotBlank() == true) {
                val path = file.local.path
                if (file.local.isDownloadingCompleted) {
                    val f = File(path)
                    if (f.exists()) {
                        Log.d(TAG, "Telegram file download complete for ${song.title}")
                        val cachedFile = playbackLruCache.getOrCacheFile(song, f, true, currentlyPlayingId)
                        // Once safely in cloud_cache, remove from TDLib to free up space and ensure 15-song limit
                        try { tdLib.send(TdApi.DeleteFile(currentFileId)) } catch (_: Exception) {}
                        return@withContext cachedFile.absolutePath
                    } else {
                        Log.w(TAG, "Telegram file completed but missing from disk in wait loop: $path")
                        try { tdLib.send(TdApi.DeleteFile(currentFileId)) } catch (_: Exception) {}
                        tdLib.send(TdApi.DownloadFile(currentFileId, 32, 0, 0, false))
                    }
                }
                val format = song.format.lowercase()
                val isMov = format == "m4a" || format == "mp4" || format.contains("alac") || song.title.contains("alac", ignoreCase = true)
                if (!isMov && File(path).exists()) {
                    Log.d(TAG, "Telegram file path available for ${song.title}: $path")
                    return@withContext path
                }
            }
            if (attempts % 20 == 0 && file != null) {
                Log.d(TAG, "Waiting for Telegram song completion ${song.title}: ${file.local.downloadedPrefixSize} / ${song.fileSizeBytes} bytes...")
            }
            delay(50)
            attempts++
        }
        Log.e(TAG, "Timeout waiting for Telegram file path: ${song.title}")
        null
    }

    private suspend fun refreshFileId(song: Song, tdLib: TdLibManager): Int? {
        val chatId = song.telegramChatId ?: return null
        val messageId = song.telegramMessageId ?: return null
        return try {
            val msg = tdLib.getMessage(chatId, messageId)
            when (val content = msg.content) {
                is TdApi.MessageAudio -> content.audio.audio.id
                is TdApi.MessageDocument -> content.document.document.id
                else -> null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to refresh Telegram fileId for ${song.title}: ${e.message}")
            null
        }
    }

    private fun getCachedFileById(id: String): File? {
        return playbackLruCache.getCachedFileById(id)
    }

    private val tokenCache = ConcurrentHashMap<String, String>()

    suspend fun prepareCache(currentSong: Song?, upcomingSongs: List<Song>, tdLib: TdLibManager? = null) = mutex.withLock {
        // Only update currentlyPlayingId if we have a non-null currentSong,
        // otherwise we might accidentally clear it due to a race with the service's state flow.
        if (currentSong != null) {
            currentlyPlayingId = currentSong.id
        }
        
        val keepIds = mutableSetOf<String>()
        currentSong?.let { if (it.isCloud()) keepIds.add(it.id) }
        // Also keep the one we THINK is playing even if currentSong passed here is null
        currentlyPlayingId?.let { keepIds.add(it) }

        upcomingSongs.take(5).forEach { 
            if (it.isCloud()) keepIds.add(it.id) 
        }

        // Pre-fetch GDrive tokens
        val emails = (listOfNotNull(currentSong) + upcomingSongs.take(5))
            .filter { it.source == SongSource.GDRIVE }
            .mapNotNull { it.driveAccountEmail }
            .distinct()
        
        emails.forEach { email ->
            downloadScope.launch {
                val token = driveAccountRepository.getAccessToken(email)
                if (token != null) tokenCache[email] = token
            }
        }

        // 1. Cancel downloads for songs no longer in 'keepIds'
        val toCancel = activeDownloads.keys - keepIds
        if (toCancel.isNotEmpty()) {
            Log.d(TAG, "Cancelling ${toCancel.size} downloads not in keepIds: $toCancel (currentlyPlaying=$currentlyPlayingId)")
        }
        toCancel.forEach { id ->
            activeDownloads[id]?.cancel()
            activeDownloads.remove(id)
        }

        // 2. Start downloads for 'keepIds' if not already cached
        // Prioritize current song
        currentSong?.let { song ->
            val cached = getCachedFile(song)
            if (cached != null) {
                // If already in cache, just update its LRU recency
                playbackLruCache.getOrCacheFile(song, cached, true, currentlyPlayingId)
            } else if (song.isCloud() && !activeDownloads.containsKey(song.id)) {
                if (song.source == SongSource.TELEGRAM && tdLib != null) {
                    startTelegramPreDownload(song, tdLib)
                } else {
                    startDownload(song)
                }
            }
        }

        // Then upcoming songs
        upcomingSongs.take(5).forEach { song ->
            val cached = getCachedFile(song)
            if (cached != null) {
                // For pre-fetch, we don't necessarily want to bump recency, 
                // but we should ensure it's tracked in lruMap
                playbackLruCache.getOrCacheFile(song, cached, false, currentlyPlayingId)
            } else if (song.isCloud() && !activeDownloads.containsKey(song.id)) {
                if (song.source == SongSource.TELEGRAM && tdLib != null) {
                    startTelegramPreDownload(song, tdLib)
                } else {
                    startDownload(song)
                }
            }
        }
    }

    private fun startTelegramPreDownload(song: Song, tdLib: TdLibManager) {
        val id = song.id
        activeDownloads[id] = downloadScope.launch {
            try {
                tdLib.ensureClientStarted()
                if (!tdLib.awaitTdlibReady()) {
                    Log.w(TAG, "Telegram pre-download failed for ${song.title}: TDLib client is not active after timeout")
                    return@launch
                }

                var currentFileId = song.telegramFileId
                if (currentFileId == null || currentFileId == 0) {
                    currentFileId = refreshFileId(song, tdLib)
                }
                if (currentFileId != null && currentFileId != 0) {
                    tdLib.send(TdApi.DownloadFile(currentFileId, 32, 0, 0, false))
                    
                    // Wait for completion to add to unified 15-song LRU cache (same logic as GDrive)
                    tdLib.getFileFlow(currentFileId).collect { file ->
                        if (file?.local?.isDownloadingCompleted == true && file.local.path.isNotBlank()) {
                            val path = file.local.path
                            if (File(path).exists()) {
                                playbackLruCache.getOrCacheFile(song, File(path), false, currentlyPlayingId)
                                // Move to unified cloud_cache and clear TDLib internal copy
                                try { tdLib.send(TdApi.DeleteFile(currentFileId)) } catch (_: Exception) {}
                                this@launch.cancel()
                            } else {
                                Log.w(TAG, "Pre-download: file reported complete but missing: $path")
                                try { tdLib.send(TdApi.DeleteFile(currentFileId)) } catch (_: Exception) {}
                                tdLib.send(TdApi.DownloadFile(currentFileId, 32, 0, 0, false))
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    Log.w(TAG, "Telegram pre-download failed for ${song.title}: ${e.message}")
                }
            } finally {
                activeDownloads.remove(id)
            }
        }
    }

    fun clearAllPlaybackCaches(excludeId: String? = null) {
        playbackLruCache.clearCache(excludeId)
        // Also clear any lingering .tmp files from cloud_cache/
        cacheDir.listFiles { _, name -> 
            name.endsWith(".tmp") && (excludeId == null || !name.startsWith("$excludeId."))
        }?.forEach { it.delete() }
    }

    fun clearFullCache(excludeId: String? = null) {
        playbackLruCache.clearCache(excludeId)
        
        // Also clear any lingering .tmp files from cloud_cache/
        cacheDir.listFiles { _, name -> 
            name.endsWith(".tmp") && (excludeId == null || !name.startsWith("$excludeId."))
        }?.forEach { it.delete() }

        // Optimized TDLib cache clearing
        if (excludeId == null) {
            val tdlibFilesDir = File(context.cacheDir, "tdlib/files")
            if (tdlibFilesDir.exists()) {
                val trash = File(context.cacheDir, "tdlib_trash_${System.currentTimeMillis()}")
                if (tdlibFilesDir.renameTo(trash)) {
                    trash.deleteRecursively()
                } else {
                    tdlibFilesDir.deleteRecursively()
                }
                tdlibFilesDir.mkdirs()
            }
        }
    }

    fun release() {
        downloadScope.cancel()
        activeDownloads.values.forEach { it.cancel() }
        activeDownloads.clear()
        tokenCache.clear()
    }

    private fun startDownload(song: Song) {
        val id = song.id
        activeDownloads[id] = downloadScope.launch {
            try {
                downloadSong(song)
            } finally {
                // Ensure job is always removed, even on error or cancellation
                activeDownloads.remove(id)
            }
        }
    }

    private suspend fun downloadSong(song: Song) {
        if (!song.isCloud() && song.source != SongSource.SMB && song.source != SongSource.FTP) return
        
        val tempFile = File(cacheDir, "${song.id}.tmp")
        val finalFile = File(cacheDir, "${song.id}.cache")

        try {
            if (song.source == SongSource.SMB) {
                downloadSmb(song, tempFile)
            } else if (song.source == SongSource.FTP) {
                downloadFtp(song, tempFile)
            } else {
                val url = resolveDownloadUrl(song) ?: return
                
                val totalSize = if (song.fileSizeBytes > 0) song.fileSizeBytes else {
                    // Fetch size if unknown
                    val request = Request.Builder().url(url).head().build()
                    okHttpClient.newCall(request).execute().use { it.header("Content-Length")?.toLong() ?: 0L }
                }

                if (totalSize <= 0) {
                    downloadSequential(url, song, tempFile)
                } else if (totalSize < 1024 * 1024 * 2) { // Less than 2MB, just do sequential
                    downloadSequential(url, song, tempFile)
                } else {
                    downloadParallel(url, song, tempFile, totalSize)
                }
            }

            if (tempFile.exists() && tempFile.length() > 0) {
                tempFile.renameTo(finalFile)
                // Register with unified 15-song LRU cache (as pre-fetch)
                playbackLruCache.getOrCacheFile(song, finalFile, false, currentlyPlayingId)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error downloading song ${song.title}", e)
        } finally {
            if (tempFile.exists()) tempFile.delete()
        }
    }

    private suspend fun downloadSmb(song: Song, tempFile: File) = withContext(Dispatchers.IO) {
        val uri = song.uri
        val host = uri.host ?: return@withContext
        val shareName = uri.pathSegments.firstOrNull() ?: return@withContext
        val filePath = uri.pathSegments.drop(1).joinToString("/")
        
        val connections = smbConnectionRepository.connections.first()
        val server = connections.find { it.host == host && it.shareName == shareName } ?: return@withContext
        
        if (smbFolderBrowser.connect(server)) {
            smbFolderBrowser.openStream(filePath)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            }
        }
    }

    private suspend fun downloadFtp(song: Song, tempFile: File) = withContext(Dispatchers.IO) {
        val uri = song.uri
        val host = uri.host ?: return@withContext
        val filePath = uri.path?.removePrefix("/") ?: return@withContext
        val protocol = when (uri.scheme) {
            "sftp" -> com.beatraxus.app.repository.FtpProtocol.SFTP
            "ftps" -> com.beatraxus.app.repository.FtpProtocol.FTPS
            else -> com.beatraxus.app.repository.FtpProtocol.FTP
        }
        
        val connections = ftpConnectionRepository.connections.first()
        val server = connections.find { it.host == host && it.protocol == protocol } ?: return@withContext
        
        if (ftpFolderBrowser.connect(server)) {
            ftpFolderBrowser.openStream(filePath)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
                if (protocol != com.beatraxus.app.repository.FtpProtocol.SFTP) {
                    ftpFolderBrowser.completePendingCommand()
                }
            }
        }
    }

    private suspend fun downloadSequential(url: String, song: Song, tempFile: File) {
        val requestBuilder = Request.Builder().url(url)
        val headers = getCloudHeaders(song)
        headers.forEach { (k, v) -> requestBuilder.header(k, v) }

        withContext(Dispatchers.IO) {
            okHttpClient.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) return@use
                val body = response.body ?: return@use
                body.byteStream().use { input ->
                    FileOutputStream(tempFile).use { output ->
                        val buffer = ByteArray(65536)
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                        }
                    }
                }
            }
        }
    }

    private suspend fun downloadParallel(url: String, song: Song, tempFile: File, totalSize: Long) = coroutineScope {
        val chunkSize = 2 * 1024 * 1024L // 2MB chunks
        val chunks = (totalSize + chunkSize - 1) / chunkSize
        val headers = getCloudHeaders(song)

        RandomAccessFile(tempFile, "rw").use { raf ->
            raf.setLength(totalSize)
            
            val deferreds = (0 until chunks).map { i ->
                async(Dispatchers.IO) {
                    val start = i * chunkSize
                    val end = minOf(start + chunkSize - 1, totalSize - 1)
                    
                    var success = false
                    var attempts = 0
                    while (!success && attempts < 3) {
                        try {
                            val requestBuilder = Request.Builder()
                                .url(url)
                                .header("Range", "bytes=$start-$end")
                            
                            headers.forEach { (k, v) -> requestBuilder.header(k, v) }

                            okHttpClient.newCall(requestBuilder.build()).execute().use { response ->
                                if (response.isSuccessful) {
                                    val body = response.body ?: return@use
                                    val bytes = body.bytes()
                                    synchronized(raf) {
                                        raf.seek(start)
                                        raf.write(bytes)
                                    }
                                    success = true
                                }
                            }
                        } catch (e: Exception) {
                            attempts++
                            delay(500L * attempts)
                        }
                    }
                    success
                }
            }
            
            val results = deferreds.awaitAll()
            if (results.any { !it }) {
                throw Exception("Some chunks failed to download")
            }
        }
    }

    suspend fun getCloudHeaders(song: Song): Map<String, String> {
        val headers = mutableMapOf<String, String>()
        headers["User-Agent"] = "Beatraxus/3.0"
        
        when (song.source) {
            SongSource.GDRIVE -> {
                val email = song.driveAccountEmail ?: return headers
                val token = tokenCache[email] ?: driveAccountRepository.getAccessToken(email)
                if (token != null) {
                    headers["Authorization"] = "Bearer $token"
                    tokenCache[email] = token
                }
            }
            SongSource.DROPBOX -> {
                val email = song.dropboxAccountEmail ?: return headers
                val token = dropboxAccountRepository.getAccessToken(email)
                if (token != null) {
                    headers["Authorization"] = "Bearer $token"
                }
            }
            SongSource.ONEDRIVE -> {
                val email = song.onedriveAccountEmail ?: return headers
                val token = onedriveAccountRepository.getAccessToken(email)
                if (token != null) {
                    headers["Authorization"] = "Bearer $token"
                }
            }
            SongSource.BOX -> {
                val email = song.boxAccountEmail ?: return headers
                val token = boxAccountRepository.getAccessToken(email)
                if (token != null) {
                    headers["Authorization"] = "Bearer $token"
                }
            }
            SongSource.NEXTCLOUD -> {
                val email = song.nextcloudAccountEmail ?: return headers
                val accounts = nextcloudAccountRepository.accounts.first()
                val account = accounts.find { it.username == email }
                if (account != null) {
                    val auth = android.util.Base64.encodeToString(
                        "${account.username}:${account.appPassword}".toByteArray(),
                        android.util.Base64.NO_WRAP
                    )
                    headers["Authorization"] = "Basic $auth"
                }
            }
            SongSource.SMB, SongSource.FTP -> {
                // TODO: Add auth headers for SMB/FTP if needed
            }
            else -> {}
        }
        return headers
    }

    private fun resolveDownloadUrl(song: Song): String? {
        return when (song.source) {
            SongSource.GDRIVE -> {
                if (song.driveFileId == null || song.driveAccountEmail == null) null
                else "https://www.googleapis.com/drive/v3/files/${song.driveFileId}?alt=media"
            }
            SongSource.DROPBOX -> {
                // For Dropbox, we usually need to call the SDK or use a special URL
                // Let's use the one from the scanner if it's there
                song.uri.toString()
            }
            SongSource.ONEDRIVE -> "https://graph.microsoft.com/v1.0/me/drive/items/${song.onedriveFileId}/content"
            SongSource.BOX -> "https://api.box.com/2.0/files/${song.boxFileId}/content"
            SongSource.NEXTCLOUD, SongSource.SMB, SongSource.FTP -> song.uri.toString()
            else -> song.uri.toString()
        }
    }

    private inner class StreamingCacheDataSource(
        private val song: Song,
        private val isSeekPending: () -> Boolean
    ) : MediaDataSource() {
        private var raf: RandomAccessFile? = null
        private var size: Long = -1L
        private var currentRafPath: String? = null
        private val lock = ReentrantLock()
        private var headers: Map<String, String>? = null

        private suspend fun getOrFetchHeaders(): Map<String, String> {
            if (isSeekPending()) return emptyMap()
            if (headers != null) return headers!!
            headers = getCloudHeaders(song)
            return headers!!
        }

        override fun getSize(): Long = lock.withLock {
            if (isSeekPending()) return -1
            if (size != -1L) return size
            if (song.fileSizeBytes > 0) {
                size = song.fileSizeBytes
                return size
            }
            val cacheFile = getCachedFile(song)
            if (cacheFile != null) {
                size = cacheFile.length()
                return size
            }
            try {
                val url = resolveDownloadUrl(song) ?: return -1
                val requestBuilder = Request.Builder().url(url)
                val h = runBlocking { getOrFetchHeaders() }
                h.forEach { (k, v) -> requestBuilder.header(k, v) }

                okHttpClient.newCall(requestBuilder.head().build()).execute().use { response ->
                    if (response.isSuccessful) {
                        val contentLength = response.header("Content-Length")?.toLongOrNull()
                        if (contentLength != null && contentLength > 0) {
                            size = contentLength
                            return size
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to get song size", e)
            }
            return -1
        }

        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int = lock.withLock {
            if (isSeekPending()) return -1
            val totalSize = getSize()
            if (totalSize > 0 && position >= totalSize) return -1
            
            getCachedFile(song)?.let { return readFromFile(it, position, buffer, offset, size) }

            val tmpFile = File(cacheDir, "${song.id}.tmp")
            if (!tmpFile.exists() && !activeDownloads.containsKey(song.id)) {
                startDownload(song)
            }

            var attempts = 0
            // PERFORMANCE: Wait more patiently for the download to start or catch up.
            // For seeks, we also wait a bit to avoid falling back to network if the cache is close.
            val maxWaitAttempts = if (position == 0L) 100 else 50 // 2s for start, 1s for subsequent
            while (position + size > tmpFile.length() && activeDownloads.containsKey(song.id) && attempts < maxWaitAttempts) {
                if (isSeekPending()) return -1
                try {
                    lock.unlock()
                    Thread.sleep(20) // Snappier check; intentional: raw thread, not a coroutine
                    lock.lock()
                } catch (e: Exception) {
                    Log.w(TAG, "Polling loop interrupted", e)
                }
                attempts++
            }

            if (tmpFile.exists() && position < tmpFile.length()) {
                val available = (tmpFile.length() - position).toInt()
                val toRead = if (available < size) available else size
                if (toRead > 0) return readFromFile(tmpFile, position, buffer, offset, toRead)
            }

            return readFromNetwork(position, buffer, offset, size)
        }

        private fun readFromFile(file: File, position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            try {
                if (raf == null || file.absolutePath != currentRafPath) {
                    raf?.close()
                    raf = RandomAccessFile(file, "r")
                    currentRafPath = file.absolutePath
                }
                raf?.seek(position)
                return raf?.read(buffer, offset, size) ?: -1
            } catch (e: Exception) { return -1 }
        }

        private fun readFromNetwork(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            try {
                val url = resolveDownloadUrl(song) ?: return -1
                // Optimization: Request slightly more to reduce number of requests
                val fetchSize = if (size < 131072) 131072 else size 
                val endPos = if (size > 0) position + fetchSize - 1 else position + 1024*1024

                val requestBuilder = Request.Builder()
                    .url(url)
                    .header("Range", "bytes=$position-$endPos")
                
                val h = runBlocking { getOrFetchHeaders() }
                h.forEach { (k, v) -> requestBuilder.header(k, v) }

                okHttpClient.newCall(requestBuilder.build()).execute().use { response ->
                    if (!response.isSuccessful) return -1
                    val body = response.body ?: return -1
                    val inputStream = body.byteStream()
                    
                    var totalRead = 0
                    while (totalRead < size) {
                        val read = inputStream.read(buffer, offset + totalRead, size - totalRead)
                        if (read == -1) break
                        totalRead += read
                    }
                    return if (totalRead == 0) -1 else totalRead
                }
            } catch (e: Exception) { return -1 }
        }

        override fun close() = lock.withLock {
            try { raf?.close() } catch (e: Exception) {}
            raf = null
            currentRafPath = null
        }
    }

    private inner class TelegramFileDataSource(
        private val song: Song,
        private val tdLib: TdLibManager
    ) : MediaDataSource() {

        private var raf: RandomAccessFile? = null
        private var currentRafPath: String? = null
        private var localPath: String? = null
        private var downloadedPrefix: Long = 0L
        private val lock = ReentrantLock()
        private val job = Job()
        private val scope = CoroutineScope(Dispatchers.IO + job)

        init {
            scope.launch {
                // Ensure client is started before waiting
                tdLib.ensureClientStarted()

                // Wait for TDLib to be ready before starting download/flow
                if (!tdLib.awaitTdlibReady()) {
                    Log.w(TAG, "Telegram data source failed for ${song.title}: TDLib client not ready after timeout")
                    return@launch
                }
                
                var currentFileId = song.telegramFileId
                
                // If ID is missing or potentially stale, refresh it
                if (currentFileId == null || currentFileId == 0) {
                    currentFileId = refreshFileId()
                }

                if (currentFileId != null && currentFileId != 0) {
                    try {
                        tdLib.send(TdApi.DownloadFile(currentFileId, 32, 0, 0, false))
                    } catch (e: Exception) {
                        Log.w(TAG, "Stale fileId detected for ${song.title}, refreshing...")
                        currentFileId = refreshFileId()
                        if (currentFileId != null) {
                            try {
                                tdLib.send(TdApi.DownloadFile(currentFileId, 32, 0, 0, false))
                            } catch (_: Exception) {
                                Log.e(TAG, "Failed to download after refresh for ${song.title}")
                            }
                        }
                    }
                }
                
                if (currentFileId != null && currentFileId != 0) {
                    tdLib.getFileFlow(currentFileId).collect { file ->
                        if (file != null) {
                            lock.withLock {
                                // TDLib returns empty path if file is not yet available for reading
                                val path = file.local.path.takeIf { it.isNotBlank() }
                                
                                // Check if file is reported as complete but missing from disk (stale DB)
                                if (file.local.isDownloadingCompleted && path != null) {
                                    if (!File(path).exists()) {
                                        Log.w(TAG, "Telegram file flow: reported complete but missing from disk: $path. Resetting.")
                                        scope.launch {
                                            try { tdLib.send(TdApi.DeleteFile(currentFileId)) } catch (_: Exception) {}
                                            tdLib.send(TdApi.DownloadFile(currentFileId, 32, 0, 0, false))
                                        }
                                        return@collect
                                    }
                                }

                                localPath = path
                                downloadedPrefix = file.local.downloadedPrefixSize.toLong()

                                // If complete, trigger unified caching
                                if (file.local.isDownloadingCompleted && localPath != null) {
                                    scope.launch {
                                        playbackLruCache.getOrCacheFile(song, File(localPath!!), false, currentlyPlayingId)
                                        // Once in cloud_cache, clear TDLib internal copy
                                        try { tdLib.send(TdApi.DeleteFile(file.id)) } catch (_: Exception) {}
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        private suspend fun refreshFileId(): Int? {
            val chatId = song.telegramChatId ?: return null
            val messageId = song.telegramMessageId ?: return null
            return try {
                val msg = tdLib.getMessage(chatId, messageId)
                when (val content = msg.content) {
                    is TdApi.MessageAudio -> content.audio.audio.id
                    is TdApi.MessageDocument -> content.document.document.id
                    else -> null
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to refresh Telegram fileId for ${song.title}: ${e.message}")
                null
            }
        }

        override fun getSize(): Long = song.fileSizeBytes

        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int = lock.withLock {
            val totalSize = getSize()
            if (totalSize > 0 && position >= totalSize) return -1
            
            // Check unified cache first (15-song logic)
            getCachedFile(song)?.let { return readFromFile(it, position, buffer, offset, size) }

            var attempts = 0
            // Wait for TDLib to have downloaded at least up to position + size
            // AND for localPath to be available (not blank)
            // Timeout reduced to 15s to prevent engine deadlocks during source switching
            while ((localPath == null || downloadedPrefix < position + size || !File(localPath ?: "").exists()) && attempts < 750) {
                try {
                    lock.unlock()
                    Thread.sleep(20) // Snappier check; intentional: raw thread, not a coroutine
                    lock.lock()
                } catch (e: Exception) {
                    Log.w(TAG, "Polling loop interrupted", e)
                }
                attempts++
                if (localPath != null && downloadedPrefix >= position + size && File(localPath!!).exists()) break
            }

            val path = localPath ?: return -1
            if (!File(path).exists()) {
                Log.e(TAG, "Telegram file missing at path: $path")
                return -1
            }
            val res = readFromFile(File(path), position, buffer, offset, size)

            if (res != -1 && downloadedPrefix >= totalSize) {
                // If it's complete, try to cache it in the unified 15-song LRU
                val fid = song.telegramFileId
                scope.launch {
                    playbackLruCache.getOrCacheFile(song, File(path), true, currentlyPlayingId)
                    if (fid != null && fid != 0) {
                        try { tdLib.send(TdApi.DeleteFile(fid)) } catch (_: Exception) {}
                    }
                }
            }
            return res
        }

        private fun readFromFile(file: File, position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            try {
                if (raf == null || file.absolutePath != currentRafPath) {
                    raf?.close()
                    raf = RandomAccessFile(file, "r")
                    currentRafPath = file.absolutePath
                }
                raf?.seek(position)
                return raf?.read(buffer, offset, size) ?: -1
            } catch (e: Exception) { return -1 }
        }

        override fun close() = lock.withLock {
            job.cancel()
            try { raf?.close() } catch (e: Exception) {}
            raf = null
            currentRafPath = null
        }
    }

    private inner class NetworkFolderDataSource(
        private val song: Song,
        private val isSeekPending: () -> Boolean
    ) : MediaDataSource() {
        private var raf: RandomAccessFile? = null
        private var currentRafPath: String? = null
        private val lock = ReentrantLock()

        override fun getSize(): Long = song.fileSizeBytes

        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int = lock.withLock {
            if (isSeekPending()) return -1
            
            getCachedFile(song)?.let { return readFromFile(it, position, buffer, offset, size) }

            val tmpFile = File(cacheDir, "${song.id}.tmp")
            if (!tmpFile.exists() && !activeDownloads.containsKey(song.id)) {
                startDownload(song)
            }

            var attempts = 0
            val maxWaitAttempts = if (position == 0L) 100 else 50
            while (position + size > tmpFile.length() && activeDownloads.containsKey(song.id) && attempts < maxWaitAttempts) {
                if (isSeekPending()) return -1
                try {
                    lock.unlock()
                    Thread.sleep(20)
                    lock.lock()
                } catch (e: Exception) {
                    Log.w(TAG, "Polling loop interrupted", e)
                }
                attempts++
            }

            if (tmpFile.exists() && position < tmpFile.length()) {
                val available = (tmpFile.length() - position).toInt()
                val toRead = if (available < size) available else size
                if (toRead > 0) return readFromFile(tmpFile, position, buffer, offset, toRead)
            }

            return -1
        }

        private fun readFromFile(file: File, position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            try {
                if (raf == null || file.absolutePath != currentRafPath) {
                    raf?.close()
                    raf = RandomAccessFile(file, "r")
                    currentRafPath = file.absolutePath
                }
                raf?.seek(position)
                return raf?.read(buffer, offset, size) ?: -1
            } catch (e: Exception) { return -1 }
        }

        override fun close() = lock.withLock {
            try { raf?.close() } catch (e: Exception) {}
            raf = null
            currentRafPath = null
        }
    }
}

