package com.beatflowy.app.drive

import android.content.Context
import android.media.MediaDataSource
import android.util.Log
import com.beatflowy.app.model.Song
import com.beatflowy.app.model.SongSource
import com.beatflowy.app.repository.DriveAccountRepository
import com.beatflowy.app.telegram.TdLibManager
import kotlinx.coroutines.*
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
    private val driveAccountRepository: DriveAccountRepository
) {
    private val TAG = "CloudCacheManager"
    private val cacheDir = File(context.cacheDir, "cloud_cache").apply { mkdirs() }
    private val downloadScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val playbackLruCache = PlaybackLruCache(context)
    
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
        .connectionPool(okhttp3.ConnectionPool(20, 5, TimeUnit.MINUTES))
        .dispatcher(okhttp3.Dispatcher().apply { maxRequestsPerHost = 15 })
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
        if (!song.isCloud()) return null
        return StreamingCacheDataSource(song, isSeekPending)
    }

    /**
     * For Telegram songs, tries to get the local path from TDLib, refreshing fileId if needed.
     * This is useful for decoders like FFmpeg that need a real file path.
     */
    suspend fun getTelegramFilePath(song: Song, tdLib: TdLibManager): String? = withContext(Dispatchers.IO) {
        if (song.source != SongSource.TELEGRAM) return@withContext null

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
                return@withContext playbackLruCache.getOrCacheFile(song, File(file.local.path), true, currentlyPlayingId).absolutePath
            }
            return@withContext file.local.path
        }

        // Wait for path to become available
        Log.d(TAG, "Waiting for Telegram file path for ${song.title} (ID: $currentFileId)...")
        var attempts = 0
        while (attempts < 300) { // 15 seconds (reduced from 120s to prevent UI/Engine hang)
            file = try { tdLib.send(TdApi.GetFile(currentFileId)) } catch (e: Exception) { null }
            if (file?.local?.path?.isNotBlank() == true) {
                if (file.local.isDownloadingCompleted) {
                    Log.d(TAG, "Telegram file download complete for ${song.title}")
                    return@withContext playbackLruCache.getOrCacheFile(song, File(file.local.path), true, currentlyPlayingId).absolutePath
                }
                Log.d(TAG, "Telegram file path available for ${song.title}: ${file.local.path}")
                return@withContext file.local.path
            }
            if (attempts % 20 == 0 && file != null) {
                Log.d(TAG, "Waiting for Telegram song path ${song.title}: ${file.local.downloadedPrefixSize} / ${song.fileSizeBytes} bytes...")
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
            (msg.content as? TdApi.MessageAudio)?.audio?.audio?.id
        } catch (e: Exception) {
            Log.e(TAG, "Failed to refresh Telegram fileId for ${song.title}: ${e.message}")
            null
        }
    }

    private fun getCachedFileById(id: String): File? {
        return playbackLruCache.getCachedFile(Song(id = id, uri = android.net.Uri.EMPTY, title = "", artist = "", album = "", durationMs = 0, format = "", sampleRateHz = 0))
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
            if (song.isCloud() && getCachedFileById(song.id) == null && !activeDownloads.containsKey(song.id)) {
                if (song.source == SongSource.TELEGRAM && tdLib != null) {
                    startTelegramPreDownload(song, tdLib)
                } else {
                    startDownload(song)
                }
            }
        }

        // Then upcoming songs
        upcomingSongs.take(5).forEach { song ->
            if (song.isCloud() && getCachedFileById(song.id) == null && !activeDownloads.containsKey(song.id)) {
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
                var currentFileId = song.telegramFileId
                if (currentFileId == null || currentFileId == 0) {
                    currentFileId = refreshFileId(song, tdLib)
                }
                if (currentFileId != null && currentFileId != 0) {
                    tdLib.send(TdApi.DownloadFile(currentFileId, 32, 0, 0, false))
                    // We don't need to wait for completion here, TDLib handles it in background
                }
            } catch (e: Exception) {
                Log.w(TAG, "Telegram pre-download failed for ${song.title}: ${e.message}")
            }
        }
    }

    fun clearAllPlaybackCaches() {
        playbackLruCache.clearCache()
        // Also clear any lingering .tmp files from cloud_cache/
        cacheDir.listFiles { _, name -> name.endsWith(".tmp") }?.forEach { it.delete() }
    }

    fun clearFullCache() {
        clearAllPlaybackCaches()
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
        if (!song.isCloud()) return
        
        val tempFile = File(cacheDir, "${song.id}.tmp")
        val finalFile = File(cacheDir, "${song.id}.cache")

        try {
            val url = resolveDownloadUrl(song) ?: return
            val requestBuilder = Request.Builder().url(url)
            
            if (song.source == SongSource.GDRIVE && song.driveAccountEmail != null) {
                val email = song.driveAccountEmail
                val token = tokenCache[email] ?: driveAccountRepository.getAccessToken(email)
                if (token != null) {
                    requestBuilder.header("Authorization", "Bearer $token")
                    tokenCache[email] = token
                }
            }

            okHttpClient.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "Failed to download song ${song.title}: ${response.code}")
                    return
                }

                val body = response.body ?: return
                body.byteStream().use { input ->
                    FileOutputStream(tempFile).use { output ->
                        val buffer = ByteArray(65536)
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                        }
                    }
                }

                if (tempFile.exists() && tempFile.length() > 0) {
                    tempFile.renameTo(finalFile)
                    // Register with unified 15-song LRU cache (as pre-fetch)
                    playbackLruCache.getOrCacheFile(song, finalFile, false, currentlyPlayingId)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error downloading song ${song.title}", e)
        } finally {
            if (tempFile.exists()) tempFile.delete()
        }
    }

    private fun resolveDownloadUrl(song: Song): String? {
        return if (song.source == SongSource.GDRIVE) {
            if (song.driveFileId == null || song.driveAccountEmail == null) null
            else "https://www.googleapis.com/drive/v3/files/${song.driveFileId}?alt=media"
        } else {
            song.uri.toString()
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
        private var accessToken: String? = null
        private var tokenFetched = false

        private fun getOrFetchToken(): String? {
            if (isSeekPending()) return null
            if (tokenFetched) return accessToken
            val email = song.driveAccountEmail
            if (song.source == SongSource.GDRIVE && email != null) {
                accessToken = tokenCache[email] ?: runBlocking { driveAccountRepository.getAccessToken(email) }
            }
            tokenFetched = true
            return accessToken
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
                val token = getOrFetchToken()
                if (token != null) requestBuilder.header("Authorization", "Bearer $token")

                okHttpClient.newCall(requestBuilder.head().build()).execute().use { response ->
                    if (response.isSuccessful) {
                        val contentLength = response.header("Content-Length")?.toLongOrNull()
                        if (contentLength != null && contentLength > 0) {
                            size = contentLength
                            return size
                        }
                    }
                }
            } catch (e: Exception) { }
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
            // PERFORMANCE: For the very first read, wait more patiently for the download to start 
            // instead of opening a redundant concurrent connection via readFromNetwork.
            val maxWaitAttempts = if (position == 0L) 100 else 15 // 2s for start, 0.3s for subsequent
            while (position + size > tmpFile.length() && activeDownloads.containsKey(song.id) && attempts < maxWaitAttempts) {
                if (isSeekPending()) return -1
                try {
                    lock.unlock()
                    Thread.sleep(20) // Snappier check
                    lock.lock()
                } catch (e: Exception) {}
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
                
                val token = getOrFetchToken()
                if (token != null) requestBuilder.header("Authorization", "Bearer $token")

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

        private var localPath: String? = null
        private var downloadedPrefix: Long = 0L
        private val lock = ReentrantLock()
        private val job = Job()
        private val scope = CoroutineScope(Dispatchers.IO + job)

        init {
            if (tdLib.isReady()) {
                scope.launch {
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
                                    localPath = file.local.path.takeIf { it.isNotBlank() }
                                    downloadedPrefix = file.local.downloadedPrefixSize.toLong()
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
                (msg.content as? TdApi.MessageAudio)?.audio?.audio?.id
            } catch (e: Exception) {
                Log.e(TAG, "Failed to refresh Telegram fileId for ${song.title}: ${e.message}")
                null
            }
        }

        override fun getSize(): Long = song.fileSizeBytes

        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int = lock.withLock {
            val totalSize = getSize()
            if (totalSize > 0 && position >= totalSize) return -1
            
            var attempts = 0
            // Wait for TDLib to have downloaded at least up to position + size
            // AND for localPath to be available (not blank)
            // Timeout reduced to 15s to prevent engine deadlocks during source switching
            while ((localPath == null || downloadedPrefix < position + size) && attempts < 750) {
                try {
                    lock.unlock()
                    Thread.sleep(20) // Snappier check
                    lock.lock()
                } catch (e: Exception) {}
                attempts++
                if (localPath != null && downloadedPrefix >= position + size) break
            }

            val path = localPath ?: return -1
            return try {
                RandomAccessFile(path, "r").use { raf ->
                    raf.seek(position)
                    raf.read(buffer, offset, size)
                }
            } catch (e: Exception) { -1 }
        }

        override fun close() {
            job.cancel()
        }
    }
}
