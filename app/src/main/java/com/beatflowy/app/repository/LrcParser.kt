package com.beatflowy.app.repository

import com.beatflowy.app.model.LrcLine
import com.beatflowy.app.model.Word

object LrcParser {
    private val lineRegex = Regex("\\[(\\d+):(\\d+)(?:[.:](\\d+))?]\\s*(.*)")
    private val wordRegex = Regex("<(\\d+):(\\d+)(?:[.:](\\d+))?>\\s*([^<]*)")

    fun parse(lrcContent: String): List<LrcLine> {
        val lines = mutableListOf<LrcLine>()
        val rawLines = lrcContent.lines()
        
        // Try parsing as synced LRC first
        rawLines.forEach { rawLine ->
            val match = lineRegex.find(rawLine)
            if (match != null) {
                val min = match.groupValues[1].toLong()
                val sec = match.groupValues[2].toLong()
                val msStr = match.groupValues[3]
                val ms = when (msStr.length) {
                    0 -> 0L
                    1 -> msStr.toLong() * 100
                    2 -> msStr.toLong() * 10
                    3 -> msStr.toLong()
                    else -> 0L
                }
                val time = (min * 60 + sec) * 1000 + ms
                val textWithWords = match.groupValues[4]
                
                val words = parseWords(textWithWords)
                val cleanText = if (words.isNotEmpty()) {
                    words.joinToString("") { it.text }.trim()
                } else {
                    textWithWords.trim()
                }
                
                if (cleanText.isNotEmpty()) {
                    lines.add(LrcLine(time, cleanText, words))
                }
            }
        }

        // If no synced lines found, treat as plain text
        if (lines.isEmpty()) {
            rawLines.filter { it.trim().isNotEmpty() }.forEachIndexed { index, text ->
                // Assign a dummy time so they show up in order (e.g., 2 seconds per line)
                lines.add(LrcLine(index * 2000L, text.trim()))
            }
        }

        return lines.sortedBy { it.time }
    }

    private fun parseWords(text: String): List<Word> {
        val words = mutableListOf<Word>()
        val matches = wordRegex.findAll(text).toList()
        for (i in matches.indices) {
            val match = matches[i]
            val min = match.groupValues[1].toLong()
            val sec = match.groupValues[2].toLong()
            val msStr = match.groupValues[3]
            val ms = when (msStr.length) {
                2 -> msStr.toLong() * 10
                3 -> msStr.toLong()
                else -> 0L
            }
            val time = (min * 60 + sec) * 1000 + ms
            val wordText = match.groupValues[4]
            
            val duration = if (i < matches.size - 1) {
                val nextMatch = matches[i + 1]
                val nMin = nextMatch.groupValues[1].toLong()
                val nSec = nextMatch.groupValues[2].toLong()
                val nMsStr = nextMatch.groupValues[3]
                val nMs = when (nMsStr.length) {
                    2 -> nMsStr.toLong() * 10
                    3 -> nMsStr.toLong()
                    else -> 0L
                }
                val nTime = (nMin * 60 + nSec) * 1000 + nMs
                (nTime - time).coerceAtLeast(0L)
            } else {
                500L // Default duration for last word
            }
            
            words.add(Word(time, wordText, duration))
        }
        return words
    }
}
