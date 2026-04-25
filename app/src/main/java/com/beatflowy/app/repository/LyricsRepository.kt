package com.beatflowy.app.repository

import android.content.Context
import com.beatflowy.app.model.AppDatabase
import com.beatflowy.app.model.LrcLine
import com.beatflowy.app.model.LyricsEntity
import com.beatflowy.app.model.Song
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class LyricsSource {
    EMBEDDED,
    CACHE,
    ONLINE
}

data class LyricsLoadResult(
    val lines: List<LrcLine>,
    val source: LyricsSource
)

class LyricsRepository(private val context: Context, private val database: AppDatabase) {
    private val embeddedSource = EmbeddedLyricsSource(context)
    private val onlineSource = OnlineLyricsSource()
    private val lyricsDao = database.lyricsDao()
    private val embeddedLyricsMemoryCache = ConcurrentHashMap<String, LyricsLoadResult>()
    private val resolvedLyricsMemoryCache = ConcurrentHashMap<String, LyricsLoadResult>()

    suspend fun getEmbeddedLyrics(song: Song): LyricsLoadResult? = withContext(Dispatchers.IO) {
        embeddedLyricsMemoryCache[song.id]?.let { return@withContext it }

        val rawLyrics = song.uri.path
            ?.takeIf { it.isNotBlank() }
            ?.let { embeddedSource.getEmbeddedLyrics(it) }
            ?: embeddedSource.getLyrics(song.uri)

        parseLyrics(rawLyrics, LyricsSource.EMBEDDED)?.also {
            embeddedLyricsMemoryCache[song.id] = it
            resolvedLyricsMemoryCache[song.id] = it
        }
    }

    suspend fun getCachedLyrics(song: Song): LyricsLoadResult? = withContext(Dispatchers.IO) {
        resolvedLyricsMemoryCache[song.id]
            ?.takeUnless { it.source == LyricsSource.EMBEDDED }
            ?.let { return@withContext it }

        val cached = lyricsDao.getLyrics(song.id) ?: return@withContext null
        parseLyrics(cached.lyrics, LyricsSource.CACHE)?.also {
            resolvedLyricsMemoryCache[song.id] = it
        }
    }

    suspend fun fetchOnlineLyrics(song: Song): LyricsLoadResult? = withContext(Dispatchers.IO) {
        val online = onlineSource.fetchLyrics(song.artist, song.title, song.album, song.durationMs)
        parseLyrics(online, LyricsSource.ONLINE)?.also { parsed ->
            lyricsDao.insertLyrics(LyricsEntity(song.id, online!!))
            resolvedLyricsMemoryCache[song.id] = parsed
        }
    }

    suspend fun getLyrics(song: Song): LyricsLoadResult? = withContext(Dispatchers.IO) {
        getEmbeddedLyrics(song)
            ?: getCachedLyrics(song)
            ?: fetchOnlineLyrics(song)
    }

    suspend fun saveLyrics(songId: String, lyricsText: String) = withContext(Dispatchers.IO) {
        lyricsDao.insertLyrics(LyricsEntity(songId, lyricsText))
        parseLyrics(lyricsText, LyricsSource.CACHE)?.also {
            resolvedLyricsMemoryCache[songId] = it
        }
    }

    private fun parseLyrics(lyricsText: String?, source: LyricsSource): LyricsLoadResult? {
        if (lyricsText.isNullOrBlank()) return null
        return LyricsLoadResult(
            lines = LrcParser.parse(lyricsText),
            source = source
        )
    }
}
