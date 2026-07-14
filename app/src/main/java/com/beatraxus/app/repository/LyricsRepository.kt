package com.beatraxus.app.repository

import android.content.Context
import android.util.Log
import com.beatraxus.app.model.AppDatabase
import com.beatraxus.app.model.LrcLine
import com.beatraxus.app.model.LyricsEntity
import com.beatraxus.app.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

enum class LyricsSource {
    EMBEDDED,
    CACHE,
    ONLINE
}

data class LyricsLoadResult(
    val lines: List<LrcLine>,
    val source: LyricsSource,
    val type: LyricsType = LyricsType.PLAIN,
    val rawContent: String? = null,
    val syncOffset: Long = 0L
)

class LyricsRepository(private val context: Context, private val database: AppDatabase) {
    private val TAG = "LyricsRepository"
    private val embeddedSource = EmbeddedLyricsSource(context)
    private val onlineSource = OnlineLyricsSource()
    private val lyricsDao = database.lyricsDao()
    private val songDao = database.songDao()
    
    private val cache = ConcurrentHashMap<String, LyricsLoadResult>()
    private val notFoundCache = ConcurrentHashMap<String, Long>() // songId -> timestamp
    private val NOT_FOUND_TTL_MS = 24 * 60 * 60 * 1000L // don't retry for 24h

    suspend fun saveLyrics(songId: String, lyricsText: String, offset: Long = 0L) {
        lyricsDao.insertLyrics(LyricsEntity(songId, lyricsText, syncOffset = offset))
        // Update memory cache
        val lines = LrcParser.parse(lyricsText)
        cache[songId] = LyricsLoadResult(
            lines = lines,
            source = LyricsSource.CACHE,
            type = determineType(lyricsText),
            rawContent = lyricsText,
            syncOffset = offset
        )
    }

    suspend fun updateSyncOffset(songId: String, offset: Long) {
        val existing = lyricsDao.getLyrics(songId)
        if (existing != null) {
            lyricsDao.insertLyrics(existing.copy(syncOffset = offset))
            cache[songId]?.let {
                cache[songId] = it.copy(syncOffset = offset)
            }
        }
    }

    /**
     * Priority Pipeline:
     * 1. Check Memory Cache
     * 2. Check Database Cache
     * 3. Check Embedded (If Synced -> Return, If Plain -> Fallback)
     * 4. Check Online (If Synced -> Return)
     * 5. Return best available
     */
    fun getLyrics(song: Song): Flow<LyricsState> = flow {
        emit(LyricsState.Loading)

        var bestResult: LyricsLoadResult? = null

        // ── 0. Song metadata (pre-extracted during scan/enrichment) ──────────────
        if (!song.lyrics.isNullOrBlank()) {
            val type = determineType(song.lyrics)
            val res = LyricsLoadResult(
                lines = LrcParser.parse(song.lyrics),
                source = LyricsSource.EMBEDDED,
                type = type,
                rawContent = song.lyrics
            )
            if (type == LyricsType.WORD_BY_WORD || type == LyricsType.SYNCED) {
                emit(LyricsState.Success(res))
                return@flow
            }
            bestResult = res
        }

        // ── 1. Memory & DB cache (instant, no I/O wait) ──────────────────────────
        val cached = getCachedLyrics(song)
        if (cached != null && (cached.type == LyricsType.WORD_BY_WORD || cached.type == LyricsType.SYNCED)) {
            emit(LyricsState.Success(cached))
            return@flow
        }
        if (cached != null) bestResult = cached

        // ── 2. Embedded tag (always check — user may have tagged file since cache) ─
        val embedded = fetchEmbedded(song)
        if (embedded != null) {
            if (embedded.type == LyricsType.WORD_BY_WORD || embedded.type == LyricsType.SYNCED) {
                emit(LyricsState.Success(embedded))
                return@flow
            }
            if (bestResult == null || bestResult.type == LyricsType.PLAIN) {
                bestResult = embedded
            }
        }

        // ── 3. Online (only if we still don't have better synced lyrics) ─────────
        Log.d(TAG, "Searching online for ${song.title}...")
        val online = fetchOnline(song)
        if (online != null) {
            if (online.type == LyricsType.WORD_BY_WORD || online.type == LyricsType.SYNCED) {
                emit(LyricsState.Success(online))
                return@flow
            }
            if (bestResult == null) bestResult = online
        }

        // ── 4. Best available fallback ─────────────────────────────────────────────
        if (bestResult != null) {
            Log.d(TAG, "Falling back to ${bestResult!!.type} lyrics for ${song.title}")
            emit(LyricsState.Success(bestResult!!))
        } else {
            Log.e(TAG, "No lyrics found for ${song.title}")
            emit(LyricsState.Error("No lyrics found"))
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun getCachedLyrics(song: Song): LyricsLoadResult? {
        cache[song.id]?.let { return it }
        
        return lyricsDao.getLyrics(song.id)?.let { entity ->
            val type = determineType(entity.lyrics)
            LyricsLoadResult(
                lines = LrcParser.parse(entity.lyrics),
                source = LyricsSource.CACHE,
                type = type,
                rawContent = entity.lyrics,
                syncOffset = entity.syncOffset
            ).also { cache[song.id] = it }
        }
    }

    private suspend fun fetchEmbedded(song: Song): LyricsLoadResult? {
        val existingOffset = lyricsDao.getLyrics(song.id)?.syncOffset ?: 0L
        val result = song.uri.path?.let { embeddedSource.getLyrics(it) }
            ?: embeddedSource.getLyrics(song.uri)
            
        return result?.let {
            LyricsLoadResult(
                lines = LrcParser.parse(it.content),
                source = LyricsSource.EMBEDDED,
                type = it.type,
                rawContent = it.content,
                syncOffset = existingOffset
            ).also { res ->
                cache[song.id] = res
                // Cache embedded to DB if it's better than what we have or if we have nothing
                saveToDbIfBetter(song.id, res)
            }
        }
    }

    suspend fun fetchOnline(song: Song, persist: Boolean = true): LyricsLoadResult? {
        val notFoundAt = notFoundCache[song.id]
        if (notFoundAt != null && System.currentTimeMillis() - notFoundAt < NOT_FOUND_TTL_MS) {
            return null // known "not found" recently — skip the network round trip
        }

        val existingOffset = lyricsDao.getLyrics(song.id)?.syncOffset ?: 0L
        val result = onlineSource.fetchLyrics(song.artist, song.title, song.album, song.durationMs)
        
        if (result == null) {
            notFoundCache[song.id] = System.currentTimeMillis()
            return null
        }
        notFoundCache.remove(song.id)

        val res = LyricsLoadResult(
            lines = LrcParser.parse(result.content),
            source = LyricsSource.ONLINE,
            type = result.type,
            rawContent = result.content,
            syncOffset = existingOffset
        )

        cache[song.id] = res

        if (persist) {
            saveToDbIfBetter(song.id, res)
            
            // Auto-save to file metadata if we fetched online lyrics
            // and the song doesn't already have them in its metadata
            if (song.lyrics.isNullOrBlank()) {
                embeddedSource.saveLyrics(song.uri, result.content)
                // Also update the songs table so the app knows it now has lyrics
                songDao.updateLyrics(song.id, result.content)
            }
        }
        
        return res
    }

    suspend fun preloadLyrics(songs: List<Song>) = withContext(Dispatchers.IO) {
        val semaphore = Semaphore(3) // up to 3 fetches in flight at once
        
        songs.map { song ->
            async {
                if (!isActive) return@async

                // Check if we already have synced/word-by-word lyrics in any of our sources
                
                // 1. Memory cache check
                val memCached = cache[song.id]
                if (memCached != null && (memCached.type == LyricsType.WORD_BY_WORD || memCached.type == LyricsType.SYNCED)) return@async
                
                // 2. Database check
                val dbEntry = lyricsDao.getLyrics(song.id)
                val dbType = dbEntry?.let { determineType(it.lyrics) } ?: LyricsType.PLAIN
                if (dbType == LyricsType.WORD_BY_WORD || dbType == LyricsType.SYNCED) return@async
                
                // 3. Metadata check
                if (!song.lyrics.isNullOrBlank()) {
                    val metaType = determineType(song.lyrics)
                    if (metaType == LyricsType.WORD_BY_WORD || metaType == LyricsType.SYNCED) return@async
                }
                
                // If we only have plain lyrics or no lyrics, attempt online fetch with concurrency limit
                semaphore.withPermit {
                    Log.d(TAG, "Preloading lyrics for ${song.title}...")
                    fetchOnline(song, persist = true)
                    // Brief delay between batches to be nice to the API
                    delay(500)
                }
            }
        }.forEach { it.await() }
    }

    private suspend fun saveToDbIfBetter(songId: String, newResult: LyricsLoadResult) {
        val existing = lyricsDao.getLyrics(songId)
        val existingType = existing?.let { determineType(it.lyrics) } ?: LyricsType.PLAIN
        
        val shouldUpdate = existing == null || 
                (newResult.type == LyricsType.WORD_BY_WORD && existingType != LyricsType.WORD_BY_WORD) ||
                (newResult.type == LyricsType.SYNCED && existingType == LyricsType.PLAIN)

        if (shouldUpdate) {
            newResult.rawContent?.let {
                lyricsDao.insertLyrics(LyricsEntity(songId, it, syncOffset = newResult.syncOffset))
            }
        }
    }

    private fun determineType(content: String): LyricsType {
        return when {
            content.contains(Regex("<\\d+:\\d+[.:]\\d+>")) -> LyricsType.WORD_BY_WORD
            content.contains(Regex("\\[\\d+:\\d+[.:]\\d+\\]")) -> LyricsType.SYNCED
            else -> LyricsType.PLAIN
        }
    }
}
