package com.beatraxus.app.repository.lyrics

import java.util.Locale
import kotlin.math.abs

object LyricsMatcher {
    fun normalize(text: String?): String {
        if (text.isNullOrBlank()) return ""
        return text.lowercase(Locale.getDefault())
            .replace(Regex("\\(feat\\..*?\\)"), "")
            .replace(Regex("- remastered.*"), "")
            .replace(Regex("\\[.*?\\]"), "")
            .replace(Regex("\\(.*?\\)"), "")
            .replace(Regex("[^a-z0-9]"), "")
            .trim()
    }

    private fun levenshteinDistance(s1: String, s2: String): Int {
        val len1 = s1.length
        val len2 = s2.length
        val dp = Array(len1 + 1) { IntArray(len2 + 1) }

        for (i in 0..len1) dp[i][0] = i
        for (j in 0..len2) dp[0][j] = j

        for (i in 1..len1) {
            for (j in 1..len2) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,
                    dp[i][j - 1] + 1,
                    dp[i - 1][j - 1] + cost
                )
            }
        }
        return dp[len1][len2]
    }

    private fun similarity(s1: String, s2: String): Double {
        val n1 = normalize(s1)
        val n2 = normalize(s2)
        if (n1.isEmpty() && n2.isEmpty()) return 1.0
        if (n1.isEmpty() || n2.isEmpty()) return 0.0
        val maxLen = maxOf(n1.length, n2.length)
        return 1.0 - levenshteinDistance(n1, n2).toDouble() / maxLen
    }

    fun isMatch(
        queryTitle: String,
        queryArtist: String,
        queryDurationMs: Long,
        candidateTitle: String,
        candidateArtist: String,
        candidateDurationMs: Long? = null
    ): Boolean {
        val titleSim = similarity(queryTitle, candidateTitle)
        val artistSim = similarity(queryArtist, candidateArtist)

        if (titleSim < 0.75 || artistSim < 0.75) return false

        if (queryDurationMs > 0 && candidateDurationMs != null && candidateDurationMs > 0) {
            if (abs(queryDurationMs - candidateDurationMs) > 3000L) {
                return false
            }
        }

        return true
    }
}