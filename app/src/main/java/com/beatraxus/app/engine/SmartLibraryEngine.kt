package com.beatraxus.app.engine

import com.beatraxus.app.model.AiAnalysisEntity
import com.beatraxus.app.model.SongQualityEntity
import java.util.Locale
import kotlin.math.sqrt

enum class EnergyLevel {
    HIGH, MEDIUM, LOW
}

data class SimilarSongResult(
    val songId: String,
    val similarityScore: Float // 0.0 to 1.0
)

data class QualityWarning(
    val title: String,
    val description: String,
    val isCritical: Boolean
)

object SmartLibraryEngine {

    /**
     * Filters songs for an auto-playlist matching a given mood or mood tag.
     */
    fun getSongsByMood(mood: String, analyses: List<AiAnalysisEntity>): List<String> {
        val targetLower = mood.lowercase()
        return analyses.filter { item ->
            item.mood.lowercase() == targetLower ||
                    item.moodTags.lowercase().split(",").any { it.trim() == targetLower }
        }.map { it.songId }
    }

    /**
     * Filters songs for an auto-playlist within a BPM range (e.g. 120-140 BPM for Workout).
     */
    fun getSongsByBpmRange(minBpm: Float, maxBpm: Float, analyses: List<AiAnalysisEntity>): List<String> {
        return analyses.filter { it.tempoBpm in minBpm..maxBpm }.map { it.songId }
    }

    /**
     * Filters songs by acoustic energy level (HIGH, MEDIUM, LOW).
     */
    fun getSongsByEnergy(energyLevel: EnergyLevel, analyses: List<AiAnalysisEntity>): List<String> {
        return analyses.filter { item ->
            when (energyLevel) {
                EnergyLevel.HIGH -> item.tempoBpm >= 120f || (item.bassScore > 0.40f && item.lufs > -10f)
                EnergyLevel.MEDIUM -> item.tempoBpm in 85f..120f
                EnergyLevel.LOW -> item.tempoBpm < 85f || item.mood == "Calm" || item.mood == "Sleep"
            }
        }.map { it.songId }
    }

    /**
     * Finds similar songs by computing the cosine similarity of 15-dimensional audio feature vectors.
     */
    fun findSimilarSongs(
        targetSongId: String,
        allAnalyses: List<AiAnalysisEntity>,
        topK: Int = 10
    ): List<SimilarSongResult> {
        val target = allAnalyses.find { it.songId == targetSongId } ?: return emptyList()
        val targetVector = extractFeatureVector(target)

        val results = mutableListOf<SimilarSongResult>()
        for (candidate in allAnalyses) {
            if (candidate.songId == targetSongId) continue
            val candidateVector = extractFeatureVector(candidate)
            val sim = cosineSimilarity(targetVector, candidateVector)
            results.add(SimilarSongResult(candidate.songId, sim.coerceIn(0.0f, 1.0f)))
        }

        return results.sortedByDescending { it.similarityScore }.take(topK)
    }

    /**
     * Evaluates audio quality indicators and generates warnings for low bitrate, digital clipping, or dynamic compression.
     */
    fun getQualityWarnings(
        quality: SongQualityEntity?,
        analysis: AiAnalysisEntity?
    ): List<QualityWarning> {
        val warnings = mutableListOf<QualityWarning>()

        if (quality != null) {
            if (quality.bitrateKbps in 1..127) {
                warnings.add(
                    QualityWarning(
                        title = "Low Bitrate (${quality.bitrateKbps} kbps)",
                        description = "Lossy compression artifacting may reduce high-frequency detail.",
                        isCritical = true
                    )
                )
            }
            if (quality.clippedSamplePct > 0.5f || quality.truePeakDb > 0.1f) {
                warnings.add(
                    QualityWarning(
                        title = "Digital Clipping Detected",
                        description = "${String.format(Locale.US, "%.2f", quality.clippedSamplePct)}% clipped samples (True Peak: ${String.format(
                            Locale.US, "%.1f", quality.truePeakDb)} dBFS).",
                        isCritical = true
                    )
                )
            }
            if (quality.dynamicRange in 0.1f..6.0f) {
                warnings.add(
                    QualityWarning(
                        title = "Loudness War Compression",
                        description = "Low dynamic range (${String.format(Locale.US, "%.1f", quality.dynamicRange)} dB) indicates heavy peak limiting.",
                        isCritical = false
                    )
                )
            }
        } else if (analysis != null) {
            if (analysis.dynamicRange in 0.1f..6.0f) {
                warnings.add(
                    QualityWarning(
                        title = "Low Dynamic Range",
                        description = "Dynamic range is ${String.format(Locale.US, "%.1f", analysis.dynamicRange)} dB.",
                        isCritical = false
                    )
                )
            }
        }

        return warnings
    }

    private fun extractFeatureVector(entity: AiAnalysisEntity): FloatArray {
        return floatArrayOf(
            (entity.lufs / -30.0f).coerceIn(-1.0f, 1.0f),
            (entity.dynamicRange / 30.0f).coerceIn(0.0f, 1.0f),
            entity.bassScore,
            entity.midScore,
            entity.trebleScore,
            entity.stereoWidth,
            (entity.tempoBpm / 200.0f).coerceIn(0.0f, 1.0f),
            entity.eq31 / 4.0f,
            entity.eq62 / 4.0f,
            entity.eq125 / 4.0f,
            entity.eq250 / 4.0f,
            entity.eq500 / 4.0f,
            entity.eq1k / 4.0f,
            entity.eq2k / 4.0f,
            entity.eq4k / 4.0f
        )
    }

    private fun cosineSimilarity(v1: FloatArray, v2: FloatArray): Float {
        var dotProduct = 0.0f
        var normA = 0.0f
        var normB = 0.0f
        val len = minOf(v1.size, v2.size)

        for (i in 0 until len) {
            dotProduct += v1[i] * v2[i]
            normA += v1[i] * v1[i]
            normB += v2[i] * v2[i]
        }

        if (normA <= 0f || normB <= 0f) return 0.0f
        return (dotProduct / (sqrt(normA) * sqrt(normB))).toFloat()
    }
}
