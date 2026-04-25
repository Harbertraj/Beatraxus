package com.beatflowy.app.engine

internal class FloatRingBuffer(capacitySamples: Int) {
    private val buffer = FloatArray(capacitySamples.coerceAtLeast(1))
    private val lock = Object()

    private var readIndex = 0
    private var writeIndex = 0
    private var size = 0
    private var closed = false

    fun write(source: FloatArray, sampleCount: Int) {
        var offset = 0
        var remaining = sampleCount.coerceAtMost(source.size)

        while (remaining > 0) {
            synchronized(lock) {
                while (!closed && size == buffer.size) {
                    lock.wait(20L)
                }
                if (closed) return

                val writable = minOf(remaining, buffer.size - size, buffer.size - writeIndex)
                source.copyInto(
                    destination = buffer,
                    destinationOffset = writeIndex,
                    startIndex = offset,
                    endIndex = offset + writable
                )
                writeIndex = (writeIndex + writable) % buffer.size
                size += writable
                offset += writable
                remaining -= writable
                lock.notifyAll()
            }
        }
    }

    fun read(target: FloatArray, maxSamples: Int, timeoutMs: Long = 40L): Int {
        synchronized(lock) {
            if (size == 0 && !closed) {
                lock.wait(timeoutMs)
            }
            if (size == 0) return 0

            val toRead = minOf(maxSamples, size)
            val firstChunk = minOf(toRead, buffer.size - readIndex)
            buffer.copyInto(target, 0, readIndex, readIndex + firstChunk)
            if (toRead > firstChunk) {
                buffer.copyInto(target, firstChunk, 0, toRead - firstChunk)
            }
            readIndex = (readIndex + toRead) % buffer.size
            size -= toRead
            lock.notifyAll()
            return toRead
        }
    }

    fun clear() {
        synchronized(lock) {
            readIndex = 0
            writeIndex = 0
            size = 0
            lock.notifyAll()
        }
    }

    fun close() {
        synchronized(lock) {
            closed = true
            lock.notifyAll()
        }
    }

    fun isEmpty(): Boolean = synchronized(lock) { size == 0 }
}
