package com.molagpt.app.feature.session

import androidx.compose.runtime.Immutable
import com.molagpt.app.core.model.Conversation
import com.molagpt.app.core.model.DesktopConversation
import com.molagpt.app.core.model.Persona
import com.molagpt.app.core.storage.SessionHit
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

sealed interface SessionListItem {
    val key: String
    val contentType: String

    data class Header(val label: String) : SessionListItem {
        override val key: String = "h:$label"
        override val contentType: String = "header"
    }

    /** 归入「置顶 / 今天 / 最近 7 天 / 更早」分组的行。 */
    sealed interface Grouped : SessionListItem {
        val groupLabel: String
    }

    data class Row(
        val conversation: Conversation,
        val time: String,
        override val groupLabel: String,
        /** 正文命中片段；标题命中或非搜索态为 null，UI 据此决定是否展示第二行。 */
        val snippet: String? = null,
    ) : Grouped {
        override val key: String = conversation.sessionId
        override val contentType: String = "conversation"
    }

    /** 电脑 Work 会话，按更新时间与本地会话交错排列。 */
    data class Desktop(
        val row: DesktopSidebarConversation,
        val time: String,
        override val groupLabel: String,
        /** 来源标签里的次要信息：离线状态，以及多台电脑时的电脑名。 */
        val detail: String?,
    ) : Grouped {
        override val key: String = "desktop:${row.machineId}/${row.conversation.id}"
        override val contentType: String = "desktop-conversation"
    }
}

/** 电脑 Work 会话在侧边栏中的投影；正文仍由电脑端提供，不进入 Room。 */
data class DesktopSidebarConversation(
    val machineId: String,
    val machineName: String,
    val conversation: DesktopConversation,
    val online: Boolean,
    val paired: Boolean,
    val pairing: Boolean = false,
)

/**
 * 会话行上的角色名快照（角色 id → 名称）。
 *
 * 只提示「非默认角色」：未绑定角色、绑定的角色已被删除，两种情况聊天页都回退内置「通用助手」，
 * 是绝大多数会话的状态，标出来只会挤掉标题。
 */
@Immutable
class PersonaLabels private constructor(private val namesById: Map<String, String>) {

    /** 角色名；null 表示这一行不需要角色提示。 */
    fun nameOf(personaId: String?): String? {
        if (personaId == null || personaId == Persona.BUILTIN_DEFAULT_ID) return null
        return namesById[personaId]
    }

    companion object {
        val Empty = PersonaLabels(emptyMap())

        fun from(personas: List<Persona>): PersonaLabels =
            PersonaLabels(personas.associate { it.id to it.name })
    }
}

internal class SessionListGrouping(
    now: Long = System.currentTimeMillis(),
    locale: Locale = Locale.getDefault(),
) {
    private val startToday: Long
    private val startWeek: Long
    private val timeFormat = SimpleDateFormat("HH:mm", locale)
    private val dateFormat = SimpleDateFormat("M/d", locale)

    init {
        val calendar = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        startToday = calendar.timeInMillis
        startWeek = startToday - DAYS_IN_RECENT_GROUP * MILLIS_PER_DAY
    }

    fun row(hit: SessionHit): SessionListItem.Row {
        val conversation = hit.conversation
        return SessionListItem.Row(
            conversation = conversation,
            time = timeLabel(conversation.updatedAt, startToday, startWeek, timeFormat, dateFormat),
            groupLabel = if (conversation.pinned) GROUP_PINNED else groupOf(conversation.updatedAt),
            snippet = hit.matchedSnippet?.let(::normalizeSnippet)?.takeIf { it.isNotEmpty() },
        )
    }

    fun desktopRow(row: DesktopSidebarConversation, showMachine: Boolean): SessionListItem.Desktop {
        val at = row.conversation.updatedAtMs
        return SessionListItem.Desktop(
            row = row,
            time = if (at > 0) timeLabel(at, startToday, startWeek, timeFormat, dateFormat) else "",
            groupLabel = groupOf(at),
            detail = listOfNotNull(
                "离线".takeIf { !row.online },
                row.machineName.takeIf { showMachine },
            ).joinToString(" · ").ifEmpty { null },
        )
    }

    private fun groupOf(updatedAt: Long): String = when {
        updatedAt >= startToday -> GROUP_TODAY
        updatedAt >= startWeek -> GROUP_RECENT
        else -> GROUP_OLDER
    }

    fun headerBetween(before: SessionListItem.Grouped?, after: SessionListItem.Grouped?): SessionListItem.Header? {
        if (after == null) return null
        return if (before?.groupLabel == after.groupLabel) null else SessionListItem.Header(after.groupLabel)
    }
}

private const val MILLIS_PER_DAY = 86_400_000L
private const val DAYS_IN_RECENT_GROUP = 6L
private const val GROUP_PINNED = "置顶"
private const val GROUP_TODAY = "今天"
private const val GROUP_RECENT = "最近 7 天"
private const val GROUP_OLDER = "更早"

private fun timeLabel(
    updatedAt: Long,
    startToday: Long,
    startWeek: Long,
    timeFormat: SimpleDateFormat,
    dateFormat: SimpleDateFormat,
): String = when {
    updatedAt >= startToday -> timeFormat.format(Date(updatedAt))
    updatedAt >= startWeek -> weekdayLabel(updatedAt)
    else -> dateFormat.format(Date(updatedAt))
}

private fun weekdayLabel(ms: Long): String {
    val calendar = Calendar.getInstance().apply { timeInMillis = ms }
    return when (calendar.get(Calendar.DAY_OF_WEEK)) {
        Calendar.MONDAY -> "周一"
        Calendar.TUESDAY -> "周二"
        Calendar.WEDNESDAY -> "周三"
        Calendar.THURSDAY -> "周四"
        Calendar.FRIDAY -> "周五"
        Calendar.SATURDAY -> "周六"
        else -> "周日"
    }
}
