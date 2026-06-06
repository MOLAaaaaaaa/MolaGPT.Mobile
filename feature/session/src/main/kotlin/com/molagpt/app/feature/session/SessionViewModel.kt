package com.molagpt.app.feature.session

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.insertSeparators
import androidx.paging.map
import com.molagpt.app.core.model.Conversation
import com.molagpt.app.core.storage.SessionRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class SessionViewModel(
    private val sessionRepository: SessionRepository,
) : ViewModel() {

    val sessionItems: Flow<PagingData<SessionListItem>> = sessionRepository.pagedSessions()
        .map { pagingData ->
            val grouping = SessionListGrouping()
            pagingData
                .map { conversation -> grouping.row(conversation) }
                .insertSeparators { before: SessionListItem.Row?, after: SessionListItem.Row? ->
                    grouping.headerBetween(before, after)
                }
        }
        .cachedIn(viewModelScope)

    fun rename(sessionId: String, title: String) = viewModelScope.launch { sessionRepository.rename(sessionId, title) }
    fun delete(sessionId: String) = viewModelScope.launch { sessionRepository.delete(sessionId) }
    fun togglePin(c: Conversation) = viewModelScope.launch { sessionRepository.setPinned(c.sessionId, !c.pinned) }
}
