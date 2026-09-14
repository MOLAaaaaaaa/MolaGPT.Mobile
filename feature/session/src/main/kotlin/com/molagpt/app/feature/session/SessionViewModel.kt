package com.molagpt.app.feature.session

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.molagpt.app.core.model.Conversation
import com.molagpt.app.core.storage.PersonaRepository
import com.molagpt.app.core.storage.SessionRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SessionViewModel(
    private val sessionRepository: SessionRepository,
    personaRepository: PersonaRepository,
) : ViewModel() {

    val sessionItems = SessionItemsSource(sessionRepository, viewModelScope)

    /** 角色名快照，供列表给 BYOK 会话标出所用角色；角色重命名后列表上的提示跟着变。 */
    val personaLabels: StateFlow<PersonaLabels> = personaRepository.observeAll()
        .map(PersonaLabels::from)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PersonaLabels.Empty)

    fun rename(sessionId: String, title: String) = viewModelScope.launch { sessionRepository.rename(sessionId, title) }
    fun delete(sessionId: String) = viewModelScope.launch { sessionRepository.delete(sessionId) }
    fun togglePin(c: Conversation) = viewModelScope.launch { sessionRepository.setPinned(c.sessionId, !c.pinned) }

    /** 批量删除；返回已删 id，供调用方逐个 schedulePush。 */
    suspend fun deleteAll(sessionIds: Collection<String>): List<String> = sessionRepository.deleteAll(sessionIds)

    /** 「全选」取数：数据库中全部可见会话，不限于 Paging 已加载的部分。 */
    suspend fun allVisibleSessionIds(): List<String> = sessionRepository.allVisibleSessionIds()
}
