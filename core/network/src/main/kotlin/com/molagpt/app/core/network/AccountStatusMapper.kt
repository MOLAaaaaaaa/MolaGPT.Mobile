package com.molagpt.app.core.network

import com.molagpt.app.core.model.AccountStatus
import com.molagpt.app.core.model.QuotaItem
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * 把 `status.php` 的原始 JSON 解析成 [AccountStatus]（个人中心用）。
 *
 * 结构差异（已对照后端 status.php）：
 * - 注册：`user.{logged_in,type,username,usage,limits}` + `model_status{mid:{available,remaining}}`；
 * - 游客：`user.{logged_in:false,type:guest}`（无 usage/limits）+ `model_status{mid:{available,remaining}}`。
 * 用量口径：注册用户 used 取 `user.usage[mid]`（权威，缺省 0）、分母取配置 `daily_limit`；
 * 游客 used 取 `daily_limit - remaining`。
 * model_status 的 `remaining` 是 anti-abuse 限流后的瞬时额度，**不能**用来反推注册用户已用、也不当分母。
 * `remaining`<0（豁免用户的 -1）或缺失都视为「不限」。只保留可用或有可数额度的模型。
 */
fun JsonObject.toAccountStatus(displayNameOf: (String) -> String? = { null }): AccountStatus {
    val user = this["user"] as? JsonObject
    val loggedIn = user?.get("logged_in").boolish()
    val username = user?.get("username")?.prim()?.contentOrNull
    val type = user?.get("type")?.prim()?.contentOrNull ?: if (loggedIn) "registered" else "guest"

    val config = this["config"] as? JsonObject
    val limits = (user?.get("limits") as? JsonObject)
        ?: (config?.get("registered_user_limits") as? JsonObject)
        ?: (config?.get("guest_limits") as? JsonObject)
    val usage = user?.get("usage") as? JsonObject
    val modelStatus = this["model_status"] as? JsonObject

    val quotas = modelStatus?.entries?.mapNotNull { (mid, st) ->
        val s = st as? JsonObject ?: return@mapNotNull null
        val available = s["available"].boolish()
        // remaining 缺失或为负（豁免 -1）都按「不限」处理。
        val remaining = s["remaining"]?.prim()?.intOrNull?.takeIf { it >= 0 }
        // 过滤禁用/需登录等噪音：既不可用又无可数额度的不展示。
        if (!available && remaining == null) return@mapNotNull null
        // 配置每日上限（user.limits 优先，回退 config.*_limits）。
        val rawLimit = (limits?.get(mid) as? JsonObject)?.get("daily_limit")?.prim()?.intOrNull?.takeIf { it >= 0 }
        val usedFromServer = usage?.get(mid)?.prim()?.intOrNull?.takeIf { it >= 0 }
        // 已用次数：注册用户以 user.usage[mid] 为准；游客由 daily_limit - remaining 推导。
        // 绝不用 remaining 反推注册用户已用——remaining 是 anti-abuse 限流后的瞬时额度。
        val used = when {
            remaining == null -> usedFromServer // 不限：有真实已用才显示
            loggedIn -> usedFromServer ?: 0 // 注册：权威用量
            rawLimit != null -> (rawLimit - remaining).coerceAtLeast(0)
            else -> null
        }
        // 分母使用配置每日上限；不限则未知。
        val limit = if (remaining == null) null else rawLimit
        QuotaItem(
            modelId = mid,
            displayName = displayNameOf(mid) ?: mid,
            available = available,
            used = used,
            remaining = remaining,
            limit = limit,
        )
    }.orEmpty()

    return AccountStatus(
        loggedIn = loggedIn,
        userType = type,
        username = username,
        quotas = quotas,
        personalizedMemoryEnabled = (user?.get("settings") as? JsonObject)
            ?.get("personalized_memory_enabled").boolishOrNull(),
        cloudSyncEnabled = (user?.get("settings") as? JsonObject)
            ?.get("cloud_sync_enabled").boolishOrNull(),
    )
}

private fun kotlinx.serialization.json.JsonElement?.prim(): JsonPrimitive? = this as? JsonPrimitive

/** 兼容 JSON 布尔与字符串 "true"（status.php 部分字段为字符串）。 */
private fun kotlinx.serialization.json.JsonElement?.boolish(): Boolean {
    val p = this as? JsonPrimitive ?: return false
    return p.booleanOrNull ?: (p.contentOrNull == "true")
}

/** 同 [boolish] 但区分「未返回」：缺失返回 null（用于读回服务端开关初值）。 */
private fun kotlinx.serialization.json.JsonElement?.boolishOrNull(): Boolean? {
    val p = this as? JsonPrimitive ?: return null
    return p.booleanOrNull ?: when (p.contentOrNull) {
        "true" -> true
        "false" -> false
        else -> null
    }
}
