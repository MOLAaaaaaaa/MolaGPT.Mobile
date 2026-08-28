package com.molagpt.app.feature.session

import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.insertSeparators
import androidx.paging.map
import com.molagpt.app.core.storage.SessionRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach

/**
 * 侧边栏会话数据源，同时持有搜索词。
 * 输入更新立即反映到 UI，数据库查询做短防抖并取消过期查询，避免连续输入时重复扫描历史消息。
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class SessionItemsSource internal constructor(
    sessionRepository: SessionRepository,
    scope: CoroutineScope,
) {
    private val mutableSearchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = mutableSearchQuery.asStateFlow()

    private val mutableAppliedQuery = MutableStateFlow("")

    /**
     * 已生效（防抖后）的搜索词。高亮用它而不是 [searchQuery] 实时值：
     * 否则防抖窗口里会拿新词去标旧结果，闪一下。UI 让位类判断（如隐藏「选择」入口）仍该用实时值。
     */
    val appliedQuery: StateFlow<String> = mutableAppliedQuery.asStateFlow()

    val pagingData: Flow<PagingData<SessionListItem>> = mutableSearchQuery
        .map { it.trim() }
        .debounce { query -> if (query.isEmpty()) 0L else SEARCH_DEBOUNCE_MS }
        .distinctUntilChanged()
        .onEach { mutableAppliedQuery.value = it }
        .flatMapLatest(sessionRepository::pagedSessions)
        .map { pagingData ->
            val grouping = SessionListGrouping()
            pagingData
                .map { hit -> grouping.row(hit) }
                .insertSeparators { before: SessionListItem.Row?, after: SessionListItem.Row? ->
                    grouping.headerBetween(before, after)
                }
        }
        .cachedIn(scope)

    fun setSearchQuery(query: String) {
        mutableSearchQuery.value = query.take(SessionRepository.MAX_SEARCH_QUERY_CHARS)
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 220L
    }
}
