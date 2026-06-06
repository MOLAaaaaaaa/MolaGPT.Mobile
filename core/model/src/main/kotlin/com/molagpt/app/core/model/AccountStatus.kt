package com.molagpt.app.core.model

/**
 * 账号状态（个人中心用）。来源 `api/auth/status.php`：
 * `{ user:{logged_in,type,username,...}, usage:{model:used}, limits:{model:{daily_limit,...}}, model_status:{model:{available,remaining}} }`。
 * 解析见 :core:network 的 AccountStatusMapper。
 */
data class AccountStatus(
    val loggedIn: Boolean,
    /** registered | guest。 */
    val userType: String,
    val username: String?,
    val quotas: List<QuotaItem> = emptyList(),
    /** 服务端 `user.settings.personalized_memory_enabled`（个性化记忆总开关）；null=未知/未返回。 */
    val personalizedMemoryEnabled: Boolean? = null,
    /** 服务端 `user.settings.cloud_sync_enabled`；null=未知/未返回。 */
    val cloudSyncEnabled: Boolean? = null,
) {
    val isRegistered: Boolean get() = userType.equals("registered", ignoreCase = true)

    companion object {
        val GUEST = AccountStatus(loggedIn = false, userType = "guest", username = null)
    }
}

/**
 * 单模型今日配额。status.php 注册分支给 user.limits/usage；游客分支只给 model_status 的 available/remaining。
 * [limit] = 配置每日上限 daily_limit；null = 不限或未知。
 * [remaining]==null 表示不限（无每日次数限制 / 豁免用户）。
 */
data class QuotaItem(
    val modelId: String,
    val displayName: String,
    val available: Boolean,
    /** 今日已用次数；null = 未知。 */
    val used: Int? = null,
    /** 今日剩余次数；null = 不限。 */
    val remaining: Int? = null,
    /** 今日有效上限；null = 不限或未知。 */
    val limit: Int? = null,
) {
    val unlimited: Boolean get() = remaining == null
    /** 已用占比（0..1）；仅当 limit/used 均已知且 limit>0 时有意义，否则 null（不画进度条）。 */
    val usedFraction: Float?
        get() = if (limit != null && limit > 0 && used != null) {
            (used.toFloat() / limit).coerceIn(0f, 1f)
        } else {
            null
        }
}
