package com.beatraxus.app.repository.lyrics

import com.beatraxus.app.model.SingerAssignment
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

object TtmlParser {
    private data class RawSpan(val beginMs: Long, val endMs: Long, val text: String)

    fun parseToEnhancedLrc(ttml: String?): String? {
        if (ttml.isNullOrBlank()) return null
        try {
            val factory = DocumentBuilderFactory.newInstance()
            try {
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            } catch (_: Throwable) {
                // Ignore if unsupported
            }

            val builder = factory.newDocumentBuilder()
            val doc = builder.parse(InputSource(StringReader(ttml)))

            val pElements = doc.getElementsByTagName("p")
            if (pElements.length == 0) return null

            // Singer per sung paragraph (translation / romanisation paragraphs are not sung lines
            // and must not disturb whose turn it is), then one decision for the whole song.
            val sungParagraphs = ArrayList<Element>(pElements.length)
            for (i in 0 until pElements.length) {
                val p = pElements.item(i) as Element
                val role = getRole(p)
                if (role.equals("x-translation", ignoreCase = true) || role.equals("x-roman", ignoreCase = true)) continue
                sungParagraphs.add(p)
            }
            // turns alternate in singing order, which is time order (not always document order)
            sungParagraphs.sortBy { parseTime(it.getAttribute("begin") ?: "") }
            val assigned = SingerAssignment.assign(
                sungParagraphs.map { getAgent(it).ifEmpty { null } },
                agentTypes(doc)
            )
            val assignedByParagraph = HashMap<Element, SingerAssignment.Assigned>()
            sungParagraphs.forEachIndexed { index, p -> assignedByParagraph[p] = assigned[index] }

            val sb = StringBuilder()

            for (i in 0 until pElements.length) {
                val p = pElements.item(i) as Element
                val pRole = getRole(p)
                if (pRole.equals("x-translation", ignoreCase = true) || pRole.equals("x-roman", ignoreCase = true)) {
                    continue
                }

                val pBeginStr = p.getAttribute("begin") ?: ""
                val pBeginMs = parseTime(pBeginStr)
                val speakerPrefix = assignedByParagraph[p]?.marker ?: ""

                val mainSpans = mutableListOf<RawSpan>()
                val bgSpans = mutableListOf<RawSpan>()

                val children = p.childNodes
                var hasSpans = false

                for (j in 0 until children.length) {
                    val child = children.item(j)
                    if (child.nodeType == Node.ELEMENT_NODE && child.nodeName == "span") {
                        hasSpans = true
                        val span = child as Element
                        val spanRole = getRole(span)
                        if (spanRole.equals("x-translation", ignoreCase = true) || spanRole.equals("x-roman", ignoreCase = true)) {
                            continue
                        }

                        if (spanRole.equals("x-bg", ignoreCase = true)) {
                            collectSpans(span, pBeginMs, bgSpans)
                        } else {
                            collectSpans(span, pBeginMs, mainSpans)
                        }
                    }
                }

                if (!hasSpans) {
                    val text = p.textContent?.trim() ?: ""
                    if (text.isNotEmpty()) {
                        mainSpans.add(RawSpan(pBeginMs, 0L, text))
                    }
                }

                val mainWords = groupSyllablesToWords(mainSpans)
                if (mainWords.isNotEmpty()) {
                    val rawLine = EnhancedLrc.line(mainWords)
                    val lineWithSpeaker = if (speakerPrefix.isNotEmpty()) {
                        val headerEnd = rawLine.indexOf("]")
                        if (headerEnd != -1) {
                            val header = rawLine.substring(0, headerEnd + 1)
                            val textPart = rawLine.substring(headerEnd + 1)
                            "$header$speakerPrefix$textPart"
                        } else {
                            "$speakerPrefix$rawLine"
                        }
                    } else {
                        rawLine
                    }
                    sb.append(lineWithSpeaker).append("\n")
                }

                val bgWords = groupSyllablesToWords(bgSpans)
                if (bgWords.isNotEmpty()) {
                    val firstTime = bgWords.first().first
                    val lineText = EnhancedLrc.line(bgWords)
                    val formattedTimeHeader = "[${EnhancedLrc.formatLrcTime(firstTime)}]"
                    val textPart = if (lineText.startsWith(formattedTimeHeader)) {
                        lineText.substring(formattedTimeHeader.length)
                    } else {
                        lineText
                    }
                    sb.append("$formattedTimeHeader$speakerPrefix($textPart)\n")
                }
            }

            return sb.toString().trim().takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            return null
        }
    }

    /** `<ttm:agent xml:id="v1" type="person">` declarations in the head: id -> type. */
    private fun agentTypes(doc: org.w3c.dom.Document): Map<String, String> {
        val agents = doc.getElementsByTagName("ttm:agent").takeIf { it.length > 0 }
            ?: doc.getElementsByTagName("agent")
        val types = HashMap<String, String>(agents.length)
        for (i in 0 until agents.length) {
            val agent = agents.item(i) as? Element ?: continue
            val id = agent.qualified("xml:id")
            val type = agent.getAttribute("type")
            if (id.isNotEmpty() && type.isNotEmpty()) types[id] = type
        }
        return types
    }

    /**
     * Attribute read by qualified name. The parser is not namespace-aware, and Android's Expat-based
     * DOM files `ttm:agent` under its local name `agent` (the JVM keeps it whole), so a plain
     * getAttribute("ttm:agent") returns "" on a phone and every line lost its singer.
     */
    private fun Element.qualified(name: String): String {
        getAttribute(name).takeIf { it.isNotEmpty() }?.let { return it }
        val local = name.substringAfter(':')
        val found = attributes ?: return ""
        for (i in 0 until found.length) {
            val attr = found.item(i) ?: continue
            if (attr.nodeName == name || attr.nodeName == local || attr.localName == local) {
                return attr.nodeValue.orEmpty()
            }
        }
        return ""
    }

    private fun getAgent(element: Element): String = element.qualified("ttm:agent")

    private fun getRole(element: Element): String = element.qualified("ttm:role")

    private fun collectSpans(element: Element, defaultBeginMs: Long, targetList: MutableList<RawSpan>) {
        val childNodes = element.childNodes
        var hasChildSpan = false

        for (i in 0 until childNodes.length) {
            val child = childNodes.item(i)
            if (child.nodeType == Node.ELEMENT_NODE && child.nodeName == "span") {
                hasChildSpan = true
                val childElement = child as Element
                val role = getRole(childElement)
                if (role.equals("x-translation", ignoreCase = true) || role.equals("x-roman", ignoreCase = true)) {
                    continue
                }
                val beginStr = childElement.getAttribute("begin") ?: ""
                val endStr = childElement.getAttribute("end") ?: ""
                val beginMs = if (beginStr.isNotEmpty()) parseTime(beginStr) else defaultBeginMs
                val endMs = if (endStr.isNotEmpty()) parseTime(endStr) else 0L
                val text = childElement.textContent ?: ""
                if (text.isNotEmpty()) {
                    targetList.add(RawSpan(beginMs, endMs, text))
                }
            }
        }

        if (!hasChildSpan) {
            val beginStr = element.getAttribute("begin") ?: ""
            val endStr = element.getAttribute("end") ?: ""
            val beginMs = if (beginStr.isNotEmpty()) parseTime(beginStr) else defaultBeginMs
            val endMs = if (endStr.isNotEmpty()) parseTime(endStr) else 0L
            val text = element.textContent ?: ""
            if (text.isNotEmpty()) {
                targetList.add(RawSpan(beginMs, endMs, text))
            }
        }
    }

    private fun groupSyllablesToWords(spans: List<RawSpan>): List<Pair<Long, String>> {
        if (spans.isEmpty()) return emptyList()

        val result = mutableListOf<Pair<Long, String>>()
        val currentGroup = mutableListOf<RawSpan>()

        for (i in spans.indices) {
            val span = spans[i]
            currentGroup.add(span)

            val endsWithWs = span.text.lastOrNull()?.isWhitespace() == true
            val nextStartsWithWs = if (i + 1 < spans.size) {
                spans[i + 1].text.firstOrNull()?.isWhitespace() == true
            } else {
                false
            }
            val isLast = i == spans.size - 1

            if (endsWithWs || nextStartsWithWs || isLast) {
                val startMs = currentGroup.first().beginMs
                val wordText = currentGroup.joinToString("") { it.text }.trim()
                if (wordText.isNotEmpty()) {
                    result.add(Pair(startMs, wordText))
                }
                currentGroup.clear()
            }
        }

        return result
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
}
