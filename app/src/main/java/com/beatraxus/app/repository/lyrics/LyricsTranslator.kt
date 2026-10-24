package com.beatraxus.app.repository.lyrics

import android.content.Context
import com.beatraxus.app.model.LrcLine
import com.beatraxus.app.model.LyricsTranslationLogic
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * On-demand lyric translation (same idea as BitChord): no models on the device, lines are sent in a
 * few batches and the compact answer is cached in memory + the app cache dir, so a song is only
 * ever translated once per target language.
 */
class LyricsTranslator(context: Context) {

    sealed interface Result {
        data class Translated(val lines: List<LrcLine>, val sourceLanguage: String) : Result
        data class SameLanguage(val language: String) : Result
        data object Unavailable : Result
    }

    private val cacheDir = File(context.cacheDir, "lyrics_translation_v1").apply { mkdirs() }
    private val client = LyricsHttpClient.client.newBuilder()
        .callTimeout(12, TimeUnit.SECONDS)
        .connectTimeout(5, TimeUnit.SECONDS)
        .build()

    private val memory = object : LinkedHashMap<String, Cached>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Cached>?) = size > 12
    }

    private data class Cached(val source: String, val texts: List<String>)
    private data class Answer(val parts: List<String>, val source: String, val weight: Int)

    suspend fun translate(trackId: String, lines: List<LrcLine>, targetTag: String): Result =
        withContext(Dispatchers.IO) {
            val target = targetTag.trim()
            if (target.isBlank() || lines.isEmpty()) return@withContext Result.Unavailable

            // Only real sung text is translated; everything else keeps translation = null.
            val indexes = lines.indices.filter { LyricsTranslationLogic.translatable(lines[it].text) }
            if (indexes.isEmpty()) return@withContext Result.Unavailable
            val texts = indexes.map { lines[it].text }

            val key = LyricsTranslationLogic.cacheKey(trackId, target, texts)
            val cached = synchronized(memory) { memory[key] } ?: readCache(key)?.also {
                synchronized(memory) { memory[key] = it }
            }
            if (cached != null && cached.texts.size == texts.size) {
                return@withContext finish(lines, indexes, cached, target)
            }

            val batches = LyricsTranslationLogic.batches(texts)
            val answers = try {
                coroutineScope {
                    // two short requests at a time: fast for long songs, light on the connection pool
                    batches.chunked(2).flatMap { group ->
                        group.map { batch -> async { request(texts, batch, target) } }.awaitAll()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            }
            if (answers.any { it == null }) return@withContext Result.Unavailable
            val complete = answers.filterNotNull()
            val source = complete.groupBy { it.source }
                .maxByOrNull { (_, v) -> v.sumOf { it.weight } }?.key.orEmpty()
            val translated = complete.flatMap { it.parts }
            if (source.isBlank() || translated.size != texts.size) return@withContext Result.Unavailable

            val entry = Cached(source, translated)
            synchronized(memory) { memory[key] = entry }
            writeCache(key, entry)
            finish(lines, indexes, entry, target)
        }

    private fun finish(lines: List<LrcLine>, indexes: List<Int>, c: Cached, target: String): Result {
        if (LyricsTranslationLogic.sameLanguage(c.source, target)) return Result.SameLanguage(c.source)
        val out = lines.toMutableList()
        indexes.forEachIndexed { n, lineIndex ->
            val t = c.texts[n]
            // identical to the original = nothing worth showing twice
            out[lineIndex] = lines[lineIndex].copy(
                translation = t.takeUnless { it.equals(lines[lineIndex].text.trim(), ignoreCase = true) }
            )
        }
        return Result.Translated(out, c.source)
    }

    private suspend fun request(texts: List<String>, batch: List<Int>, target: String): Answer? {
        val payload = LyricsTranslationLogic.payload(texts, batch)
        val body = FormBody.Builder()
            .add("client", "dict-chrome-ex")
            .add("sl", "auto")
            .add("tl", target)
            .add("dt", "t")
            .add("q", payload)
            .build()
        val request = Request.Builder()
            .url("https://translate.googleapis.com/translate_a/single")
            .header("Accept", "application/json")
            .post(body)
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val root = JsonParser.parseString(response.body?.string().orEmpty()).asJsonArray
                val translatedBody = root[0].asJsonArray.joinToString("") { seg ->
                    seg.asJsonArray.takeIf { it.size() > 0 && !it[0].isJsonNull }?.get(0)?.asString.orEmpty()
                }
                val source = root.takeIf { it.size() > 2 && !it[2].isJsonNull }?.get(2)?.asString.orEmpty()
                val parts = LyricsTranslationLogic.split(translatedBody, batch.size) ?: return null
                Answer(parts, source, payload.length)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: IOException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun readCache(key: String): Cached? = runCatching {
        val f = File(cacheDir, key)
        if (!f.exists()) return null
        val all = f.readLines()
        Cached(all.first(), all.drop(1).map { it.replace("\\n", "\n") })
    }.getOrNull()

    private fun writeCache(key: String, c: Cached) {
        runCatching {
            File(cacheDir, key).writeText((listOf(c.source) + c.texts.map { it.replace("\n", "\\n") }).joinToString("\n"))
            // bounded: keep the newest ~200 songs
            val files = cacheDir.listFiles()?.sortedByDescending { it.lastModified() } ?: return
            files.drop(200).forEach { it.delete() }
        }
    }
}
