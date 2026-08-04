package com.molagpt.app.core.render

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi

/** Keeps incremental Markdown parsing from competing for multiple CPU cores during streaming. */
object MarkdownRenderScheduler {
    @OptIn(ExperimentalCoroutinesApi::class)
    val dispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1)
}
