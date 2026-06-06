package com.molagpt.app.core.network

import com.molagpt.app.core.model.StreamEvent
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch

/**
 * 流式输出节奏控制：用 4 ms 打字 tick 与自适应批量大小平滑正文增量，
 * UI 层仍按帧节奏提交重组。
 */
fun Flow<StreamEvent>.webTypingPaced(): Flow<StreamEvent> = channelFlow {
    val pieces = Channel<StreamPiece>(Channel.UNLIMITED)

    val producer = launch {
        collect { event ->
            if (event is StreamEvent.Delta && (event.text != null || event.thinking != null)) {
                event.thinking?.forEach { pieces.send(StreamPiece.Char(Kind.THINKING, it)) }
                event.text?.forEach { pieces.send(StreamPiece.Char(Kind.TEXT, it)) }
            } else {
                pieces.send(StreamPiece.Event(event))
            }
        }
        pieces.close()
    }

    val queue = ArrayDeque<StreamPiece>()
    try {
        while (true) {
            drainAvailable(pieces, queue)
            if (queue.isEmpty()) {
                val next = pieces.receiveCatching().getOrNull() ?: break
                queue.addLast(next)
            }

            when (val first = queue.first()) {
                is StreamPiece.Event -> {
                    queue.removeFirst()
                    send(first.event)
                }
                is StreamPiece.Char -> {
                    val pendingChars = queue.countLeadingChars()
                    val batchSize = adaptiveBatchSize(pendingChars)
                    val chunk = StringBuilder(batchSize)
                    var taken = 0
                    while (taken < batchSize) {
                        val piece = queue.firstOrNull() as? StreamPiece.Char ?: break
                        if (piece.kind != first.kind) break
                        queue.removeFirst()
                        chunk.append(piece.value)
                        taken++
                    }
                    if (chunk.isNotEmpty()) {
                        send(
                            when (first.kind) {
                                Kind.TEXT -> StreamEvent.Delta(text = chunk.toString())
                                Kind.THINKING -> StreamEvent.Delta(thinking = chunk.toString())
                            },
                        )
                        delay(WEB_TYPING_TICK_MS)
                    }
                }
            }
        }
    } finally {
        producer.cancel()
    }
}

private fun drainAvailable(channel: ReceiveChannel<StreamPiece>, queue: ArrayDeque<StreamPiece>) {
    while (true) {
        val result = channel.tryReceive()
        if (result.isFailure) return
        queue.addLast(result.getOrThrow())
    }
}

private fun ArrayDeque<StreamPiece>.countLeadingChars(): Int {
    var count = 0
    for (piece in this) {
        if (piece !is StreamPiece.Char) break
        count++
    }
    return count
}

private fun adaptiveBatchSize(queueSize: Int): Int = when {
    queueSize >= 1200 -> 36
    queueSize >= 700 -> 24
    queueSize >= 320 -> 14
    queueSize >= 120 -> 8
    queueSize >= 40 -> 4
    queueSize >= 12 -> 2
    else -> 1
}

private sealed interface StreamPiece {
    data class Char(val kind: Kind, val value: kotlin.Char) : StreamPiece
    data class Event(val event: StreamEvent) : StreamPiece
}

private enum class Kind { TEXT, THINKING }

private const val WEB_TYPING_TICK_MS = 4L
