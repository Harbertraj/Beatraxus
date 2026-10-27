package com.beatraxus.app.subtitles.generate

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.Locale

/** One recognised word. Times are milliseconds from the start of the audio that was sent for recognition. */
data class WhisperWord(val startMs: Long, val endMs: Long, val text: String)

data class WhisperSegment(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val noSpeechProb: Double? = null,
    val avgLogprob: Double? = null,
    val compressionRatio: Double? = null
)

/** The result of recognising one chunk of audio. Every time is relative to the start of that chunk. */
data class WhisperTranscript(
    /** Language the service detected, as it names it ("hindi"), or null when it did not say. */
    val languageName: String?,
    val segments: List<WhisperSegment>,
    val words: List<WhisperWord>
)

/** Why generating subtitles failed. [kind] decides what the viewer is told and whether a retry makes sense. */
class GenerationException(
    val kind: Kind,
    message: String? = null,
    cause: Throwable? = null,
    /** For [Kind.RATE_LIMITED]: how long the service asked to wait, or null when waiting is pointless (quota used up). */
    val retryAfterMs: Long? = null
) : Exception(message ?: kind.name, cause) {
    enum class Kind {
        NOT_CONFIGURED,
        UNSUPPORTED_SOURCE,
        NO_AUDIO,
        NO_SPEECH,
        NETWORK,
        AUTH,
        RATE_LIMITED,
        SERVICE,
        BAD_RESPONSE
    }
}

/**
 * Reads the `verbose_json` answer of an OpenAI-style `/audio/transcriptions` endpoint (OpenAI, Groq and most
 * self-hosted Whisper servers answer in this shape).
 */
object WhisperResponseParser {

    fun parse(json: String): WhisperTranscript {
        val root = try {
            JsonParser.parseString(json).asJsonObject
        } catch (e: Exception) {
            throw GenerationException(GenerationException.Kind.BAD_RESPONSE, "Speech service returned something that is not JSON", e)
        }

        val segments = root.getAsArrayOrNull("segments").orEmpty().mapNotNull { el ->
            val o = el.asObjectOrNull() ?: return@mapNotNull null
            val start = o.seconds("start") ?: return@mapNotNull null
            val end = o.seconds("end") ?: return@mapNotNull null
            WhisperSegment(
                startMs = start,
                endMs = end,
                text = o.string("text").orEmpty().trim(),
                noSpeechProb = o.double("no_speech_prob"),
                avgLogprob = o.double("avg_logprob"),
                compressionRatio = o.double("compression_ratio")
            )
        }.sortedBy { it.startMs }

        val words = root.getAsArrayOrNull("words").orEmpty().mapNotNull { el ->
            val o = el.asObjectOrNull() ?: return@mapNotNull null
            val start = o.seconds("start") ?: return@mapNotNull null
            val end = o.seconds("end") ?: return@mapNotNull null
            val text = o.string("word").orEmpty().trim()
            if (text.isEmpty()) null else WhisperWord(start, end, text)
        }.sortedBy { it.startMs }

        val hasText = !root.string("text").isNullOrBlank()
        if (segments.isEmpty() && hasText) {
            // Text without timestamps cannot be placed on the video, so it is not usable as subtitles.
            throw GenerationException(
                GenerationException.Kind.BAD_RESPONSE,
                "The speech service did not return timestamps. Use a Whisper model that supports verbose_json."
            )
        }

        return WhisperTranscript(root.string("language"), segments, words)
    }

    private fun JsonObject.getAsArrayOrNull(name: String) =
        get(name)?.takeIf { it.isJsonArray }?.asJsonArray?.toList()

    private fun JsonElement.asObjectOrNull(): JsonObject? = if (isJsonObject) asJsonObject else null

    private fun JsonObject.string(name: String): String? =
        get(name)?.takeIf { it.isJsonPrimitive }?.asString

    private fun JsonObject.double(name: String): Double? =
        get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble

    private fun JsonObject.seconds(name: String): Long? = double(name)?.let { Math.round(it * 1000.0) }
}

/** Language names the way Whisper reports them, mapped to the ISO codes the rest of the app uses. */
object WhisperLanguages {

    private val NAME_TO_CODE: Map<String, String> = mapOf(
        "english" to "en", "hindi" to "hi", "tamil" to "ta", "telugu" to "te", "malayalam" to "ml",
        "kannada" to "kn", "bengali" to "bn", "marathi" to "mr", "urdu" to "ur", "gujarati" to "gu",
        "punjabi" to "pa", "nepali" to "ne", "sinhala" to "si", "assamese" to "as", "sanskrit" to "sa",
        "spanish" to "es", "french" to "fr", "german" to "de", "italian" to "it", "portuguese" to "pt",
        "russian" to "ru", "japanese" to "ja", "korean" to "ko", "chinese" to "zh", "arabic" to "ar",
        "turkish" to "tr", "dutch" to "nl", "polish" to "pl", "ukrainian" to "uk", "vietnamese" to "vi",
        "thai" to "th", "indonesian" to "id", "malay" to "ms", "persian" to "fa", "hebrew" to "he",
        "greek" to "el", "swedish" to "sv", "norwegian" to "no", "danish" to "da", "finnish" to "fi",
        "czech" to "cs", "hungarian" to "hu", "romanian" to "ro", "bulgarian" to "bg", "croatian" to "hr",
        "serbian" to "sr", "slovak" to "sk", "slovenian" to "sl", "catalan" to "ca", "tagalog" to "tl",
        "swahili" to "sw", "afrikaans" to "af", "burmese" to "my", "khmer" to "km", "lao" to "lo",
        "mongolian" to "mn", "azerbaijani" to "az", "kazakh" to "kk", "uzbek" to "uz", "armenian" to "hy",
        "georgian" to "ka", "lithuanian" to "lt", "latvian" to "lv", "estonian" to "et", "icelandic" to "is",
        "albanian" to "sq", "macedonian" to "mk", "bosnian" to "bs", "belarusian" to "be", "welsh" to "cy",
        "basque" to "eu", "galician" to "gl", "somali" to "so", "amharic" to "am", "pashto" to "ps"
    )

    /** "hindi" -> "hi". Accepts a code too ("hi", "zh-CN"), and returns null for a name it does not know. */
    fun codeFor(name: String?): String? {
        val n = name?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: return null
        NAME_TO_CODE[n]?.let { return it }
        return if (n.matches(Regex("[a-z]{2}(-[a-z]{2})?"))) n else null
    }

    /** Languages written without spaces between words; their subtitle text is never rebuilt by joining words. */
    private val NO_SPACE = setOf("zh", "ja", "th", "lo", "my", "km")

    fun usesSpaces(code: String?): Boolean =
        code?.substringBefore('-')?.lowercase(Locale.ROOT) !in NO_SPACE

    /** The language part only: "zh-CN" -> "zh". */
    fun base(code: String?): String? = code?.substringBefore('-')?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }

    fun sameLanguage(a: String?, b: String?): Boolean {
        val x = base(a) ?: return false
        val y = base(b) ?: return false
        return x == y
    }

    /** English display name of a language code, for prompts and labels ("hi" -> "Hindi"). */
    fun displayName(code: String?): String {
        val c = code?.takeIf { it.isNotBlank() } ?: return "Unknown"
        val name = Locale.forLanguageTag(c).getDisplayName(Locale.ENGLISH)
        return if (name.isBlank() || name.equals(c, ignoreCase = true)) c.uppercase(Locale.ROOT) else name
    }
}
