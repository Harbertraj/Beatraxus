package com.beatraxus.app.engine.aieq

import android.content.Context
import android.util.Log
import com.beatraxus.app.engine.AudioFeatures
import com.beatraxus.app.engine.NativeDsp
import com.beatraxus.app.engine.PcmFeatureSource
import com.beatraxus.app.model.Song
import com.beatraxus.app.model.SongSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Free, offline, per-song EQ predictor. NEVER fails: whatever can't be measured falls back to what the
 * song's tags / names say, and as a last resort to a neutral "Pop" curve.
 *
 *  1. Tags + titles + folder -> genre, mood, language ([AiEqKnowledge.inferFromMetadata]).
 *  2. Local files: a short native analysis (or the app's own decoders for ALAC/Dolby/DTS) measures the real
 *     spectrum, loudness and dynamics, which then correct the genre curve for THIS recording.
 *  3. File facts (year, bitrate) refine the curve even when the audio can't be read.
 */
class AiEqEngine(
    private val context: Context,
    private val pcmFeatureSource: PcmFeatureSource? = null
) {
    suspend fun predict(song: Song): AiEqProfile = withContext(Dispatchers.Default) {
        val inferred = try {
            AiEqKnowledge.inferFromMetadata(
                genreTag = song.genre, title = song.title, artist = song.artist, album = song.album,
                folder = song.folder, fileName = song.uri.lastPathSegment.orEmpty()
            )
        } catch (t: Throwable) {
            Log.w(TAG, "Metadata inference failed for ${song.title}: ${t.message}")
            AiEqKnowledge.Inferred("Pop", "Pop", "Happy", "English", known = false)
        }

        val features: AudioFeatures? =
            if (song.source == SongSource.LOCAL) measure(song) else null

        val measured = features?.let {
            AiEqKnowledge.Measured(
                spectral128 = it.spectralData, lufs = it.lufs, dynamicRangeDb = it.dynamicRange,
                bassScore = it.bassScore, midScore = it.midScore, trebleScore = it.trebleScore, tempoBpm = it.tempoBpm
            )
        }

        // Tempo + key from the audio (BPM from the onset curve, key from the pitch-class profile).
        val tempo = estimateTempo(features)
        val key = AiEqMusicAnalysis.estimateKey(features?.chroma)?.takeIf { it.confidence >= MIN_KEY_CONFIDENCE }
        val music = AiEqKnowledge.MusicTraits(
            bpm = tempo.bpm, bpmConfidence = tempo.confidence,
            tonic = key?.tonic ?: 0, minor = key?.minor ?: false, keyConfidence = key?.confidence ?: 0f
        )

        // Tempo and mode sharpen the mood when the title gave no hint and the genre is a broad one.
        val mood = refineMood(inferred, tempo, key)

        val traits = AiEqKnowledge.FileTraits(
            year = song.year,
            bitrateKbps = song.bitrate,
            lossy = song.format.lowercase().let { f ->
                f.contains("mp3") || f.contains("aac") || f.contains("ogg") || f.contains("opus") || f.contains("wma") || f.contains("m4a") && song.bitrate in 1..400
            }
        )

        val gains = try {
            AiEqKnowledge.buildGains(inferred.copy(mood = mood), traits, measured, music)
        } catch (t: Throwable) {
            Log.w(TAG, "Gain build failed for ${song.title}: ${t.message}")
            AiEqKnowledge.curveFor("Pop")
        }

        AiEqProfile(
            songId = song.id,
            genre = inferred.genre,
            mood = mood,
            language = inferred.language,
            gains = gains,
            confidence = AiEqKnowledge.confidence(inferred, measured != null, music),
            source = if (measured != null) AiEqProfile.Source.AUDIO else AiEqProfile.Source.METADATA,
            bpm = tempo.bpm,
            key = key?.label.orEmpty()
        )
    }

    /**
     * Tempo from the onset curve. When the native analyzer gave no usable curve (or its rate), fall back to its
     * own coarse tempo - but only with low confidence, and never its 120 BPM "couldn't tell" default.
     */
    private fun estimateTempo(f: AudioFeatures?): AiEqMusicAnalysis.Tempo {
        if (f == null) return AiEqMusicAnalysis.Tempo(0f, 0f)
        val rate = if (f.noveltyRateHz > 0f) f.noveltyRateHz else DEFAULT_NOVELTY_RATE_HZ
        val fromCurve = AiEqMusicAnalysis.estimateTempo(f.noveltyVector, rate)
        if (fromCurve.known) return fromCurve
        val native = f.tempoBpm
        return if (native in 60f..200f && native != 120f) AiEqMusicAnalysis.Tempo(native, 0.25f)
        else AiEqMusicAnalysis.Tempo(0f, 0f)
    }

    private fun refineMood(inferred: AiEqKnowledge.Inferred, tempo: AiEqMusicAnalysis.Tempo, key: AiEqMusicAnalysis.Key?): String {
        val bpm = tempo.bpm
        val mood = inferred.mood
        if (bpm <= 0f || tempo.confidence < 0.3f) return mood
        // keep a mood that came from the title/genre unless it's just the generic default for a fast/slow track
        return when {
            bpm >= 135f && mood in setOf("Happy", "Romantic", "Calm") -> "Energetic"
            bpm in 1f..70f && mood in setOf("Happy", "Energetic", "Party") -> "Calm"
            bpm < 100f && key != null && key.minor && key.confidence >= 0.6f && mood == "Happy" -> "Emotional"
            else -> mood
        }
    }

    /** Short audio measurement; null when the audio can't be read (never throws, except cancellation). */
    private suspend fun measure(song: Song): AudioFeatures? {
        return try {
            withTimeoutOrNull(MEASURE_TIMEOUT_MS) {
                NativeDsp().use { dsp ->
                    dsp.extractFeatures(context, song.uri, ANALYSIS_SECONDS)
                        ?: try { pcmFeatureSource?.invoke(song, dsp) } catch (e: CancellationException) { throw e } catch (t: Throwable) {
                            Log.w(TAG, "Decoder analysis failed for ${song.title}: ${t.message}"); null
                        }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w(TAG, "Audio measurement failed for ${song.title}: ${t.message}")
            null
        }
    }

    private companion object {
        const val TAG = "AiEqEngine"
        const val ANALYSIS_SECONDS = 30
        const val MEASURE_TIMEOUT_MS = 45_000L
        const val MIN_KEY_CONFIDENCE = 0.25f
        /** 44.1 kHz / 2048-sample hop - the native analyzer's novelty rate when it doesn't report one. */
        const val DEFAULT_NOVELTY_RATE_HZ = 44100f / 2048f
    }
}
