package com.molagpt.app.core.common

/** 轻量结果类型，用于网络/仓库层把成功与失败统一表达，避免到处 try/catch 泄漏。 */
sealed interface AppResult<out T> {
    data class Success<T>(val data: T) : AppResult<T>
    data class Failure(val error: Throwable, val message: String? = error.message) : AppResult<Nothing>

    val isSuccess: Boolean get() = this is Success
}

inline fun <T, R> AppResult<T>.map(transform: (T) -> R): AppResult<R> = when (this) {
    is AppResult.Success -> AppResult.Success(transform(data))
    is AppResult.Failure -> this
}

inline fun <T> AppResult<T>.getOrElse(fallback: (AppResult.Failure) -> T): T = when (this) {
    is AppResult.Success -> data
    is AppResult.Failure -> fallback(this)
}

inline fun <T> runCatchingResult(block: () -> T): AppResult<T> = try {
    AppResult.Success(block())
} catch (c: kotlinx.coroutines.CancellationException) {
    throw c // 协程取消不当作业务失败
} catch (t: Throwable) {
    AppResult.Failure(t)
}
