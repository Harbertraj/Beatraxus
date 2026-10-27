package com.beatraxus.app.subtitles.generate

import kotlinx.coroutines.delay
import java.io.File

/** One speech-recognition account/model the app may send audio to. */
data class SpeechProviderConfig(val endpoint: String, val apiKey: String, val model: String) {

    companion object {
        /**
         * Reads the build settings, each of which may hold several comma-separated values:
         * `SPEECH_API_KEY=keyA,keyB`, `SPEECH_MODEL=whisper-large-v3-turbo,whisper-large-v3`, `SPEECH_ENDPOINT=...`.
         *
         * The providers are the longest of the three lists; a list that is shorter repeats its last value. So one key with
         * two models gives two providers (free plans count the audio limit per model, so that doubles it), and two keys
         * with one model give two providers. The order is the order of preference: the first is used until it is limited.
         * No key means no providers (auto subtitles are not configured).
         */
        fun parse(apiKeys: String, endpoints: String, models: String): List<SpeechProviderConfig> {
            val keys = split(apiKeys)
            val urls = split(endpoints)
            val names = split(models)
            if (keys.isEmpty() || urls.isEmpty() || names.isEmpty()) return emptyList()
            val count = maxOf(keys.size, urls.size, names.size)
            return (0 until count).map { i ->
                SpeechProviderConfig(
                    endpoint = urls[minOf(i, urls.lastIndex)],
                    apiKey = keys[minOf(i, keys.lastIndex)],
                    model = names[minOf(i, names.lastIndex)]
                )
            }.distinct()
        }

        private fun split(value: String): List<String> = value.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    }
}

/**
 * Recognises speech with several providers in order of preference. A provider that answers "limit reached" is put aside
 * for as long as it asked and the next one takes over at once, so a limit on one free key or model no longer stops the run.
 * Only when every provider is limited does it wait, until the soonest one is free again (showing the countdown).
 *
 * A key that is rejected is dropped for the rest of the run when another provider exists. Other failures (no connection,
 * a bad file) are reported as they are, because another provider would not fix them.
 */
class FailoverSpeechTranscriber(
    private val providers: List<SpeechTranscriber>,
    /** Called when the work moves from one provider to another (their positions in the list). */
    private val onSwitch: (from: Int, to: Int) -> Unit = { _, _ -> },
    /** Called every second while all providers are limited: (time left, the whole wait). 0 left = the wait is over. */
    private val onRateLimited: (leftMs: Long, totalMs: Long) -> Unit = { _, _ -> },
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val sleepMs: suspend (Long) -> Unit = { delay(it) },
    /** Total time one chunk may spend waiting for limits before the run stops (it can be resumed later). */
    private val maxTotalWaitMs: Long = 90L * 60_000L
) : SpeechTranscriber {

    init {
        require(providers.isNotEmpty()) { "At least one speech provider is needed" }
    }

    /** When each provider may be used again (epoch ms), and which ones are out for the rest of the run. */
    private val readyAt = LongArray(providers.size)
    private val unusable = BooleanArray(providers.size)
    private var current = 0

    override suspend fun transcribe(audio: File, languageCode: String?): WhisperTranscript {
        var waited = 0L
        var lastError: GenerationException? = null

        while (true) {
            val now = nowMs()
            val pick = providers.indices.firstOrNull { !unusable[it] && readyAt[it] <= now }

            if (pick == null) {
                val soonest = providers.indices.filter { !unusable[it] }.minOfOrNull { readyAt[it] }
                    ?: throw (lastError ?: GenerationException(GenerationException.Kind.RATE_LIMITED))
                val wait = (soonest - now).coerceAtLeast(1_000L)
                if (waited + wait > maxTotalWaitMs) {
                    throw (lastError ?: GenerationException(GenerationException.Kind.RATE_LIMITED))
                }
                waited += wait
                var left = wait
                while (left > 0) {
                    onRateLimited(left, wait)
                    val step = minOf(1_000L, left)
                    sleepMs(step)
                    left -= step
                }
                onRateLimited(0L, wait)
                continue
            }

            if (pick != current) {
                onSwitch(current, pick)
                current = pick
            }
            try {
                return providers[pick].transcribe(audio, languageCode)
            } catch (e: GenerationException) {
                when {
                    e.kind == GenerationException.Kind.RATE_LIMITED -> {
                        val retry = e.retryAfterMs
                        if (retry == null) unusable[pick] = true else readyAt[pick] = nowMs() + retry
                        lastError = e
                    }
                    e.kind == GenerationException.Kind.AUTH && providers.size > 1 -> {
                        unusable[pick] = true
                        lastError = e
                    }
                    else -> throw e
                }
            }
        }
    }
}
