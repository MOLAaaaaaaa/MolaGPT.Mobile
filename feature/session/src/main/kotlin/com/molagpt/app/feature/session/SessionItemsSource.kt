package com.molagpt.app.feature.session

import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.flatMap
import androidx.paging.insertSeparators
import androidx.paging.map
import com.molagpt.app.core.model.ConversationFilter
import com.molagpt.app.core.storage.SessionRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn

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

    private val mutableFilter = MutableStateFlow(ConversationFilter.ALL)
    val filter: StateFlow<ConversationFilter> = mutableFilter.asStateFlow()

    private val mutableDesktop = MutableStateFlow<List<DesktopSidebarConversation>>(emptyList())

    /** 当前筛选与搜索下可见的电脑会话，按更新时间倒序；只有一台电脑时行上不重复电脑名。 */
    private val visibleDesktop = combine(mutableDesktop, mutableAppliedQuery, mutableFilter) { rows, query, filter ->
        val showMachine = rows.distinctBy { it.machineId }.size > 1
        if (filter != ConversationFilter.ALL && filter != ConversationFilter.DESKTOP_WORK) {
            emptyList()
        } else {
            rows.filter { row ->
                query.isEmpty() || row.conversation.title.contains(query, ignoreCase = true) ||
                    row.machineName.contains(query, ignoreCase = true)
            }.sortedByDescending { it.conversation.updatedAtMs }
        }.map { it to showMachine }
    }

    private val localPaging = combine(mutableSearchQuery, mutableFilter) { query, filter ->
        query.trim() to filter
    }
        .debounce { (query, _) -> if (query.isEmpty()) 0L else SEARCH_DEBOUNCE_MS }
        .distinctUntilChanged()
        .onEach { (query, _) -> mutableAppliedQuery.value = query }
        .flatMapLatest { (query, filter) -> sessionRepository.pagedSessions(query, filter) }
        .cachedIn(scope)

    /**
     * 本地会话分页，电脑会话按更新时间插在相邻两条本地会话之间。
     * 比最后一条已加载会话更旧的电脑会话，等分页加载到底后才出现。本地一条都没有时为空，
     * 由 [desktopOnlyItems] 单独提供。
     */
    val pagingData: Flow<PagingData<SessionListItem>> = combine(localPaging, visibleDesktop) { paging, desktop ->
        val grouping = SessionListGrouping()
        val remote = desktop.map { (row, showMachine) -> grouping.desktopRow(row, showMachine) }
        paging
            .map { hit -> grouping.row(hit) }
            .insertSeparators<SessionListItem.Row, Any> { before, after ->
                if (before == null && after == null) return@insertSeparators null
                val upper = before?.let(::sortKey) ?: Long.MAX_VALUE
                val lower = after?.let(::sortKey) ?: Long.MIN_VALUE
                remote.filter { it.row.conversation.updatedAtMs.let { at -> at > lower && at <= upper } }
                    .takeIf { it.isNotEmpty() }
                    ?.let(::DesktopRun)
            }
            .flatMap<Any, SessionListItem.Grouped> { item ->
                if (item is DesktopRun) item.rows else listOf(item as SessionListItem.Row)
            }
            .insertSeparators<SessionListItem.Grouped, SessionListItem> { before, after ->
                grouping.headerBetween(before, after)
            }
    }

    /** 本地没有可显示的会话时（如「电脑 Work」筛选），电脑会话连同分组标题单独成表。 */
    val desktopOnlyItems: StateFlow<List<SessionListItem>> = visibleDesktop
        .map { desktop ->
            val grouping = SessionListGrouping()
            val rows = desktop.map { (row, showMachine) -> grouping.desktopRow(row, showMachine) }
            buildList {
                rows.forEachIndexed { index, row ->
                    grouping.headerBetween(rows.getOrNull(index - 1), row)?.let(::add)
                    add(row)
                }
            }
        }
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setSearchQuery(query: String) {
        mutableSearchQuery.value = query.take(SessionRepository.MAX_SEARCH_QUERY_CHARS)
    }

    fun setFilter(filter: ConversationFilter) {
        mutableFilter.value = filter
    }

    fun setDesktopSessions(rows: List<DesktopSidebarConversation>) {
        mutableDesktop.value = rows
    }

    /** 相邻两条本地会话之间要插入的电脑会话。 */
    private class DesktopRun(val rows: List<SessionListItem.Desktop>)

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 220L

        /** 置顶会话排在最前，电脑会话不会插进置顶区。 */
        fun sortKey(row: SessionListItem.Row): Long =
            if (row.conversation.pinned) Long.MAX_VALUE else row.conversation.updatedAt
    }
}
