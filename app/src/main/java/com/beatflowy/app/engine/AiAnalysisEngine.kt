package com.beatflowy.app.engine

import android.content.Context
import android.net.Uri
import android.util.Log
import com.beatflowy.app.model.AiAnalysisEntity
import com.beatflowy.app.model.Song
import com.beatflowy.app.repository.GenreApiService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AiAnalysisEngine(private val context: Context) {
    private val TAG = "AiAnalysisEngine"
    
    // Model interpreters
    private var genreInterpreter: Interpreter? = null
    private var languageInterpreter: Interpreter? = null
    private var moodInterpreter: Interpreter? = null
    private val onlineAiService = GenreApiService()
    
    init {
        try {
            // Assuming models are in assets. In a real production app, 
            // these would be downloaded or bundled.
            genreInterpreter = loadModel("models/genre_model.tflite")
            languageInterpreter = loadModel("models/language_model.tflite")
            moodInterpreter = loadModel("models/mood_model.tflite")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load TFLite models", e)
        }
    }

    private fun loadModel(path: String): Interpreter? {
        return try {
            val model = FileUtil.loadMappedFile(context, path)
            Interpreter(model, Interpreter.Options().apply {
                setNumThreads(4)
            })
        } catch (e: Exception) {
            null
        }
    }

    suspend fun analyzeSong(song: Song): AiAnalysisEntity? = withContext(Dispatchers.Default) {
        try {
            // 1. Extract audio features using native C++ engine
            // We analyze the first 60 seconds as requested
            val features = NativeDsp().extractFeatures(context, song.uri, 60) ?: return@withContext null
            
            // 2. Run AI Inference for Genre (Local + Online)
            val genreResult = runInference(genreInterpreter, features.spectralData)
            var primaryGenre = GENRES[genreResult.primaryIndex]
            
            // Online AI Enhancement for Accuracy
            val onlineGenre = onlineAiService.fetchAccurateGenre(song.artist, song.title)
            if (onlineGenre != null) {
                primaryGenre = onlineGenre
                Log.d(TAG, "Online AI refined genre: $onlineGenre")
            }

            val secondaryGenre = if (genreResult.secondaryConfidence > 0.5f) GENRES[genreResult.secondaryIndex] else null
            
            // 3. Run AI Inference for Language
            val langResult = runInference(languageInterpreter, features.spectralData)
            val language = LANGUAGES[langResult.primaryIndex]
            
            // 4. Run AI Inference for Mood
            val moodResult = runInference(moodInterpreter, features.spectralData)
            val mood = MOODS[moodResult.primaryIndex]
            
            // 5. Generate AI EQ Profile
            val aiEq = generateAiEq(
                primaryGenre, language, mood, 
                features.lufs, features.dynamicRange, 
                features.bassScore, features.midScore, features.trebleScore
            )
            
            AiAnalysisEntity(
                songId = song.id,
                genre = primaryGenre,
                genreConfidence = genreResult.primaryConfidence,
                secondaryGenre = secondaryGenre,
                secondaryGenreConfidence = if (secondaryGenre != null) genreResult.secondaryConfidence else null,
                language = language,
                languageConfidence = langResult.primaryConfidence,
                mood = mood,
                moodConfidence = moodResult.primaryConfidence,
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
                analysisVersion = 1,
                lastAnalyzed = System.currentTimeMillis()
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error analyzing song ${song.title}", e)
            null
        }
    }

    private fun runInference(interpreter: Interpreter?, inputData: FloatArray): InferenceResult {
        if (interpreter == null) return InferenceResult(0, 0f, 0, 0f)
        
        // This is a placeholder for actual TFLite inference logic
        // YAMNet or custom models would take mel-spectrogram as input
        val output = Array(1) { FloatArray(interpreter.getOutputTensor(0).shape()[1]) }
        interpreter.run(inputData, output)
        
        val results = output[0]
        var maxIdx = 0
        var maxVal = 0f
        var secIdx = 0
        var secVal = 0f
        
        for (i in results.indices) {
            if (results[i] > maxVal) {
                secVal = maxVal
                secIdx = maxIdx
                maxVal = results[i]
                maxIdx = i
            } else if (results[i] > secVal) {
                secVal = results[i]
                secIdx = i
            }
        }
        
        return InferenceResult(maxIdx, maxVal, secIdx, secVal)
    }

    private fun generateAiEq(
        genre: String, lang: String, mood: String,
        lufs: Float, dr: Float, bass: Float, mid: Float, treble: Float
    ): FloatArray {
        val eq = FloatArray(10) { 0f }
        
        // Base profile based on genre
        when (genre) {
            "Tamil Melody", "Hindi Melody" -> {
                eq[0] = 0.8f; eq[1] = 1.0f; eq[2] = 0.7f; eq[3] = 0.2f
                eq[5] = 0.3f; eq[6] = 0.5f; eq[7] = 0.8f; eq[8] = 1.0f; eq[9] = 0.6f
            }
            "EDM", "Electronic", "Dance" -> {
                eq[0] = 1.5f; eq[1] = 2.0f; eq[2] = 1.2f; eq[3] = 0.4f
                eq[5] = -0.3f; eq[6] = 0.2f; eq[7] = 0.5f; eq[8] = 0.8f; eq[9] = 0.5f
            }
            "Classical" -> {
                eq[6] = 0.3f; eq[7] = 0.5f; eq[8] = 0.5f; eq[9] = 0.3f
            }
            "Rock", "Metal" -> {
                eq[0] = 1.0f; eq[1] = 1.2f; eq[4] = -0.5f; eq[7] = 0.8f; eq[8] = 1.0f
            }
            "Hip-Hop", "Rap" -> {
                eq[0] = 2.0f; eq[1] = 1.5f; eq[2] = 0.8f; eq[8] = 0.5f
            }
        }
        
        // Adjust based on Mood
        when (mood) {
            "Energetic", "Aggressive", "Workout" -> {
                eq[0] += 0.5f; eq[1] += 0.5f; eq[7] += 0.3f; eq[8] += 0.3f
            }
            "Calm", "Relaxing", "Sleep" -> {
                eq[0] -= 0.5f; eq[1] -= 0.5f; eq[7] -= 0.5f; eq[8] -= 0.5f
            }
        }
        
        // Compensate for spectral imbalance
        if (bass < 0.3f) { eq[0] += 0.5f; eq[1] += 0.5f }
        if (treble < 0.3f) { eq[7] += 0.5f; eq[8] += 0.5f }
        
        // Clamp to ±3dB as per requirements
        for (i in eq.indices) {
            eq[i] = eq[i].coerceIn(-3f, 3f)
        }
        
        return eq
    }

    private data class InferenceResult(
        val primaryIndex: Int,
        val primaryConfidence: Float,
        val secondaryIndex: Int,
        val secondaryConfidence: Float
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
    }
}

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
    val spectralData: FloatArray // Pre-processed for TFLite
)
