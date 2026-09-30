package com.beatraxus.app.engine

import android.content.Context
import android.util.Log
import androidx.annotation.Keep
import com.beatraxus.app.model.AiAnalysisEntity
import com.beatraxus.app.model.Song
import com.beatraxus.app.model.SongSource
import com.beatraxus.app.repository.GenreApiService
import com.beatraxus.app.repository.MoodApiService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil

class AiAnalysisEngine(private val context: Context) {
    private val TAG = "AiAnalysisEngine"

    // Model interpreters
    private var genreInterpreter: Interpreter? = null
    private var languageInterpreter: Interpreter? = null
    private var moodInterpreter: Interpreter? = null
    private val onlineAiService = GenreApiService()
    private val moodApiService = MoodApiService()
    private val melSpectrogram = LogMelSpectrogram(numMelBins = 64)

    init {
        // Attempt loading primary audio model (e.g., YAMNet or dedicated TFLite classifiers)
        genreInterpreter = loadModel("models/yamnet.tflite") ?: loadModel("models/genre_model.tflite")
        languageInterpreter = loadModel("models/language_model.tflite")
        moodInterpreter = loadModel("models/mood_model.tflite")
    }

    private fun loadModel(path: String): Interpreter? {
        return try {
            val model = FileUtil.loadMappedFile(context, path)
            Interpreter(model, Interpreter.Options().apply {
                setNumThreads(4)
            })
        } catch (e: Exception) {
            Log.w(TAG, "Model file 'assets/$path' not found or failed to load; falling back to heuristic classification rules.")
            null
        }
    }

    suspend fun analyzeSong(song: Song): SongAnalysisResult = withContext(Dispatchers.Default) {
        // Skip cloud/telegram songs for AI analysis to avoid native crashes on web URIs.
        if (song.source != SongSource.LOCAL) return@withContext SongAnalysisResult(null, null)

        try {
            // 1. Extract audio features using native C++ engine (analyzes up to 60s)
            val features = NativeDsp().use { dsp ->
                dsp.extractFeatures(context, song.uri, 60)
            } ?: return@withContext SongAnalysisResult(null, null)

            // 2. Genre Classification (TFLite or Heuristic Fallback)
            val genreResult = runInference(genreInterpreter, features.spectralData)
            var primaryGenre = if (genreResult.isModelInference && genreResult.primaryIndex in GENRES.indices) {
                GENRES[genreResult.primaryIndex]
            } else {
                heuristicGenre(features)
            }

            // Online AI Enhancement for Accuracy (optional refinement)
            try {
                val onlineGenre = onlineAiService.fetchAccurateGenre(song.artist, song.title)
                if (!onlineGenre.isNullOrEmpty()) {
                    primaryGenre = onlineGenre
                    Log.d(TAG, "Online AI refined genre: $onlineGenre")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Online genre service unavailable: ${e.message}")
            }

            val secondaryGenre = if (genreResult.isModelInference && genreResult.secondaryConfidence > 0.35f && genreResult.secondaryIndex in GENRES.indices) {
                GENRES[genreResult.secondaryIndex]
            } else null

            // 3. Language Classification
            val langResult = runInference(languageInterpreter, features.spectralData)
            val language = if (langResult.isModelInference && langResult.primaryIndex in LANGUAGES.indices) {
                LANGUAGES[langResult.primaryIndex]
            } else {
                heuristicLanguage(song)
            }

            // 4. Mood Classification
            val moodResult = runInference(moodInterpreter, features.spectralData)
            val primaryMoodRaw = if (moodResult.isModelInference && moodResult.primaryIndex in MOODS.indices) {
                MOODS[moodResult.primaryIndex]
            } else {
                heuristicMood(features)
            }
            val mood = mapToUiMood(primaryMoodRaw)

            // 4b. Multi-label mood set (Local model + beat/BPM rules + Last.fm community tags)
            val finalMoods = linkedSetOf<String>()
            finalMoods.add(mood)
            if (moodResult.isModelInference && moodResult.secondaryConfidence > 0.35f && moodResult.secondaryIndex in MOODS.indices) {
                finalMoods.add(mapToUiMood(MOODS[moodResult.secondaryIndex]))
            }
            finalMoods.addAll(bpmMoods(features.tempoBpm))
            try {
                finalMoods.addAll(moodApiService.fetchAccurateMoodTags(song.artist, song.title))
            } catch (e: Exception) {
                Log.e(TAG, "Last.fm mood lookup failed for ${song.title}", e)
            }
            val moodTags = finalMoods.joinToString(",")

            // 5. Generate Adaptive AI EQ Profile
            val aiEqResult = AiEqCalculator.calculateAiEq(
                genre = primaryGenre,
                mood = mood,
                spectral128 = features.spectralData,
                lufs = features.lufs,
                dr = features.dynamicRange,
                bassScore = features.bassScore,
                midScore = features.midScore,
                trebleScore = features.trebleScore
            )
            val aiEq = aiEqResult.bandGains

            val entity = AiAnalysisEntity(
                songId = song.id,
                genre = primaryGenre,
                genreConfidence = if (genreResult.isModelInference) genreResult.primaryConfidence else 0.0f,
                secondaryGenre = secondaryGenre,
                secondaryGenreConfidence = if (genreResult.isModelInference && secondaryGenre != null) genreResult.secondaryConfidence else null,
                language = language,
                languageConfidence = if (langResult.isModelInference) langResult.primaryConfidence else 0.0f,
                mood = mood,
                moodConfidence = if (moodResult.isModelInference) moodResult.primaryConfidence else 0.0f,
                moodTags = moodTags,
                lufs = features.lufs,
                rms = features.rms,
                peak = features.peak,
                dynamicRange = features.dynamicRange,
                bassScore = features.bassScore,
                midScore = features.midScore,
                trebleScore = features.trebleScore,
                stereoWidth = features.stereoWidth,
                tempoBpm = features.tempoBpm,
                eq31 = aiEq[0],
                eq62 = aiEq[1],
                eq125 = aiEq[2],
                eq250 = aiEq[3],
                eq500 = aiEq[4],
                eq1k = aiEq[5],
                eq2k = aiEq[6],
                eq4k = aiEq[7],
                eq8k = aiEq[8],
                eq16k = aiEq[9],
                analysisVersion = 2,
                lastAnalyzed = System.currentTimeMillis()
            )
            SongAnalysisResult(entity, features)
        } catch (e: Exception) {
            Log.e(TAG, "Error analyzing song ${song.title}", e)
            SongAnalysisResult(null, null)
        } catch (t: Throwable) {
            Log.e(TAG, "Fatal error analyzing song ${song.title}", t)
            SongAnalysisResult(null, null)
        }
    }

    private fun runInference(interpreter: Interpreter?, spectralData: FloatArray): InferenceResult {
        if (interpreter == null) {
            return InferenceResult(0, 0.0f, 0, 0.0f, isModelInference = false)
        }

        return try {
            val inputTensor = interpreter.getInputTensor(0)
            val outputTensor = interpreter.getOutputTensor(0)

            val inputShape = inputTensor.shape()
            val outputShape = outputTensor.shape()
            val numClasses = if (outputShape.size > 1) outputShape[1] else outputShape[0]

            val logMel = melSpectrogram.computeFrom128BinSpectrum(spectralData)

            // Format input tensor based on model shape
            val inputObject: Any = when {
                inputShape.size == 2 && inputShape[1] == 128 -> arrayOf(spectralData)
                inputShape.size == 2 && inputShape[1] == 64 -> arrayOf(logMel)
                inputShape.size == 3 -> Array(1) { Array(inputShape[1]) { logMel } }
                else -> arrayOf(spectralData)
            }

            val output = Array(1) { FloatArray(numClasses) }
            interpreter.run(inputObject, output)

            val probabilities = output[0]
            var maxIdx = 0
            var maxVal = 0.0f
            var secIdx = 0
            var secVal = 0.0f

            for (i in probabilities.indices) {
                val p = probabilities[i]
                if (p > maxVal) {
                    secVal = maxVal
                    secIdx = maxIdx
                    maxVal = p
                    maxIdx = i
                } else if (p > secVal) {
                    secVal = p
                    secIdx = i
                }
            }

            InferenceResult(maxIdx, maxVal, secIdx, secVal, isModelInference = true)
        } catch (e: Exception) {
            Log.e(TAG, "TFLite inference failed; using heuristic fallback", e)
            InferenceResult(0, 0.0f, 0, 0.0f, isModelInference = false)
        }
    }

    private fun heuristicGenre(features: AudioFeatures): String {
        return when {
            features.bassScore > 0.45f && features.tempoBpm > 115f -> "EDM"
            features.bassScore > 0.40f -> "Hip-Hop"
            features.trebleScore > 0.40f -> "Rock"
            features.midScore > 0.50f && features.dynamicRange > 14f -> "Classical"
            else -> "Pop"
        }
    }

    private fun heuristicLanguage(song: Song): String {
        val text = "${song.title} ${song.artist}".lowercase()
        return when {
            text.contains("tamil") || text.contains("kaatru") || text.contains("kanmani") -> "Tamil"
            text.contains("hindi") || text.contains("pyaar") || text.contains("dil") -> "Hindi"
            text.contains("telugu") -> "Telugu"
            text.contains("malayalam") -> "Malayalam"
            else -> "English"
        }
    }

    private fun heuristicMood(features: AudioFeatures): String {
        return when {
            features.tempoBpm >= 130f || features.bassScore > 0.45f -> "Energetic"
            features.tempoBpm in 95f..120f -> "Happy"
            features.tempoBpm < 85f && features.dynamicRange < 12f -> "Relaxing"
            features.tempoBpm < 75f -> "Calm"
            else -> "Calm"
        }
    }

    private data class InferenceResult(
        val primaryIndex: Int,
        val primaryConfidence: Float,
        val secondaryIndex: Int,
        val secondaryConfidence: Float,
        val isModelInference: Boolean = false
    )

    companion object {
        private val GENRES = listOf(
            "Tamil Film Music", "Tamil Melody", "Tamil Mass", "Tamil Folk", "Tamil Classical", "Tamil Devotional",
            "Hindi Film Music", "Hindi Melody", "Hindi Folk", "Hindi Classical",
            "English Pop", "English Rock", "English Alternative", "English Indie", "English Electronic", "English Dance", "English Hip-Hop", "English R&B",
            "Pop", "Rock", "Metal", "Alternative", "Indie", "Jazz", "Blues", "Country", "Classical", "Electronic", "EDM", "House", "Techno", "Trance", "Hip-Hop", "Rap", "R&B", "Soul", "Reggae", "Lo-Fi", "Ambient", "Soundtrack", "Instrumental", "Podcast", "Audiobook"
        )

        private val LANGUAGES = listOf(
            "Tamil", "English", "Hindi", "Malayalam", "Telugu", "Kannada", "Punjabi", "Bengali", "Marathi", "Gujarati", "Urdu", "Sanskrit", "French", "Spanish", "German", "Japanese", "Korean", "Chinese", "Mixed", "Unknown", "Instrumental"
        )

        private val MOODS = listOf(
            "Calm", "Relaxing", "Happy", "Energetic", "Aggressive", "Romantic", "Sad", "Motivational", "Party", "Workout", "Focus", "Sleep", "Meditation", "Emotional", "Epic", "Dark", "Uplifting"
        )

        private val MOOD_UI_MAP = mapOf(
            "Relaxing" to "Calm",
            "Uplifting" to "Happy"
        )
        fun mapToUiMood(raw: String): String = MOOD_UI_MAP[raw] ?: raw

        fun bpmMoods(bpm: Float): List<String> = buildList {
            when {
                bpm in 1f..70f    -> { add("Sleep"); add("Calm"); add("Meditation") }
                bpm in 70f..95f   -> { add("Calm"); add("Romantic"); add("Sad") }
                bpm in 95f..115f  -> { add("Happy"); add("Focus") }
                bpm in 115f..135f -> { add("Motivational"); add("Party"); add("Epic") }
                bpm >= 135f       -> { add("Energetic"); add("Workout"); add("Aggressive") }
            }
        }
    }
}

@Keep
data class AudioFeatures(
    val lufs: Float,
    val rms: Float,
    val peak: Float,
    val dynamicRange: Float,
    val bassScore: Float,
    val midScore: Float,
    val trebleScore: Float,
    val stereoWidth: Float,
    val tempoBpm: Float,
    val spectralData: FloatArray, // Pre-processed for TFLite
    val noveltyVector: FloatArray?, // FFT novelty (spectral flux) for segment detection
    val truePeakDb: Float,
    val clippedSamplePct: Float,
    val freqRangeLowHz: Float,
    val freqRangeHighHz: Float
)

/** Result of [AiAnalysisEngine.analyzeSong], bundling the AI entity with the raw
 *  [AudioFeatures] used to build it so callers (e.g. the quality-analysis scan step)
 *  can reuse the same native analysis pass instead of extracting features twice. */
data class SongAnalysisResult(
    val aiAnalysis: AiAnalysisEntity?,
    val features: AudioFeatures?
)
