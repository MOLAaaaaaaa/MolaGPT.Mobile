package com.molagpt.app.core.model

import kotlin.math.ceil
import kotlin.math.floor

/**
 * 账号状态（个人中心用）。来源 `api/auth/status.php`：
 * `{ user:{logged_in,type,username,credits,...}, usage:{model:used}, limits:{model:{daily_limit,...}}, model_status:{model:{available,credit_multiplier,...}} }`。
 * 解析见 :core:network 的 AccountStatusMapper。
 */
data class AccountStatus(
    val loggedIn: Boolean,
    /** registered | guest。 */
    val userType: String,
    val username: String?,
    val quotas: List<QuotaItem> = emptyList(),
    /** 服务端 `user.credits`；null = 后端未启用点数配额，按旧的逐模型额度渲染。 */
    val credits: CreditsInfo? = null,
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
 * 全站共享的额度，取代了原来「每个模型一个次数/tokens 桶」的配额。
 *
 * 所有模型扣同一份额度，**切换模型不会恢复**；模型之间只差一个扣点倍率
 * （[QuotaItem.creditMultiplier]）。后端关掉点数时 status.php 给 null，
 * 客户端据此回落到旧渲染——这是灰度开关的客户端一侧。
 *
 * @param allowance 已按 [windowDays] 乘开，是整个窗口的预算而非一天的。
 * @param windowDays 滚动结算窗口。1 = 旧的 0 点清零；大于 1 时额度是「近 N 天累计」，
 *   任何时刻都不会整份归零，**所有「明日 0 点重置」的说法都不成立**。
 * @param recoversAt `yyyy-MM-dd`，窗口里最早那笔消耗滚出窗口的日子；没有任何消耗时为 null。
 * @param byModelTokens 窗口内逐模型 token 累计。「约 N 次」按窗口余额算，逐模型用量
 *   必须取这里——`user.tokens_usage` 只有当天，混用就是两个口径。
 */
data class CreditsInfo(
    val enforcing: Boolean,
    /** guest | new | registered | donor。 */
    val tier: String,
    val used: Double,
    val allowance: Double,
    val remaining: Double,
    val baseTokensPerCredit: Int,
    val windowDays: Int = 1,
    val recoversAt: String? = null,
    val byModelTokens: Map<String, Int>? = null,
) {
    val exhausted: Boolean get() = remaining <= 0.0

    /** 结算跨度超过一天。为真时不能再出现任何「明日 0 点」文案。 */
    val isRolling: Boolean get() = windowDays > 1

    /** 窗口内已用占比（0..1）。allowance 为 0 时返回 0，不做除零。 */
    val usedFraction: Float
        get() = if (allowance > 0) (used / allowance).toFloat().coerceIn(0f, 1f) else 0f

    /**
     * 剩余百分比。向上取整：「还剩一点点」不能显示成 0%，那会被读成「已经用完」，
     * 只有真正耗尽（[exhausted]）才是 0。
     */
    val remainingPercent: Int
        get() = if (exhausted) 0 else ceil(100.0 - usedFraction * 100.0).toInt().coerceIn(1, 100)

    /**
     * 档位文案与 Web 面板一致。**点数余额本身不向用户展示**——客户端只给这个标签、
     * 剩余百分比，以及逐模型的「约 N 次」。
     */
    val tierLabel: String
        get() = when (tier) {
            "donor" -> "捐赠者"
            "new" -> "新账号"
            "guest" -> "访客"
            else -> "注册用户"
        }

    /**
     * 剩余点数还能在该倍率的模型上跑几次（按平均对话长度估算）。
     * null = 未定价（服务端视为不可用），[Int.MAX_VALUE] = 免费不计次。
     * 向下取整，估算值宁可保守也不能给出跑不完的次数。
     */
    fun estimatedUses(multiplier: Double?): Int? = when {
        multiplier == null -> null
        multiplier <= 0.0 -> Int.MAX_VALUE
        else -> floor(remaining / multiplier).toInt()
    }

    /** 额度怎么结算，跟在档位标签后面的那行。 */
    val windowLabel: String
        get() = if (isRolling) "近 $windowDays 天累计" else "明日 0 点重置"

    /**
     * 耗尽后什么时候能再用。滚动窗口是一天一天滚出来的，不会在某一刻回满，
     * 所以只说「起逐步恢复」。**不带句末标点**，由调用方组句。
     */
    val recoveryLabel: String
        get() {
            if (!isRolling) return "明日 0 点恢复"
            val m = recoversAt?.let { DATE_RE.matchEntire(it) } ?: return "额度会随时间逐步恢复"
            return "${m.groupValues[2].toInt()} 月 ${m.groupValues[3].toInt()} 日起逐步恢复"
        }

    /** 逐模型用量的前缀，与 [windowLabel] 同口径。 */
    val spentLabel: String
        get() = if (isRolling) "近 $windowDays 天已用" else "今日已用"

    /**
     * 某模型在窗口内消耗的 tokens。服务端没下发窗口明细时才回落到调用方的当日数据
     * ——那些版本上两者本来就是同一个数。
     */
    fun tokensFor(modelId: String, sameDayFallback: Int?): Int? =
        if (byModelTokens != null) byModelTokens[modelId]?.takeIf { it > 0 } else sameDayFallback

    private companion object {
        /** `yyyy-MM-dd`。按本地日历读，不经时区换算，否则会差一天。 */
        val DATE_RE = Regex("""(\d{4})-(\d{2})-(\d{2})""")
    }
}

/**
 * 单模型今日状态。
 *
 * 点数模式下没有逐模型上限——[used]/[remaining]/[limit] 全为 null，可用信息是
 * [creditMultiplier]（扣点倍率）与账号级的 [CreditsInfo.remaining]。旧的逐模型
 * 额度字段保留，用于后端关掉点数时的回落渲染。
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
    /**
     * 已消耗 tokens；点数模式下这是唯一有意义的逐模型用量。
     * 口径跟着 [CreditsInfo.windowDays] 走——点数模式取窗口累计，旧模式取当日。
     */
    val usedTokens: Int? = null,
    /** 扣点倍率；null = 未定价，服务端据此判定不可用（而不是免费）。 */
    val creditMultiplier: Double? = null,
    /** 档位符号：`""`=免费，`"$"`..`"$$$$"`，null=未定价。 */
    val creditSymbol: String? = null,
    /** 不可用时的服务端文案。 */
    val message: String? = null,
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
