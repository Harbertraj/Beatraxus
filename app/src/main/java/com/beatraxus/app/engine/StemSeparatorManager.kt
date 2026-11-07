package com.beatraxus.app.engine

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import com.beatraxus.app.model.Song
import com.beatraxus.app.model.StemQuality
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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

    @Synchronized
    fun requestSeparation(context: Context, song: Song, quality: StemQuality, force: Boolean = false) {
        val songId = song.id
        val appContext = context.applicationContext

        if (StemCache.isStemsCached(appContext, songId, quality)) {
            StemCache.store(appContext).touch(songId, quality)
            _state.value = StemSeparationState.Ready(songId, quality)
            return
        }

        if (activeSongId == songId && activeQuality == quality && activeJob?.isActive == true) return

        val cur = _state.value
        if (!force && cur is StemSeparationState.Failed && cur.songId == songId && cur.quality == quality) return

        cancelSeparation()
        activeSongId = songId
        activeQuality = quality

        activeJob = scope.launch(start = CoroutineStart.LAZY) {
            val me = coroutineContext.job
            // Only the job that is still the active one may publish state (cancelled / replaced jobs stay silent).
            fun publish(st: StemSeparationState) {
                synchronized(StemSeparatorManager) { if (activeJob === me) _state.value = st }
            }
            // A cancelled job must not delete the stems of the job that replaced it.
            fun ownsSong(): Boolean = synchronized(StemSeparatorManager) { activeJob === me || activeJob == null }
            fun cleanup() { if (ownsSong()) StemCache.deleteStems(appContext, songId, quality) }

            publish(StemSeparationState.Separating(songId, 0f, quality))

            val engine = StemEngines.create(appContext, quality)
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
                if (need > 0 && avail != null && avail < need) {
                    val msg = "Not enough free memory for ${quality.label} (needs about ${need / 1_000_000} MB free, " +
                        "${avail / 1_000_000} MB available). Close other apps or use Fast."
                    Log.w(TAG, msg)
                    publish(StemSeparationState.Failed(songId, msg, quality))
                    return@launch
                }

                StemCache.purgeOutdated(appContext)
                StemCache.deleteStems(appContext, songId, quality)

                // Step 1: decode the song to a 44.1 kHz stereo float temp file.
                val tDecode = System.nanoTime()
                val totalFrames = StemAudioDecoder.decodeToStereoFloat44100File(
                    appContext, song, tempPcmFile, isCancelled = { me.isCancelled }
                )
                if (totalFrames == null || totalFrames <= 0L || me.isCancelled) {
                    publish(if (me.isCancelled) StemSeparationState.Idle else StemSeparationState.Failed(songId, "Failed to decode song audio", quality))
                    cleanup()
                    return@launch
                }
                Log.i(TAG, "Decoded $songId: ${totalFrames / StemSeparator.SAMPLE_RATE} s in ${(System.nanoTime() - tDecode) / 1_000_000} ms")

                // Disk guard: stems of this song plus the cache size limit.
                val stemBytes = StemCache.stemBytes(totalFrames, engine.stemCount)
                StemCache.enforceLimit(appContext, stemBytes, songId, quality)
                val dir = StemCache.getStemsDir(appContext, songId, quality)
                if (dir.usableSpace < stemBytes + 100L * 1024 * 1024) {
                    publish(StemSeparationState.Failed(songId, "Not enough free storage for ${engine.stemCount} stems (${stemBytes / 1_000_000} MB)", quality))
                    cleanup()
                    return@launch
                }

                if (!engine.initialize()) {
                    publish(StemSeparationState.Failed(songId, engine.lastError ?: "Failed to initialize ${quality.label} model", quality))
                    cleanup()
                    return@launch
                }

                // Step 2: streaming separation into the cache files (shared loop for every engine).
                val totalChunks = StemOverlapAdd.chunkCount(totalFrames, engine.segmentSamples, engine.hopSamples)
                val tStart = System.nanoTime()
                var lastChunkAt = tStart
                Log.i(TAG, "Separating $songId with ${engine.describe()}: $totalChunks chunks")

                val chunks = StemOverlapAdd.separateFile(
                    inputFile = tempPcmFile,
                    totalFrames = totalFrames,
                    outputFiles = StemCache.stemFiles(appContext, songId, quality),
                    segmentSamples = engine.segmentSamples,
                    hopSamples = engine.hopSamples,
                    stemCount = engine.stemCount,
                    window = engine.window,
                    isCancelled = { me.isCancelled },
                    errorMessage = { engine.lastError },
                    onProgress = { p ->
                        val now = System.nanoTime()
                        Log.i(TAG, "chunk ${p.chunk}/${p.totalChunks} wall ${(now - lastChunkAt) / 1_000_000} ms")
                        lastChunkAt = now
                        val eta = if (p.chunk >= 2) ((now - tStart) / 1_000_000) * (p.totalChunks - p.chunk) / p.chunk else null
                        publish(StemSeparationState.Separating(songId, p.fraction, quality, p.chunk, p.totalChunks, eta))
                    },
                    inferChunk = { inChunk, size -> engine.inferChunk(inChunk, size) }
                )
                val elapsedMs = (System.nanoTime() - tStart) / 1_000_000
                Log.i(
                    TAG,
                    "Separation of $songId (${quality.name}): $chunks chunks, ${totalFrames / StemSeparator.SAMPLE_RATE} s of audio " +
                        "in $elapsedMs ms"
                )

                StemCache.markStemsDone(appContext, songId, quality)
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
                if (tempPcmFile.exists()) tempPcmFile.delete()
                engine.release()   // frees the ONNX session and its RAM as soon as the song is done
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
        _state.value = StemSeparationState.Idle
    }
}
