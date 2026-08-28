package com.molagpt.app.feature.session

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.molagpt.app.core.model.Conversation
import com.molagpt.app.core.storage.SessionRepository
import kotlinx.coroutines.launch

class SessionViewModel(
    private val sessionRepository: SessionRepository,
) : ViewModel() {

    val sessionItems = SessionItemsSource(sessionRepository, viewModelScope)

    fun rename(sessionId: String, title: String) = viewModelScope.launch { sessionRepository.rename(sessionId, title) }
    fun delete(sessionId: String) = viewModelScope.launch { sessionRepository.delete(sessionId) }
    fun togglePin(c: Conversation) = viewModelScope.launch { sessionRepository.setPinned(c.sessionId, !c.pinned) }

    /** 批量删除；返回已删 id，供调用方逐个 schedulePush。 */
    suspend fun deleteAll(sessionIds: Collection<String>): List<String> = sessionRepository.deleteAll(sessionIds)

    /** 「全选」取数：数据库中全部可见会话，不限于 Paging 已加载的部分。 */
    suspend fun allVisibleSessionIds(): List<String> = sessionRepository.allVisibleSessionIds()
}
