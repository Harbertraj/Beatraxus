package com.beatraxus.app.subtitles.domain

object SubtitleValidator {

    private const val MAX_SIZE_CHARS = 5 * 1024 * 1024 // 5 MB limit
    private val HTML_PATTERNS = listOf(
        "<!doctype html", "<html", "<head", "404 not found",
        "access denied", "cloudflare", "<title>error", "<body"
    )

    fun validate(content: String): Result<Unit> {
        if (content.isBlank()) {
            return Result.failure(SubtitleException(SubtitleError.InvalidSubtitle))
        }

        if (content.length > MAX_SIZE_CHARS) {
            return Result.failure(SubtitleException(SubtitleError.InvalidSubtitle))
        }

        val lowerContent = content.substring(0, content.length.coerceAtMost(2048)).lowercase()
        if (HTML_PATTERNS.any { lowerContent.contains(it) }) {
            return Result.failure(SubtitleException(SubtitleError.InvalidSubtitle))
        }

        val cueCount = Regex("\\d{2}:\\d{2}:\\d{2}|-->").findAll(content).count()
        if (cueCount < 1) {
            return Result.failure(SubtitleException(SubtitleError.InvalidSubtitle))
        }

        return Result.success(Unit)
    }
}
