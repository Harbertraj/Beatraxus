package com.beatraxus.app.engine

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/** The model could not process a chunk (the message is meant for the user). */
class StemSeparationException(message: String) : Exception(message)

/**
 * Chunked overlap-add shared by every stem engine (SCNet and HTDemucs): cut the song into model-sized chunks that
 * start [hopSamples] apart, run [inferChunk] on each, cross-fade the overlaps with a window and normalise by the
 * summed window weights. It knows nothing about Android or ONNX, only about [stemCount] stems of stereo float audio.
 *
 * Audio files are interleaved stereo 32-bit float in native byte order (what [StemCacheStore.Reader] reads).
 */
object StemOverlapAdd {

    /** Decoded audio is not trusted: one NaN in a chunk would poison the whole model run. */
    const val INPUT_LIMIT = 1e3f

    /** Number of chunks the loop runs for [totalFrames] frames. */
    fun chunkCount(totalFrames: Long, segmentSamples: Int, hopSamples: Int): Int {
        require(segmentSamples > 0 && hopSamples in 1..segmentSamples)
        if (totalFrames <= 0L) return 0
        if (totalFrames <= segmentSamples) return 1
        val rest = totalFrames - segmentSamples
        return 1 + ((rest + hopSamples - 1) / hopSamples).toInt()
    }

    /** sin^2 window: smooth, never exactly 0 (so the weight sum is never 0 inside a chunk). */
    fun hannWindow(n: Int): FloatArray = FloatArray(n) { i ->
        sin(PI * (i + 0.5) / n).let { (it * it).toFloat() }
    }

    /** Linear fade in and out over [fade] samples, 1 in the middle, never exactly 0. Good for waveform models. */
    fun trapezoidWindow(n: Int, fade: Int): FloatArray {
        val f = fade.coerceIn(1, n / 2).toFloat()
        return FloatArray(n) { i ->
            val up = (i + 0.5f) / f
            val down = (n - i - 0.5f) / f
            min(1f, min(up, down))
        }
    }

    /** Progress after a chunk: [chunk] of [totalChunks] chunks are done. */
    class Progress(val chunk: Int, val totalChunks: Int, val fraction: Float)

    /**
     * Separates [inputFile] (interleaved stereo float, [totalFrames] frames) into [outputFiles], one per stem, with
     * constant memory: only the overlap between two chunks is held. Stem files are rewritten from scratch.
     *
     * @param inferChunk gets [2][segmentSamples] (zero padded at the end of the song) and returns
     *   [stemCount][2][segmentSamples], or null when the model failed. The returned arrays may be reused by the engine.
     * @param errorMessage asked for the reason when [inferChunk] returns null.
     * @throws CancellationException when [isCancelled] turns true between chunks.
     * @throws StemSeparationException when a chunk fails.
     * @return the number of chunks that ran.
     */
    fun separateFile(
        inputFile: File,
        totalFrames: Long,
        outputFiles: List<File>,
        segmentSamples: Int,
        hopSamples: Int,
        stemCount: Int = outputFiles.size,
        window: FloatArray = hannWindow(segmentSamples),
        isCancelled: () -> Boolean = { false },
        onProgress: (Progress) -> Unit = {},
        errorMessage: () -> String? = { null },
        inferChunk: (inChunk: Array<FloatArray>, segmentSamples: Int) -> Array<Array<FloatArray>>?
    ): Int {
        require(segmentSamples > 0 && hopSamples in 1..segmentSamples) { "bad segment/hop $segmentSamples/$hopSamples" }
        require(stemCount > 0 && outputFiles.size == stemCount) { "need $stemCount output files, got ${outputFiles.size}" }
        require(window.size >= segmentSamples) { "window shorter than the segment" }
        if (totalFrames <= 0L) return 0

        val bufferCap = segmentSamples + hopSamples
        val totalChunks = chunkCount(totalFrames, segmentSamples, hopSamples)

        val outAccum = Array(stemCount) { Array(2) { FloatArray(bufferCap) } }
        val weightAccum = FloatArray(bufferCap)
        val inChunk = Array(2) { FloatArray(segmentSamples) }
        val inByteBuf = ByteBuffer.allocateDirect(segmentSamples * 8).order(ByteOrder.nativeOrder())
        val outByteBuf = ByteBuffer.allocateDirect(bufferCap * 8).order(ByteOrder.nativeOrder())

        val stemRafs = arrayOfNulls<RandomAccessFile>(stemCount)
        try {
            for (s in 0 until stemCount) {
                stemRafs[s] = RandomAccessFile(outputFiles[s], "rw").also { it.setLength(0) }
            }

            var baseFrame = 0L
            var framePos = 0L
            var chunks = 0

            RandomAccessFile(inputFile, "r").use { inRaf ->
                val inChannel = inRaf.channel

                while (framePos < totalFrames) {
                    if (isCancelled()) throw CancellationException("cancelled")

                    val framesInChunk = min(segmentSamples.toLong(), totalFrames - framePos).toInt()

                    inByteBuf.clear()
                    inByteBuf.limit(framesInChunk * 8)
                    inChannel.position(framePos * 8)
                    while (inByteBuf.hasRemaining()) if (inChannel.read(inByteBuf) < 0) break
                    inByteBuf.flip()
                    val inFloats = inByteBuf.asFloatBuffer()
                    val readFrames = inFloats.remaining() / 2
                    for (i in 0 until readFrames) {
                        inChunk[0][i] = inFloats.get()
                        inChunk[1][i] = inFloats.get()
                    }
                    java.util.Arrays.fill(inChunk[0], readFrames, segmentSamples, 0f)
                    java.util.Arrays.fill(inChunk[1], readFrames, segmentSamples, 0f)
                    StemDsp.sanitize(inChunk[0], 0, readFrames, INPUT_LIMIT)
                    StemDsp.sanitize(inChunk[1], 0, readFrames, INPUT_LIMIT)

                    val chunkStems = inferChunk(inChunk, segmentSamples)
                        ?: throw StemSeparationException(errorMessage() ?: "Inference chunk failed")
                    chunks++

                    for (s in 0 until stemCount) {
                        for (ch in 0..1) {
                            val stemCh = chunkStems[s][ch]
                            val outCh = outAccum[s][ch]
                            for (i in 0 until framesInChunk) {
                                val rel = (framePos + i - baseFrame).toInt()
                                outCh[rel] += stemCh[i] * window[i]
                            }
                        }
                    }
                    for (i in 0 until framesInChunk) {
                        weightAccum[(framePos + i - baseFrame).toInt()] += window[i]
                    }

                    // The last chunk reaches the end of the song: another one would only re-run the tail.
                    val isLast = framePos + segmentSamples >= totalFrames
                    val finishedUntil = if (isLast) totalFrames else framePos + hopSamples
                    val numFlushed = (finishedUntil - baseFrame).toInt()

                    if (numFlushed > 0) {
                        for (s in 0 until stemCount) {
                            outByteBuf.clear()
                            val outFloats = outByteBuf.asFloatBuffer()
                            for (i in 0 until numFlushed) {
                                val w = if (weightAccum[i] > 1e-30f) weightAccum[i] else 1.0f
                                val invW = 1.0f / w
                                outFloats.put(outAccum[s][0][i] * invW)
                                outFloats.put(outAccum[s][1][i] * invW)
                            }
                            outByteBuf.position(0)
                            outByteBuf.limit(numFlushed * 8)
                            val chnl = stemRafs[s]!!.channel
                            chnl.position(baseFrame * 8)
                            while (outByteBuf.hasRemaining()) chnl.write(outByteBuf)
                        }

                        // Shift the accumulators left; what is left is the start of the next chunk's overlap.
                        val remaining = bufferCap - numFlushed
                        for (s in 0 until stemCount) {
                            for (ch in 0..1) {
                                System.arraycopy(outAccum[s][ch], numFlushed, outAccum[s][ch], 0, remaining)
                                outAccum[s][ch].fill(0f, remaining, bufferCap)
                            }
                        }
                        System.arraycopy(weightAccum, numFlushed, weightAccum, 0, remaining)
                        weightAccum.fill(0f, remaining, bufferCap)
                        baseFrame = finishedUntil
                    }

                    onProgress(Progress(chunks, totalChunks, (finishedUntil.toFloat() / totalFrames).coerceIn(0f, 1f)))
                    if (isLast) break
                    framePos += hopSamples
                }
            }
            return chunks
        } finally {
            for (raf in stemRafs) {
                try { raf?.close() } catch (_: Throwable) {}
            }
        }
    }

    /**
     * The same overlap-add on audio held in memory ([inputPcm] interleaved stereo float). Returns one interleaved
     * array per stem, or null if cancelled or a chunk failed. For tests and short clips; songs use [separateFile].
     */
    fun processInMemory(
        inputPcm: FloatArray,
        stemCount: Int,
        segmentSamples: Int,
        hopSamples: Int,
        window: FloatArray = hannWindow(segmentSamples),
        progressCallback: ((Float) -> Unit)? = null,
        isCancelled: (() -> Boolean)? = null,
        processChunk: (inChunk: Array<FloatArray>, numFrames: Int) -> Array<Array<FloatArray>>?
    ): Array<FloatArray>? {
        val totalFrames = inputPcm.size / 2
        if (totalFrames == 0) return null
        require(segmentSamples > 0 && hopSamples in 1..segmentSamples && stemCount > 0)

        val outAccum = Array(stemCount) { Array(2) { FloatArray(totalFrames) } }
        val weightAccum = FloatArray(totalFrames)
        val inChunk = Array(2) { FloatArray(segmentSamples) }

        var framePos = 0
        while (framePos < totalFrames) {
            if (isCancelled?.invoke() == true) return null

            val framesInChunk = min(segmentSamples, totalFrames - framePos)
            java.util.Arrays.fill(inChunk[0], 0f)
            java.util.Arrays.fill(inChunk[1], 0f)
            for (i in 0 until framesInChunk) {
                val idx = (framePos + i) * 2
                inChunk[0][i] = inputPcm[idx]
                inChunk[1][i] = inputPcm[idx + 1]
            }

            val chunkStems = processChunk(inChunk, segmentSamples) ?: return null

            for (s in 0 until stemCount) {
                for (ch in 0..1) {
                    val stemCh = chunkStems[s][ch]
                    val outCh = outAccum[s][ch]
                    for (i in 0 until framesInChunk) outCh[framePos + i] += stemCh[i] * window[i]
                }
            }
            for (i in 0 until framesInChunk) weightAccum[framePos + i] += window[i]

            if (framePos + segmentSamples >= totalFrames) break
            framePos += hopSamples
            progressCallback?.invoke((framePos.toFloat() / totalFrames).coerceIn(0f, 1f))
        }

        val result = Array(stemCount) { FloatArray(totalFrames * 2) }
        for (f in 0 until totalFrames) {
            val w = if (weightAccum[f] > 1e-30f) weightAccum[f] else 1.0f
            val invW = 1.0f / w
            for (s in 0 until stemCount) {
                result[s][f * 2] = outAccum[s][0][f] * invW
                result[s][f * 2 + 1] = outAccum[s][1][f] * invW
            }
        }
        return result
    }
}
