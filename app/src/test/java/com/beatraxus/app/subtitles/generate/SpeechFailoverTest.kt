package com.beatraxus.app.subtitles.generate

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/** Answers from a script: each call takes the next outcome (a transcript or an exception to throw). */
private class ScriptedProvider(private vararg val outcomes: Any) : SpeechTranscriber {
    var calls = 0
    override suspend fun transcribe(audio: File, languageCode: String?): WhisperTranscript {
        val outcome = outcomes[minOf(calls, outcomes.lastIndex)]
        calls++
        if (outcome is GenerationException) throw outcome
        return outcome as WhisperTranscript
    }
}

private fun transcript(text: String) =
    WhisperTranscript("english", listOf(WhisperSegment(0, 1_000, text)), emptyList())

private fun limited(retryAfterMs: Long?) =
    GenerationException(GenerationException.Kind.RATE_LIMITED, "limit", retryAfterMs = retryAfterMs)

class SpeechFailoverTest {

    private val audio = File("unused.wav")

    /** A clock that only moves when the code under test sleeps. */
    private class FakeClock {
        var now = 1_000_000L
        var slept = 0L
        suspend fun sleep(ms: Long) { now += ms; slept += ms }
    }

    private fun failover(
        clock: FakeClock,
        vararg providers: SpeechTranscriber,
        onSwitch: (Int, Int) -> Unit = { _, _ -> },
        onRateLimited: (Long, Long) -> Unit = { _, _ -> }
    ) = FailoverSpeechTranscriber(
        providers = providers.toList(),
        onSwitch = onSwitch,
        onRateLimited = onRateLimited,
        nowMs = { clock.now },
        sleepMs = { clock.sleep(it) }
    )

    // --- settings ---

    @Test
    fun twoKeysOneModelGiveTwoProviders() {
        val list = SpeechProviderConfig.parse("keyA, keyB", "https://x/v1/audio/transcriptions", "whisper-large-v3-turbo")
        assertEquals(2, list.size)
        assertEquals("keyA", list[0].apiKey)
        assertEquals("keyB", list[1].apiKey)
        assertEquals("whisper-large-v3-turbo", list[1].model)
    }

    @Test
    fun oneKeyTwoModelsGiveTwoProvidersSharingTheKey() {
        val list = SpeechProviderConfig.parse("keyA", "https://x/v1/audio/transcriptions", "whisper-large-v3-turbo,whisper-large-v3")
        assertEquals(listOf("whisper-large-v3-turbo", "whisper-large-v3"), list.map { it.model })
        assertTrue(list.all { it.apiKey == "keyA" })
    }

    @Test
    fun singleValuesStaySingleAndDuplicatesCollapse() {
        assertEquals(1, SpeechProviderConfig.parse("keyA", "https://x", "whisper-1").size)
        assertEquals(1, SpeechProviderConfig.parse("keyA,keyA", "https://x", "whisper-1").size)
    }

    @Test
    fun noKeyMeansNoProviders() {
        assertTrue(SpeechProviderConfig.parse("", "https://x", "whisper-1").isEmpty())
        assertTrue(SpeechProviderConfig.parse(" , ", "https://x", "whisper-1").isEmpty())
    }

    // --- failover ---

    @Test
    fun limitedProviderHandsOverAtOnceWithoutWaiting() = runBlocking {
        val clock = FakeClock()
        val first = ScriptedProvider(limited(600_000L))
        val second = ScriptedProvider(transcript("from second"))
        val switches = mutableListOf<Pair<Int, Int>>()
        val f = failover(clock, first, second, onSwitch = { a, b -> switches += a to b })

        val result = f.transcribe(audio, null)

        assertEquals("from second", result.segments.single().text)
        assertEquals(0L, clock.slept)
        assertEquals(listOf(0 to 1), switches)
    }

    @Test
    fun limitedProviderIsSkippedUntilItsWaitHasPassed() = runBlocking {
        val clock = FakeClock()
        val first = ScriptedProvider(limited(60_000L), transcript("first again"))
        val second = ScriptedProvider(transcript("second"))
        val f = failover(clock, first, second)

        f.transcribe(audio, null)
        assertEquals(1, first.calls)

        // Still cooling: the second one serves the next chunk and the first is not even asked.
        f.transcribe(audio, null)
        assertEquals(1, first.calls)
        assertEquals(2, second.calls)

        // After its wait the preferred provider is used again.
        clock.now += 61_000L
        val back = f.transcribe(audio, null)
        assertEquals("first again", back.segments.single().text)
    }

    @Test
    fun waitsForTheSoonestProviderOnlyWhenAllAreLimited() = runBlocking {
        val clock = FakeClock()
        val first = ScriptedProvider(limited(300_000L), transcript("first"))
        val second = ScriptedProvider(limited(120_000L), transcript("second"))
        val countdown = mutableListOf<Long>()
        val f = failover(clock, first, second, onRateLimited = { left, _ -> countdown += left })

        val result = f.transcribe(audio, null)

        // The second one is free first, after its 120 s.
        assertEquals("second", result.segments.single().text)
        assertEquals(120_000L, clock.slept)
        assertEquals(0L, countdown.last())
        assertTrue(countdown.size > 100)
    }

    @Test
    fun aProviderWithItsQuotaUsedUpIsNeverAskedAgain() = runBlocking {
        val clock = FakeClock()
        val first = ScriptedProvider(limited(null))
        val second = ScriptedProvider(transcript("a"), transcript("b"))
        val f = failover(clock, first, second)

        f.transcribe(audio, null)
        clock.now += 24L * 3_600_000L
        f.transcribe(audio, null)

        assertEquals(1, first.calls)
        assertEquals(2, second.calls)
    }

    @Test
    fun aSingleProviderWithNoQuotaLeftReportsTheLimit() = runBlocking {
        val f = failover(FakeClock(), ScriptedProvider(limited(null)))
        try {
            f.transcribe(audio, null)
            fail("expected the limit to be reported")
        } catch (e: GenerationException) {
            assertEquals(GenerationException.Kind.RATE_LIMITED, e.kind)
        }
    }

    @Test
    fun waitingLongerThanTheCapStopsTheRun() = runBlocking {
        val clock = FakeClock()
        val f = FailoverSpeechTranscriber(
            providers = listOf(ScriptedProvider(limited(10 * 60_000L)), ScriptedProvider(limited(10 * 60_000L))),
            nowMs = { clock.now },
            sleepMs = { clock.sleep(it) },
            maxTotalWaitMs = 5 * 60_000L
        )
        try {
            f.transcribe(audio, null)
            fail("expected the run to stop")
        } catch (e: GenerationException) {
            assertEquals(GenerationException.Kind.RATE_LIMITED, e.kind)
        }
    }

    @Test
    fun rejectedKeyIsDroppedWhenAnotherProviderExists() = runBlocking {
        val first = ScriptedProvider(GenerationException(GenerationException.Kind.AUTH, "bad key"))
        val second = ScriptedProvider(transcript("ok"))
        val f = failover(FakeClock(), first, second)

        assertEquals("ok", f.transcribe(audio, null).segments.single().text)
        f.transcribe(audio, null)
        assertEquals(1, first.calls)
    }

    @Test
    fun rejectedKeyOfTheOnlyProviderIsReported() = runBlocking {
        val f = failover(FakeClock(), ScriptedProvider(GenerationException(GenerationException.Kind.AUTH, "bad key")))
        try {
            f.transcribe(audio, null)
            fail("expected the key problem to be reported")
        } catch (e: GenerationException) {
            assertEquals(GenerationException.Kind.AUTH, e.kind)
        }
    }

    @Test
    fun otherFailuresAreNotHiddenBySwitching() = runBlocking {
        val first = ScriptedProvider(GenerationException(GenerationException.Kind.NETWORK, "offline"))
        val second = ScriptedProvider(transcript("never used"))
        val f = failover(FakeClock(), first, second)
        try {
            f.transcribe(audio, null)
            fail("expected the network failure")
        } catch (e: GenerationException) {
            assertEquals(GenerationException.Kind.NETWORK, e.kind)
        }
        assertEquals(0, second.calls)
    }
}
