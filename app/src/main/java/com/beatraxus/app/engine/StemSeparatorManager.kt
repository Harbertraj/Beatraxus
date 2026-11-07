package com.beatraxus.app.engine

import android.app.ActivityManager
import android.content.Context
import android.os.Process
import android.util.Log
import com.beatraxus.app.service.StemSeparationService
import com.beatraxus.app.model.Song
import com.beatraxus.app.model.StemQuality
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext

sealed interface StemSeparationState {
    object Idle : StemSeparationState

    /**
     * [chunk] of [totalChunks] model chunks are done. [etaMs] is the remaining time extrapolated from the chunks
     * already run on this device (null until two chunks are done); it is never a built-in guess.
     */
    data class Separating(
        val songId: String,
        val progress: Float,
        val quality: StemQuality = StemQuality.FAST,
        val chunk: Int = 0,
        val totalChunks: Int = 0,
        val etaMs: Long? = null
    ) : StemSeparationState

    data class Ready(val songId: String, val quality: StemQuality = StemQuality.FAST) : StemSeparationState
    data class Failed(val songId: String, val errorText: String, val quality: StemQuality = StemQuality.FAST) : StemSeparationState
}

object StemSeparatorManager {
    private const val TAG = "StemSeparatorManager"

    private val _state = MutableStateFlow<StemSeparationState>(StemSeparationState.Idle)
    val state: StateFlow<StemSeparationState> = _state.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.Default)
    private var activeJob: Job? = null
    private var activeEngine: StemEngine? = null
    private var activeSongId: String? = null
    private var activeQuality: StemQuality? = null

    private fun stateQuality(st: StemSeparationState): StemQuality? = when (st) {
        is StemSeparationState.Separating -> st.quality
        is StemSeparationState.Ready -> st.quality
        is StemSeparationState.Failed -> st.quality
        else -> null
    }

    private fun stateSong(st: StemSeparationState): String? = when (st) {
        is StemSeparationState.Separating -> st.songId
        is StemSeparationState.Ready -> st.songId
        is StemSeparationState.Failed -> st.songId
        else -> null
    }

    /** Status of [songId] for [quality]: cached stems win, otherwise the live state if it is about this song and quality. */
    fun getStatusForSong(context: Context, songId: String?, quality: StemQuality): StemSeparationState {
        if (songId == null) return StemSeparationState.Idle
        if (StemCache.isStemsCached(context, songId, quality)) return StemSeparationState.Ready(songId, quality)
        val current = _state.value
        return if (stateSong(current) == songId && stateQuality(current) == quality) current else StemSeparationState.Idle
    }

    /** Free RAM the system reports right now (a heuristic: it counts what could be reclaimed only partly). */
    private fun availableRamBytes(context: Context): Long? = try {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.availMem
    } catch (_: Exception) {
        null
    }

    private class MemSnapshot(val availBytes: Long, val totalBytes: Long, val thresholdBytes: Long, val low: Boolean)

    private fun memSnapshot(context: Context): MemSnapshot? = try {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        MemSnapshot(mi.availMem, mi.totalMem, mi.threshold, mi.lowMemory)
    } catch (_: Exception) {
        null
    }

    /** Resident memory of this process in MB (from /proc), or -1. For the log only. */
    private fun rssMb(): Long = try {
        File("/proc/self/status").readLines().firstOrNull { it.startsWith("VmRSS:") }
            ?.filter { it.isDigit() }?.toLongOrNull()?.div(1024) ?: -1L
    } catch (_: Exception) {
        -1L
    }

    /**
     * Runs [block] on the calling thread at a priority just below normal. Separation is pure background work, and the
     * audio thread must never wait for it. Nice 9 stays on the big cores (10 and up would move the thread to the
     * background cgroup, i.e. the slow cores, which would make it slower). The native threads ONNX Runtime starts
     * while the block runs inherit this priority. Only for blocks that do not suspend (the thread must not change).
     */
    private inline fun <T> lowPriority(block: () -> T): T {
        val previous = try { Process.getThreadPriority(Process.myTid()) } catch (_: Throwable) { 0 }
        try { Process.setThreadPriority(9) } catch (_: Throwable) {}
        try {
            return block()
        } finally {
            try { Process.setThreadPriority(previous) } catch (_: Throwable) {}
        }
    }

    private var checkedPreviousRun = false

    /**
     * What the audio stage may read while a song is still being separated. [coverage] grows after every flushed part;
     * [generation] changes with every run, so a reader opened on files of an older run is dropped.
     */
    class LiveStems(val songId: String, val quality: StemQuality, val generation: Long, val coverage: StemCoverage)

    @Volatile private var live: LiveStems? = null
    private var generationCounter = 0L

    /** Lock-free snapshot for the audio thread: the live coverage of this song + quality, or null. */
    fun liveFor(songId: String, quality: StemQuality): LiveStems? {
        val l = live ?: return null
        return if (l.songId == songId && l.quality == quality) l else null
    }

    /** Safety lead: separation starts this far ahead of the playhead (the part right under it is gone by then). */
    private const val START_LEAD_MS = 1_500L

    // A loaded Fast engine is kept for a short while after a song, so the next song does not pay the model load again.
    private const val WARM_ENGINE_MS = 90_000L
    private var warmEngine: StemEngine? = null
    private var warmQuality: StemQuality? = null
    private var warmReleaseJob: Job? = null

    @Synchronized
    private fun takeWarmEngine(quality: StemQuality): StemEngine? {
        val e = warmEngine
        if (e == null || warmQuality != quality) return null
        warmReleaseJob?.cancel(); warmReleaseJob = null
        warmEngine = null; warmQuality = null
        return e
    }

    @Synchronized
    private fun stashWarmEngine(engine: StemEngine, quality: StemQuality) {
        warmReleaseJob?.cancel()
        val old = warmEngine
        if (old != null && old !== engine) scope.launch(Dispatchers.IO) { old.release() }
        warmEngine = engine; warmQuality = quality
        warmReleaseJob = scope.launch {
            kotlinx.coroutines.delay(WARM_ENGINE_MS)
            val drop = synchronized(StemSeparatorManager) {
                if (warmEngine === engine) { warmEngine = null; warmQuality = null; true } else false
            }
            if (drop) engine.release()
        }
    }

    @Synchronized
    fun requestSeparation(
        context: Context,
        song: Song,
        quality: StemQuality,
        force: Boolean = false,
        /** Playback position right now; separation starts there (then wraps to the beginning). Null = from 0. */
        positionProvider: (() -> Long)? = null
    ) {
        val songId = song.id
        val appContext = context.applicationContext

        if (StemCache.isStemsCached(appContext, songId, quality)) {
            StemCache.store(appContext).touch(songId, quality)
            _state.value = StemSeparationState.Ready(songId, quality)
            return
        }

        if (activeSongId == songId && activeQuality == quality && activeJob?.isActive == true) return

        // Did the previous process die in the middle of a separation (Android's low-memory killer leaves no trace)?
        val marker = StemRunMarker(File(appContext.cacheDir, "stem_run.marker"))
        if (!checkedPreviousRun) {
            checkedPreviousRun = true
            val unfinished = marker.consumeUnfinished()
            if (unfinished != null && unfinished.quality == StemQuality.MAX) {
                val lvl = StemLoadProfile.escalate(appContext)
                Log.w(TAG, "Max separation was killed last time; load level is now $lvl")
            }
            if (unfinished != null && unfinished.quality == quality && !force) {
                Log.w(TAG, "Previous ${unfinished.quality.name} separation of ${unfinished.songId} was cut short (process killed)")
                _state.value = StemSeparationState.Failed(
                    songId,
                    if (quality == StemQuality.MAX && StemLoadProfile.isMaxBlocked(StemLoadProfile.level(appContext)))
                        "Max separation was stopped by Android on this phone more than once (not enough memory). Use Fast."
                    else
                        "The last ${quality.label} separation was stopped by Android, most likely because the phone ran low on memory. " +
                            "Tap Retry to try again with a lighter, slower load, or switch to Fast.",
                    quality
                )
                return
            }
        }

        val cur = _state.value
        if (!force && cur is StemSeparationState.Failed && cur.songId == songId && cur.quality == quality) return

        if (quality == StemQuality.MAX && StemLoadProfile.isMaxBlocked(StemLoadProfile.level(appContext))) {
            _state.value = StemSeparationState.Failed(
                songId,
                "Max separation was stopped by Android on this phone more than once (not enough memory). Use Fast.",
                quality
            )
            return
        }

        cancelSeparation()
        activeSongId = songId
        activeQuality = quality
        _state.value = StemSeparationState.Separating(songId, 0f, quality)

        activeJob = scope.launch(start = CoroutineStart.LAZY) {
            val me = coroutineContext.job
            // Only the job that is still the active one may publish state (cancelled / replaced jobs stay silent).
            fun publish(st: StemSeparationState) {
                synchronized(StemSeparatorManager) { if (activeJob === me) _state.value = st }
            }
            // A cancelled job must not delete the stems of the job that replaced it.
            fun ownsSong(): Boolean = synchronized(StemSeparatorManager) { activeJob === me || activeJob == null }
            fun cleanup() {
                if (ownsSong()) {
                    synchronized(StemSeparatorManager) { if (live?.songId == songId && live?.quality == quality) live = null }
                    StemCache.deleteStems(appContext, songId, quality)
                }
            }

            val runToken = marker.begin(songId, quality)
            val engine = takeWarmEngine(quality) ?: StemEngines.create(appContext, quality)
            var finishedOk = false
            synchronized(StemSeparatorManager) { if (activeJob === me) activeEngine = engine }

            val tempPcmFile = File(appContext.cacheDir, "temp_decode_${quality.folderName}_$songId.pcm")

            try {
                if (!StemEngines.isModelInstalled(appContext, quality)) {
                    publish(StemSeparationState.Failed(songId, "The ${quality.label} separation model is not installed", quality))
                    return@launch
                }

                // RAM guard: the Max model is big, and a native out-of-memory kill cannot be caught.
                val need = engine.minFreeRamBytes
                val avail = availableRamBytes(appContext)
                memSnapshot(appContext)?.let {
                    Log.i(TAG, "memory before start: available ${it.availBytes / 1_000_000} MB of ${it.totalBytes / 1_000_000} MB, " +
                        "system threshold ${it.thresholdBytes / 1_000_000} MB, low=${it.low}, app RSS ${rssMb()} MB")
                }
                if (need > 0 && avail != null && avail < need) {
                    val msg = "Not enough free memory for ${quality.label} (needs about ${need / 1_000_000} MB free, " +
                        "${avail / 1_000_000} MB available). Close other apps or use Fast."
                    Log.w(TAG, msg)
                    publish(StemSeparationState.Failed(songId, msg, quality))
                    return@launch
                }

                StemCache.purgeOutdated(appContext)
                StemCache.deleteStems(appContext, songId, quality)
                val generation = synchronized(StemSeparatorManager) { ++generationCounter }
                synchronized(StemSeparatorManager) {
                    if (activeJob === me) live = LiveStems(songId, quality, generation, StemCoverage.EMPTY)
                }

                // Load the model while the song decodes: the two do not need each other, and loading is slow.
                val initDeferred = async(Dispatchers.Default) { lowPriority { engine.initialize() } }

                // Step 1: decode the song to a 44.1 kHz stereo float temp file.
                val tDecode = System.nanoTime()
                val totalFrames = StemAudioDecoder.decodeToStereoFloat44100File(
                    appContext, song, tempPcmFile, isCancelled = { me.isCancelled }
                )
                if (totalFrames == null || totalFrames <= 0L || me.isCancelled) {
                    initDeferred.cancel()
                    publish(if (me.isCancelled) StemSeparationState.Idle else StemSeparationState.Failed(songId, "Failed to decode song audio (${song.format})", quality))
                    cleanup()
                    return@launch
                }
                Log.i(TAG, "Decoded $songId: ${totalFrames / StemSeparator.SAMPLE_RATE} s in ${(System.nanoTime() - tDecode) / 1_000_000} ms")

                // Disk guard: stems of this song plus the cache size limit.
                val stemBytes = StemCache.stemBytes(totalFrames, engine.stemCount)
                StemCache.enforceLimit(appContext, stemBytes, songId, quality)
                val dir = StemCache.getStemsDir(appContext, songId, quality)
                if (dir.usableSpace < stemBytes + 100L * 1024 * 1024) {
                    initDeferred.cancel()
                    publish(StemSeparationState.Failed(songId, "Not enough free storage for ${engine.stemCount} stems (${stemBytes / 1_000_000} MB)", quality))
                    cleanup()
                    return@launch
                }

                val tInit = System.nanoTime()
                if (!initDeferred.await()) {
                    publish(StemSeparationState.Failed(songId, engine.lastError ?: "Failed to initialize ${quality.label} model", quality))
                    cleanup()
                    return@launch
                }

                Log.i(TAG, "Model ready (waited ${(System.nanoTime() - tInit) / 1_000_000} ms after decoding), app RSS ${rssMb()} MB")

                // Step 2: streaming separation into the cache files (shared loop for every engine).
                // Start where the listener is now (they moved on while the song decoded and the model loaded), then wrap.
                val posMs: Long = (try { positionProvider?.invoke() } catch (_: Throwable) { null }) ?: 0L
                val startFrame = ((posMs + START_LEAD_MS).coerceAtLeast(0L) * StemSeparator.SAMPLE_RATE) / 1000L
                val totalChunks = StemOverlapAdd.chunkCountFrom(totalFrames, startFrame, engine.segmentSamples, engine.hopSamples)
                val tStart = System.nanoTime()
                var lastChunkAt = tStart
                Log.i(TAG, "Separating $songId with ${engine.describe()}: $totalChunks chunks")

                var waits = 0
                val chunks = lowPriority { StemOverlapAdd.separateFile(
                    inputFile = tempPcmFile,
                    totalFrames = totalFrames,
                    outputFiles = StemCache.stemFiles(appContext, songId, quality),
                    segmentSamples = engine.segmentSamples,
                    hopSamples = engine.hopSamples,
                    stemCount = engine.stemCount,
                    window = engine.window,
                    isCancelled = { me.isCancelled },
                    errorMessage = { engine.lastError },
                    startFrame = startFrame,
                    onFlushed = { from, to ->
                        // Publish the new part so the audio stage can use it right away.
                        synchronized(StemSeparatorManager) {
                            val cur = live
                            if (activeJob === me && cur != null && cur.generation == generation) {
                                live = LiveStems(songId, quality, generation, cur.coverage.plus(from, to))
                            }
                        }
                    },
                    onProgress = { p ->
                        val now = System.nanoTime()
                        Log.i(TAG, "chunk ${p.chunk}/${p.totalChunks} wall ${(now - lastChunkAt) / 1_000_000} ms, app RSS ${rssMb()} MB")
                        lastChunkAt = now
                        val eta = StemBudget.etaMs((now - tStart) / 1_000_000, p.chunk, p.totalChunks)
                        publish(StemSeparationState.Separating(songId, p.fraction, quality, p.chunk, p.totalChunks, eta))
                    },
                    inferChunk = { inChunk, size ->
                        // Short of memory: wait a little for Android to free some, then stop cleanly instead of being killed.
                        while (true) {
                            val snap = memSnapshot(appContext)
                            val verdict = StemMemoryGate.decide(snap?.low ?: false, snap?.availBytes ?: Long.MAX_VALUE, waits)
                            if (verdict == StemMemoryGate.Verdict.OK) { waits = 0; break }
                            if (verdict == StemMemoryGate.Verdict.ABORT) {
                                throw StemSeparationException(
                                    "The phone ran out of free memory while separating (${(snap?.availBytes ?: 0L) / 1_000_000} MB left). " +
                                        "Close other apps or use Fast."
                                )
                            }
                            waits++
                            Log.w(TAG, "low memory (available ${(snap?.availBytes ?: 0L) / 1_000_000} MB), waiting $waits/${StemMemoryGate.MAX_WAITS}")
                            Thread.sleep(StemMemoryGate.WAIT_MS)
                            if (me.isCancelled) throw CancellationException("cancelled")
                        }
                        engine.inferChunk(inChunk, size)
                    }
                ) }
                val elapsedMs = (System.nanoTime() - tStart) / 1_000_000
                Log.i(
                    TAG,
                    "Separation of $songId (${quality.name}): $chunks chunks, ${totalFrames / StemSeparator.SAMPLE_RATE} s of audio " +
                        "in $elapsedMs ms"
                )

                StemCache.markStemsDone(appContext, songId, quality)
                synchronized(StemSeparatorManager) {
                    if (activeJob === me) live = LiveStems(songId, quality, generation, StemCoverage.FULL)
                }
                finishedOk = true
                publish(StemSeparationState.Ready(songId, quality))
                Log.i(TAG, "Successfully separated and cached ${engine.stemCount} stems for song $songId")

            } catch (e: CancellationException) {
                cleanup()
                publish(StemSeparationState.Idle)
            } catch (e: OutOfMemoryError) {
                Log.e(TAG, "Out of memory while separating $songId", e)
                cleanup()
                publish(StemSeparationState.Failed(songId, "Out of memory while separating. Close other apps or use Fast.", quality))
            } catch (e: Exception) {
                Log.e(TAG, "Separation error: ${e.message}", e)
                cleanup()
                publish(StemSeparationState.Failed(songId, e.message ?: "Separation error", quality))
            } finally {
                marker.end(runToken)
                if (tempPcmFile.exists()) tempPcmFile.delete()
                // The small Fast engine stays loaded for a moment (next song starts without a model load); Max is freed at once.
                if (finishedOk && quality == StemQuality.FAST && !me.isCancelled) stashWarmEngine(engine, quality) else engine.release()
                synchronized(StemSeparatorManager) {
                    if (activeEngine === engine) activeEngine = null
                    if (activeJob === me) {
                        activeJob = null
                        activeSongId = null
                        activeQuality = null
                    }
                }
            }
        }
        activeJob?.start()
        StemSeparationService.start(appContext)
    }

    @Synchronized
    fun cancelSeparation() {
        activeJob?.cancel()
        activeJob = null
        // release() waits for a running inference chunk: never block the caller (often the main thread) on it.
        val old = activeEngine
        if (old != null) scope.launch(Dispatchers.IO) { old.release() }
        activeEngine = null
        activeSongId = null
        activeQuality = null
        live = null
        _state.value = StemSeparationState.Idle
    }
}
