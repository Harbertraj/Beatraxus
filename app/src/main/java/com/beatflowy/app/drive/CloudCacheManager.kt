package com.beatflowy.app.drive

import android.content.Context
import android.media.MediaDataSource
import android.util.Log
import com.beatflowy.app.model.Song
import com.beatflowy.app.model.SongSource
import com.beatflowy.app.repository.DriveAccountRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
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
    
    // Use ConcurrentHashMap for thread-safe access from media threads
    private val activeDownloads = ConcurrentHashMap<String, Job>()
    private val mutex = Mutex()
    private val MAX_CACHE_SIZE = 1024L * 1024L * 1024L // 1GB
    
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    fun getCachedFile(song: Song): File? {
        if (!song.isCloud()) return null
        val file = File(cacheDir, "${song.id}.cache")
        return if (file.exists() && file.length() > 0) {
            file.setLastModified(System.currentTimeMillis())
            file
        } else null
    }

    fun getDataSource(song: Song, isSeekPending: () -> Boolean = { false }): MediaDataSource? {
        if (!song.isCloud()) return null
        return StreamingCacheDataSource(song, isSeekPending)
    }

    private fun getCachedFileById(id: String): File? {
        val file = File(cacheDir, "$id.cache")
        return if (file.exists() && file.length() > 0) file else null
    }

    suspend fun prepareCache(currentSong: Song?, upcomingSongs: List<Song>) = mutex.withLock {
        val keepIds = mutableSetOf<String>()
        currentSong?.let { if (it.isCloud()) keepIds.add(it.id) }
        upcomingSongs.take(5).forEach { 
            if (it.isCloud()) keepIds.add(it.id) 
        }

        // 1. Cancel downloads for songs no longer in 'keepIds'
        val toCancel = activeDownloads.keys - keepIds
        toCancel.forEach { id ->
            activeDownloads[id]?.cancel()
            activeDownloads.remove(id)
        }

        // 2. Start downloads for 'keepIds' if not already cached
        // Prioritize current song
        currentSong?.let { song ->
            if (song.isCloud() && getCachedFileById(song.id) == null && !activeDownloads.containsKey(song.id)) {
                startDownload(song)
            }
        }

        // Then upcoming songs
        upcomingSongs.take(5).forEach { song ->
            if (song.isCloud() && getCachedFileById(song.id) == null && !activeDownloads.containsKey(song.id)) {
                startDownload(song)
            }
        }

        // 3. Cleanup old cache files if limit exceeded (LRU)
        cleanupCache(keepIds)
    }

    private fun cleanupCache(keepIds: Set<String>) {
        val files = cacheDir.listFiles() ?: return
        val cacheFiles = files.filter { it.name.endsWith(".cache") }
        var totalSize = cacheFiles.sumOf { it.length() }
        
        if (totalSize <= MAX_CACHE_SIZE) return

        // Sort by last modified (oldest first)
        val sortedFiles = cacheFiles.sortedBy { it.lastModified() }

        for (file in sortedFiles) {
            val id = file.name.removeSuffix(".cache")
            if (id in keepIds) continue
            
            val fileSize = file.length()
            if (file.delete()) {
                totalSize -= fileSize
                if (totalSize <= MAX_CACHE_SIZE * 0.7) break // Clean up until we reach 70% of max
            }
        }
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

    private fun Song.isCloud(): Boolean = source == SongSource.GDRIVE || source == SongSource.TELEGRAM || source == SongSource.WEB

    private suspend fun downloadSong(song: Song) {
        if (!song.isCloud()) return
        
        val tempFile = File(cacheDir, "${song.id}.tmp")
        val finalFile = File(cacheDir, "${song.id}.cache")

        try {
            val url = resolveDownloadUrl(song) ?: return
            val requestBuilder = Request.Builder().url(url)
            
            if (song.source == SongSource.GDRIVE && song.driveAccountEmail != null) {
                val token = driveAccountRepository.getAccessToken(song.driveAccountEmail)
                if (token != null) requestBuilder.header("Authorization", "Bearer $token")
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
            if (song.source == SongSource.GDRIVE && song.driveAccountEmail != null) {
                accessToken = runBlocking { driveAccountRepository.getAccessToken(song.driveAccountEmail) }
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
            val waitTimeout = if (position == 0L) 200 else 100 
            while (position + size > tmpFile.length() && activeDownloads.containsKey(song.id) && attempts < waitTimeout) {
                if (isSeekPending()) return -1
                try {
                    lock.unlock()
                    Thread.sleep(100)
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
}
