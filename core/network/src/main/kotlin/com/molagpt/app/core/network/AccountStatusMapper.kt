package com.molagpt.app.core.network

import com.molagpt.app.core.model.AccountStatus
import com.molagpt.app.core.model.CreditsInfo
import com.molagpt.app.core.model.QuotaItem
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/**
 * 把 `status.php` 的原始 JSON 解析成 [AccountStatus]（个人中心用）。
 *
 * 结构差异（已对照后端 status.php）：
 * - 注册：`user.{logged_in,type,username,usage,tokens_usage,credits}` + `model_status{mid:{available,credit_multiplier,...}}`；
 * - 游客：`user.{logged_in:false,type:guest,credits}`（无 usage/limits）+ `model_status{mid:{...}}`。
 *
 * **两套口径**：后端启用点数（`user.credits.enabled`）后，逐模型的 `daily_limit` 全是 -1、
 * `remaining` 干脆不再下发，真正的预算是账号级共享的 [CreditsInfo]（按滚动窗口结算，
 * 见 `window_days` / `recovers_at` / `by_model_tokens`）；此时按 [CreditsInfo] 渲染。
 * 后端关掉点数则 `credits` 为 null，回落到下面的逐模型口径：
 * 注册用户 used 取 `user.usage[mid]`（权威，缺省 0）、分母取配置 `daily_limit`；游客 used 取
 * `daily_limit - remaining`。model_status 的 `remaining` 是 anti-abuse 限流后的瞬时额度，
 * **不能**用来反推注册用户已用、也不当分母。`remaining`<0（豁免用户的 -1）或缺失都视为「不限」。
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
    val tokensUsage = user?.get("tokens_usage") as? JsonObject
    val modelStatus = this["model_status"] as? JsonObject
    val credits = (user?.get("credits") as? JsonObject)?.toCreditsInfo()

    val quotas = modelStatus?.entries?.mapNotNull { (mid, st) ->
        val s = st as? JsonObject ?: return@mapNotNull null
        val available = s["available"].boolish()
        val reason = s["reason"]?.prim()?.contentOrNull
        // remaining 缺失或为负（豁免 -1）都按「不限」处理。
        val remaining = s["remaining"]?.prim()?.intOrNull?.takeIf { it >= 0 }

        if (credits != null) {
            // 点数模式：只丢掉结构性噪音（下架 / 不存在 / 捐赠者专属）。被点数或风控
            // 挡住的必须留下来标红——点数耗尽时所有计费模型都是 available:false，
            // 沿用旧的「不可用就丢弃」会让整个配额列表凭空消失。
            if (!available && reason in STRUCTURALLY_HIDDEN_REASONS) return@mapNotNull null
        } else {
            // 过滤禁用/需登录等噪音：既不可用又无可数额度的不展示。
            if (!available && remaining == null) return@mapNotNull null
        }

        val limitEntry = limits?.get(mid) as? JsonObject
        // 配置每日上限（user.limits 优先，回退 config.*_limits）。
        val rawLimit = limitEntry?.get("daily_limit")?.prim()?.intOrNull?.takeIf { it >= 0 }
        val usedFromServer = usage?.get(mid)?.prim()?.intOrNull?.takeIf { it >= 0 }
        // 已用次数：注册用户以 user.usage[mid] 为准；游客由 daily_limit - remaining 推导。
        // 绝不用 remaining 反推注册用户已用——remaining 是 anti-abuse 限流后的瞬时额度。
        val used = when {
            remaining == null -> usedFromServer // 不限：有真实已用才显示
            loggedIn -> usedFromServer ?: 0 // 注册：权威用量
            rawLimit != null -> (rawLimit - remaining).coerceAtLeast(0)
            else -> null
        }
        QuotaItem(
            modelId = mid,
            displayName = displayNameOf(mid)
                ?: limitEntry?.get("display_name")?.prim()?.contentOrNull
                ?: mid,
            available = available,
            used = used,
            remaining = remaining,
            // 分母使用配置每日上限；不限则未知。
            limit = if (remaining == null) null else rawLimit,
            // 窗口口径优先：「约 N 次」是按窗口余额算的，这里若混当日数据就是两个尺子。
            usedTokens = credits?.tokensFor(mid, sameDayTokens(tokensUsage, mid))
                ?: sameDayTokens(tokensUsage, mid),
            creditMultiplier = s["credit_multiplier"]?.prim()?.doubleOrNull,
            creditSymbol = s["credit_symbol"]?.prim()?.contentOrNull,
            message = s["message"]?.prim()?.contentOrNull,
        )
    }.orEmpty()

    return AccountStatus(
        loggedIn = loggedIn,
        userType = type,
        username = username,
        quotas = quotas,
        credits = credits,
        personalizedMemoryEnabled = (user?.get("settings") as? JsonObject)
            ?.get("personalized_memory_enabled").boolishOrNull(),
        cloudSyncEnabled = (user?.get("settings") as? JsonObject)
            ?.get("cloud_sync_enabled").boolishOrNull(),
    )
}

/**
 * 这些原因下模型对当前用户根本不存在，留在配额列表里只是噪音。
 * `unpriced` 不在其中：那是「后台漏了定价」，标出来比藏起来有用。
 */
private val STRUCTURALLY_HIDDEN_REASONS = setOf("model_not_found", "model_disabled", "donor_only")

/** `user.tokens_usage[mid]`，当日口径。 */
private fun sameDayTokens(tokensUsage: JsonObject?, mid: String): Int? =
    tokensUsage?.get(mid)?.prim()?.intOrNull?.takeIf { it > 0 }

/** `user.credits`。`enabled` 为 false / 字段缺失时返回 null，调用方据此回落到旧渲染。 */
private fun JsonObject.toCreditsInfo(): CreditsInfo? {
    if (!this["enabled"].boolish()) return null
    return CreditsInfo(
        enforcing = this["enforcing"].boolish(),
        tier = this["tier"]?.prim()?.contentOrNull ?: "registered",
        used = this["used"]?.prim()?.doubleOrNull ?: 0.0,
        allowance = this["allowance"]?.prim()?.doubleOrNull ?: 0.0,
        remaining = this["remaining"]?.prim()?.doubleOrNull ?: 0.0,
        baseTokensPerCredit = this["base_tokens_per_credit"]?.prim()?.intOrNull ?: 0,
        // 下面三个是滚动窗口引入的。旧版 status.php 不下发，缺省值正好等于
        // 那些版本真实的「每日 0 点清零」行为。
        windowDays = this["window_days"]?.prim()?.intOrNull?.coerceAtLeast(1) ?: 1,
        recoversAt = this["recovers_at"]?.prim()?.contentOrNull,
        byModelTokens = (this["by_model_tokens"] as? JsonObject)?.mapNotNull { (mid, v) ->
            v.prim()?.intOrNull?.let { mid to it }
        }?.toMap(),
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
