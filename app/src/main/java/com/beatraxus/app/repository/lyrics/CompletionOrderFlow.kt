package com.beatraxus.app.repository.lyrics

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch

/**
 * Runs [block] for every item at the same time, each one on its own, and emits every result the moment that one is
 * ready, in the order they finish: a fast item is never held back by a slow one.
 *
 * The flow ends when the last item is done. Cancelling the collector cancels the items that are still running.
 * [block] has to handle its own errors (return a "failed" value): an exception in one item cancels all of them.
 */
fun <T, R> Collection<T>.mapAsCompleted(block: suspend (T) -> R): Flow<R> {
    val items = this
    return channelFlow {
        for (item in items) {
            launch { send(block(item)) }
        }
    }
}
