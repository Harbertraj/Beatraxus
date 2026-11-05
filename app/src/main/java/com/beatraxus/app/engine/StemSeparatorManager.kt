package com.beatraxus.app.engine

import android.content.Context
import android.util.Log
import com.beatraxus.app.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.PI
import kotlin.math.sin

sealed interface StemSeparationState {
    object Idle : StemSeparationState
    data class Separating(val songId: String, val progress: Float) : StemSeparationState
    data class Ready(val songId: String) : StemSeparationState
    data class Failed(val songId: String, val errorText: String) : StemSeparationState
}

object StemSeparatorManager {
    private const val TAG = "StemSeparatorManager"

    private val _state = MutableStateFlow<StemSeparationState>(StemSeparationState.Idle)
    val state: StateFlow<StemSeparationState> = _state.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.Default)
    private var activeJob: Job? = null
    private var activeSeparator: StemSeparator? = null
    private var activeSongId: String? = null

    fun getStatusForSong(context: Context, songId: String?): StemSeparationState {
        if (songId == null) return StemSeparationState.Idle
        if (StemCache.isStemsCached(context, songId)) return StemSeparationState.Ready(songId)

        val current = _state.value
        return when (current) {
            is StemSeparationState.Separating -> if (current.songId == songId) current else StemSeparationState.Idle
            is StemSeparationState.Ready -> if (current.songId == songId) current else StemSeparationState.Idle
            is StemSeparationState.Failed -> if (current.songId == songId) current else StemSeparationState.Idle
            else -> StemSeparationState.Idle
        }
    }

    @Synchronized
    fun requestSeparation(context: Context, song: Song, force: Boolean = false) {
        val songId = song.id
        val appContext = context.applicationContext

        if (StemCache.isStemsCached(appContext, songId)) {
            _state.value = StemSeparationState.Ready(songId)
            return
        }

        if (activeSongId == songId && activeJob?.isActive == true) {
            return
        }

        if (!force && _state.value is StemSeparationState.Failed && (_state.value as StemSeparationState.Failed).songId == songId) {
            return
        }

        cancelSeparation()
        activeSongId = songId

        activeJob = scope.launch(start = CoroutineStart.LAZY) {
            val me = coroutineContext.job
            // Only the job that is still the active one may publish state (cancelled / replaced jobs stay silent).
            fun publish(st: StemSeparationState) {
                synchronized(StemSeparatorManager) { if (activeJob === me) _state.value = st }
            }
            publish(StemSeparationState.Separating(songId, 0f))

            val separator = StemSeparator(appContext)
            synchronized(StemSeparatorManager) { if (activeJob === me) activeSeparator = separator }

            val tempPcmFile = File(appContext.cacheDir, "temp_decode_$songId.pcm")

            try {
                StemCache.deleteStems(appContext, songId)

                // Step 1: Decode song audio to 44.1 kHz stereo float temp file
                val totalFrames = StemAudioDecoder.decodeToStereoFloat44100File(
                    appContext,
                    song,
                    tempPcmFile,
                    isCancelled = { me.isCancelled }
                )

                if (totalFrames == null || totalFrames <= 0L || me.isCancelled) {
                    if (!me.isCancelled) {
                        publish(StemSeparationState.Failed(songId, "Failed to decode song audio"))
                    } else {
                        publish(StemSeparationState.Idle)
                    }
                    StemCache.deleteStems(appContext, songId)
                    return@launch
                }

                if (!separator.initialize()) {
                    publish(StemSeparationState.Failed(songId, "Failed to initialize separator model"))
                    StemCache.deleteStems(appContext, songId)
                    return@launch
                }

                // Step 2: Streaming separation into cache files
                val chunkSize = 485100
                val hopSize = 121275
                val bufferCap = chunkSize + hopSize

                val outAccum = Array(4) { Array(2) { FloatArray(bufferCap) } }
                val weightAccum = FloatArray(bufferCap)
                val hann = FloatArray(chunkSize) { i ->
                    sin(PI * (i + 0.5) / chunkSize).let { (it * it).toFloat() }
                }

                val dir = StemCache.getStemsDir(appContext, songId)
                val stemRafs = Array(4) { i ->
                    RandomAccessFile(File(dir, StemCache.STEM_FILENAMES[i]), "rw")
                }

                var baseFrame = 0L
                var framePos = 0L

                try {
                    RandomAccessFile(tempPcmFile, "r").use { tempRaf ->
                        val tempChannel = tempRaf.channel
                        val inByteBuf = ByteBuffer.allocateDirect(chunkSize * 8).order(ByteOrder.nativeOrder())

                        while (framePos < totalFrames) {
                            if (me.isCancelled) throw CancellationException()

                            val framesInChunk = minOf(chunkSize.toLong(), totalFrames - framePos).toInt()

                            inByteBuf.clear()
                            inByteBuf.limit(framesInChunk * 8)
                            tempChannel.position(framePos * 8)
                            tempChannel.read(inByteBuf)
                            inByteBuf.rewind()
                            val inFloats = inByteBuf.asFloatBuffer()

                            val inChunk = Array(2) { FloatArray(chunkSize) }
                            for (i in 0 until framesInChunk) {
                                inChunk[0][i] = inFloats.get()
                                inChunk[1][i] = inFloats.get()
                            }

                            val chunkStems = separator.inferChunk(inChunk, chunkSize)
                                ?: throw Exception("Inference chunk failed")

                            // Accumulate
                            for (s in 0 until 4) {
                                for (ch in 0..1) {
                                    val stemCh = chunkStems[s][ch]
                                    val outCh = outAccum[s][ch]
                                    for (i in 0 until framesInChunk) {
                                        val targetFrame = framePos + i
                                        val relIdx = (targetFrame - baseFrame).toInt()
                                        val w = hann[i]
                                        outCh[relIdx] += stemCh[i] * w
                                    }
                                }
                            }
                            for (i in 0 until framesInChunk) {
                                val targetFrame = framePos + i
                                val relIdx = (targetFrame - baseFrame).toInt()
                                weightAccum[relIdx] += hann[i]
                            }

                            val nextFramePos = framePos + hopSize
                            val finishedUntil = if (nextFramePos < totalFrames && framesInChunk == chunkSize) nextFramePos else totalFrames
                            val numFlushed = (finishedUntil - baseFrame).toInt()

                            if (numFlushed > 0) {
                                val outByteBuf = ByteBuffer.allocateDirect(numFlushed * 8).order(ByteOrder.nativeOrder())
                                for (s in 0 until 4) {
                                    outByteBuf.clear()
                                    val outFloats = outByteBuf.asFloatBuffer()
                                    for (i in 0 until numFlushed) {
                                        val w = if (weightAccum[i] > 1e-30f) weightAccum[i] else 1.0f
                                        val invW = 1.0f / w
                                        outFloats.put(outAccum[s][0][i] * invW)
                                        outFloats.put(outAccum[s][1][i] * invW)
                                    }
                                    outByteBuf.rewind()
                                    outByteBuf.limit(numFlushed * 8)

                                    val chnl = stemRafs[s].channel
                                    chnl.position(baseFrame * 8)
                                    chnl.write(outByteBuf)
                                }

                                // Shift accumulators left
                                val remaining = bufferCap - numFlushed
                                for (s in 0 until 4) {
                                    for (ch in 0..1) {
                                        System.arraycopy(outAccum[s][ch], numFlushed, outAccum[s][ch], 0, remaining)
                                        outAccum[s][ch].fill(0f, remaining, bufferCap)
                                    }
                                }
                                System.arraycopy(weightAccum, numFlushed, weightAccum, 0, remaining)
                                weightAccum.fill(0f, remaining, bufferCap)

                                baseFrame = finishedUntil
                            }

                            // Final region flushed: stop (continuing would index before baseFrame).
                            if (finishedUntil >= totalFrames) break

                            framePos += hopSize
                            val pct = (framePos.toFloat() / totalFrames).coerceIn(0f, 1f)
                            publish(StemSeparationState.Separating(songId, pct))
                        }
                    }
                } finally {
                    for (raf in stemRafs) {
                        try { raf.close() } catch (_: Throwable) {}
                    }
                }

                StemCache.markStemsDone(appContext, songId)
                publish(StemSeparationState.Ready(songId))
                Log.i(TAG, "Successfully separated and cached 4 stems for song $songId")

            } catch (e: CancellationException) {
                StemCache.deleteStems(appContext, songId)
                publish(StemSeparationState.Idle)
            } catch (e: Exception) {
                Log.e(TAG, "Separation error: ${e.message}", e)
                StemCache.deleteStems(appContext, songId)
                publish(StemSeparationState.Failed(songId, e.message ?: "Separation error"))
            } finally {
                if (tempPcmFile.exists()) tempPcmFile.delete()
                separator.release()
                synchronized(StemSeparatorManager) {
                    if (activeSeparator == separator) activeSeparator = null
                    if (activeJob === me) {
                        activeJob = null
                        activeSongId = null
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
        val old = activeSeparator
        if (old != null) scope.launch(Dispatchers.IO) { old.release() }
        activeSeparator = null
        activeSongId = null
        _state.value = StemSeparationState.Idle
    }
}
