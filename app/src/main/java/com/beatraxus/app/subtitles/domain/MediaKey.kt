package com.beatraxus.app.subtitles.domain

import com.beatraxus.app.model.Video
import java.security.MessageDigest

object MediaKey {
    fun of(video: Video, hash: String? = null): String {
        if (!hash.isNullOrBlank()) {
            return sanitize(hash)
        }
        val raw = "${video.id}|${video.sizeBytes}|${video.durationMs}"
        val sha1 = sha1Hex(raw)
        return sanitize(sha1)
    }

    private fun sha1Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-1")
        val bytes = digest.digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun sanitize(input: String): String {
        val cleaned = input.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        return if (cleaned.length > 64) cleaned.substring(0, 64) else cleaned
    }
}
