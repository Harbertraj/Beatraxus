package com.beatraxus.app.repository.lyrics

import android.icu.text.Transliterator
import android.os.Build
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

object LyricsMatcher {
    fun normalize(text: String?): String {
        if (text.isNullOrBlank()) return ""
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFKC)
        return normalized.lowercase(Locale.getDefault())
            .replace(Regex("\\(feat\\..*?\\)"), "")
            .replace(Regex("- remastered.*"), "")
            .replace(Regex("\\[.*?\\]"), "")
            .replace(Regex("\\(.*?\\)"), "")
            .replace(Regex("[^\\p{L}\\p{N}]"), "")
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

    @Suppress("NewApi")
    fun phoneticKey(text: String?): String {
        if (text.isNullOrBlank()) return ""
        val hasTamil = text.any { it in '\u0B80'..'\u0BFF' }
        val transliterated = if (hasTamil) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    Transliterator.getInstance("Tamil-Latin; Latin-ASCII").transliterate(text)
                } else {
                    val clazz = Class.forName("android.icu.text.Transliterator")
                    val getInstance = clazz.getMethod("getInstance", String::class.java)
                    val transliterate = clazz.getMethod("transliterate", String::class.java)
                    val inst = getInstance.invoke(null, "Tamil-Latin; Latin-ASCII")
                    transliterate.invoke(inst, text) as String
                }
            } catch (_: Throwable) {
                text
            }
        } else {
            text
        }

        var s = transliterated.lowercase(Locale.getDefault())
        s = s.replace("zh", "l")
        s = s.replace(Regex("(?<=[tdkgbpcs])h"), "")
        s = s.replace("d", "t")
        s = s.replace("ee", "i")
        s = s.replace("oo", "u")
        s = s.replace("y", "i")
        s = s.replace("w", "v")
        s = s.replace(Regex("(.)\\1+"), "$1")
        s = s.replace(Regex("[^a-z0-9]"), "")
        return s.trim()
    }

    fun similarity(s1: String, s2: String): Double {
        val n1 = normalize(s1)
        val n2 = normalize(s2)
        val ratioNorm = if (n1.isEmpty() || n2.isEmpty()) {
            0.0
        } else {
            val maxLen = maxOf(n1.length, n2.length)
            1.0 - levenshteinDistance(n1, n2).toDouble() / maxLen
        }

        val k1 = phoneticKey(s1)
        val k2 = phoneticKey(s2)
        val ratioPhone = if (k1.isEmpty() || k2.isEmpty()) {
            0.0
        } else {
            val maxLen = maxOf(k1.length, k2.length)
            1.0 - levenshteinDistance(k1, k2).toDouble() / maxLen
        }

        return maxOf(ratioNorm, ratioPhone)
    }

    fun primaryArtists(artist: String?): List<String> {
        if (artist.isNullOrBlank()) return emptyList()
        val splitRegex = Regex("(?i)\\s*(?:&|,|;|/|\\band\\b|\\bx\\b|\\bfeat\\.\\b|\\bft\\.\\b|\\bwith\\b)\\s*")
        val parts = artist.split(splitRegex).map { it.trim() }.filter { it.isNotEmpty() }
        return parts.ifEmpty { listOf(artist.trim()) }
    }

    fun artistSimilarity(a: String?, b: String?): Double {
        val aList = primaryArtists(a)
        val bList = primaryArtists(b)
        if (aList.isEmpty() || bList.isEmpty()) return 0.0

        var best = 0.0
        for (pA in aList) {
            for (pB in bList) {
                best = maxOf(best, similarity(pA, pB))
            }
        }
        return best
    }

    fun cleanTitle(title: String?): String {
        if (title.isNullOrBlank()) return ""
        var t = title
        t = t.replace(Regex("(?i)\\s*\\(From\\s+[\"'].*?[\"']\\)"), "")
        t = t.replace(Regex("(?i)\\s*-\\s*From\\s+[\"'].*?[\"']"), "")
        t = t.replace(Regex("(?i)\\s*\\(Original Motion Picture Soundtrack\\)"), "")
        t = t.replace(Regex("(?i)\\s*-\\s*Single\\b"), "")
        t = t.replace(Regex("(?i)\\s*-\\s*EP\\b"), "")
        t = t.replace(Regex("(?i)\\s*\\(Remastered.*?\\)"), "")
        t = t.replace(Regex("(?i)\\s*\\(Official.*?\\)"), "")
        t = t.replace(Regex("(?i)\\s*\\(Lyric Video.*?\\)"), "")
        t = t.replace(Regex("\\s*\\[.*?\\]"), "")
        return t.replace(Regex("\\s+"), " ").trim()
    }

    fun score(
        queryTitle: String,
        queryArtist: String,
        queryDurationMs: Long,
        candTitle: String,
        candArtist: String,
        candDurationMs: Long? = null
    ): Double {
        val titleSim = similarity(cleanTitle(queryTitle), cleanTitle(candTitle))
        val artistSim = artistSimilarity(queryArtist, candArtist)

        val durationScore = if (queryDurationMs <= 0 || candDurationMs == null || candDurationMs <= 0) {
            0.5
        } else {
            val diffSec = abs(queryDurationMs - candDurationMs) / 1000.0
            when {
                diffSec <= 3.0 -> 1.0
                diffSec >= 12.0 -> 0.0
                else -> 1.0 - (diffSec - 3.0) / 9.0
            }
        }

        return (0.5 * titleSim + 0.3 * artistSim + 0.2 * durationScore).coerceIn(0.0, 1.0)
    }

    fun isConfidentMatch(
        queryTitle: String,
        queryArtist: String,
        queryDurationMs: Long,
        candTitle: String,
        candArtist: String,
        candDurationMs: Long? = null
    ): Boolean {
        val titleSim = similarity(cleanTitle(queryTitle), cleanTitle(candTitle))
        val totalScore = score(queryTitle, queryArtist, queryDurationMs, candTitle, candArtist, candDurationMs)
        return totalScore >= 0.8 && titleSim >= 0.75
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
