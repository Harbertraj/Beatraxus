package com.beatraxus.app.subtitles.generate

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** The request and answer format used to translate subtitle lines with a chat-completions model. */
object TranslationJson {

    fun systemPrompt(fromLanguageName: String?, toLanguageName: String): String {
        val from = fromLanguageName?.takeIf { it.isNotBlank() && !it.equals("Unknown", true) } ?: "the original language"
        return """
            You are a professional subtitle translator. Translate every subtitle line from $from to $toLanguageName.
            Rules:
            - Keep the meaning, tone and register. Write natural, concise $toLanguageName that reads well on screen.
            - Never merge, split, add or drop lines. Every input id must appear once in the output.
            - Keep names of people and places. Keep sound cues such as [music] but translate them.
            - Output only the translation of each line, with no notes.
            The input is a JSON array of {"i": id, "t": text}. Reply with ONLY a JSON array of {"i": id, "t": translation}, using the same ids. No markdown, no commentary.
        """.trimIndent()
    }

    fun userMessage(lines: List<IndexedLine>): String {
        val arr = JsonArray()
        for (line in lines) {
            arr.add(JsonObject().apply {
                addProperty("i", line.index)
                addProperty("t", line.text)
            })
        }
        return arr.toString()
    }

    /**
     * Reads the model's answer. Tolerates markdown fences and chatter around the array, and an answer that is a plain
     * array of strings in input order. Anything it cannot place is left out of the map, and the caller asks again.
     */
    fun parseAnswer(content: String, asked: List<IndexedLine>): Map<Int, String> {
        val array = extractArray(content) ?: return emptyMap()
        val askedIds = asked.map { it.index }.toSet()
        val out = HashMap<Int, String>()

        val allStrings = array.size() > 0 && array.all { it.isJsonPrimitive }
        if (allStrings) {
            // No ids: only trust it when the count matches, so a dropped line cannot shift every later one.
            if (array.size() == asked.size) {
                array.forEachIndexed { pos, el -> out[asked[pos].index] = el.asString }
            }
            return out
        }

        for (el in array) {
            if (!el.isJsonObject) continue
            val o = el.asJsonObject
            val id = o.get("i")?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asInt }.getOrNull() } ?: continue
            val text = o.get("t")?.takeIf { it.isJsonPrimitive }?.asString ?: continue
            if (id in askedIds) out[id] = text
        }
        return out
    }

    private fun extractArray(content: String): JsonArray? {
        val start = content.indexOf('[')
        val end = content.lastIndexOf(']')
        if (start < 0 || end <= start) return null
        return try {
            JsonParser.parseString(content.substring(start, end + 1)).takeIf { it.isJsonArray }?.asJsonArray
        } catch (_: Exception) {
            null
        }
    }
}
