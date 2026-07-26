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

class CloudCacheManager private constructor(
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
    companion object {
        @Volatile
        private var INSTANCE: CloudCacheManager? = null

        fun getInstance(
            context: Context,
            driveAccountRepository: DriveAccountRepository,
            dropboxAccountRepository: com.beatraxus.app.repository.DropboxAccountRepository,
            onedriveAccountRepository: com.beatraxus.app.repository.OneDriveAccountRepository,
            boxAccountRepository: com.beatraxus.app.repository.BoxAccountRepository,
            nextcloudAccountRepository: com.beatraxus.app.repository.NextcloudAccountRepository,
            smbConnectionRepository: com.beatraxus.app.repository.SmbConnectionRepository,
            ftpConnectionRepository: com.beatraxus.app.repository.FtpConnectionRepository,
            smbFolderBrowser: com.beatraxus.app.network.SmbFolderBrowser,
            ftpFolderBrowser: com.beatraxus.app.network.FtpFolderBrowser
        ): CloudCacheManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: CloudCacheManager(
                    context,
                    driveAccountRepository,
                    dropboxAccountRepository,
                    onedriveAccountRepository,
                    boxAccountRepository,
                    nextcloudAccountRepository,
                    smbConnectionRepository,
                    ftpConnectionRepository,
                    smbFolderBrowser,
                    ftpFolderBrowser
                ).also { INSTANCE = it }
            }
        }
    }

    private val TAG = "CloudCacheManager"
    private val cacheDir = File(context.cacheDir, "cloud_cache").apply { mkdirs() }
    private val downloadScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val playbackLruCache = PlaybackLruCache.getInstance(context)
    
    // Use ConcurrentHashMap for thread-safe access from media threads
    private val activeDownloads = ConcurrentHashMap<String, Job>()
    private val activeWindows = ConcurrentHashMap<String, WindowBuffer>()
    private val activeTelegramFileIds = ConcurrentHashMap<String, Int>()
    private val mutex = Mutex()
    private var currentlyPlayingId: String? = null
    
    // Always use no-cache streaming for GDrive/Dropbox/etc.
    // Telegram uses its own 4-song cache logic below.
    private val noCacheEnabled: Boolean = true

    fun setCurrentlyPlayingId(id: String?) {
        currentlyPlayingId = id
    }

    // --- Telegram windowed-streaming tuning ---
    // Mirrors GDrive's primeWindow()/checkPrefetch() sizes. Telegram downloads are always
    // requested from offset 0 (TDLib prefix-download semantics), so "window" here means
    // "how far into the file we've asked TDLib to download", not a circular in-memory buffer.
    private val TELEGRAM_INITIAL_WINDOW_BYTES = 6L * 1024 * 1024 // Increased to 6MB for ALAC/high-res speed
    private val TELEGRAM_WINDOW_EXTEND_BYTES = 4L * 1024 * 1024  // grow-ahead margin as playback advances

    /**
     * M4A/MP4/ALAC containers often keep their 'moov' atom at the end of the file.
     * For these, we trigger a special head+tail download to allow streaming.
     * We also include WAV here because tags/headers can live at the end.
     */
    private fun needsSpecialContainerHandling(song: Song): Boolean {
        val format = song.format.lowercase()
        val isAlac = format.contains("alac") || song.title.contains("alac", ignoreCase = true)
        val isWav = format.contains("wav")
        // Only treat as special if it's actually lossless/complex, 
        // not every single M4A/MP4 (which are usually just AAC).
        return isAlac || isWav || (format == "m4a" && song.bitrate > 500000)
    }

    private fun needsFullContainerDownload(song: Song): Boolean {
        // Force full sequential download for Telegram to avoid sparse file gaps
        // which cause "30s + 30s" playback loops.
        if (song.source == SongSource.TELEGRAM) return true
        return needsSpecialContainerHandling(song)
    }

    /**
     * Represents a rolling-window cache for a single active song.
     * Uses a circular buffer (8MB) to store a contiguous region of the file.
     */
    private inner class WindowBuffer(val song: Song) {
        val buffer = ByteArray(8 * 1024 * 1024) // 8MB scratch buffer
        val BUFFER_SIZE = buffer.size
        
        // --- NEW: Head+Tail storage for no-cache mode (special containers) ---
        var headBuffer: ByteArray? = null 
        var tailBuffer: ByteArray? = null 
        var tailStartPos: Long = -1L
        // ---------------------------------------------------------------------

        var startPos: Long = -1L // Absolute file position of buffer start
        var endPos: Long = -1L   // Absolute file position of data end (exclusive)
        var isFetching = false
        var totalSize: Long = -1L
        
        val lock = ReentrantLock()
        val condition = lock.newCondition()
        var prefetchJob: Job? = null

        fun contains(pos: Long, size: Int): Boolean {
            return startPos != -1L && pos >= startPos && (pos + size) <= endPos
        }

        // Circular buffer write
        fun write(filePos: Long, src: ByteArray, length: Int) {
            val startIdx = (filePos % BUFFER_SIZE).toInt()
            val remaining = BUFFER_SIZE - startIdx
            if (length <= remaining) {
                System.arraycopy(src, 0, buffer, startIdx, length)
            } else {
                System.arraycopy(src, 0, buffer, startIdx, remaining)
                System.arraycopy(src, remaining, buffer, 0, length - remaining)
            }
        }

        // Circular buffer read
        fun read(filePos: Long, dest: ByteArray, offset: Int, length: Int) {
            val startIdx = (filePos % BUFFER_SIZE).toInt()
            val remaining = BUFFER_SIZE - startIdx
            if (length <= remaining) {
                System.arraycopy(buffer, startIdx, dest, offset, length)
            } else {
                System.arraycopy(buffer, startIdx, dest, offset, remaining)
                System.arraycopy(buffer, remaining, dest, offset + remaining, length - remaining)
            }
        }
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

        // Trigger windowed download immediately for play-start speed.
        val totalSize = song.fileSizeBytes
        val initialLimit = if (totalSize > 0 && totalSize < TELEGRAM_INITIAL_WINDOW_BYTES) 0L else TELEGRAM_INITIAL_WINDOW_BYTES
        
        var file = try {
            tdLib.send(TdApi.DownloadFile(currentFileId, 32, 0L, initialLimit, false))
        } catch (e: Exception) {
            val errorMsg = e.message ?: ""
            if (errorMsg.contains("400") || errorMsg.contains("File not found", ignoreCase = true)) {
                Log.w(TAG, "DownloadFile failed for ${song.title}: File not found in Telegram. Refreshing...")
            } else {
                Log.w(TAG, "DownloadFile failed for ${song.title}, refreshing ID: $errorMsg")
            }
            
            delay(200) // Small delay before refresh/retry to avoid race with deletion
            currentFileId = refreshFileId(song, tdLib) ?: return@withContext null
            try { 
                tdLib.send(TdApi.DownloadFile(currentFileId, 32, 0L, initialLimit, false)) 
            } catch (e2: Exception) { 
                Log.e(TAG, "DownloadFile failed again after refresh: ${e2.message}")
                null 
            }
        }

        if (file?.local?.path?.isNotBlank() == true) {
            if (file.local.isDownloadingCompleted) {
                val f = File(file.local.path)
                if (f.exists()) return@withContext file.local.path
            }
            
            // Unblock early if we have the required window for streaming.
            val prefix = file.local.downloadedPrefixSize
            val total = song.fileSizeBytes
            if (file.local.isDownloadingCompleted || prefix >= TELEGRAM_INITIAL_WINDOW_BYTES || (total > 0 && prefix >= total)) {
                if (File(file.local.path).exists()) {
                    return@withContext file.local.path
                }
            }
        }

        // Wait for path and window to become available
        Log.d(TAG, "Waiting for Telegram file path for ${song.title} (ID: $currentFileId)...")
        var attempts = 0
        while (attempts < 1200) { // Increased to 60 seconds
            file = try { tdLib.send(TdApi.GetFile(currentFileId)) } catch (e: Exception) { null }
            if (file?.local?.path?.isNotBlank() == true) {
                val path = file.local.path
                if (file.local.isDownloadingCompleted && File(path).exists()) {
                    Log.d(TAG, "Full Telegram download available for ${song.title}: $path")
                    return@withContext path
                }
                
                // Allow early unblock if we have enough data (6MB window).
                val prefix = file.local.downloadedPrefixSize
                val total = song.fileSizeBytes
                if (file.local.isDownloadingCompleted || prefix >= TELEGRAM_INITIAL_WINDOW_BYTES || (total > 0 && prefix >= total)) {
                    if (File(path).exists()) {
                        Log.d(TAG, "Telegram path available for ${song.title}: $path (Prefix: $prefix)")
                        return@withContext path
                    }
                }
            }
            delay(50)
            attempts++
        }
        Log.e(TAG, "Timeout waiting for Telegram file path: ${song.title}")
        null
    }

    private fun downloadedRangeExists(fileId: Int, offset: Long, size: Long, tdLib: TdLibManager): Boolean {
        // Return true if we are windowed streaming and the prefix is met
        return true 
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

    suspend fun prepareCache(
        currentSong: Song?, 
        upcomingSongs: List<Song>, 
        previousSongs: List<Song> = emptyList(), 
        tdLib: TdLibManager? = null
    ) = mutex.withLock {
        // Only update currentlyPlayingId if we have a non-null currentSong,
        // otherwise we might accidentally clear it due to a race with the service's state flow.
        if (currentSong != null) {
            currentlyPlayingId = currentSong.id
        }
        
        val keepIds = mutableSetOf<String>()
        currentSong?.let { if (it.isCloud()) keepIds.add(it.id) }
        currentlyPlayingId?.let { keepIds.add(it) }

        // Logic for keeping previous songs to prevent re-download on "Back"
        // We keep the immediate previous song to make navigation snappy.
        if (currentSong?.source == SongSource.TELEGRAM || currentlyPlayingId?.startsWith("tg_") == true) {
            previousSongs.lastOrNull()?.let { if (it.isCloud()) keepIds.add(it.id) }
        }

        // GDrive keeps next 5, others (Telegram) keep next 2 (total 4 with prev+curr)
        val perSourceKept = mutableMapOf<SongSource, Int>()
        upcomingSongs.forEach { song ->
            if (song.isCloud()) {
                val limit = if (song.source == SongSource.GDRIVE) 5 else 2
                val countSoFar = perSourceKept.getOrDefault(song.source, 0)
                if (countSoFar < limit) {
                    keepIds.add(song.id)
                    perSourceKept[song.source] = countSoFar + 1
                }
            }
        }

        // Pre-fetch GDrive tokens
        val gDriveUpcoming = upcomingSongs.filter { it.source == SongSource.GDRIVE }.take(5)
        val emails = (listOfNotNull(currentSong).filter { it.source == SongSource.GDRIVE } + gDriveUpcoming)
            .mapNotNull { it.driveAccountEmail }
            .distinct()
        
        emails.forEach { email ->
            downloadScope.launch {
                val token = driveAccountRepository.getAccessToken(email)
                if (token != null) tokenCache[email] = token
            }
        }

        // 1. Cancel full downloads for songs no longer in 'keepIds'
        val toCancel = activeDownloads.keys - keepIds
        if (toCancel.isNotEmpty()) {
            Log.d(TAG, "Cancelling ${toCancel.size} downloads not in keepIds: $toCancel (currentlyPlaying=$currentlyPlayingId)")
        }
        toCancel.forEach { id ->
            activeDownloads[id]?.cancel()
            activeDownloads.remove(id)
        }

        // --- Specific Telegram 4-song cache logic ---
        // Current slot + Previous slot + Next 2 slots = 4 slots total.
        val telegramKeepIds = mutableSetOf<String>()
        val prevTg = previousSongs.lastOrNull()?.takeIf { it.source == SongSource.TELEGRAM }
        val currTg = currentSong?.takeIf { it.source == SongSource.TELEGRAM }
        val nextTg = upcomingSongs.filter { it.source == SongSource.TELEGRAM }.take(2)
        
        prevTg?.let { telegramKeepIds.add(it.id) }
        currTg?.let { telegramKeepIds.add(it.id) }
        nextTg.forEach { telegramKeepIds.add(it.id) }

        // Start full downloads for these 4 Telegram slots if not already cached.
        // Once cached, TelegramFileDataSource will use the local file for instant seeking.
        if (tdLib != null) {
            (listOfNotNull(currTg, prevTg) + nextTg).forEach { song ->
                val cached = getCachedFile(song)
                if (cached != null) {
                    // Update LRU recency
                    playbackLruCache.getOrCacheFile(song, cached, song.id == currentlyPlayingId, currentlyPlayingId)
                } else if (!activeDownloads.containsKey(song.id)) {
                    Log.d(TAG, "Triggering 4-slot cache download for Telegram song: ${song.title}")
                    startTelegramFullDownload(song, tdLib)
                }
            }
        }
        
        // Aggressively reconcile Telegram cache: delete any file not in the 4 active slots
        playbackLruCache.reconcileSource(SongSource.TELEGRAM, telegramKeepIds)
        
        // --- CLEANUP: Aggressively reconcile other cloud sources ---
        // Since we now use pure "No-Cache" windowed streaming for GDrive, Dropbox, etc., 
        // any existing persistent files for these sources should be purged immediately.
        playbackLruCache.reconcileSource(SongSource.GDRIVE, emptySet())
        playbackLruCache.reconcileSource(SongSource.DROPBOX, emptySet())
        playbackLruCache.reconcileSource(SongSource.ONEDRIVE, emptySet())
        playbackLruCache.reconcileSource(SongSource.BOX, emptySet())
        playbackLruCache.reconcileSource(SongSource.NEXTCLOUD, emptySet())

        // --- NEW: Aggressive Physical Garbage Collection ---
        // This handles .tmp files, partially copied files, and abandoned downloads
        // that aren't tracked in the LRU maps.
        val masterKeepIds = mutableSetOf<String>()
        masterKeepIds.addAll(telegramKeepIds)
        currentlyPlayingId?.let { masterKeepIds.add(it) }
        
        // Also keep files for songs currently in the download queue (SMB/FTP)
        activeDownloads.keys.forEach { masterKeepIds.add(it) }

        playbackLruCache.aggressivePhysicalCleanup(masterKeepIds)

        // 2. Prune rolling window buffers no longer in keepIds
        val windowsToRemove = activeWindows.keys - keepIds
        windowsToRemove.forEach { id ->
            activeWindows[id]?.lock?.withLock {
                activeWindows[id]?.prefetchJob?.cancel()
            }
            activeWindows.remove(id)
        }

        // 3. Aggressive Telegram Storage Cleanup:
        // If a Telegram song is no longer in 'keepIds', delete its internal TDLib cache immediately.
        // This handles the "fast skip through 8 songs" scenario.
        val telegramIdsToPurge = activeTelegramFileIds.keys - keepIds
        telegramIdsToPurge.forEach { songId ->
            val fileId = activeTelegramFileIds[songId]
            if (fileId != null && fileId != 0 && tdLib != null) {
                downloadScope.launch {
                    try { tdLib.send(TdApi.DeleteFile(fileId)) } catch (_: Exception) {}
                }
            }
            activeTelegramFileIds.remove(songId)
        }

        // 4. Start windowed priming for 'keepIds'
        // Always use windowed priming for all cloud sources (except Telegram which uses full cache above).
        (listOfNotNull(currentSong) + upcomingSongs.filter { it.id in keepIds }).distinctBy { it.id }.forEach { song ->
            if (song.isCloud()) {
                if (song.source == SongSource.TELEGRAM) {
                    // Handled by 4-slot cache logic above
                } else if (song.source == SongSource.SMB || song.source == SongSource.FTP) {
                    if (!activeDownloads.containsKey(song.id)) startDownload(song)
                } else {
                    // Windowed priming for GDrive/Dropbox/etc.
                    primeWindow(song)
                }
            }
        }
    }

    /**
     * Primes the window for a song (initial 2MB fetch).
     */
    private fun primeWindow(song: Song) {
        val window = activeWindows.getOrPut(song.id) { WindowBuffer(song) }
        window.lock.withLock {
            if (window.startPos == -1L) {
                window.startPos = 0
                window.endPos = 0
                window.isFetching = true
                window.prefetchJob = downloadScope.launch {
                    if (needsFullContainerDownload(song)) {
                        // 1. Fetch Head (2MB)
                        fetchWindowRange(song, window, 0, 2 * 1024 * 1024, isHead = true)
                        
                        // 2. Fetch Tail (1MB) - fetchWindowRange will update totalSize from Content-Range
                        val total = window.totalSize
                        if (total > 0) {
                            val tailPos = (total - 1024 * 1024).coerceAtLeast(0L)
                            fetchWindowRange(song, window, tailPos, 1024 * 1024, isTail = true)
                        }
                    } else {
                        fetchWindowRange(song, window, 0, 2 * 1024 * 1024)
                    }
                }
            }
        }
    }

    /**
     * Fetches a specific range from the network into the WindowBuffer.
     */
    private suspend fun fetchWindowRange(song: Song, window: WindowBuffer, start: Long, length: Long, isHead: Boolean = false, isTail: Boolean = false) {
        try {
            val url = resolveDownloadUrl(song) ?: return
            val end = start + length - 1
            val requestBuilder = Request.Builder()
                .url(url)
                .header("Range", "bytes=$start-$end")
            
            val h = getCloudHeaders(song)
            h.forEach { (k, v) -> requestBuilder.header(k, v) }

            okHttpClient.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) return
                val body = response.body ?: return
                
                // Update totalSize from Content-Range if possible
                val contentRange = response.header("Content-Range")
                if (contentRange != null) {
                    val total = contentRange.substringAfterLast("/").toLongOrNull()
                    if (total != null && total > 0) {
                        window.totalSize = total
                    }
                }

                if (isHead) {
                    window.headBuffer = body.bytes()
                    window.lock.withLock { window.condition.signalAll() }
                    return
                }
                
                if (isTail) {
                    window.tailBuffer = body.bytes()
                    window.tailStartPos = start
                    window.lock.withLock { window.condition.signalAll() }
                    return
                }

                val input = body.byteStream()
                val tempBuffer = ByteArray(65536)
                var bytesRead = 0
                while (currentCoroutineContext().isActive && input.read(tempBuffer).also { bytesRead = it } != -1) {
                    window.lock.withLock {
                        val writePos = window.endPos
                        window.write(writePos, tempBuffer, bytesRead)
                        window.endPos += bytesRead
                        
                        // Maintain 8MB bounded size: shift startPos forward if we exceed capacity
                        if (window.endPos - window.startPos > window.BUFFER_SIZE) {
                            window.startPos = window.endPos - window.BUFFER_SIZE
                        }
                        window.condition.signalAll()
                    }
                }
            }
        } catch (e: Exception) {
            if (e !is CancellationException) {
                Log.e(TAG, "Window fetch failed for ${song.title}: ${e.message}")
            }
        } finally {
            window.lock.withLock {
                window.isFetching = false
                window.condition.signalAll()
            }
        }
    }

    private fun startTelegramFullDownload(song: Song, tdLib: TdLibManager) {
        val id = song.id
        activeDownloads[id] = downloadScope.launch {
            try {
                tdLib.ensureClientStarted()
                if (!tdLib.awaitTdlibReady()) return@launch

                var currentFileId = song.telegramFileId ?: refreshFileId(song, tdLib)
                if (currentFileId != null && currentFileId != 0) {
                    activeTelegramFileIds[song.id] = currentFileId
                    
                    // Always request FULL download for Telegram "cache streaming"
                    tdLib.send(TdApi.DownloadFile(currentFileId, 32, 0L, 0L, false))

                    // Observe progress and register with unified cache when finished
                    tdLib.getFileFlow(currentFileId).collect { file ->
                        if (file?.local?.isDownloadingCompleted == true && file.local.path.isNotBlank()) {
                            val path = file.local.path
                            val sourceFile = File(path)
                            if (sourceFile.exists()) {
                                // Register with unified LRU cache
                                playbackLruCache.getOrCacheFile(song, sourceFile, song.id == currentlyPlayingId, currentlyPlayingId)
                                // Free TDLib storage
                                try { tdLib.send(TdApi.DeleteFile(currentFileId)) } catch (_: Exception) {}
                                this@launch.cancel()
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                if (e !is CancellationException) Log.w(TAG, "Telegram full download failed: ${e.message}")
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

        // Optimized TDLib cache clearing: reach deeper into internal storage
        if (excludeId == null) {
            val tdlibDir = File(context.cacheDir, "tdlib")
            if (tdlibDir.exists()) {
                val trash = File(context.cacheDir, "tdlib_trash_${System.currentTimeMillis()}")
                if (tdlibDir.renameTo(trash)) {
                    trash.deleteRecursively()
                } else {
                    tdlibDir.deleteRecursively()
                }
            }
        }
    }

    fun release() {
        downloadScope.cancel()
        activeDownloads.values.forEach { it.cancel() }
        activeDownloads.clear()
        activeTelegramFileIds.clear()
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
                // Register with unified 5-song LRU cache (as pre-fetch)
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
        private val needsFullDownload = needsFullContainerDownload(song)

        // Get or create the shared window buffer for this song
        private val window = activeWindows.getOrPut(song.id) { WindowBuffer(song) }

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
                window.totalSize = size
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
                            window.totalSize = size
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
            
            // Always use rolling-window buffer for cloud playback
            return readFromWindow(position, buffer, offset, size)
        }

        private fun readFromWindow(position: Long, outBuffer: ByteArray, offset: Int, size: Int): Int {
            window.lock.withLock {
                // --- Head/Tail Buffers for No-Cache mode ---
                // Check Head
                window.headBuffer?.let { head ->
                    if (position < head.size) {
                        val available = (head.size - position).toInt()
                        val toRead = minOf(size, available)
                        System.arraycopy(head, position.toInt(), outBuffer, offset, toRead)
                        return toRead
                    }
                }
                // Check Tail
                val tail = window.tailBuffer
                val tailStart = window.tailStartPos
                if (tail != null && tailStart != -1L && position >= tailStart) {
                    val tailOffset = (position - tailStart).toInt()
                    if (tailOffset >= 0 && tailOffset < tail.size) {
                        val available = tail.size - tailOffset
                        val toRead = minOf(size, available)
                        System.arraycopy(tail, tailOffset, outBuffer, offset, toRead)
                        return toRead
                    }
                }
                // -----------------------------------------------------

                // If seek is outside current window or window is empty
                if (window.startPos == -1L || position < window.startPos || position >= window.endPos) {
                    // DISCARD: Discard old window and fetch fresh window starting at position
                    refreshWindow(position)
                }

                // Wait if current request exceeds available buffered data but fetch is in progress
                var attempts = 0
                // Increase timeout to 20 seconds (1000 * 20ms) to prevent auto-skipping 
                // on slow network seeks. Returning -1 too early signals EOF to MediaExtractor.
                while (position + size > window.endPos && window.isFetching && attempts < 1000) {
                    if (isSeekPending()) return -1
                    try {
                        window.condition.await(20, TimeUnit.MILLISECONDS)
                    } catch (e: InterruptedException) {
                        return -1
                    }
                    attempts++
                }

                // Serve from window if data is available
                if (position >= window.startPos && position < window.endPos) {
                    val available = (window.endPos - position).toInt()
                    val toRead = minOf(size, available)
                    
                    // Copy from circular ring buffer
                    window.read(position, outBuffer, offset, toRead)
                    
                    // Background-extend: Trigger prefetch if consumed more than half of forward region
                    checkPrefetch(position)
                    
                    return toRead
                }
            }
            
            // Fallback to direct network read only if window mechanism fails completely
            return readFromNetwork(position, outBuffer, offset, size)
        }

        private fun refreshWindow(position: Long) {
            window.prefetchJob?.cancel()
            window.startPos = position
            window.endPos = position
            window.isFetching = true
            
            // Fetch a fresh window starting at position, sized to the full BUFFER_SIZE (8MB)
            window.prefetchJob = downloadScope.launch {
                fetchWindowRange(song, window, position, window.BUFFER_SIZE.toLong())
            }
        }

        private fun checkPrefetch(currentPos: Long) {
            // forwardBuffered is distance from current read to end of window
            val forwardBuffered = window.endPos - currentPos
            val totalSize = window.totalSize
            
            // If less than half the window (4MB) is left, prefetch next 2MB chunk
            if (forwardBuffered < 4 * 1024 * 1024 && !window.isFetching && (totalSize == -1L || window.endPos < totalSize)) {
                window.isFetching = true
                window.prefetchJob = downloadScope.launch {
                    // Background-extend the window forward by 2MB
                    fetchWindowRange(song, window, window.endPos, 2 * 1024 * 1024)
                }
            }
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
            var lastError: Exception? = null
            for (attempt in 1..3) {
                try {
                    val url = resolveDownloadUrl(song) ?: return -1
                    // Optimization: Request slightly more to reduce number of requests
                    val fetchSize = if (size < 131072) 131072 else size 
                    val endPos = position + fetchSize - 1

                    val requestBuilder = Request.Builder()
                        .url(url)
                        .header("Range", "bytes=$position-$endPos")
                    
                    val h = runBlocking { getOrFetchHeaders() }
                    h.forEach { (k, v) -> requestBuilder.header(k, v) }

                    okHttpClient.newCall(requestBuilder.build()).execute().use { response ->
                        if (!response.isSuccessful) {
                            if (response.code == 416) return -1 // Real EOF
                            throw Exception("HTTP ${response.code}")
                        }
                        val body = response.body ?: throw Exception("Empty body")
                        val inputStream = body.byteStream()
                        
                        var totalRead = 0
                        while (totalRead < size) {
                            val read = inputStream.read(buffer, offset + totalRead, size - totalRead)
                            if (read == -1) break
                            totalRead += read
                        }
                        return if (totalRead == 0) -1 else totalRead
                    }
                } catch (e: Exception) {
                    lastError = e
                    if (e is CancellationException) throw e
                    Log.w(TAG, "Network read attempt $attempt failed for ${song.title}: ${e.message}")
                    if (attempt < 3) runBlocking { delay(500L * attempt) }
                }
            }
            Log.e(TAG, "All network read attempts failed for ${song.title}: ${lastError?.message}")
            return -1
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

        // TDLib prefix-download bookkeeping. We always download from offset 0 and grow the
        private val activeFileId = java.util.concurrent.atomic.AtomicReference<Int?>(null)
        @Volatile private var requestedPrefixEnd: Long = 0L
        @Volatile private var isExpanding: Boolean = false

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
                    activeFileId.set(currentFileId)
                    activeTelegramFileIds[song.id] = currentFileId
                    
                    // STREAMING STRATEGY: 
                    // Force full sequential download (0L limit) to avoid sparse file gaps.
                    requestWindow(currentFileId, 0L)
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
                                            requestWindow(currentFileId, 0L)
                                        }
                                        return@collect
                                    }
                                }

                                localPath = path
                                downloadedPrefix = file.local.downloadedPrefixSize
                            }
                        }
                    }
                }
            }
        }

        /**
         * Sends a TDLib DownloadFile request from offset 0 up to [limit] bytes (0 = whole file),
         * refreshing the fileId and retrying once if it turns out to be stale.
         */
        private suspend fun requestWindow(fileId: Int, limit: Long) {
            try {
                tdLib.send(TdApi.DownloadFile(fileId, 32, 0L, limit, false))
                activeTelegramFileIds[song.id] = fileId
                requestedPrefixEnd = if (limit <= 0L) Long.MAX_VALUE else limit
            } catch (e: Exception) {
                Log.w(TAG, "Stale fileId detected for ${song.title}, refreshing...")
                val refreshed = refreshFileId()
                if (refreshed != null && refreshed != 0) {
                    activeFileId.set(refreshed)
                    activeTelegramFileIds[song.id] = refreshed
                    try {
                        tdLib.send(TdApi.DownloadFile(refreshed, 32, 0L, limit, false))
                        requestedPrefixEnd = if (limit <= 0L) Long.MAX_VALUE else limit
                    } catch (_: Exception) {
                        Log.e(TAG, "Failed to download after refresh for ${song.title}")
                    }
                }
            }
        }

        /**
         * Extends the TDLib download window forward as playback (or a seek) approaches the
         * edge of what we've already requested.
         */
        private fun maybeExpandWindow(targetPosition: Long) {
            // No-op if we are already downloading the full file
            if (requestedPrefixEnd == Long.MAX_VALUE) return
            
            val fileId = activeFileId.get() ?: return
            if (isExpanding) return
            var target = targetPosition + TELEGRAM_WINDOW_EXTEND_BYTES
            if (target <= requestedPrefixEnd) return
            val totalSize = song.fileSizeBytes
            if (totalSize > 0 && target >= totalSize) target = 0L // close enough to the end: just fetch the rest
            isExpanding = true
            scope.launch {
                try {
                    requestWindow(fileId, target)
                } finally {
                    isExpanding = false
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
            
            // 1. Check unified cache first (Telegram 4-slot logic)
            getCachedFile(song)?.let { return readFromFile(it, position, buffer, offset, size) }

            // 2. Fallback to windowed streaming if not cached yet
            // Make sure TDLib has been asked to download far enough ahead of this read.
            maybeExpandWindow(position + size)

            var attempts = 0
            // Polling loop: Wait for localPath and required data range.
            Log.d(TAG, "Polling for Telegram data at pos $position for ${song.title}...")
            while ((localPath.isNullOrBlank() || downloadedPrefix < position + size || !File(localPath ?: "").exists()) && attempts < 4000) {
                try {
                    lock.unlock()
                    Thread.sleep(10) // Snappier check (10ms)
                    lock.lock()
                } catch (e: Exception) {
                    Log.w(TAG, "Polling loop interrupted", e)
                }
                attempts++
                if (attempts % 25 == 0) maybeExpandWindow(position + size)
                if (!localPath.isNullOrBlank() && downloadedPrefix >= position + size && File(localPath!!).exists()) break
            }

            if (attempts >= 4000) {
                Log.e(TAG, "Telegram polling timeout (40s) for ${song.title} at pos $position. Path: $localPath, Prefix: $downloadedPrefix")
            }

            val path = localPath.takeIf { it?.isNotBlank() == true } ?: return -1
            if (!File(path).exists()) {
                Log.e(TAG, "Telegram file missing at path: $path")
                return -1
            }
            return readFromFile(File(path), position, buffer, offset, size)
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

