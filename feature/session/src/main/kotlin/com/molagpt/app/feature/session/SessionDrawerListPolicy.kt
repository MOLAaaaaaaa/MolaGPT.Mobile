package com.molagpt.app.feature.session

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.layout.LazyLayoutCacheWindow
import androidx.compose.ui.unit.Dp

internal object SessionDrawerListPolicy {
    // Cache by viewport count, not by device pixels, screen model, or account size.
    private const val AHEAD_VIEWPORTS = 2
    private const val BEHIND_VIEWPORTS = 1

    @OptIn(ExperimentalFoundationApi::class)
    fun cacheWindow(viewportHeight: Dp): LazyLayoutCacheWindow =
        LazyLayoutCacheWindow(
            ahead = viewportHeight * AHEAD_VIEWPORTS,
            behind = viewportHeight * BEHIND_VIEWPORTS,
        )
}
