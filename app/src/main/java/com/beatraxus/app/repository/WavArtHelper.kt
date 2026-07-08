package com.beatraxus.app.repository

import java.io.RandomAccessFile

object WavArtHelper {
    /**
     * Extracts embedded album art (APIC from ID3 or DISP chunk) from a WAV file.
     * Works with partial/sparse files if the relevant chunks are downloaded.
     */
    fun extractArt(path: String): ByteArray? {
        return try {
            RandomAccessFile(path, "r").use { raf ->
                val len = raf.length()
                if (len < 12) return null
                
                // WAV files are RIFF containers
                if (readFourCc(raf) != "RIFF") return null
                raf.skipBytes(4) // skip RIFF size
                if (readFourCc(raf) != "WAVE") return null
                
                while (raf.filePointer + 8 <= len) {
                    val chunkIdRaw = readFourCc(raf)
                    val chunkSize = readLittleEndianInt(raf).toLong().and(0xFFFFFFFFL)
                    val chunkStart = raf.filePointer
                    
                    val chunkId = chunkIdRaw.trim().uppercase()

                    // Safety break for holes in sparse cloud files
                    if (chunkIdRaw.all { it == '\u0000' } && chunkSize == 0L) {
                        // If we hit a hole, jump to the footer area (last 8MB) where tags usually live in WAV
                        if (raf.filePointer < len - 8_388_608L) {
                            raf.seek(len - 8_388_608L)
                            continue
                        }
                        break
                    }

                    if (chunkSize > len - chunkStart) break

                    when (chunkId) {
                        "ID3" -> {
                            val bytes = ByteArray(chunkSize.toInt())
                            raf.readFully(bytes)
                            extractApicFromId3(bytes)?.let { return it }
                        }
                        "DISP" -> {
                            if (chunkSize > 4) {
                                raf.skipBytes(4)
                                val artSize = (chunkSize - 4).toInt()
                                val bytes = ByteArray(artSize)
                                raf.readFully(bytes)
                                if (bytes.isNotEmpty()) return bytes
                            }
                        }
                    }
                    
                    raf.seek(chunkStart + chunkSize)
                    // WAV chunks are word-aligned
                    if ((chunkSize % 2) != 0L && raf.filePointer < len) {
                        raf.skipBytes(1)
                    }
                }
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    fun readFourCc(raf: RandomAccessFile): String {
        val bytes = ByteArray(4)
        raf.readFully(bytes)
        return String(bytes, Charsets.US_ASCII)
    }

    fun readLittleEndianInt(raf: RandomAccessFile): Int {
        val b0 = raf.read()
        val b1 = raf.read()
        val b2 = raf.read()
        val b3 = raf.read()
        return (b0 and 0xFF) or ((b1 and 0xFF) shl 8) or ((b2 and 0xFF) shl 16) or ((b3 and 0xFF) shl 24)
    }

    private fun extractApicFromId3(bytes: ByteArray): ByteArray? {
        if (bytes.size < 10 || String(bytes, 0, 3) != "ID3") return null
        
        // Handle synchsafe size
        val tagSize = synchsafeToInt(bytes.copyOfRange(6, 10)).coerceAtMost(bytes.size - 10)
        var offset = 10
        
        while (offset + 10 <= 10 + tagSize && offset + 10 <= bytes.size) {
            val frameId = String(bytes, offset, 4)
            val frameSize = bytesToInt(bytes, offset + 4)
            
            if (frameSize <= 0 || offset + 10 + frameSize > bytes.size) break
            
            if (frameId == "APIC") {
                val frame = bytes.copyOfRange(offset + 10, offset + 10 + frameSize)
                return parseApicFrame(frame)
            }
            offset += 10 + frameSize
        }
        return null
    }

    private fun parseApicFrame(frame: ByteArray): ByteArray? {
        if (frame.size < 5) return null
        val encoding = frame[0].toInt() and 0xFF
        var index = 1
        
        // Skip MIME type
        while (index < frame.size && frame[index].toInt() != 0) index++
        index++ // skip null
        
        if (index >= frame.size) return null
        index++ // skip picture type
        
        // Skip description
        if (encoding == 0 || encoding == 3) {
            while (index < frame.size && frame[index].toInt() != 0) index++
            index++
        } else {
            while (index + 1 < frame.size && !(frame[index].toInt() == 0 && frame[index + 1].toInt() == 0)) index += 2
            index += 2
        }
        
        return if (index in 0 until frame.size) frame.copyOfRange(index, frame.size) else null
    }

    private fun synchsafeToInt(bytes: ByteArray): Int {
        if (bytes.size < 4) return 0
        return (bytes[0].toInt() and 0x7F shl 21) or
                (bytes[1].toInt() and 0x7F shl 14) or
                (bytes[2].toInt() and 0x7F shl 7) or
                (bytes[3].toInt() and 0x7F)
    }

    private fun bytesToInt(bytes: ByteArray, offset: Int): Int {
        if (offset + 4 > bytes.size) return 0
        return ((bytes[offset].toInt() and 0xFF) shl 24) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
                (bytes[offset + 3].toInt() and 0xFF)
    }
}
