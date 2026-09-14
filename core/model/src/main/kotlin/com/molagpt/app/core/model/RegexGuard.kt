package com.molagpt.app.core.model

import java.util.concurrent.ExecutionException
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * 跑用户自己写的正则时的止损闸。
 *
 * **先说清楚这道闸能做到什么、做不到什么**，因为很容易想当然：
 *
 * Android 的 `java.util.regex.Matcher.reset()` 一进来就 `this.text = input.toString()`，
 * 然后把这个 String 交给原生 ICU 引擎（platform 源码 android-36 `Matcher.java:1812`）。
 * 所以「包一层 CharSequence、在 `get()` 上查到期」那种写法在真机上一次都不会被调用到——
 * 它只在桌面 JVM 成立，因为 OpenJDK 的 Matcher 保留 CharSequence 并逐字符 `charAt`。
 * 平台也没有把 ICU 的 `uregex_setTimeLimit` 暴露出来，`Thread.interrupt()` 同样打断不了
 * 原生匹配。**结论：在 Android 上没有办法中止一次已经开始的正则匹配。**
 *
 * 于是这里只保证做得到的那一件事：**调用方不会被拖住**。匹配放到一条可抛弃的工作线程上，
 * 到期就不再等它，原文原样返回、把这条规则记为失败；被放弃的那条线程会继续算到自己结束
 * （它是守护线程，不持有任何共享状态，也不会影响回答落库）。
 *
 * 线程数量有上限：一条病态正则被反复触发时，多出来的调用直接按超时处理，
 * 而不是无限制地再开线程——宁可让后处理暂时失效，也不能把设备拖垮。
 *
 * 对正则语法没有任何限制：不禁用嵌套量词，也不改写用户写的模式。
 */
object RegexGuard {

    /** 到期放弃等待。不带栈、不可抑制——它是流程信号，不是异常现场。 */
    class Expired : RuntimeException(null, null, false, false)

    /** 同时允许存在的匹配线程数。超过就直接判超时。 */
    const val MAX_WORKERS = 4

    private val workers = ThreadPoolExecutor(
        0,
        MAX_WORKERS,
        10L,
        TimeUnit.SECONDS,
        SynchronousQueue(),
    ) { runnable ->
        Thread(runnable, "regex-guard").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
    }

    /**
     * 在工作线程上执行 [block]，最多等 [timeoutMs] 毫秒。
     *
     * 到期抛 [Expired]；[block] 自己抛出的异常原样向上抛。
     */
    fun <T> runBounded(timeoutMs: Long, block: () -> T): T {
        val future = try {
            workers.submit(block)
        } catch (rejected: RejectedExecutionException) {
            // 线程都卡在别的病态正则上了。再排队只会让这次调用也一起卡死。
            throw Expired()
        }
        return try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (timeout: TimeoutException) {
            // cancel 只能阻止「还没开始」的任务；已经进原生匹配的那条只能由它自己跑完。
            future.cancel(true)
            throw Expired()
        } catch (failure: ExecutionException) {
            throw failure.cause ?: failure
        }
    }
}
