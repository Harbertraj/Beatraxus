package com.beatraxus.app.subtitles.generate

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Shared HTTP plumbing and error mapping for OpenAI-compatible services (OpenAI, Groq, self-hosted servers). */
internal object OpenAiStyle {

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        // A ten-minute chunk can take a while to recognise on a slow server.
        .readTimeout(300, TimeUnit.SECONDS)
        .build()

    /** Maps an HTTP failure to the kind the viewer needs to act on it. */
    fun failure(code: Int, body: String): GenerationException {
        val detail = runCatching { JsonParser.parseString(body).asJsonObject["error"]?.asJsonObject?.get("message")?.asString }
            .getOrNull()
        val kind = when (code) {
            401, 403 -> GenerationException.Kind.AUTH
            429 -> GenerationException.Kind.RATE_LIMITED
            413 -> GenerationException.Kind.UNSUPPORTED_SOURCE
            in 500..599 -> GenerationException.Kind.SERVICE
            else -> GenerationException.Kind.SERVICE
        }
        return GenerationException(kind, detail ?: "Speech service answered HTTP $code")
    }

    fun ioFailure(e: IOException): GenerationException =
        GenerationException(GenerationException.Kind.NETWORK, "No connection to the speech service", e)
}

/**
 * Recognises speech with an OpenAI-style `/audio/transcriptions` endpoint and `verbose_json` output, which carries
 * segment and word timestamps. The timestamps are what keep the subtitles in step with the audio.
 */
class OpenAiSpeechTranscriber(
    private val endpoint: String,
    private val apiKey: String,
    private val model: String
) : SpeechTranscriber {

    override suspend fun transcribe(audio: File, languageCode: String?): WhisperTranscript = withContext(Dispatchers.IO) {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", audio.name, audio.asRequestBody("audio/wav".toMediaType()))
            .addFormDataPart("model", model)
            .addFormDataPart("response_format", "verbose_json")
            .addFormDataPart("timestamp_granularities[]", "segment")
            .addFormDataPart("timestamp_granularities[]", "word")
            .apply { if (!languageCode.isNullOrBlank()) addFormDataPart("language", WhisperLanguages.base(languageCode)!!) }
            .build()

        val request = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer $apiKey")
            .post(body)
            .build()

        try {
            OpenAiStyle.client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw OpenAiStyle.failure(response.code, text)
                WhisperResponseParser.parse(text)
            }
        } catch (e: IOException) {
            throw OpenAiStyle.ioFailure(e)
        }
    }
}

/**
 * Translates subtitle lines with a chat-completions model. Only the text is sent; the timing of every line stays
 * with the caller, so a translation cannot move a subtitle.
 */
class ChatCompletionsTranslator(
    private val endpoint: String,
    private val apiKey: String,
    private val model: String
) : TextTranslator {

    override suspend fun translate(
        lines: List<IndexedLine>,
        fromLanguageName: String?,
        toLanguageName: String
    ): Map<Int, String> = withContext(Dispatchers.IO) {
        val messages = JsonArray().apply {
            add(JsonObject().apply {
                addProperty("role", "system")
                addProperty("content", TranslationJson.systemPrompt(fromLanguageName, toLanguageName))
            })
            add(JsonObject().apply {
                addProperty("role", "user")
                addProperty("content", TranslationJson.userMessage(lines))
            })
        }
        val payload = JsonObject().apply {
            addProperty("model", model)
            addProperty("temperature", 0.2)
            add("messages", messages)
        }

        val request = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer $apiKey")
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        val content = try {
            OpenAiStyle.client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw OpenAiStyle.failure(response.code, text)
                JsonParser.parseString(text).asJsonObject
                    .getAsJsonArray("choices")[0].asJsonObject
                    .getAsJsonObject("message")["content"].asString
            }
        } catch (e: IOException) {
            throw OpenAiStyle.ioFailure(e)
        } catch (e: GenerationException) {
            throw e
        } catch (e: Exception) {
            throw GenerationException(GenerationException.Kind.BAD_RESPONSE, "Translation answer was not understood", e)
        }
        TranslationJson.parseAnswer(content, lines)
    }
}
