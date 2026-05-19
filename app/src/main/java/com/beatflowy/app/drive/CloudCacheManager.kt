package com.beatflowy.app.drive

import android.content.Context
import android.media.MediaDataSource
import android.util.Log
import com.beatflowy.app.model.Song
import com.beatflowy.app.model.SongSource
import com.beatflowy.app.repository.DriveAccountRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
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
    private val activeDownloads = mutableMapOf<String, Job>()
    private val mutex = Mutex()
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    fun getCachedFile(song: Song): File? {
        if (!song.isCloud()) return null
        val file = File(cacheDir, "${song.id}.cache")
        return if (file.exists() && file.length() > 0) file else null
    }

    fun getDataSource(song: Song): MediaDataSource? {
        if (!song.isCloud()) return null
        return StreamingCacheDataSource(song)
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

        val toCancel = activeDownloads.keys - keepIds
        toCancel.forEach { id ->
            activeDownloads[id]?.cancel()
            activeDownloads.remove(id)
        }

        val files = cacheDir.listFiles() ?: emptyArray()
        files.forEach { file ->
            val name = file.name
            val id = when {
                name.endsWith(".cache") -> name.removeSuffix(".cache")
                name.endsWith(".tmp") -> name.removeSuffix(".tmp")
                else -> null
            }
            if (id != null && id !in keepIds) {
                file.delete()
            }
        }

        keepIds.forEach { id ->
            if (getCachedFileById(id) == null && !activeDownloads.containsKey(id)) {
                val song = (upcomingSongs + listOfNotNull(currentSong)).find { it.id == id }
                if (song != null) {
                    activeDownloads[id] = downloadScope.launch {
                        downloadSong(song)
                        mutex.withLock { activeDownloads.remove(id) }
                    }
                }
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
                    Log.d(TAG, "Cached song: ${song.title}")
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

    private inner class StreamingCacheDataSource(private val song: Song) : MediaDataSource() {
        private var raf: RandomAccessFile? = null
        private var size: Long = -1L
        private var currentRafPath: String? = null
        private val lock = ReentrantLock()

        override fun getSize(): Long = lock.withLock {
            if (size != -1L) return size
            
            // 1. Try song object
            if (song.fileSizeBytes > 0) {
                size = song.fileSizeBytes
                return size
            }
            
            // 2. Try cache file
            val cacheFile = getCachedFile(song)
            if (cacheFile != null) {
                size = cacheFile.length()
                return size
            }
            
            // 3. Try to get size from download in progress
            val tmpFile = File(cacheDir, "${song.id}.tmp")
            
            // 4. Try network HEAD or GET to find Content-Length
            try {
                val url = resolveDownloadUrl(song) ?: return 0
                val requestBuilder = Request.Builder().url(url)
                
                // If it's a Drive song, headers are needed
                if (song.source == SongSource.GDRIVE && song.driveAccountEmail != null) {
                    val token = runBlocking { driveAccountRepository.getAccessToken(song.driveAccountEmail) }
                    if (token != null) requestBuilder.header("Authorization", "Bearer $token")
                }

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
                Log.w(TAG, "Failed to get size via HEAD for ${song.title}, error: ${e.message}")
            }
            
            // 5. If we still don't know the size, return a huge value to keep MediaExtractor happy 
            // during initial probe, but this is risky. Let's return 0 and rely on the retry logic in Decoder.
            return 0
        }

        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int = lock.withLock {
            val totalSize = getSize()
            if (totalSize > 0 && position >= totalSize) return -1
            
            val cacheFile = getCachedFile(song)
            if (cacheFile != null) {
                return readFromFile(cacheFile, position, buffer, offset, size)
            }

            val tmpFile = File(cacheDir, "${song.id}.tmp")
            
            if (!tmpFile.exists() && !activeDownloads.containsKey(song.id)) {
                downloadScope.launch {
                    mutex.withLock {
                        if (!activeDownloads.containsKey(song.id)) {
                            activeDownloads[song.id] = downloadScope.launch { downloadSong(song) }
                        }
                    }
                }
            }

            // Wait for data to be available at this position
            // MediaExtractor needs the beginning of the file to probe tracks.
            var attempts = 0
            val waitTimeout = if (position == 0L) 150 else 600 // Wait longer for initial probe
            while (position + size > tmpFile.length() && activeDownloads.containsKey(song.id) && attempts < waitTimeout) {
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
                if (toRead > 0) {
                    return readFromFile(tmpFile, position, buffer, offset, toRead)
                }
            }

            // Fallback to direct network read if cache is lagging
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
            } catch (e: Exception) {
                return -1
            }
        }

        private fun readFromNetwork(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            try {
                val url = resolveDownloadUrl(song) ?: return -1
                val requestBuilder = Request.Builder()
                    .url(url)
                    .header("Range", "bytes=$position-${position + size - 1}")
                
                if (song.source == SongSource.GDRIVE && song.driveAccountEmail != null) {
                    val token = runBlocking { driveAccountRepository.getAccessToken(song.driveAccountEmail) }
                    if (token != null) requestBuilder.header("Authorization", "Bearer $token")
                }

                okHttpClient.newCall(requestBuilder.build()).execute().use { response ->
                    if (!response.isSuccessful) return -1
                    val body = response.body ?: return -1
                    val bytes = body.bytes()
                    val bytesToCopy = if (bytes.size < size) bytes.size else size
                    System.arraycopy(bytes, 0, buffer, offset, bytesToCopy)
                    return bytesToCopy
                }
            } catch (e: Exception) {
                return -1
            }
        }

        override fun close() = lock.withLock {
            try {
                raf?.close()
            } catch (e: Exception) {}
            raf = null
            currentRafPath = null
        }
    }
}
