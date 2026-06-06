package com.molagpt.app.core.common

/**
 * 纯 Kotlin 日志门面。默认无输出；:app 在启动时安装 Android logcat sink。
 * release 构建可装空 sink 以禁用 debug 日志（方案 L）。
 */
object Logger {
    interface Sink {
        fun log(level: Level, tag: String, message: String, throwable: Throwable?)
    }

    enum class Level { DEBUG, INFO, WARN, ERROR }

    @Volatile
    var sink: Sink? = null

    fun d(tag: String, message: String) = sink?.log(Level.DEBUG, tag, message, null)
    fun i(tag: String, message: String) = sink?.log(Level.INFO, tag, message, null)
    fun w(tag: String, message: String, t: Throwable? = null) = sink?.log(Level.WARN, tag, message, t)
    fun e(tag: String, message: String, t: Throwable? = null) = sink?.log(Level.ERROR, tag, message, t)
}
