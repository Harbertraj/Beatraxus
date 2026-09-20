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
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

import com.beatraxus.app.repository.lyrics.LyricsProviderRegistry
import com.beatraxus.app.repository.lyrics.LyricsQuery
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

import com.beatraxus.app.repository.lyrics.LyricsGranularity

data class LyricsCandidate(
    val providerId: String,
    val providerName: String,
    val granularity: LyricsGranularity,
    val type: LyricsType,
    val lineCount: Int,
    val preview: String,
    val content: String
)

data class LyricsProviderConfig(val order: List<String>, val enabled: Set<String>)

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
    val syncOffset: Long = 0L,
    val score: Double = 0.0,
    val providerId: String? = null
)

class LyricsRepository(
    private val context: Context, 
    private val database: AppDatabase,
    private val providerConfig: () -> LyricsProviderConfig
) {
    private val TAG = "LyricsRepository"
    private val embeddedSource = EmbeddedLyricsSource(context)
    private val lyricsDao = database.lyricsDao()
    private val songDao = database.songDao()
    
    private val cache = ConcurrentHashMap<String, LyricsLoadResult>()
    private val notFoundCache = ConcurrentHashMap<String, Long>() // songId -> timestamp
    private val NOT_FOUND_TTL_MS = 24 * 60 * 60 * 1000L // don't retry for 24h
    
    private var lastConfigHash = 0

    private fun checkConfigChange() {
        val config = providerConfig()
        val currentHash = config.hashCode()
        if (currentHash != lastConfigHash) {
            lastConfigHash = currentHash
            cache.clear()
            notFoundCache.clear()
        }
    }

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
        checkConfigChange()
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
            Log.d(TAG, "Falling back to ${bestResult.type} lyrics for ${song.title}")
            emit(LyricsState.Success(bestResult))
        } else {
            Log.e(TAG, "No lyrics found for ${song.title}")
            emit(LyricsState.Error("No lyrics found"))
        }
    }.flowOn(Dispatchers.IO)
        .catch { e ->
            Log.e(TAG, "getLyrics flow crashed unexpectedly for ${song.title}", e)
            emit(LyricsState.Error("Lyrics lookup failed: ${e.message ?: "unknown error"}"))
        }

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

    suspend fun fetchOnline(song: Song, persist: Boolean = true, forceRefresh: Boolean = false): LyricsLoadResult? {
        checkConfigChange()
        
        if (!forceRefresh) {
            val notFoundAt = notFoundCache[song.id]
            if (notFoundAt != null && System.currentTimeMillis() - notFoundAt < NOT_FOUND_TTL_MS) {
                return null // known "not found" recently — skip the network round trip
            }
        }

        val config = providerConfig()
        val registryProviders = LyricsProviderRegistry.providers
        val enabledProviders = registryProviders.filter { config.enabled.contains(it.id) && it.isConfigured }
        val orderedProviders = enabledProviders.sortedBy { provider ->
            val idx = config.order.indexOf(provider.id)
            if (idx == -1) Int.MAX_VALUE else idx
        }

        var bestPlainResult: LyricsResult? = null
        var bestPlainProviderId: String? = null
        
        val query = LyricsQuery(
            title = song.title,
            artist = song.artist,
            album = song.album,
            durationMs = song.durationMs,
            videoId = null // Handled in step 6
        )

        var finalResult: LyricsResult? = null
        var finalProviderId: String? = null

        withTimeoutOrNull(20_000) {
            for (provider in orderedProviders) {
                if (provider.requiresVideoId && query.videoId == null) continue

                val result = try {
                    withTimeout(8_000) {
                        provider.fetch(query)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Provider ${provider.id} failed: ${e.message}")
                    null
                }

                if (result != null) {
                    if (result.type == LyricsType.WORD_BY_WORD || result.type == LyricsType.SYNCED) {
                        finalResult = result
                        finalProviderId = provider.id
                        break
                    } else if (result.type == LyricsType.PLAIN) {
                        if (bestPlainResult == null) {
                            bestPlainResult = result
                            bestPlainProviderId = provider.id
                        }
                    }
                }
            }
        }
        
        if (finalResult == null && bestPlainResult != null) {
            finalResult = bestPlainResult
            finalProviderId = bestPlainProviderId
        }

        if (finalResult == null) {
            notFoundCache[song.id] = System.currentTimeMillis()
            return null
        }
        notFoundCache.remove(song.id)

        val finalRes = finalResult!!
        val existingOffset = lyricsDao.getLyrics(song.id)?.syncOffset ?: 0L
        val res = LyricsLoadResult(
            lines = LrcParser.parse(finalRes.content),
            source = LyricsSource.ONLINE,
            type = finalRes.type,
            rawContent = finalRes.content,
            syncOffset = existingOffset,
            providerId = finalProviderId
        )

        cache[song.id] = res

        if (persist) {
            val provider = registryProviders.find { it.id == finalProviderId }
            val isExperimental = provider?.experimental == true
            
            saveToDbIfBetter(song.id, res)
            
            if (!isExperimental && song.lyrics.isNullOrBlank()) {
                embeddedSource.saveLyrics(song.uri, finalRes.content)
                songDao.updateLyrics(song.id, finalRes.content)
            }
        }
        
        return res
    }

    private val candidatesCache = ConcurrentHashMap<String, List<LyricsCandidate>>()

    suspend fun fetchAllCandidates(song: Song): List<LyricsCandidate> = withContext(Dispatchers.IO) {
        candidatesCache[song.id]?.let { return@withContext it }

        val config = providerConfig()
        val registryProviders = LyricsProviderRegistry.providers
        val enabledProviders = registryProviders.filter { config.enabled.contains(it.id) && it.isConfigured }
        val orderedProviders = enabledProviders.sortedBy { provider ->
            val idx = config.order.indexOf(provider.id)
            if (idx == -1) Int.MAX_VALUE else idx
        }

        val semaphore = Semaphore(4)
        val query = LyricsQuery(
            title = song.title,
            artist = song.artist,
            album = song.album,
            durationMs = song.durationMs,
            videoId = null
        )

        val deferredResults = orderedProviders.map { provider ->
            async {
                if (provider.requiresVideoId && query.videoId == null) return@async null

                semaphore.withPermit {
                    try {
                        withTimeout(8_000) {
                            val result = provider.fetch(query) ?: return@withTimeout null
                            val lines = LrcParser.parse(result.content)
                            val previewLine = lines.firstOrNull { it.text.isNotBlank() }?.text ?: "No preview available"
                            
                            LyricsCandidate(
                                providerId = provider.id,
                                providerName = provider.displayName,
                                granularity = provider.granularity,
                                type = result.type,
                                lineCount = lines.size,
                                preview = previewLine,
                                content = result.content
                            )
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        null
                    }
                }
            }
        }

        val results = deferredResults.mapNotNull { it.await() }
        
        // Re-sort results to match the user's explicit priority order
        val sortedResults = results.sortedBy { candidate ->
            val idx = config.order.indexOf(candidate.providerId)
            if (idx == -1) Int.MAX_VALUE else idx
        }

        candidatesCache[song.id] = sortedResults
        sortedResults
    }

    suspend fun preloadLyrics(songs: List<Song>) = withContext(Dispatchers.IO) {
        val semaphore = Semaphore(3) // up to 3 fetches in flight at once
        
        songs.map { song ->
            async {
                if (!isActive) return@async

                val memCached = cache[song.id]
                if (memCached != null && (memCached.type == LyricsType.WORD_BY_WORD || memCached.type == LyricsType.SYNCED)) return@async
                
                val dbEntry = lyricsDao.getLyrics(song.id)
                val dbType = dbEntry?.let { determineType(it.lyrics) } ?: LyricsType.PLAIN
                if (dbType == LyricsType.WORD_BY_WORD || dbType == LyricsType.SYNCED) return@async
                
                if (!song.lyrics.isNullOrBlank()) {
                    val metaType = determineType(song.lyrics)
                    if (metaType == LyricsType.WORD_BY_WORD || metaType == LyricsType.SYNCED) return@async
                }
                
                semaphore.withPermit {
                    Log.d(TAG, "Preloading lyrics for ${song.title}...")
                    
                    val config = providerConfig()
                    val registryProviders = LyricsProviderRegistry.providers
                    val enabledProviders = registryProviders.filter { config.enabled.contains(it.id) && it.isConfigured }
                    val orderedProviders = enabledProviders.sortedBy { provider ->
                        val idx = config.order.indexOf(provider.id)
                        if (idx == -1) Int.MAX_VALUE else idx
                    }.take(2) // Only try first two providers for preload
                    
                    var found = false
                    val query = LyricsQuery(
                        title = song.title,
                        artist = song.artist,
                        album = song.album,
                        durationMs = song.durationMs,
                        videoId = null
                    )
                    
                    for (provider in orderedProviders) {
                        if (provider.requiresVideoId && query.videoId == null) continue
                        val result = try {
                            withTimeout(8_000) { provider.fetch(query) }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            null
                        }
                        
                        if (result != null) {
                            val res = LyricsLoadResult(
                                lines = LrcParser.parse(result.content),
                                source = LyricsSource.ONLINE,
                                type = result.type,
                                rawContent = result.content,
                                syncOffset = 0L,
                                providerId = provider.id
                            )
                            cache[song.id] = res
                            saveToDbIfBetter(song.id, res)
                            if (!provider.experimental && song.lyrics.isNullOrBlank()) {
                                embeddedSource.saveLyrics(song.uri, result.content)
                                songDao.updateLyrics(song.id, result.content)
                            }
                            found = true
                            break
                        }
                    }
                    if (!found) {
                        notFoundCache[song.id] = System.currentTimeMillis()
                    }
                    
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
        val hasWordTags = LrcParser.WORD_TIME_PATTERN.matcher(content).find()
        val hasLineTags = content.contains(Regex("""\[(?:(\d+):)?(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?\]"""))
        
        return when {
            hasWordTags && hasLineTags -> LyricsType.WORD_BY_WORD
            hasLineTags -> LyricsType.SYNCED
            else -> LyricsType.PLAIN
        }
    }
}
