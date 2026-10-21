package com.beatraxus.app.theme

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** The store's index plus whether it came from the network or from the offline cache. */
data class StoreIndex(val entries: List<ThemeIndexEntry>, val fromCache: Boolean)

/**
 * Downloads themes from the theme repo and keeps everything on the phone so it works offline.
 *
 * Files live in `filesDir/themes/`:
 *  - `index.json`            last store index that downloaded successfully
 *  - `installed/<id>.json`   every installed theme (from the store or imported)
 */
class ThemeRepository(context: Context) {

    private val root = File(context.applicationContext.filesDir, "themes")
    private val installedDir = File(root, "installed")
    private val indexFile = File(root, "index.json")

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    // ---------------------------------------------------------------- store

    /** Fresh index from the network, or the cached one when offline. Throws only if there is neither. */
    suspend fun loadIndex(forceRefresh: Boolean = false): StoreIndex = withContext(Dispatchers.IO) {
        val cached = readCachedIndex()
        if (!forceRefresh && cached != null && System.currentTimeMillis() - indexFile.lastModified() < INDEX_FRESH_MS) {
            return@withContext StoreIndex(cached, fromCache = true)
        }
        try {
            val text = download("index.json", ThemeCodec.MAX_INDEX_BYTES)
            val entries = ThemeCodec.parseIndex(text).getOrThrow()
            writeAtomic(indexFile, text)
            StoreIndex(entries, fromCache = false)
        } catch (e: Exception) {
            if (cached != null) StoreIndex(cached, fromCache = true)
            else throw IOException("Couldn't load themes. Check your connection and try again.", e)
        }
    }

    /** Downloads one store theme, validates it, and saves it to Installed. */
    suspend fun installFromStore(entry: ThemeIndexEntry): Result<ThemeSpec> = withContext(Dispatchers.IO) {
        runCatching {
            val text = download(entry.file, ThemeCodec.MAX_THEME_BYTES)
            val spec = ThemeCodec.parseTheme(text).getOrThrow()
            // The file must be the theme the index promised, otherwise a swapped file could
            // masquerade as another theme.
            require(spec.id == entry.id) { "Theme file doesn't match the store listing" }
            save(spec)
            spec
        }
    }

    // ---------------------------------------------------------------- installed

    suspend fun installed(): List<ThemeSpec> = withContext(Dispatchers.IO) {
        installedDir.listFiles { f -> f.extension == "json" }.orEmpty()
            .mapNotNull { f -> runCatching { ThemeCodec.parseTheme(f.readText()).getOrThrow() }.getOrNull() }
            .sortedBy { it.name.lowercase() }
    }

    suspend fun save(spec: ThemeSpec) = withContext(Dispatchers.IO) {
        require(ThemeCodec.isValidId(spec.id))
        writeAtomic(File(installedDir, "${spec.id}.json"), ThemeCodec.toJson(spec))
    }

    suspend fun remove(id: String) = withContext(Dispatchers.IO) {
        if (ThemeCodec.isValidId(id)) File(installedDir, "$id.json").delete()
        Unit
    }

    /** Import from a pasted code, a beatraxus://theme link, or the contents of a .json theme file. */
    fun parseImport(input: String): Result<ThemeSpec> {
        val text = input.trim()
        return if (text.startsWith("{")) ThemeCodec.parseTheme(text) else ThemeCodec.decodeShare(text)
    }

    // ---------------------------------------------------------------- io

    private fun readCachedIndex(): List<ThemeIndexEntry>? =
        if (indexFile.exists()) runCatching { ThemeCodec.parseIndex(indexFile.readText()).getOrThrow() }.getOrNull() else null

    /** Tries raw.githubusercontent first (always current), then jsDelivr as a mirror. */
    private fun download(path: String, maxBytes: Int): String {
        var last: Exception? = null
        for (base in BASES) {
            try {
                return fetch(base + path, maxBytes)
            } catch (e: Exception) {
                last = e
            }
        }
        throw IOException("Download failed: $path", last)
    }

    private fun fetch(url: String, maxBytes: Int): String {
        val request = Request.Builder().url(url).header("Accept", "application/json").build()
        http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val body = resp.body ?: throw IOException("Empty response")
            val declared = body.contentLength()
            if (declared > maxBytes) throw IOException("Response too large")
            // Read at most maxBytes + 1 so an endless or mislabelled body can't fill memory.
            val bytes = body.source().let { src ->
                src.request(maxBytes + 1L)
                src.buffer.readByteArray(minOf(src.buffer.size, maxBytes + 1L))
            }
            if (bytes.size > maxBytes) throw IOException("Response too large")
            return String(bytes, Charsets.UTF_8)
        }
    }

    private fun writeAtomic(target: File, text: String) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) {
            target.writeText(text)
            tmp.delete()
        }
    }

    companion object {
        const val REPO = "Harbertraj/Beatraxus-themes"
        const val BRANCH = "main"
        private val BASES = listOf(
            "https://raw.githubusercontent.com/$REPO/$BRANCH/",
            "https://cdn.jsdelivr.net/gh/$REPO@$BRANCH/"
        )
        private const val INDEX_FRESH_MS = 10 * 60 * 1000L
    }
}
