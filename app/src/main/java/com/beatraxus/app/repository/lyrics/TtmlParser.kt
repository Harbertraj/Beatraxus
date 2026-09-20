package com.beatraxus.app.repository.lyrics

import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import java.lang.StringBuilder
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

object TtmlParser {
    private data class Span(val begin: String, val end: String, val text: String)

    fun parseToEnhancedLrc(ttml: String?): String? {
        if (ttml.isNullOrBlank()) return null
        try {
            val factory = DocumentBuilderFactory.newInstance()
            val builder = factory.newDocumentBuilder()
            val doc = builder.parse(InputSource(StringReader(ttml)))

            val pElements = doc.getElementsByTagName("p")
            val sb = StringBuilder()

            for (i in 0 until pElements.length) {
                val p = pElements.item(i) as Element
                val lineBegin = p.getAttribute("begin") ?: ""

                val spanElements = p.getElementsByTagName("span")
                val spans = mutableListOf<Span>()

                if (spanElements.length > 0) {
                    for (j in 0 until spanElements.length) {
                        val span = spanElements.item(j) as Element
                        val begin = span.getAttribute("begin") ?: ""
                        val end = span.getAttribute("end") ?: ""
                        val text = span.textContent ?: ""
                        spans.add(Span(begin, end, text))
                    }
                } else {
                    val text = p.textContent?.trim() ?: ""
                    if (text.isNotEmpty()) {
                        spans.add(Span(lineBegin, "", text))
                    }
                }

                if (spans.isNotEmpty()) {
                    val formatted = formatLine(lineBegin, spans)
                    if (formatted.isNotEmpty()) {
                        sb.append(formatted).append("\n")
                    }
                }
            }

            return sb.toString().trim().takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            return null
        }
    }

    private fun formatLine(lineBegin: String, spans: List<Span>): String {
        if (spans.isEmpty()) return ""
        val startMs = parseTime(if (spans.first().begin.isNotEmpty()) spans.first().begin else lineBegin)
        val lineSb = StringBuilder()
        lineSb.append("[").append(formatLrcTime(startMs)).append("]")

        if (spans.size == 1 && spans[0].end.isEmpty()) {
            lineSb.append(spans[0].text.trim())
            return lineSb.toString()
        }

        for (i in spans.indices) {
            val span = spans[i]
            lineSb.append(span.text)
            if (span.end.isNotEmpty() && i < spans.size - 1) {
                val endMs = parseTime(span.end)
                lineSb.append(" <").append(formatLrcTime(endMs)).append(">")
            }
        }
        return lineSb.toString()
    }

    private fun parseTime(timeStr: String): Long {
        val t = timeStr.trim().removeSuffix("s")
        val parts = t.split(":")
        return when (parts.size) {
            1 -> (parts[0].toFloatOrNull() ?: 0f).let { (it * 1000).toLong() }
            2 -> {
                val m = parts[0].toLongOrNull() ?: 0L
                val s = parts[1].toFloatOrNull() ?: 0f
                (m * 60000 + s * 1000).toLong()
            }
            3 -> {
                val h = parts[0].toLongOrNull() ?: 0L
                val m = parts[1].toLongOrNull() ?: 0L
                val s = parts[2].toFloatOrNull() ?: 0f
                (h * 3600000 + m * 60000 + s * 1000).toLong()
            }
            else -> 0L
        }
    }

    private fun formatLrcTime(ms: Long): String {
        val min = ms / 60000
        val sec = (ms % 60000) / 1000
        val hun = (ms % 1000) / 10
        return String.format(Locale.US, "%02d:%02d.%02d", min, sec, hun)
    }
}
