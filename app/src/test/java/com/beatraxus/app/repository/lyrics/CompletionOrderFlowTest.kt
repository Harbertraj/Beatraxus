package com.beatraxus.app.repository.lyrics

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompletionOrderFlowTest {

    @Test
    fun resultsArriveInTheOrderTheyFinishNotTheOrderTheyStarted() = runBlocking {
        val delays = listOf("slow" to 400L, "fast" to 20L, "middle" to 150L)
        val out = delays.mapAsCompleted { (name, ms) -> delay(ms); name }.toList()
        assertEquals(listOf("fast", "middle", "slow"), out)
    }

    @Test
    fun theFastItemIsDeliveredWhileTheSlowOneIsStillRunning() = runBlocking {
        val start = System.currentTimeMillis()
        val times = mutableMapOf<String, Long>()
        listOf("slow" to 500L, "fast" to 20L).mapAsCompleted { (name, ms) -> delay(ms); name }
            .collect { times[it] = System.currentTimeMillis() - start }
        assertTrue("fast should arrive early, was ${times["fast"]} ms", times.getValue("fast") < 250)
        assertTrue("slow should arrive late, was ${times["slow"]} ms", times.getValue("slow") >= 450)
    }

    @Test
    fun itemsRunTogetherSoTheTotalIsTheSlowestNotTheSum() = runBlocking {
        val start = System.currentTimeMillis()
        List(8) { 300L }.mapAsCompleted { delay(it); it }.toList()
        val total = System.currentTimeMillis() - start
        assertTrue("took $total ms, items did not run in parallel", total < 900)
    }

    @Test
    fun emptyInputEndsAtOnce() = runBlocking {
        assertEquals(emptyList<Int>(), emptyList<Int>().mapAsCompleted { it }.toList())
    }
}
