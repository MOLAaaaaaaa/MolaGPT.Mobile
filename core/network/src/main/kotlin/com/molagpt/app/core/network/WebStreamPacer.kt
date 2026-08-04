package com.molagpt.app.core.network

import com.molagpt.app.core.model.StreamEvent
import com.molagpt.app.core.model.ToolStatus
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch

/**
 * Smooths bursty text deltas while preserving the exact order of structural events.
 *
 * Adjacent text of the same kind is stored as one continuous run. Each tick releases a
 * backlog-dependent batch: small queues keep a readable typing cadence, while large queues
 * catch up quickly enough that a fast provider cannot leave the UI far behind.
 */
fun Flow<StreamEvent>.webTypingPaced(): Flow<StreamEvent> = channelFlow {
    val pieces = Channel<StreamPiece>(Channel.UNLIMITED)

    val producer = launch {
        try {
            collect { event ->
                if (event is StreamEvent.Delta && (event.text != null || event.thinking != null)) {
                    event.thinking?.takeIf(String::isNotEmpty)?.let {
                        pieces.send(StreamPiece.Text(Kind.THINKING, it))
                    }
                    event.text?.takeIf(String::isNotEmpty)?.let {
                        pieces.send(StreamPiece.Text(Kind.TEXT, it))
                    }
                } else {
                    pieces.send(StreamPiece.Event(event))
                }
            }
        } finally {
            pieces.close()
        }
    }

    val queue = ArrayDeque<StreamPiece>()
    val batchSizer = StreamBatchSizer()
    var activeKind: Kind? = null

    try {
        while (true) {
            drainAvailable(pieces, queue)
            if (queue.isEmpty()) {
                activeKind = null
                batchSizer.reset()
                val next = pieces.receiveCatching().getOrNull() ?: break
                queue.enqueue(next)
                drainAvailable(pieces, queue)
            }

            when (val first = queue.first()) {
                is StreamPiece.Event -> {
                    queue.removeFirst()
                    activeKind = null
                    batchSizer.reset()
                    send(first.event)

                    // Keep short-lived RUNNING states visible even when the provider immediately
                    // follows them with a terminal tool result.
                    if (first.event is StreamEvent.Tool && first.event.status == ToolStatus.RUNNING) {
                        delay(TOOL_RUNNING_FRAME_MS)
                    }
                }

                is StreamPiece.Text -> {
                    if (activeKind != first.kind) {
                        activeKind = first.kind
                        batchSizer.reset()
                    }

                    val batchSize = batchSizer.nextBatch(first.remaining)
                    val chunk = first.take(batchSize)
                    if (first.isEmpty) queue.removeFirst()

                    send(
                        when (first.kind) {
                            Kind.TEXT -> StreamEvent.Delta(text = chunk)
                            Kind.THINKING -> StreamEvent.Delta(thinking = chunk)
                        },
                    )
                    delay(STREAM_TEXT_TICK_MS)
                }
            }
        }
    } finally {
        producer.cancel()
    }
}

internal data class StreamPacingConfig(
    val tickMillis: Long = STREAM_TEXT_TICK_MS,
    val minimumCharsPerSecond: Double = 62.5,
    val maximumCharsPerSecond: Double = 600.0,
    val targetDrainMillis: Double = 600.0,
    val accelerationSmoothing: Double = 0.18,
    val decelerationSmoothing: Double = 0.12,
    val maximumBatch: Int = 10,
    val maximumBacklogFraction: Double = 0.15,
) {
    init {
        require(tickMillis > 0L)
        require(minimumCharsPerSecond > 0.0)
        require(maximumCharsPerSecond >= minimumCharsPerSecond)
        require(targetDrainMillis > 0.0)
        require(accelerationSmoothing in 0.0..1.0)
        require(decelerationSmoothing in 0.0..1.0)
        require(maximumBatch > 0)
        require(maximumBacklogFraction in 0.0..1.0)
    }
}

/** Calculates a frame-sized text batch from the current backlog. */
internal class StreamBatchSizer(
    private val config: StreamPacingConfig = StreamPacingConfig(),
) {
    private var smoothedRate = 0.0
    private var fractionalCharacters = 0.0

    fun reset() {
        smoothedRate = 0.0
        fractionalCharacters = 0.0
    }

    fun nextBatch(backlog: Int): Int {
        if (backlog <= 0) return 0

        val targetRate = (backlog * MILLIS_PER_SECOND / config.targetDrainMillis)
            .coerceIn(config.minimumCharsPerSecond, config.maximumCharsPerSecond)
        if (smoothedRate == 0.0) smoothedRate = config.minimumCharsPerSecond

        val smoothing = if (targetRate >= smoothedRate) {
            config.accelerationSmoothing
        } else {
            config.decelerationSmoothing
        }
        smoothedRate += (targetRate - smoothedRate) * smoothing

        fractionalCharacters += smoothedRate * config.tickMillis / MILLIS_PER_SECOND
        val rateBatch = fractionalCharacters.toInt()
        if (rateBatch > 0) fractionalCharacters -= rateBatch

        val tailLimit = (backlog * config.maximumBacklogFraction)
            .toInt()
            .coerceAtLeast(1)
        return rateBatch
            .coerceAtLeast(1)
            .coerceAtMost(config.maximumBatch)
            .coerceAtMost(tailLimit)
            .coerceAtMost(backlog)
    }
}

private fun drainAvailable(channel: ReceiveChannel<StreamPiece>, queue: ArrayDeque<StreamPiece>) {
    while (true) {
        val result = channel.tryReceive()
        if (result.isFailure) return
        queue.enqueue(result.getOrThrow())
    }
}

private fun ArrayDeque<StreamPiece>.enqueue(piece: StreamPiece) {
    val tail = lastOrNull()
    if (piece is StreamPiece.Text && tail is StreamPiece.Text && tail.kind == piece.kind) {
        tail.append(piece)
    } else {
        addLast(piece)
    }
}

private sealed interface StreamPiece {
    class Text(
        val kind: Kind,
        text: String,
    ) : StreamPiece {
        private val value = StringBuilder(text)
        private var start = 0

        val remaining: Int get() = value.length - start
        val isEmpty: Boolean get() = start >= value.length

        fun append(other: Text) {
            value.append(other.value, other.start, other.value.length)
        }

        fun take(requested: Int): String {
            var end = (start + requested).coerceAtMost(value.length)
            if (
                end in (start + 1) until value.length &&
                value[end - 1].isHighSurrogate() &&
                value[end].isLowSurrogate()
            ) {
                end++
            }

            val result = value.substring(start, end)
            start = end
            compactIfNeeded()
            return result
        }

        private fun compactIfNeeded() {
            if (start >= TEXT_BUFFER_COMPACT_THRESHOLD && start * 2 >= value.length) {
                value.delete(0, start)
                start = 0
            }
        }
    }

    data class Event(val event: StreamEvent) : StreamPiece
}

private enum class Kind { TEXT, THINKING }

private const val STREAM_TEXT_TICK_MS = 16L
private const val TOOL_RUNNING_FRAME_MS = 64L
private const val TEXT_BUFFER_COMPACT_THRESHOLD = 1_024
private const val MILLIS_PER_SECOND = 1_000.0
