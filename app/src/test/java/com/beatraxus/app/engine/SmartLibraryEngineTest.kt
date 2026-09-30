package com.beatraxus.app.engine

import com.beatraxus.app.model.AiAnalysisEntity
import com.beatraxus.app.model.SongQualityEntity
import org.junit.Assert.*
import org.junit.Test

class SmartLibraryEngineTest {

    private fun createDummyAnalysis(
        songId: String,
        mood: String,
        moodTags: String = "",
        bpm: Float = 120.0f,
        bass: Float = 0.33f,
        mid: Float = 0.33f,
        treble: Float = 0.33f
    ): AiAnalysisEntity {
        return AiAnalysisEntity(
            songId = songId,
            genre = "Pop",
            genreConfidence = 0.9f,
            secondaryGenre = null,
            secondaryGenreConfidence = null,
            language = "English",
            languageConfidence = 0.9f,
            mood = mood,
            moodConfidence = 0.9f,
            moodTags = moodTags,
            lufs = -12.0f,
            rms = 0.2f,
            peak = 0.9f,
            dynamicRange = 10.0f,
            bassScore = bass,
            midScore = mid,
            trebleScore = treble,
            stereoWidth = 0.8f,
            tempoBpm = bpm,
            eq31 = 0f, eq62 = 0f, eq125 = 0f, eq250 = 0f, eq500 = 0f,
            eq1k = 0f, eq2k = 0f, eq4k = 0f, eq8k = 0f, eq16k = 0f,
            analysisVersion = 2,
            lastAnalyzed = System.currentTimeMillis()
        )
    }

    @Test
    fun testGetSongsByMood() {
        val song1 = createDummyAnalysis("s1", "Energetic", "Workout,Party")
        val song2 = createDummyAnalysis("s2", "Calm", "Sleep,Meditation")
        val song3 = createDummyAnalysis("s3", "Happy", "Energetic,Uplifting")

        val workoutSongs = SmartLibraryEngine.getSongsByMood("Workout", listOf(song1, song2, song3))
        assertEquals(1, workoutSongs.size)
        assertEquals("s1", workoutSongs[0])

        val energeticSongs = SmartLibraryEngine.getSongsByMood("Energetic", listOf(song1, song2, song3))
        assertEquals(2, energeticSongs.size)
    }

    @Test
    fun testGetSongsByBpmRange() {
        val song1 = createDummyAnalysis("s1", "Energetic", bpm = 128.0f)
        val song2 = createDummyAnalysis("s2", "Calm", bpm = 70.0f)
        val song3 = createDummyAnalysis("s3", "Workout", bpm = 135.0f)

        val fastSongs = SmartLibraryEngine.getSongsByBpmRange(125.0f, 140.0f, listOf(song1, song2, song3))
        assertEquals(2, fastSongs.size)
        assertTrue(fastSongs.contains("s1"))
        assertTrue(fastSongs.contains("s3"))
    }

    @Test
    fun testFindSimilarSongsCosineSimilarity() {
        val target = createDummyAnalysis("target", "EDM", bass = 0.6f, mid = 0.2f, treble = 0.2f, bpm = 128f)
        val twin = createDummyAnalysis("twin", "EDM", bass = 0.6f, mid = 0.2f, treble = 0.2f, bpm = 128f)
        val different = createDummyAnalysis("different", "Classical", bass = 0.1f, mid = 0.6f, treble = 0.3f, bpm = 70f)

        val similar = SmartLibraryEngine.findSimilarSongs("target", listOf(target, twin, different))
        assertEquals(2, similar.size)
        assertEquals("twin", similar[0].songId)
        assertTrue("Twin similarity should be near 1.0", similar[0].similarityScore > 0.98f)
        assertTrue("Twin similarity should be higher than different song", similar[0].similarityScore > similar[1].similarityScore)
    }

    @Test
    fun testQualityWarnings() {
        val clippedQuality = SongQualityEntity(
            songId = "s1",
            bitrateKbps = 96, // low bitrate warning
            sampleRateHz = 44100,
            bitDepth = 16,
            codec = "MP3",
            lufs = -6.0f,
            dynamicRange = 4.0f, // loudness war warning
            truePeakDb = 1.2f, // clipping warning
            clippedSamplePct = 2.5f,
            stereoWidth = 0.7f,
            freqRangeLowHz = 20f,
            freqRangeHighHz = 16000f,
            qualityScore = 35,
            qualityTier = "Poor",
            analysisVersion = 1,
            lastAnalyzed = System.currentTimeMillis()
        )

        val warnings = SmartLibraryEngine.getQualityWarnings(clippedQuality, null)
        assertEquals(3, warnings.size)
        assertTrue(warnings.any { it.title.contains("Low Bitrate") })
        assertTrue(warnings.any { it.title.contains("Clipping") })
        assertTrue(warnings.any { it.title.contains("Loudness War") })
    }
}
