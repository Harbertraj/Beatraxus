package com.beatraxus.app.engine

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

data class NlEqResponse(
    val gains: FloatArray, // 10 floats: 31Hz, 62Hz, 125Hz, 250Hz, 500Hz, 1kHz, 2kHz, 4kHz, 8kHz, 16kHz
    val preampDb: Float,
    val explanation: String
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as NlEqResponse

        if (!gains.contentEquals(other.gains)) return false
        if (preampDb != other.preampDb) return false
        if (explanation != other.explanation) return false

        return true
    }

    override fun hashCode(): Int {
        var result = gains.contentHashCode()
        result = 31 * result + preampDb.hashCode()
        result = 31 * result + explanation.hashCode()
        return result
    }
}

class NlEqAssistant(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
) {
    private val TAG = "NlEqAssistant"
    private val gson = Gson()

    suspend fun processPrompt(
        userPrompt: String,
        apiKey: String = getBuildConfigField("LLM_API_KEY"),
        endpoint: String = getBuildConfigField("LLM_ENDPOINT", "https://api.openai.com/v1/chat/completions"),
        modelName: String = getBuildConfigField("LLM_MODEL", "gemini-2.0-flash")
    ): Result<NlEqResponse> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || userPrompt.isBlank()) {
            Log.i(TAG, "LLM API key not configured or blank prompt; using offline keyword assistant.")
            return@withContext Result.success(generateOfflineFallback(userPrompt))
        }

        try {
            val systemInstruction = """
                You are an expert audio engineer assistant for Beatraxus Music Player.
                The user asks for tonal audio adjustments in natural language.
                Translate the request into a 10-band equalizer setting (frequencies: 31Hz, 62Hz, 125Hz, 250Hz, 500Hz, 1kHz, 2kHz, 4kHz, 8kHz, 16kHz).
                Respond STRICTLY with valid JSON in this schema and no markdown wrappers:
                {"gains":[f1, f2, f3, f4, f5, f6, f7, f8, f9, f10], "preampDb": float, "explanation": "string"}
                Gains must be floats between -12.0 and +12.0 dB.
            """.trimIndent()

            val effectiveModel = if (modelName.isNotBlank()) modelName else "gemini-2.0-flash"
            val requestJson = JsonObject().apply {
                addProperty("model", effectiveModel)
                addProperty("temperature", 0.3)
                add("messages", gson.toJsonTree(listOf(
                    mapOf("role" to "system", "content" to systemInstruction),
                    mapOf("role" to "user", "content" to userPrompt)
                )))
            }

            val requestBody = requestJson.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder()
                .url(endpoint)
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .post(requestBody)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "LLM API request failed code ${response.code}; using fallback")
                    return@withContext Result.success(generateOfflineFallback(userPrompt))
                }

                val bodyString = response.body?.string() ?: ""
                val rootObj = JsonParser.parseString(bodyString).asJsonObject
                val choices = rootObj.getAsJsonArray("choices")
                val content = choices[0].asJsonObject.getAsJsonObject("message").get("content").asString

                val validated = parseAndValidateResponse(content)
                if (validated.isSuccess) {
                    return@withContext validated
                } else {
                    Log.w(TAG, "LLM response JSON validation failed; falling back to offline engine")
                    return@withContext Result.success(generateOfflineFallback(userPrompt))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "NlEqAssistant network or parsing error: ${e.message}; using offline fallback")
            return@withContext Result.success(generateOfflineFallback(userPrompt))
        }
    }

    fun parseAndValidateResponse(jsonString: String): Result<NlEqResponse> {
        return try {
            val cleanedJson = jsonString.trim()
                .removePrefix("```json")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()

            val jsonObj = JsonParser.parseString(cleanedJson).asJsonObject

            if (!jsonObj.has("gains") || !jsonObj.get("gains").isJsonArray) {
                return Result.failure(IllegalArgumentException("Missing or invalid 'gains' array in JSON response"))
            }

            val gainsArray = jsonObj.getAsJsonArray("gains")
            if (gainsArray.size() != 10) {
                return Result.failure(IllegalArgumentException("Expected 10 band gains, received ${gainsArray.size()}"))
            }

            val gains = FloatArray(10) { i ->
                val g = gainsArray[i].asFloat
                g.coerceIn(-12.0f, 12.0f)
            }

            val preampDb = if (jsonObj.has("preampDb")) {
                jsonObj.get("preampDb").asFloat.coerceIn(-12.0f, 6.0f)
            } else {
                val maxBoost = gains.maxOrNull() ?: 0.0f
                if (maxBoost > 0f) -maxBoost else 0.0f
            }

            val explanation = if (jsonObj.has("explanation")) {
                jsonObj.get("explanation").asString.take(256)
            } else {
                "Applied natural language EQ adjustment."
            }

            Result.success(NlEqResponse(gains, preampDb, explanation))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun generateOfflineFallback(prompt: String): NlEqResponse {
        val lower = prompt.lowercase()
        val gains = FloatArray(10) { 0.0f }
        val explanations = mutableListOf<String>()

        if (lower.contains("bass") || lower.contains("warm") || lower.contains("punch")) {
            gains[0] += 2.5f; gains[1] += 2.0f; gains[2] += 1.0f
            explanations.add("Boosted low frequencies for warmth and bass punch")
        }
        if (lower.contains("vocal") || lower.contains("voice") || lower.contains("clarity") || lower.contains("speech")) {
            gains[3] -= 1.0f; gains[5] += 1.5f; gains[6] += 2.0f; gains[7] += 1.5f
            explanations.add("Enhanced 1k-4k midrange for vocal clarity")
        }
        if (lower.contains("harsh") || lower.contains("sibilan") || lower.contains("cymbals") || lower.contains("tame")) {
            gains[7] -= 2.0f; gains[8] -= 2.5f; gains[9] -= 2.0f
            explanations.add("Attenuated high frequencies to soften harsh cymbals and sibilance")
        }
        if (lower.contains("air") || lower.contains("sparkle") || lower.contains("bright")) {
            gains[8] += 1.5f; gains[9] += 2.5f
            explanations.add("Boosted 8k-16k band for airy highs and sparkle")
        }

        if (explanations.isEmpty()) {
            gains[0] = 1.0f; gains[1] = 0.8f; gains[6] = 0.8f; gains[7] = 1.0f
            explanations.add("Applied balanced dynamic curve for: \"$prompt\"")
        }

        val maxBoost = gains.maxOrNull() ?: 0.0f
        val preampDb = if (maxBoost > 0f) -maxBoost else 0.0f

        return NlEqResponse(
            gains = gains,
            preampDb = preampDb,
            explanation = explanations.joinToString(". ")
        )
    }

    companion object {
        private fun getBuildConfigField(fieldName: String, defaultValue: String = ""): String {
            return try {
                val clazz = Class.forName("com.beatraxus.app.BuildConfig")
                val field = clazz.getField(fieldName)
                field.get(null) as? String ?: defaultValue
            } catch (e: Exception) {
                defaultValue
            }
        }
    }
}
