package com.beatraxus.app.subtitles.generate

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Response
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
    fun failure(code: Int, body: String, service: String = "Speech service"): GenerationException {
        val detail = runCatching { JsonParser.parseString(body).asJsonObject["error"]?.asJsonObject?.get("message")?.asString }
            .getOrNull()
        val kind = when (code) {
            401, 403 -> GenerationException.Kind.AUTH
            429 -> GenerationException.Kind.RATE_LIMITED
            413 -> GenerationException.Kind.UNSUPPORTED_SOURCE
            in 500..599 -> GenerationException.Kind.SERVICE
            else -> GenerationException.Kind.SERVICE
        }
        return GenerationException(kind, detail ?: "$service answered HTTP $code")
    }

    fun ioFailure(e: IOException, service: String = "Speech service"): GenerationException =
        GenerationException(GenerationException.Kind.NETWORK, "No connection to the ${service.lowercase()}", e)

    /** The longest single wait honoured after a rate-limited answer (an hourly audio quota can ask for many minutes). */
    const val MAX_SINGLE_WAIT_MS = 20L * 60_000L

    /**
     * How long a rate-limited answer asks us to wait: its Retry-After, else the "try again in 7m12s" of its message, else a
     * back-off. Null when waiting is pointless: the paid quota is used up, or the limit lifts only hours from now.
     */
    fun rateLimitWaitMs(response: Response, body: String, attempt: Int): Long? {
        if (body.contains("insufficient_quota", ignoreCase = true) || body.contains("exceeded your current quota", ignoreCase = true)) return null
        val fromHeader = response.header("Retry-After")?.trim()?.toDoubleOrNull()?.let { (it * 1000).toLong() }
        val backoff = 5_000L shl attempt.coerceIn(0, 4)
        val wanted = fromHeader ?: parseTryAgainMs(body) ?: backoff
        if (wanted > MAX_SINGLE_WAIT_MS) return null
        // A little extra, so the request does not arrive a moment before the limit is lifted.
        return wanted.coerceAtLeast(2_000L) + 1_000L
    }

    private val TRY_AGAIN = Regex("try again in\\s+([0-9hms.]+)", RegexOption.IGNORE_CASE)
    private val DURATION_PART = Regex("([0-9]+(?:\\.[0-9]+)?)(ms|h|m|s)")

    /** Reads the wait out of a message such as "Please try again in 7m12.5s", "in 1h2m3s" or "in 350ms"; null when there is none. */
    fun parseTryAgainMs(body: String): Long? {
        val spec = TRY_AGAIN.find(body)?.groupValues?.get(1) ?: return null
        var total = 0.0
        var found = false
        for (part in DURATION_PART.findAll(spec)) {
            val value = part.groupValues[1].toDoubleOrNull() ?: continue
            total += when (part.groupValues[2]) {
                "h" -> value * 3_600_000.0
                "m" -> value * 60_000.0
                "s" -> value * 1_000.0
                else -> value
            }
            found = true
        }
        return if (found) total.toLong() else null
    }
}

/**
 * Recognises speech with an OpenAI-style `/audio/transcriptions` endpoint and `verbose_json` output, which carries
 * segment and word timestamps. The timestamps are what keep the subtitles in step with the audio.
 */
class OpenAiSpeechTranscriber(
    private val endpoint: String,
    private val apiKey: String,
    private val model: String,
    /** Called every second while a rate limit is being waited out: (time left, the whole wait). 0 left = the wait is over. */
    private val onRateLimited: (leftMs: Long, totalMs: Long) -> Unit = { _, _ -> }
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

        // Free plans cap the audio seconds per hour (and requests per minute). Going over is not a failure: the answer says
        // how long to wait, so wait that long (showing it) and ask again instead of giving up after a few seconds.
        var attempt = 0
        var waitedMs = 0L
        var result: WhisperTranscript? = null
        while (result == null) {
            var waitMs = 0L
            result = try {
                OpenAiStyle.client.newCall(request).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    when {
                        response.isSuccessful -> WhisperResponseParser.parse(text)
                        response.code == 429 -> {
                            waitMs = OpenAiStyle.rateLimitWaitMs(response, text, attempt)
                                ?: throw OpenAiStyle.failure(429, text)
                            if (waitedMs + waitMs > MAX_TOTAL_WAIT_MS) throw OpenAiStyle.failure(429, text)
                            null
                        }
                        else -> throw OpenAiStyle.failure(response.code, text)
                    }
                }
            } catch (e: IOException) {
                throw OpenAiStyle.ioFailure(e)
            }
            if (result == null) {
                attempt++
                waitedMs += waitMs
                var left = waitMs
                while (left > 0) {
                    onRateLimited(left, waitMs)
                    val step = minOf(1_000L, left)
                    delay(step)
                    left -= step
                }
                onRateLimited(0L, waitMs)
            }
        }
        result
    }

    private companion object {
        /** Total time one chunk may spend waiting for a rate limit before the run stops (it can be resumed later). */
        const val MAX_TOTAL_WAIT_MS = 90L * 60_000L
    }
}

/**
 * Translates subtitle lines with a chat-completions model. Only the text is sent; the timing of every line stays
 * with the caller, so a translation cannot move a subtitle.
 */
class ChatCompletionsTranslator(
    private val endpoint: String,
    private val apiKey: String,
    private val model: String,
    /** Called every second while a rate limit is being waited out: (time left, the whole wait). 0 left = the wait is over. */
    private val onRateLimited: (leftMs: Long, totalMs: Long) -> Unit = { _, _ -> }
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

        // Free translation plans cap tokens/requests per minute. Going over is not a failure: the answer says how long
        // to wait, so wait that long (showing it) and ask again instead of stopping the whole subtitle at part 1.
        var attempt = 0
        var waitedMs = 0L
        var content: String? = null
        while (content == null) {
            var waitMs = 0L
            content = try {
                OpenAiStyle.client.newCall(request).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    when {
                        response.isSuccessful -> JsonParser.parseString(text).asJsonObject
                            .getAsJsonArray("choices")[0].asJsonObject
                            .getAsJsonObject("message")["content"].asString
                        response.code == 429 -> {
                            waitMs = OpenAiStyle.rateLimitWaitMs(response, text, attempt)
                                ?: throw OpenAiStyle.failure(429, text, SERVICE_NAME)
                            if (waitedMs + waitMs > MAX_TOTAL_WAIT_MS) throw OpenAiStyle.failure(429, text, SERVICE_NAME)
                            null
                        }
                        else -> throw OpenAiStyle.failure(response.code, text, SERVICE_NAME)
                    }
                }
            } catch (e: IOException) {
                throw OpenAiStyle.ioFailure(e, SERVICE_NAME)
            } catch (e: GenerationException) {
                throw e
            } catch (e: Exception) {
                throw GenerationException(GenerationException.Kind.BAD_RESPONSE, "Translation answer was not understood", e)
            }
            if (content == null) {
                attempt++
                waitedMs += waitMs
                var left = waitMs
                while (left > 0) {
                    onRateLimited(left, waitMs)
                    val step = minOf(1_000L, left)
                    delay(step)
                    left -= step
                }
                onRateLimited(0L, waitMs)
            }
        }
        TranslationJson.parseAnswer(content, lines)
    }

    private companion object {
        const val SERVICE_NAME = "Translation service"

        /** Total time one batch may spend waiting for a rate limit before the run stops (it continues from there later). */
        const val MAX_TOTAL_WAIT_MS = 30L * 60_000L
    }
}
