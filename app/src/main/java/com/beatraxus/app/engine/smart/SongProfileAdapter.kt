package com.beatraxus.app.engine.smart

import com.beatraxus.app.engine.AiAnalysisEngine
import com.beatraxus.app.model.AiAnalysisEntity
import com.beatraxus.app.model.Song

/** Builds the profiler input from a song and (when it has one) its stored AI analysis. */
fun Song.toProfileInput(a: AiAnalysisEntity?): ProfileInput {
    if (a == null) {
        return ProfileInput(title = title, artist = artist, album = album, folder = folder, tagGenre = genre, year = year)
    }
    val measured = a.analysisVersion != AiAnalysisEngine.VERSION_ESTIMATED && (a.tempoBpm > 0f || a.rms > 0f)
    // Stored mood tags mix real tags with ones the old code added purely from BPM: drop the BPM ones.
    val bpmOnly = AiAnalysisEngine.bpmMoods(a.tempoBpm).toSet()
    val trusted = a.moodTags.split(",").map { it.trim() }.filter { it.isNotEmpty() && it !in bpmOnly }
    return ProfileInput(
        title = title, artist = artist, album = album, folder = folder, tagGenre = genre, year = year,
        aiGenre = a.genre, aiGenreConfidence = a.genreConfidence, aiSecondaryGenre = a.secondaryGenre,
        language = a.language.takeIf { a.languageConfidence > 0f || !it.equals("English", true) },
        moodTags = trusted,
        measured = measured,
        tempoBpm = a.tempoBpm, lufs = a.lufs, dynamicRange = a.dynamicRange,
        bass = a.bassScore, mid = a.midScore, treble = a.trebleScore, stereoWidth = a.stereoWidth
    )
}

fun Song.smartProfile(a: AiAnalysisEntity?): SongProfile = SongProfiler.profile(toProfileInput(a))

/** Fast, lightweight profile using only tag genre. Safe for main thread fallback. */
fun Song.basicProfile(): SongProfile {
    val canonical = GenreTaxonomy.canonical(genre)
    val g = if (canonical.isEmpty()) listOf(GenreTaxonomy.UNKNOWN) else canonical
    return SongProfile(
        genres = g,
        styles = emptyList(),
        moods = emptyList(),
        moodScores = emptyMap(),
        language = null,
        energy = 0.5f,
        valence = 0.5f
    )
}
