package com.beatraxus.app.subtitles.domain

import java.util.Locale

/**
 * Video feature 19: decides which online subtitle, if any, may be downloaded without the viewer choosing it.
 *
 * The match scorer's "best match" flag is too generous for this on its own (it counts a video hash as matched whenever
 * one was sent), and an automatic download uses up the viewer's daily quota. So on top of the flag this requires a
 * language the viewer asked for and rejects machine and AI translations. A wrong pick still costs a download, so when
 * nothing qualifies the answer is null and the viewer chooses by hand from the list.
 */
object AutoSubtitlePicker {

    /**
     * @param scored search results already scored and sorted best first
     * @param preferredLanguages language codes in the viewer's order of preference
     * @return the subtitle to download, or null when no result is safe to take automatically
     */
    fun pick(scored: List<ScoredSubtitle>, preferredLanguages: List<String>): ScoredSubtitle? {
        if (preferredLanguages.isEmpty()) return null
        val wanted = preferredLanguages.map { baseCode(it) }

        return scored
            .filter { it.isBestMatch }
            .filter { !it.subtitle.isMachineTranslated && !it.subtitle.isAiTranslated }
            .mapNotNull { candidate ->
                val rank = wanted.indexOf(baseCode(candidate.subtitle.language))
                if (rank < 0) null else rank to candidate
            }
            // The viewer's first language wins over a higher score in a later one; score decides within a language.
            .sortedWith(compareBy<Pair<Int, ScoredSubtitle>> { it.first }.thenByDescending { it.second.score })
            .firstOrNull()
            ?.second
    }

    /** "pt-BR" and "pt_br" both count as "pt", the way the language list and the search results spell them. */
    private fun baseCode(code: String): String =
        code.trim().lowercase(Locale.ROOT).substringBefore('-').substringBefore('_')
}
