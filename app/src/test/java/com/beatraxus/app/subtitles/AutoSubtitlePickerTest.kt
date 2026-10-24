package com.beatraxus.app.subtitles

import com.beatraxus.app.subtitles.domain.AutoSubtitlePicker
import com.beatraxus.app.subtitles.domain.ScoredSubtitle
import com.beatraxus.app.subtitles.domain.SubtitleResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AutoSubtitlePickerTest {

    private fun scored(
        id: String,
        language: String = "en",
        score: Float = 900f,
        best: Boolean = true,
        machine: Boolean = false,
        ai: Boolean = false
    ) = ScoredSubtitle(
        subtitle = SubtitleResult(
            id = id, fileId = id.hashCode().toLong(), fileName = "$id.srt", releaseName = id,
            language = language, languageName = null, downloadCount = 10, isHearingImpaired = false,
            isMachineTranslated = machine, isAiTranslated = ai, rating = 5f, uploaderName = null,
            fps = null, featureTitle = null, year = null, imdbId = null, tmdbId = null
        ),
        score = score,
        isBestMatch = best
    )

    @Test
    fun picksTheBestScoringBestMatchInAPreferredLanguage() {
        val pick = AutoSubtitlePicker.pick(listOf(scored("a", score = 900f), scored("b", score = 800f)), listOf("en"))
        assertEquals("a", pick?.subtitle?.id)
    }

    @Test
    fun ignoresResultsThatAreNotFlaggedAsBestMatch() {
        assertNull(AutoSubtitlePicker.pick(listOf(scored("a", best = false)), listOf("en")))
    }

    @Test
    fun ignoresLanguagesTheViewerDidNotAskFor() {
        assertNull(AutoSubtitlePicker.pick(listOf(scored("a", language = "fr")), listOf("en", "es")))
    }

    @Test
    fun rejectsMachineAndAiTranslations() {
        val results = listOf(scored("m", machine = true), scored("i", ai = true))
        assertNull(AutoSubtitlePicker.pick(results, listOf("en")))
    }

    @Test
    fun firstPreferredLanguageBeatsAHigherScoreInALaterOne() {
        val results = listOf(scored("es", language = "es", score = 1000f), scored("en", language = "en", score = 700f))
        assertEquals("en", AutoSubtitlePicker.pick(results, listOf("en", "es"))?.subtitle?.id)
        assertEquals("es", AutoSubtitlePicker.pick(results, listOf("es", "en"))?.subtitle?.id)
    }

    @Test
    fun regionalCodesMatchTheirBaseLanguage() {
        assertEquals("pt", AutoSubtitlePicker.pick(listOf(scored("pt", language = "pt-BR")), listOf("pt"))?.subtitle?.id)
        assertEquals("pt", AutoSubtitlePicker.pick(listOf(scored("pt", language = "pt")), listOf("pt-PT"))?.subtitle?.id)
    }

    @Test
    fun noPreferredLanguagesOrNoResultsMeansNoPick() {
        assertNull(AutoSubtitlePicker.pick(listOf(scored("a")), emptyList()))
        assertNull(AutoSubtitlePicker.pick(emptyList(), listOf("en")))
    }
}
