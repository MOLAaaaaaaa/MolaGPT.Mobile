package com.molagpt.app.core.network

import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Response

/** Runs on the caller's IO dispatcher and keeps cancellation attached through body consumption. */
internal suspend inline fun <T> Call.useCancellable(block: (Response) -> T): T {
    val context = currentCoroutineContext()
    context.ensureActive()
    val finished = AtomicBoolean()
    // Register before execute and keep Flow collection in the original coroutine context.
    val cancellation = CoroutineScope(context).launch(start = CoroutineStart.UNDISPATCHED) {
        suspendCancellableCoroutine<Nothing> { continuation ->
            continuation.invokeOnCancellation { if (!finished.get()) cancel() }
        }
    }
    try {
        return execute().use(block).also { context.ensureActive() }
    } catch (error: IOException) {
        context.ensureActive()
        throw error
    } finally {
        finished.set(true)
        cancellation.cancel()
    }
}
