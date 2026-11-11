package com.molagpt.app.feature.settings

import com.molagpt.app.core.model.MembershipInfo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal fun MembershipInfo.summary(): String {
    if (!isMember) return if (sourceStatus == "expired") "支持者权益已到期" else "尚未成为支持者"
    val expiry = expiresAt
    val term = when {
        sourceStatus == "legacy_pending_review" -> "历史权益待确认"
        isPermanent -> "长期权益"
        expiry != null -> "到期 " + SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(expiry * 1000))
        else -> "查看支持者有效期"
    }
    return "$tierLabel · $term"
}
