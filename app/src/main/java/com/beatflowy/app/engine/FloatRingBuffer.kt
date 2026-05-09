package com.beatflowy.app.engine

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

internal class FloatRingBuffer(capacitySamples: Int = DEFAULT_CAPACITY_SAMPLES) {
    private val buffer = FloatArray(calculateEffectiveCapacity(capacitySamples))
    private val lock = ReentrantLock()
    private val notEmpty = lock.newCondition()
    private val notFull = lock.newCondition()

    private var readIndex = 0
    private var writeIndex = 0
    private var size = 0
    private var closed = false

    fun write(source: FloatArray, sampleCount: Int) {
        var offset = 0
        var remaining = sampleCount.coerceAtMost(source.size)

        while (remaining > 0) {
            lock.lock()
            try {
                while (!closed && size == buffer.size) {
                    if (!notFull.await(5, TimeUnit.MILLISECONDS)) {
                        // Timeout on wait, but we stay in loop to retry until closed
                    }
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
                notEmpty.signalAll()
            } finally {
                lock.unlock()
            }
        }
    }

    fun read(target: FloatArray, maxSamples: Int, timeoutMs: Long = 40L): Int {
        lock.lock()
        try {
            if (size == 0 && !closed) {
                notEmpty.await(timeoutMs, TimeUnit.MILLISECONDS)
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
            notFull.signalAll()
            return toRead
        } finally {
            lock.unlock()
        }
    }

    fun clear() {
        lock.lock()
        try {
            readIndex = 0
            writeIndex = 0
            size = 0
            notFull.signalAll()
        } finally {
            lock.unlock()
        }
    }

    fun close() {
        lock.lock()
        try {
            closed = true
            notFull.signalAll()
            notEmpty.signalAll()
        } finally {
            lock.unlock()
        }
    }

    fun isEmpty(): Boolean {
        lock.lock()
        try {
            return size == 0
        } finally {
            lock.unlock()
        }
    }

    fun availableRead(): Int {
        lock.lock()
        try {
            return size
        } finally {
            lock.unlock()
        }
    }

    companion object {
        const val DEFAULT_CAPACITY_SAMPLES = 65536

        private fun calculateEffectiveCapacity(requested: Int): Int {
            val min = 32768
            val v = maxOf(requested, min)
            var n = v - 1
            n = n or (n shr 1)
            n = n or (n shr 2)
            n = n or (n shr 4)
            n = n or (n shr 8)
            n = n or (n shr 16)
            return n + 1
        }
    }
}
