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

/**
 * Second chance for songs the native MediaCodec extractor declines (ALAC, Dolby, DTS, ...): returns the same
 * [AudioFeatures] computed from PCM decoded by the app's own decoders, or null if that fails too.
 */
typealias PcmFeatureSource = suspend (song: Song, dsp: NativeDsp) -> AudioFeatures?

class AiAnalysisEngine(
    private val context: Context,
    private val pcmFeatureSource: PcmFeatureSource? = null
) {
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

    /**
     * Builds the per-song library analysis (genre, mood, language, loudness...) and ALWAYS returns one (for any song, local or cloud):
     *  1. audio analysis - native extractor, then the app's own decoders (ALAC, Dolby, DTS, ...);
     *  2. if the audio can't be read (cloud/streamed song, broken file, unsupported codec) the in-app online AI
     *     lookups (genre + mood tags) and the song's metadata choose the genre/mood target curve instead.
     * Metadata-only profiles carry analysisVersion [VERSION_ESTIMATED] so they can be upgraded to a real
     * measurement later.
     */
    suspend fun analyzeSong(song: Song): SongAnalysisResult = withContext(Dispatchers.Default) {
        var nativeFeatures: AudioFeatures? = null
        var features: AudioFeatures? = null
        var audioNote: String? = null
        if (song.source == SongSource.LOCAL) {
            try {
                NativeDsp().use { dsp ->
                    nativeFeatures = dsp.extractFeatures(context, song.uri, 60)
                    features = nativeFeatures ?: try {
                        pcmFeatureSource?.invoke(song, dsp)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (t: Throwable) {
                        Log.w(TAG, "Decoder-based analysis failed for ${song.title}: ${t.message}")
                        null
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.w(TAG, "Audio analysis failed for ${song.title}: ${t.message}")
            }
            if (features == null) audioNote = "audio unreadable (format: ${song.format.ifBlank { "unknown" }})"
        }
        val measured = features
        val fromAudio = measured != null

        val entity = try {
            buildAnalysis(song, measured ?: neutralFeatures(), fromAudio, online = true)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.e(TAG, "Analysis failed for ${song.title}; using offline defaults", t)
            try {
                buildAnalysis(song, neutralFeatures(), false, online = false)
            } catch (t2: Throwable) {
                Log.e(TAG, "Offline analysis failed for ${song.title}", t2)
                return@withContext SongAnalysisResult(null, null, "Analysis error: ${t2.message ?: t2.javaClass.simpleName}")
            }
        }
        // `features` stays the NATIVE result only: the quality scorer keys off it and must behave as before.
        SongAnalysisResult(entity, nativeFeatures, if (fromAudio) null else audioNote)
    }

    private suspend fun <T> withOnline(block: suspend () -> T?): T? = try {
        kotlinx.coroutines.withTimeoutOrNull(ONLINE_TIMEOUT_MS) { block() }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Online lookup unavailable: ${e.message}")
        null
    }

    private suspend fun buildAnalysis(
        song: Song,
        audioFeatures: AudioFeatures,
        fromAudio: Boolean,
        online: Boolean
    ): AiAnalysisEntity {
        // 2. Genre: TFLite / heuristic on the measured audio, otherwise from the song's own metadata.
        val genreResult = if (fromAudio) runInference(genreInterpreter, audioFeatures.spectralData)
        else InferenceResult(0, 0.0f, 0, 0.0f, isModelInference = false)
        var primaryGenre = when {
            genreResult.isModelInference && genreResult.primaryIndex in GENRES.indices -> GENRES[genreResult.primaryIndex]
            fromAudio -> heuristicGenre(audioFeatures)
            else -> metadataGenre(song)
        }

        // Online AI refinement (optional): the in-app genre service knows the artist/title.
        if (online && song.title.isNotBlank()) {
            val onlineGenre = withOnline { onlineAiService.fetchAccurateGenre(song.artist, song.title) }
            if (!onlineGenre.isNullOrEmpty()) {
                primaryGenre = onlineGenre
                Log.d(TAG, "Online AI refined genre: $onlineGenre")
            }
        }

        val secondaryGenre = if (genreResult.isModelInference && genreResult.secondaryConfidence > 0.35f && genreResult.secondaryIndex in GENRES.indices) {
            GENRES[genreResult.secondaryIndex]
        } else null

        // 3. Language
        val langResult = if (fromAudio) runInference(languageInterpreter, audioFeatures.spectralData)
        else InferenceResult(0, 0.0f, 0, 0.0f, isModelInference = false)
        val language = if (langResult.isModelInference && langResult.primaryIndex in LANGUAGES.indices) {
            LANGUAGES[langResult.primaryIndex]
        } else {
            heuristicLanguage(song)
        }

        // 4. Mood
        val moodResult = if (fromAudio) runInference(moodInterpreter, audioFeatures.spectralData)
        else InferenceResult(0, 0.0f, 0, 0.0f, isModelInference = false)
        val communityTags = if (online && song.title.isNotBlank()) {
            withOnline { moodApiService.fetchAccurateMoodTags(song.artist, song.title) } ?: emptyList()
        } else emptyList()
        val primaryMoodRaw = when {
            moodResult.isModelInference && moodResult.primaryIndex in MOODS.indices -> MOODS[moodResult.primaryIndex]
            fromAudio -> heuristicMood(audioFeatures)
            else -> communityTags.firstOrNull { it in MOODS } ?: moodFromGenre(primaryGenre)
        }
        val mood = mapToUiMood(primaryMoodRaw)

        // 4b. Multi-label mood set (local model + beat/BPM rules + community tags)
        val finalMoods = linkedSetOf<String>()
        finalMoods.add(mood)
        if (moodResult.isModelInference && moodResult.secondaryConfidence > 0.35f && moodResult.secondaryIndex in MOODS.indices) {
            finalMoods.add(mapToUiMood(MOODS[moodResult.secondaryIndex]))
        }
        if (fromAudio) finalMoods.addAll(bpmMoods(audioFeatures.tempoBpm))
        finalMoods.addAll(communityTags)
        val moodTags = finalMoods.joinToString(",")

        // The EQ is no longer part of this analysis: see engine/aieq (AiEqEngine). eq* columns stay 0 (legacy).

        return AiAnalysisEntity(
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
            lufs = audioFeatures.lufs,
            rms = audioFeatures.rms,
            peak = audioFeatures.peak,
            dynamicRange = audioFeatures.dynamicRange,
            bassScore = audioFeatures.bassScore,
            midScore = audioFeatures.midScore,
            trebleScore = audioFeatures.trebleScore,
            stereoWidth = audioFeatures.stereoWidth,
            tempoBpm = audioFeatures.tempoBpm,
            eq31 = 0f, eq62 = 0f, eq125 = 0f, eq250 = 0f, eq500 = 0f,
            eq1k = 0f, eq2k = 0f, eq4k = 0f, eq8k = 0f, eq16k = 0f,
            analysisVersion = if (fromAudio) VERSION_MEASURED else VERSION_ESTIMATED,
            lastAnalyzed = System.currentTimeMillis()
        )
    }

    /** Stand-in for a song whose audio couldn't be measured. */
    private fun neutralFeatures() = AudioFeatures(
        lufs = -14f, rms = 0.1f, peak = 0.8f, dynamicRange = 10f,
        bassScore = 0.33f, midScore = 0.33f, trebleScore = 0.33f,
        stereoWidth = 1f, tempoBpm = 0f,
        spectralData = FloatArray(128) { 1f }, noveltyVector = null,
        truePeakDb = -1f, clippedSamplePct = 0f, freqRangeLowHz = 0f, freqRangeHighHz = 0f
    )

    /** Genre from the song's tags, or a keyword guess from title/artist/folder, else Pop. */
    private fun metadataGenre(song: Song): String {
        val tag = song.genre.trim()
        if (tag.isNotEmpty() && !tag.equals("unknown", true) && !tag.equals("other", true) && !tag.equals("<unknown>", true)) {
            return tag
        }
        val text = "${song.title} ${song.artist} ${song.album}".lowercase()
        return when {
            listOf("remix", "edm", "club mix", "dj ").any { it in text } -> "EDM"
            listOf("lofi", "lo-fi").any { it in text } -> "Lo-Fi"
            listOf("acoustic", "unplugged").any { it in text } -> "Indie"
            listOf("instrumental", "karaoke").any { it in text } -> "Instrumental"
            listOf("podcast", "episode").any { it in text } -> "Podcast"
            listOf("audiobook", "chapter ").any { it in text } -> "Audiobook"
            listOf("devotional", "bhajan", "slokam", "stotram").any { it in text } -> "Tamil Devotional"
            else -> "Pop"
        }
    }

    private fun moodFromGenre(genre: String): String {
        val g = genre.lowercase()
        return when {
            listOf("metal", "rock", "hip", "rap", "mass").any { it in g } -> "Energetic"
            listOf("edm", "house", "techno", "trance", "dance", "electronic").any { it in g } -> "Party"
            listOf("classical", "ambient", "lo-fi", "instrumental", "jazz", "devotional", "melody").any { it in g } -> "Calm"
            listOf("blues", "soul", "r&b").any { it in g } -> "Romantic"
            listOf("podcast", "audiobook").any { it in g } -> "Focus"
            else -> "Happy"
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
        const val VERSION_MEASURED = 2
        const val VERSION_ESTIMATED = 3
        private const val ONLINE_TIMEOUT_MS = 8_000L

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
    /** Features from the NATIVE extractor only (null when it declined the file), as the quality scan expects. */
    val features: AudioFeatures?,
    /** Short, user-readable reason when [aiAnalysis] is null. */
    val failureReason: String? = null
)
