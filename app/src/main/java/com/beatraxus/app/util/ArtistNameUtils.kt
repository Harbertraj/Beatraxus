package com.beatraxus.app.util

object ArtistNameUtils {

    // Delimiters that separate multiple performers in one tag
    private val SPLIT_REGEX = Regex(
        """\s*(?:&|,|/|;|\bfeat\.?\b|\bft\.?\b|\bfeaturing\b|\bx\b|\bvs\.?\b)\s*""",
        RegexOption.IGNORE_CASE
    )

    /** Splits "A.R. Rahman & ADK" -> ["A.R. Rahman", "ADK"] */
    fun splitArtists(raw: String): List<String> =
        raw.split(SPLIT_REGEX)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .ifEmpty { listOf(raw.trim()) }

    /** Normalizes for matching: strips every non-alphanumeric character so
     *  "A.R. Rahman", "A. R. Rahman", and "A.R.Rahman" all → "arrahman" */
    fun normalizeKey(name: String): String =
        name.lowercase()
            .replace(Regex("[^a-z0-9]"), "")  // strips spaces, dots, hyphens, & — comparison key only
            .trim()
}
