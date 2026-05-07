package com.beatflowy.app.repository

import android.content.Context
import android.util.Log
import com.beatflowy.app.model.AppDatabase
import com.beatflowy.app.model.LrcLine
import com.beatflowy.app.model.LyricsEntity
import com.beatflowy.app.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
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
    val rawContent: String? = null
)

class LyricsRepository(private val context: Context, private val database: AppDatabase) {
    private val TAG = "LyricsRepository"
    private val embeddedSource = EmbeddedLyricsSource(context)
    private val onlineSource = OnlineLyricsSource()
    private val lyricsDao = database.lyricsDao()
    
    private val cache = ConcurrentHashMap<String, LyricsLoadResult>()

    suspend fun saveLyrics(songId: String, lyricsText: String) {
        lyricsDao.insertLyrics(LyricsEntity(songId, lyricsText))
        // Update memory cache
        val lines = LrcParser.parse(lyricsText)
        cache[songId] = LyricsLoadResult(
            lines = lines,
            source = LyricsSource.CACHE,
            type = determineType(lyricsText),
            rawContent = lyricsText
        )
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
        
        var fallback: LyricsLoadResult? = null

        // 1. Memory & DB Cache check
        val cached = getCachedLyrics(song)
        if (cached != null) {
            Log.d(TAG, "Using cached lyrics for ${song.title} from ${cached.source} (Type: ${cached.type})")
            if (cached.type != LyricsType.PLAIN) {
                emit(LyricsState.Success(cached))
                return@flow
            }
            fallback = cached
        }

        // 2. Embedded Check
        if (fallback == null || fallback.source != LyricsSource.EMBEDDED) {
            val embedded = fetchEmbedded(song)
            if (embedded != null) {
                Log.d(TAG, "Found embedded lyrics for ${song.title} (Type: ${embedded.type})")
                if (embedded.type != LyricsType.PLAIN) {
                    emit(LyricsState.Success(embedded))
                    return@flow
                }
                fallback = embedded
            }
        }

        // 3. Online Check (Only if we don't have Synced yet)
        Log.d(TAG, "Searching online for ${song.title}...")
        val online = fetchOnline(song)
        if (online != null) {
            Log.d(TAG, "Found online lyrics for ${song.title} (Type: ${online.type})")
            if (online.type != LyricsType.PLAIN || fallback == null) {
                emit(LyricsState.Success(online))
                return@flow
            }
        }

        // 4. Fallback to Plain if nothing better found
        if (fallback != null) {
            Log.d(TAG, "Falling back to plain lyrics for ${song.title}")
            emit(LyricsState.Success(fallback))
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
                rawContent = entity.lyrics
            ).also { cache[song.id] = it }
        }
    }

    private suspend fun fetchEmbedded(song: Song): LyricsLoadResult? {
        val result = song.uri.path?.let { embeddedSource.getLyrics(it) }
            ?: embeddedSource.getLyrics(song.uri)
            
        return result?.let {
            LyricsLoadResult(
                lines = LrcParser.parse(it.content),
                source = LyricsSource.EMBEDDED,
                type = it.type,
                rawContent = it.content
            ).also { res ->
                cache[song.id] = res
                // Cache embedded to DB if it's better than what we have or if we have nothing
                saveToDbIfBetter(song.id, res)
            }
        }
    }

    private suspend fun fetchOnline(song: Song): LyricsLoadResult? {
        val result = onlineSource.fetchLyrics(song.artist, song.title, song.album, song.durationMs)
        
        return result?.let {
            LyricsLoadResult(
                lines = LrcParser.parse(it.content),
                source = LyricsSource.ONLINE,
                type = it.type,
                rawContent = it.content
            ).also { res ->
                cache[song.id] = res
                saveToDbIfBetter(song.id, res)
            }
        }
    }

    private suspend fun saveToDbIfBetter(songId: String, newResult: LyricsLoadResult) {
        val existing = lyricsDao.getLyrics(songId)
        val existingType = existing?.let { determineType(it.lyrics) } ?: LyricsType.PLAIN
        
        val shouldUpdate = existing == null || 
                (newResult.type == LyricsType.WORD_BY_WORD && existingType != LyricsType.WORD_BY_WORD) ||
                (newResult.type == LyricsType.SYNCED && existingType == LyricsType.PLAIN)

        if (shouldUpdate) {
            newResult.rawContent?.let {
                lyricsDao.insertLyrics(LyricsEntity(songId, it))
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
