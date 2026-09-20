package com.beatraxus.app.subtitles.domain

enum class SubtitlePositionPreset(val displayName: String, val offsetDp: Float) {
    BOTTOM("Bottom", 0f),
    LOWER_MIDDLE("Lower Middle", -150f),
    MIDDLE("Middle", -350f)
}
