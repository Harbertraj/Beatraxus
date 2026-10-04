package com.beatraxus.app.repository

import android.content.Context
import android.util.Log
import com.beatraxus.app.model.AppDatabase
import com.beatraxus.app.model.LrcLine
import com.beatraxus.app.model.LyricsEntity
import com.beatraxus.app.model.Song
import com.beatraxus.app.repository.lyrics.LyricsGranularity
import com.beatraxus.app.repository.lyrics.LyricsProvider
import com.beatraxus.app.repository.lyrics.LyricsProviderRegistry
import com.beatraxus.app.repository.lyrics.LyricsQuery
import com.beatraxus.app.repository.lyrics.LyricsTransientException
import com.beatraxus.app.repository.lyrics.LyricsValidator
import com.beatraxus.app.repository.lyrics.ValidationResult
import com.beatraxus.app.repository.lyrics.VideoIdResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

data class LyricsCandidate(
    val providerId: String,
    val providerName: String,
    val granularity: LyricsGranularity,
    val type: LyricsType,
    val lineCount: Int,
    val preview: String,
    val content: String,
    val warning: String? = null
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

private data class ProviderFetchResult(
    val provider: LyricsProvider,
    val result: LyricsResult?,
    val status: String,
    val durationMs: Long,
    val exception: Throwable? = null
)

private data class CandidateEval(
    val provider: LyricsProvider,
    val result: LyricsResult,
    val validation: ValidationResult,
    val effectiveScore: Double,
    val providerOrder: Int
)

private data class RunProvidersResult(
    val bestResult: LyricsResult?,
    val providerId: String?,
    val allResults: List<ProviderFetchResult>,
    val hasTransientError: Boolean,
    val bestValidation: ValidationResult? = null
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
    private val candidatesCache = ConcurrentHashMap<String, List<LyricsCandidate>>()
    private val NOT_FOUND_TTL_MS = 24 * 60 * 60 * 1000L // don't retry for 24h

    private var lastConfigHash = 0

    private fun checkConfigChange() {
        val config = providerConfig()
        val currentHash = config.hashCode()
        if (currentHash != lastConfigHash) {
            lastConfigHash = currentHash
            cache.clear()
            notFoundCache.clear()
            candidatesCache.clear()
        }
    }

    fun getCandidatesCacheKey(song: Song): String {
        val config = providerConfig()
        return "${song.id}_${song.durationMs}_${config.hashCode()}"
    }

    suspend fun saveLyrics(songId: String, lyricsText: String, offset: Long = 0L) {
        val lines = LrcParser.parse(lyricsText)
        val type = determineType(lyricsText)
        val validation = LyricsValidator.validate(lyricsText, 0L, type)

        lyricsDao.insertLyrics(
            LyricsEntity(
                songId = songId,
                lyrics = lyricsText,
                syncOffset = offset,
                timestamp = System.currentTimeMillis(),
                providerId = "user_saved",
                fetchedAt = System.currentTimeMillis(),
                isValidated = validation.isValid,
                durationMs = null
            )
        )

        cache[songId] = LyricsLoadResult(
            lines = lines,
            source = LyricsSource.CACHE,
            type = type,
            rawContent = lyricsText,
            syncOffset = offset
        )
    }

    suspend fun clearSavedLyrics(song: Song, clearEmbeddedFileTag: Boolean = false) = withContext(Dispatchers.IO) {
        lyricsDao.deleteLyricsBySongIds(listOf(song.id))
        cache.remove(song.id)
        notFoundCache.remove(song.id)
        candidatesCache.remove(getCandidatesCacheKey(song))

        if (clearEmbeddedFileTag) {
            embeddedSource.saveLyrics(song.uri, "")
            songDao.updateLyrics(song.id, "")
        }
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

    fun getLyrics(song: Song): Flow<LyricsState> = flow {
        checkConfigChange()
        emit(LyricsState.Loading)

        var bestResult: LyricsLoadResult? = null

        // ── 0. Song metadata (pre-extracted during scan/enrichment) ──────────────
        if (!song.lyrics.isNullOrBlank()) {
            val type = determineType(song.lyrics)
            val res = LyricsLoadResult(
                lines = LrcParser.parse(song.lyrics, song.durationMs),
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

        // ── 2. Embedded tag ───────────────────────────────────────────────────────
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

        // ── 3. Online ─────────────────────────────────────────────────────────────
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
                lines = LrcParser.parse(entity.lyrics, song.durationMs),
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
                lines = LrcParser.parse(it.content, song.durationMs),
                source = LyricsSource.EMBEDDED,
                type = it.type,
                rawContent = it.content,
                syncOffset = existingOffset
            ).also { res ->
                cache[song.id] = res
                saveToDbIfBetter(song.id, res)
            }
        }
    }

    private suspend fun runProviders(
        song: Song,
        providers: List<LyricsProvider>,
        overallTimeoutMs: Long = 15_000L
    ): RunProvidersResult = coroutineScope {
        var query = LyricsQuery(
            title = song.title,
            artist = song.artist,
            album = song.album,
            durationMs = song.durationMs,
            videoId = null
        )

        if (providers.any { it.requiresVideoId }) {
            val videoId = VideoIdResolver.resolve(query)
            if (videoId != null) {
                query = query.copy(videoId = videoId)
            }
        }

        val finalQuery = query
        val semaphore = Semaphore(4)
        val config = providerConfig()

        val deferredList = providers.map { provider ->
            async(Dispatchers.IO) {
                if (provider.requiresVideoId && finalQuery.videoId == null) {
                    Log.d("LyricsProvider", "id=${provider.id} status=miss type=- ms=0")
                    return@async ProviderFetchResult(provider, null, "miss", 0L)
                }

                semaphore.withPermit {
                    val timeoutMs = if (provider.id == "paxsenix") 12_000L else 8_000L
                    val startTime = System.currentTimeMillis()
                    var status = "miss"
                    var typeStr = "-"
                    var lyricsResult: LyricsResult? = null
                    var error: Throwable? = null

                    try {
                        lyricsResult = withTimeout(timeoutMs) {
                            provider.fetch(finalQuery)
                        }
                        if (lyricsResult != null) {
                            status = "hit"
                            typeStr = lyricsResult.type.name
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        status = "error"
                        error = e
                    }
                    val durationMs = System.currentTimeMillis() - startTime
                    Log.d("LyricsProvider", "id=${provider.id} status=$status type=$typeStr ms=$durationMs")
                    ProviderFetchResult(provider, lyricsResult, status, durationMs, error)
                }
            }
        }

        val fetchResults = withTimeoutOrNull(overallTimeoutMs) {
            deferredList.map { it.await() }
        } ?: deferredList.mapIndexed { idx, deferred ->
            if (deferred.isCompleted) {
                deferred.getCompleted()
            } else {
                deferred.cancel()
                ProviderFetchResult(
                    providers[idx],
                    null,
                    "error",
                    overallTimeoutMs,
                    LyricsTransientException("Timed out")
                )
            }
        }

        val candidateEvals = mutableListOf<CandidateEval>()
        var hasTransientError = false

        for (fetchRes in fetchResults) {
            val ex = fetchRes.exception
            if (ex is LyricsTransientException || ex?.cause is LyricsTransientException) {
                hasTransientError = true
            }

            val res = fetchRes.result ?: continue
            val validation = LyricsValidator.validate(res.content, song.durationMs, res.type)
            val effectiveScore = maxOf(0.0, res.score - validation.penalty)
            val providerOrder = config.order.indexOf(fetchRes.provider.id).let { if (it == -1) Int.MAX_VALUE else it }

            candidateEvals.add(
                CandidateEval(
                    provider = fetchRes.provider,
                    result = res,
                    validation = validation,
                    effectiveScore = effectiveScore,
                    providerOrder = providerOrder
                )
            )
        }

        val bestEval = candidateEvals
            .filter { it.validation.isValid }
            .maxWithOrNull(
                compareBy<CandidateEval> {
                    when (it.result.type) {
                        LyricsType.WORD_BY_WORD -> 2
                        LyricsType.SYNCED -> 1
                        LyricsType.PLAIN -> 0
                    }
                }.thenBy { it.effectiveScore }
                    .thenByDescending { -it.providerOrder }
            ) ?: candidateEvals.maxWithOrNull(compareBy { it.effectiveScore })

        val selectedResult = bestEval?.result
        val selectedProviderId = bestEval?.provider?.id
        val bestValidation = bestEval?.validation

        RunProvidersResult(selectedResult, selectedProviderId, fetchResults, hasTransientError, bestValidation)
    }

    suspend fun fetchOnline(song: Song, persist: Boolean = true, forceRefresh: Boolean = false): LyricsLoadResult? {
        checkConfigChange()

        if (!forceRefresh) {
            val notFoundAt = notFoundCache[song.id]
            if (notFoundAt != null && System.currentTimeMillis() - notFoundAt < NOT_FOUND_TTL_MS) {
                return null
            }
        }

        val config = providerConfig()
        val registryProviders = LyricsProviderRegistry.providers
        val enabledProviders = registryProviders.filter { config.enabled.contains(it.id) && it.isConfigured }
        val orderedProviders = enabledProviders.sortedBy { provider ->
            val idx = config.order.indexOf(provider.id)
            if (idx == -1) Int.MAX_VALUE else idx
        }

        val runResult = runProviders(song, orderedProviders, overallTimeoutMs = 15_000L)

        if (runResult.bestResult == null) {
            if (!runResult.hasTransientError) {
                notFoundCache[song.id] = System.currentTimeMillis()
            }
            return null
        }
        notFoundCache.remove(song.id)

        val finalRes = runResult.bestResult
        val finalProviderId = runResult.providerId
        val existingOffset = lyricsDao.getLyrics(song.id)?.syncOffset ?: 0L

        val res = LyricsLoadResult(
            lines = LrcParser.parse(finalRes.content, song.durationMs),
            source = LyricsSource.ONLINE,
            type = finalRes.type,
            rawContent = finalRes.content,
            syncOffset = existingOffset,
            score = finalRes.score,
            providerId = finalProviderId
        )

        cache[song.id] = res

        if (persist) {
            val validation = runResult.bestValidation ?: LyricsValidator.validate(finalRes.content, song.durationMs, finalRes.type)
            saveToDbIfBetter(song.id, res, finalProviderId, validation)

            val provider = registryProviders.find { it.id == finalProviderId }
            val isExperimental = provider?.experimental == true

            // Only auto-embed when validator says OK (isValid && penalty == 0.0) AND score >= 0.85
            if (!isExperimental && validation.isValid && validation.penalty == 0.0 && finalRes.score >= 0.85 && song.lyrics.isNullOrBlank()) {
                embeddedSource.saveLyrics(song.uri, finalRes.content)
                songDao.updateLyrics(song.id, finalRes.content)
            }
        }

        return res
    }

    suspend fun fetchEmbeddedCandidate(song: Song): LyricsCandidate? = withContext(Dispatchers.IO) {
        val actualFileResult = song.uri.path?.let { embeddedSource.getLyrics(it) }
            ?: embeddedSource.getLyrics(song.uri)

        val dbEntity = lyricsDao.getLyrics(song.id)

        val res = actualFileResult ?: dbEntity?.let {
            LyricsResult(type = determineType(it.lyrics), content = it.lyrics.trim())
        } ?: song.lyrics?.takeIf { it.isNotBlank() }?.let {
            LyricsResult(type = determineType(it), content = it.trim())
        } ?: return@withContext null

        if (res.content.isBlank()) return@withContext null

        val isTrueFileTag = actualFileResult != null
        val providerName = when {
            isTrueFileTag -> "Embedded (file tag)"
            dbEntity?.providerId != null -> "Saved (from ${dbEntity.providerId})"
            else -> "Saved lyrics"
        }
        val providerId = when {
            isTrueFileTag -> "embedded"
            dbEntity?.providerId != null -> "saved_${dbEntity.providerId}"
            else -> "saved"
        }

        val lines = LrcParser.parse(res.content, song.durationMs)
        val previewLine = lines.firstOrNull { it.text.isNotBlank() }?.text ?: "No preview available"
        val granularity = when (res.type) {
            LyricsType.WORD_BY_WORD -> LyricsGranularity.WORD
            LyricsType.SYNCED -> LyricsGranularity.LINE
            LyricsType.PLAIN -> LyricsGranularity.PLAIN
        }

        val validation = LyricsValidator.validate(res.content, song.durationMs, res.type)

        LyricsCandidate(
            providerId = providerId,
            providerName = providerName,
            granularity = granularity,
            type = res.type,
            lineCount = lines.size,
            preview = previewLine,
            content = res.content,
            warning = validation.reason
        )
    }

    /** How many upcoming songs get their lyric-source results preloaded. */
    private val PRELOAD_COUNT = 10
    private val preloadSemaphore = Semaphore(3)
    private val preloadInFlight: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** Word-by-word first, then line-synced, then plain. */
    private fun typeRank(type: LyricsType): Int = when (type) {
        LyricsType.WORD_BY_WORD -> 0
        LyricsType.SYNCED -> 1
        LyricsType.PLAIN -> 2
    }

    /**
     * Turns raw provider results into the list shown in the sources popup, ordered by the
     * priority  Word -> Line -> Plain,  then clean (no validator warning) before warned,
     * then the user's provider order.
     */
    private fun buildCandidates(song: Song, results: List<ProviderFetchResult>): List<LyricsCandidate> {
        val order = providerConfig().order
        return results.mapNotNull { fetchRes ->
            val res = fetchRes.result ?: return@mapNotNull null
            val lines = LrcParser.parse(res.content, song.durationMs)
            val previewLine = lines.firstOrNull { it.text.isNotBlank() }?.text ?: "No preview available"
            val validation = LyricsValidator.validate(res.content, song.durationMs, res.type)
            LyricsCandidate(
                providerId = fetchRes.provider.id,
                providerName = fetchRes.provider.displayName,
                granularity = fetchRes.provider.granularity,
                type = res.type,
                lineCount = lines.size,
                preview = previewLine,
                content = res.content,
                warning = validation.reason
            )
        }.sortedWith(
            compareBy<LyricsCandidate>(
                { typeRank(it.type) },
                { if (it.warning.isNullOrBlank()) 0 else 1 },
                { order.indexOf(it.providerId).let { idx -> if (idx == -1) Int.MAX_VALUE else idx } }
            )
        )
    }

    private fun enabledOrderedProviders(): List<LyricsProvider> {
        val config = providerConfig()
        return LyricsProviderRegistry.providers
            .filter { config.enabled.contains(it.id) && it.isConfigured }
            .sortedBy { provider ->
                val idx = config.order.indexOf(provider.id)
                if (idx == -1) Int.MAX_VALUE else idx
            }
    }

    /** Instant lookup for the sources popup (null when this song has not been fetched/preloaded yet). */
    fun getCachedCandidates(song: Song): List<LyricsCandidate>? = candidatesCache[getCandidatesCacheKey(song)]

    suspend fun fetchAllCandidates(song: Song): List<LyricsCandidate> = withContext(Dispatchers.IO) {
        val cacheKey = getCandidatesCacheKey(song)
        candidatesCache[cacheKey]?.let { return@withContext it }

        val runResult = runProviders(song, enabledOrderedProviders(), overallTimeoutMs = 15_000L)
        val candidates = buildCandidates(song, runResult.allResults)

        candidatesCache[cacheKey] = candidates
        candidates
    }

    /**
     * Preloads lyric results for the next [PRELOAD_COUNT] songs:
     *  1. every enabled source is queried once and the per-source results are stored for the
     *     sources popup (so it opens instantly), ordered Word -> Line -> Plain;
     *  2. the best result (same Word > Line > Plain rule in runProviders) is cached for playback,
     *     unless the song already has synced/word lyrics.
     */
    suspend fun preloadLyrics(songs: List<Song>) = withContext(Dispatchers.IO) {
        checkConfigChange()
        val semaphore = preloadSemaphore // gentle on the providers: 3 songs in flight at once (shared across calls)

        songs.take(PRELOAD_COUNT).map { song ->
            async {
                if (!isActive) return@async

                val candidatesKey = getCandidatesCacheKey(song)
                if (candidatesCache.containsKey(candidatesKey)) return@async
                if (!preloadInFlight.add(candidatesKey)) return@async // already being preloaded by another call

                try {
                semaphore.withPermit {
                    if (candidatesCache.containsKey(candidatesKey)) return@withPermit

                    val orderedProviders = enabledOrderedProviders()
                    if (orderedProviders.isEmpty()) return@withPermit

                    Log.d(TAG, "Preloading lyric sources for ${song.title}...")
                    val runResult = runProviders(song, orderedProviders, overallTimeoutMs = 15_000L)

                    // 1) per-source results for the popup
                    val anyHit = runResult.allResults.any { it.result != null }
                    if (anyHit || !runResult.hasTransientError) {
                        candidatesCache[candidatesKey] = buildCandidates(song, runResult.allResults)
                    }

                    // 2) best result for playback, only when the song has nothing better already
                    val memCached = cache[song.id]
                    val dbType = lyricsDao.getLyrics(song.id)?.let { determineType(it.lyrics) } ?: LyricsType.PLAIN
                    val metaType = song.lyrics?.takeIf { it.isNotBlank() }?.let { determineType(it) } ?: LyricsType.PLAIN
                    fun LyricsType.isTimedType() = this == LyricsType.WORD_BY_WORD || this == LyricsType.SYNCED
                    val alreadyHasTimed = (memCached?.type?.isTimedType() == true) || dbType.isTimedType() || metaType.isTimedType()

                    val finalRes = runResult.bestResult
                    if (finalRes != null) {
                        if (!alreadyHasTimed) {
                            val providerId = runResult.providerId
                            val validation = runResult.bestValidation
                                ?: LyricsValidator.validate(finalRes.content, song.durationMs, finalRes.type)

                            val res = LyricsLoadResult(
                                lines = LrcParser.parse(finalRes.content, song.durationMs),
                                source = LyricsSource.ONLINE,
                                type = finalRes.type,
                                rawContent = finalRes.content,
                                syncOffset = 0L,
                                score = finalRes.score,
                                providerId = providerId
                            )
                            cache[song.id] = res
                            saveToDbIfBetter(song.id, res, providerId, validation)

                            val provider = LyricsProviderRegistry.providers.find { it.id == providerId }
                            val isExperimental = provider?.experimental == true
                            if (!isExperimental && validation.isValid && validation.penalty == 0.0 &&
                                finalRes.score >= 0.85 && song.lyrics.isNullOrBlank()
                            ) {
                                embeddedSource.saveLyrics(song.uri, finalRes.content)
                                songDao.updateLyrics(song.id, finalRes.content)
                            }
                        }
                    } else if (!runResult.hasTransientError) {
                        notFoundCache[song.id] = System.currentTimeMillis()
                    }

                    delay(150)
                }
                } finally {
                    preloadInFlight.remove(candidatesKey)
                }
            }
        }.forEach { it.await() }
    }

    private suspend fun saveToDbIfBetter(
        songId: String,
        newResult: LyricsLoadResult,
        providerId: String? = null,
        validation: ValidationResult? = null
    ) {
        val existing = lyricsDao.getLyrics(songId)
        val existingType = existing?.let { determineType(it.lyrics) } ?: LyricsType.PLAIN

        val shouldUpdate = existing == null ||
                (newResult.type == LyricsType.WORD_BY_WORD && existingType != LyricsType.WORD_BY_WORD) ||
                (newResult.type == LyricsType.SYNCED && existingType == LyricsType.PLAIN)

        if (shouldUpdate) {
            newResult.rawContent?.let {
                lyricsDao.insertLyrics(
                    LyricsEntity(
                        songId = songId,
                        lyrics = it,
                        syncOffset = newResult.syncOffset,
                        timestamp = System.currentTimeMillis(),
                        providerId = providerId ?: newResult.providerId,
                        fetchedAt = System.currentTimeMillis(),
                        isValidated = (validation?.isValid == true && validation.penalty == 0.0),
                        durationMs = null
                    )
                )
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
