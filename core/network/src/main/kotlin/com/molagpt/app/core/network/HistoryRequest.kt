package com.molagpt.app.core.network

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext

/** Keep the originating history session attached across flowOn, token acquisition and SSE retries. */
class HistoryRequest(private val current: () -> Boolean) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<HistoryRequest> {
        suspend fun checkCurrent() {
            if (currentCoroutineContext()[Key]?.current?.invoke() == false) {
                throw CancellationException("History session changed")
            }
        }
    }
}
