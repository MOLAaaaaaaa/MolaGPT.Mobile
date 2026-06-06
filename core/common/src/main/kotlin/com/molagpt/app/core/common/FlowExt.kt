package com.molagpt.app.core.common

import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch

/**
 * 时间窗节流：把上游在每个 [windowMillis] 窗口内到达的元素**合并成一批**下发。
 *
 * 这是流式 token "16–50ms 合并一次"的核心原语——避免每个 token 触发一次 UI 重组。
 * 语义：首个元素到达即开一个计时器，窗口结束时把已缓冲元素整批 emit；上游结束时
 * 等当前窗口收尾并冲刷剩余。即使末批因竞态略有偏差，ChatStreamController 的终止
 * Complete 命令也会让仓库落定最终完整消息，故 UI 最终态始终正确。
 */
fun <T> Flow<T>.chunkedTimeWindow(windowMillis: Long): Flow<List<T>> = channelFlow {
    val buffer = ArrayList<T>()
    val lock = Any()
    var ticker: Job? = null

    suspend fun ProducerScope<List<T>>.flush() {
        val batch: List<T> = synchronized(lock) {
            if (buffer.isEmpty()) return
            val copy = ArrayList(buffer)
            buffer.clear()
            copy
        }
        send(batch)
    }

    collect { item ->
        synchronized(lock) { buffer.add(item) }
        if (ticker?.isActive != true) {
            ticker = launch {
                delay(windowMillis)
                flush()
            }
        }
    }
    // 上游结束：等最后一个窗口收尾，再冲刷期间新到的残余。
    ticker?.join()
    flush()
}
