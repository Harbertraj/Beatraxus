package com.beatraxus.app.theme

import android.net.Uri
import android.util.Base64
import androidx.compose.ui.graphics.toArgb
import com.beatraxus.app.model.NowPlayingBackgroundMode
import com.beatraxus.app.model.SeekbarStyle
import com.beatraxus.app.ui.theme.DspPalette
import com.beatraxus.app.ui.theme.FontRepository
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/**
 * Reads, validates and writes theme files, the store index and share links/codes.
 *
 * Theme files come from the internet (the store repo, a friend's link), so everything is treated
 * as untrusted: sizes are capped, ids and paths are matched against strict patterns, numbers are
 * clamped, and unknown keys are ignored. A theme can only ever name things the app already
 * knows (a seekbar style, a font id, a palette id), never run anything.
 *
 * ### File format (schema 1)
 * ```json
 * {
 *   "schema": 1,
 *   "id": "neon-night",
 *   "name": "Neon Night",
 *   "author": "Harbertraj",
 *   "version": 1,
 *   "mode": "dark",
 *   "colors": { "accent": "#00E5C0", "accentSecondary": "#8B5CF6",
 *               "preview": ["#0B0B14", "#00E5C0", "#8B5CF6"] },
 *   "dsp": { "palette": "aurora_dusk" },
 *   "seekbar": "BEATRAXUS_MATRIX",
 *   "font": "outfit",
 *   "background": { "mode": "BLUR", "blurScale": 1.0, "blurDarkness": 0.4,
 *                   "solidIntensity": 0.6, "solidDarkness": 0.4 }
 * }
 * ```
 * A custom DSP palette is `"dsp": { "primary": "#RRGGBB", "secondary": "#RRGGBB" }`.
 */
object ThemeCodec {
    const val SCHEMA = 1
    const val MAX_THEME_BYTES = 16 * 1024
    const val MAX_INDEX_BYTES = 256 * 1024

    const val SHARE_SCHEME = "beatraxus"
    const val SHARE_HOST = "theme"
    const val CODE_PREFIX = "BTX1."

    private const val MAX_SHARE_CHARS = 32 * 1024
    private const val MAX_INDEX_ENTRIES = 500
    private const val NOT_A_THEME = "That doesn't look like a Beatraxus theme"

    private const val DARK_BG = 0xFF0B0B14.toInt()
    private const val LIGHT_BG = 0xFFF4F4F8.toInt()

    private val ID_REGEX = Regex("^[a-z0-9][a-z0-9_-]{0,47}$")
    private val FILE_REGEX = Regex("^themes/[a-z0-9][a-z0-9_-]{0,47}\\.json$")
    private val COLOR_REGEX = Regex("^#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})$")

    fun isValidId(id: String): Boolean = ID_REGEX.matches(id)
    fun isValidFile(path: String): Boolean = FILE_REGEX.matches(path)

    // ---------------------------------------------------------------- theme file

    fun parseTheme(text: String): Result<ThemeSpec> = runCatching {
        require(text.length <= MAX_THEME_BYTES) { "Theme is too large" }
        val o = JSONObject(text)
        require(o.optInt("schema", SCHEMA) == SCHEMA) { "This theme needs a newer version of Beatraxus" }

        val id = o.optString("id").trim()
        require(isValidId(id)) { "Theme id must be 1-48 characters: a-z, 0-9, - or _" }
        val name = cleanText(o.optString("name"), 40)
        require(name.isNotEmpty()) { "Theme needs a name" }
        val author = cleanText(o.optString("author"), 40).ifEmpty { "Unknown" }
        val version = o.optInt("version", 1).coerceIn(1, 9999)
        val isDark = when (o.optString("mode", "dark").trim().lowercase()) {
            "dark" -> true
            "light" -> false
            else -> throw IllegalArgumentException("mode must be \"dark\" or \"light\"")
        }

        val colors = o.optJSONObject("colors") ?: throw IllegalArgumentException("Theme needs a colors section")
        val accent = parseColor(colors.optString("accent"), "colors.accent")
        val accentSecondary =
            if (colors.has("accentSecondary")) parseColor(colors.optString("accentSecondary"), "colors.accentSecondary")
            else accent
        val preview = parsePreview(colors.optJSONArray("preview"))
            ?: listOf(if (isDark) DARK_BG else LIGHT_BG, accent, accentSecondary)

        // DSP palette: a preset id, or a custom pair. Unknown ids fall back to the default palette
        // so a theme made for a newer app version still applies on an older one.
        var paletteId = DspPalette.default.id
        var primary = DspPalette.default.primary.toArgb()
        var secondary = DspPalette.default.secondary.toArgb()
        o.optJSONObject("dsp")?.let { dsp ->
            val wanted = dsp.optString("palette").trim()
            val preset = DspPalette.presets.firstOrNull { it.id == wanted }
            if (preset != null) {
                paletteId = preset.id
                primary = preset.primary.toArgb()
                secondary = preset.secondary.toArgb()
            } else if (dsp.has("primary") && dsp.has("secondary")) {
                paletteId = DspPalette.CUSTOM_ID
                primary = parseColor(dsp.optString("primary"), "dsp.primary")
                secondary = parseColor(dsp.optString("secondary"), "dsp.secondary")
            }
        }

        val seekbar = o.optString("seekbar").trim()
            .takeIf { s -> SeekbarStyle.entries.any { it.name == s } }
            ?: SeekbarStyle.BEATRAXUS_MATRIX.name
        val font = o.optString("font").trim()
            .takeIf { f -> FontRepository.availableFonts.any { it.id == f } }
            ?: "system"

        val bg = o.optJSONObject("background")
        val mode = bg?.optString("mode")?.trim()?.uppercase()
            ?.let { m -> NowPlayingBackgroundMode.entries.firstOrNull { it.name == m } }
            ?: NowPlayingBackgroundMode.BLUR
        val background = ThemeBackground(
            mode = mode,
            blurScale = number(bg, "blurScale", 1f, 0.25f, 2f),
            blurDarkness = number(bg, "blurDarkness", 0.4f, 0f, 1f),
            solidIntensity = number(bg, "solidIntensity", 0.6f, 0f, 1f),
            solidDarkness = number(bg, "solidDarkness", 0.4f, 0f, 1f)
        )

        ThemeSpec(
            id = id, name = name, author = author, version = version, isDark = isDark,
            accent = accent, accentSecondary = accentSecondary, preview = preview,
            dspPaletteId = paletteId, dspPrimary = primary, dspSecondary = secondary,
            seekbarStyle = seekbar, fontId = font, background = background
        )
    }

    fun toJson(spec: ThemeSpec): String {
        val colors = JSONObject()
            .put("accent", hex(spec.accent))
            .put("accentSecondary", hex(spec.accentSecondary))
            .put("preview", JSONArray(spec.preview.map { hex(it) }))
        val dsp = JSONObject()
        if (spec.dspPaletteId == DspPalette.CUSTOM_ID) {
            dsp.put("primary", hex(spec.dspPrimary)).put("secondary", hex(spec.dspSecondary))
        } else {
            dsp.put("palette", spec.dspPaletteId)
        }
        val bg = spec.background
        return JSONObject()
            .put("schema", SCHEMA)
            .put("id", spec.id)
            .put("name", spec.name)
            .put("author", spec.author)
            .put("version", spec.version)
            .put("mode", if (spec.isDark) "dark" else "light")
            .put("colors", colors)
            .put("dsp", dsp)
            .put("seekbar", spec.seekbarStyle)
            .put("font", spec.fontId)
            .put(
                "background",
                JSONObject()
                    .put("mode", bg.mode.name)
                    .put("blurScale", bg.blurScale.toDouble())
                    .put("blurDarkness", bg.blurDarkness.toDouble())
                    .put("solidIntensity", bg.solidIntensity.toDouble())
                    .put("solidDarkness", bg.solidDarkness.toDouble())
            )
            .toString()
    }

    // ---------------------------------------------------------------- store index

    /** Entries that fail validation are skipped; one bad row never hides the rest of the store. */
    fun parseIndex(text: String): Result<List<ThemeIndexEntry>> = runCatching {
        require(text.length <= MAX_INDEX_BYTES) { "Theme list is too large" }
        val o = JSONObject(text)
        require(o.optInt("schema", SCHEMA) == SCHEMA) { "This theme store needs a newer version of Beatraxus" }
        val arr = o.optJSONArray("themes") ?: throw IllegalArgumentException("The theme list is empty")
        val seen = HashSet<String>()
        val out = ArrayList<ThemeIndexEntry>()
        for (i in 0 until minOf(arr.length(), MAX_INDEX_ENTRIES)) {
            val e = arr.optJSONObject(i) ?: continue
            val entry = runCatching { parseIndexEntry(e) }.getOrNull() ?: continue
            if (seen.add(entry.id)) out.add(entry)
        }
        out
    }

    private fun parseIndexEntry(e: JSONObject): ThemeIndexEntry {
        val id = e.optString("id").trim()
        require(isValidId(id))
        val file = e.optString("file").trim()
        require(isValidFile(file))
        val name = cleanText(e.optString("name"), 40)
        require(name.isNotEmpty())
        return ThemeIndexEntry(
            id = id,
            name = name,
            author = cleanText(e.optString("author"), 40).ifEmpty { "Unknown" },
            version = e.optInt("version", 1).coerceIn(1, 9999),
            file = file,
            isDark = e.optString("mode", "dark").trim().lowercase() != "light",
            preview = parsePreview(e.optJSONArray("preview")) ?: emptyList()
        )
    }

    // ---------------------------------------------------------------- sharing

    /** Deflated, URL-safe base64 of the theme JSON. Roughly 300-450 characters for a normal theme. */
    fun encodeShare(spec: ThemeSpec): String {
        val raw = toJson(spec).toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream()
        DeflaterOutputStream(out, Deflater(Deflater.BEST_COMPRESSION, true)).use { it.write(raw) }
        return Base64.encodeToString(out.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    fun shareLink(spec: ThemeSpec): String = "$SHARE_SCHEME://$SHARE_HOST?data=${encodeShare(spec)}"

    fun shareCode(spec: ThemeSpec): String = CODE_PREFIX + encodeShare(spec)

    /** Accepts a beatraxus://theme?data=... link, a BTX1.<code> code, or the bare payload. */
    fun decodeShare(input: String): Result<ThemeSpec> = runCatching {
        val text = input.trim()
        require(text.isNotEmpty()) { "Nothing to import" }
        require(text.length <= MAX_SHARE_CHARS) { "That is too long to be a theme" }

        val payload = when {
            text.startsWith("$SHARE_SCHEME://", ignoreCase = true) -> {
                val uri = Uri.parse(text)
                require(uri.host.equals(SHARE_HOST, ignoreCase = true)) { "That isn't a theme link" }
                uri.getQueryParameter("data") ?: throw IllegalArgumentException("The theme link has no data")
            }
            text.startsWith(CODE_PREFIX) -> text.removePrefix(CODE_PREFIX)
            else -> text
        }.filterNot { it.isWhitespace() }

        val compressed = try {
            Base64.decode(payload, Base64.URL_SAFE or Base64.NO_WRAP)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException(NOT_A_THEME)
        }
        val json = try {
            String(inflateLimited(compressed), Charsets.UTF_8)
        } catch (e: DataFormatException) {
            throw IllegalArgumentException(NOT_A_THEME)
        }
        parseTheme(json).getOrThrow()
    }

    /** Inflate with a hard output cap so a tiny payload cannot expand into a huge one. */
    private fun inflateLimited(data: ByteArray): ByteArray {
        val inflater = Inflater(true)
        try {
            inflater.setInput(data)
            val out = ByteArrayOutputStream()
            val buf = ByteArray(4096)
            while (!inflater.finished()) {
                val n = inflater.inflate(buf)
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                out.write(buf, 0, n)
                require(out.size() <= MAX_THEME_BYTES) { "Theme is too large" }
            }
            return out.toByteArray()
        } finally {
            inflater.end()
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun parseColor(raw: String?, field: String): Int {
        val s = raw?.trim().orEmpty()
        require(COLOR_REGEX.matches(s)) { "$field must be a colour like #RRGGBB" }
        val v = s.substring(1).toLong(16)
        return if (s.length == 7) (0xFF000000L or v).toInt() else v.toInt()
    }

    private fun parsePreview(arr: JSONArray?): List<Int>? {
        if (arr == null) return null
        val out = ArrayList<Int>()
        for (i in 0 until minOf(arr.length(), 5)) {
            out.add(runCatching { parseColor(arr.optString(i), "preview") }.getOrNull() ?: return null)
        }
        return out.takeIf { it.size >= 2 }
    }

    private fun number(o: JSONObject?, key: String, default: Float, min: Float, max: Float): Float {
        val v = o?.optDouble(key, default.toDouble())?.toFloat() ?: default
        return if (v.isFinite()) v.coerceIn(min, max) else default
    }

    private fun cleanText(raw: String, max: Int): String =
        raw.filterNot { it.isISOControl() }.trim().take(max)

    fun hex(argb: Int): String =
        if ((argb ushr 24) == 0xFF) "#%06X".format(argb and 0xFFFFFF) else "#%08X".format(argb)
}
