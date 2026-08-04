package com.molagpt.app.feature.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.isActive

@Composable
internal fun StreamRenderPacingEffect(active: Boolean) {
    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect

        StreamRenderPacing.startMonitoring()
        try {
            while (isActive) {
                withFrameNanos(StreamRenderPacing::onFrame)
            }
        } finally {
            StreamRenderPacing.stopMonitoring()
        }
    }
}

internal object StreamRenderPacing {
    private val tracker = FrameIntervalTracker()
    private var monitorCount = 0

    @Synchronized
    fun startMonitoring() {
        if (monitorCount++ == 0) tracker.reset()
    }

    @Synchronized
    fun stopMonitoring() {
        monitorCount = (monitorCount - 1).coerceAtLeast(0)
        if (monitorCount == 0) tracker.reset()
    }

    @Synchronized
    fun onFrame(frameTimeNanos: Long) {
        tracker.onFrame(frameTimeNanos)
    }

    @Synchronized
    fun windowMillis(baseWindowMillis: Long): Long =
        tracker.recommendedWindowMillis(baseWindowMillis)
}

internal class FrameIntervalTracker(
    private val sampleWindowNanos: Long = 1_000_000_000L,
    private val healthyFrameMillis: Long = 32L,
    private val healthyWindowMillis: Long = 16L,
    private val maximumWindowMillis: Long = 250L,
) {
    private var windowStartNanos: Long? = null
    private var previousFrameNanos: Long? = null
    private var currentWorstFrameNanos = 0L
    private var previousWorstFrameNanos = 0L

    fun reset() {
        windowStartNanos = null
        previousFrameNanos = null
        currentWorstFrameNanos = 0L
        previousWorstFrameNanos = 0L
    }

    fun onFrame(frameTimeNanos: Long) {
        val previous = previousFrameNanos
        if (previous == null) {
            windowStartNanos = frameTimeNanos
            previousFrameNanos = frameTimeNanos
            return
        }

        val frameInterval = (frameTimeNanos - previous).coerceAtLeast(0L)
        currentWorstFrameNanos = maxOf(currentWorstFrameNanos, frameInterval)

        val windowStart = windowStartNanos ?: frameTimeNanos
        if (frameTimeNanos - windowStart >= sampleWindowNanos) {
            previousWorstFrameNanos = currentWorstFrameNanos
            currentWorstFrameNanos = 0L
            windowStartNanos = frameTimeNanos
        }
        previousFrameNanos = frameTimeNanos
    }

    fun recommendedWindowMillis(baseWindowMillis: Long): Long {
        val worstFrameMillis = maxOf(currentWorstFrameNanos, previousWorstFrameNanos) /
            NANOS_PER_MILLISECOND
        val adaptiveWindow = if (worstFrameMillis <= healthyFrameMillis) {
            healthyWindowMillis
        } else {
            (worstFrameMillis * 2).coerceAtMost(maximumWindowMillis)
        }
        return maxOf(baseWindowMillis.coerceAtLeast(1L), adaptiveWindow)
    }
}

private const val NANOS_PER_MILLISECOND = 1_000_000L
