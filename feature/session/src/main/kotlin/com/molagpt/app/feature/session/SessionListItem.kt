package com.molagpt.app.feature.session

import com.molagpt.app.core.model.Conversation
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

    data class Row(
        val conversation: Conversation,
        val time: String,
        val groupLabel: String,
    ) : SessionListItem {
        override val key: String = conversation.sessionId
        override val contentType: String = "conversation"
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

    fun row(conversation: Conversation): SessionListItem.Row {
        val groupLabel = when {
            conversation.pinned -> GROUP_PINNED
            conversation.updatedAt >= startToday -> GROUP_TODAY
            conversation.updatedAt >= startWeek -> GROUP_RECENT
            else -> GROUP_OLDER
        }
        return SessionListItem.Row(
            conversation = conversation,
            time = timeLabel(conversation.updatedAt, startToday, startWeek, timeFormat, dateFormat),
            groupLabel = groupLabel,
        )
    }

    fun headerBetween(before: SessionListItem.Row?, after: SessionListItem.Row?): SessionListItem.Header? {
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
