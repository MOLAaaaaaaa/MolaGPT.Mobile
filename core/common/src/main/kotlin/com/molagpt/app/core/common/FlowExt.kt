package com.molagpt.app.core.common

import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch

/** Collects values that arrive within the same fixed time window. */
fun <T> Flow<T>.chunkedTimeWindow(windowMillis: Long): Flow<List<T>> =
    chunkedTimeWindow(windowMillis = { windowMillis })

/**
 * Collects values into batches using a window that can adapt between emissions.
 *
 * [emitImmediately] is intended for short-lived semantic states that must be observable even
 * when the normal UI update window is temporarily enlarged.
 */
fun <T> Flow<T>.chunkedTimeWindow(
    windowMillis: () -> Long,
    emitImmediately: (T) -> Boolean = { false },
): Flow<List<T>> = channelFlow {
    val buffer = ArrayList<T>()
    val lock = Any()
    var ticker: Job? = null

    suspend fun ProducerScope<List<T>>.flush() {
        val batch = synchronized(lock) {
            if (buffer.isEmpty()) {
                null
            } else {
                ArrayList(buffer).also { buffer.clear() }
            }
        } ?: return
        send(batch)
    }

    collect { item ->
        if (emitImmediately(item)) {
            ticker?.cancelAndJoin()
            ticker = null
            flush()
            send(listOf(item))
        } else {
            synchronized(lock) { buffer.add(item) }
            if (ticker?.isActive != true) {
                ticker = launch {
                    delay(windowMillis().coerceAtLeast(1L))
                    flush()
                }
            }
        }
    }

    ticker?.join()
    flush()
}
