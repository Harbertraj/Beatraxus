package com.beatraxus.app.subtitles

import com.beatraxus.app.subtitles.domain.VideoHashCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile

class VideoHashCalculatorTest {

    @Test
    fun testFileUnder128KBReturnsNull() {
        val tempFile = File.createTempFile("small_test", ".tmp")
        tempFile.deleteOnExit()

        RandomAccessFile(tempFile, "rw").use { raf ->
            raf.setLength(100 * 1024L) // 100 KB < 128 KB
        }

        RandomAccessFile(tempFile, "r").use { raf ->
            val hash = VideoHashCalculator.computeHashFromChannelForTesting(raf.channel)
            assertNull(hash)
        }
    }

    @Test
    fun testSyntheticFileHashComputation() {
        val tempFile = File.createTempFile("synthetic_video", ".tmp")
        tempFile.deleteOnExit()

        val fileSize = 256 * 1024L // 256 KB
        RandomAccessFile(tempFile, "rw").use { raf ->
            raf.setLength(fileSize)
            raf.seek(0)
            // Write known pattern in first 64 KB
            val bytes = ByteArray(64 * 1024) { (it % 256).toByte() }
            raf.write(bytes)
            raf.seek(fileSize - 64 * 1024)
            raf.write(bytes)
        }

        RandomAccessFile(tempFile, "r").use { raf ->
            val hash = VideoHashCalculator.computeHashFromChannelForTesting(raf.channel)
            assertNotNull(hash)
            assertEquals(16, hash!!.length)
            // Repeating the computation on the same file produces identical hash
            val hash2 = VideoHashCalculator.computeHashFromChannelForTesting(raf.channel)
            assertEquals(hash, hash2)
        }
    }
}
