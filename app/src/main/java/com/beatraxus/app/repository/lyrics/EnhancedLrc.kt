package com.beatraxus.app.repository.lyrics

import java.util.Locale

object EnhancedLrc {
    fun formatLrcTime(ms: Long): String {
        val safeMs = maxOf(0L, ms)
        val min = safeMs / 60000
        val sec = (safeMs % 60000) / 1000
        val hun = (safeMs % 1000) / 10
        return String.format(Locale.US, "%02d:%02d.%02d", min, sec, hun)
    }

    fun line(words: List<Pair<Long, String>>): String {
        if (words.isEmpty()) return ""
        val sb = StringBuilder()
        val (firstTime, firstText) = words[0]
        sb.append("[").append(formatLrcTime(firstTime)).append("]").append(firstText)

        for (i in 1 until words.size) {
            val (time, text) = words[i]
            sb.append(" <").append(formatLrcTime(time)).append(">").append(text)
        }
        return sb.toString()
    }
}
