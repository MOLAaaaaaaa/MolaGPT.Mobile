package com.molagpt.app.core.model

/** 仅用于展示；会员、模型和额度的授权由服务器执行。时间均为 Unix 秒。 */
data class MembershipInfo(
    val groupKey: String? = null,
    val displayName: String? = null,
    val tier: String,
    val expiresAt: Long?,
    val serverTime: Long,
    val policyVersion: Int,
    val donorUntil: Long?,
    val premiumUntil: Long?,
    val sourceStatus: String,
    val isPermanent: Boolean,
) {
    val isMember: Boolean get() = tier == "donor" || tier == "premium"
    val tierLabel: String
        get() = when (supportDisplayName(displayName)) {
            null -> when (tier) {
                "premium" -> "高级支持者"
                "donor" -> "支持者"
                else -> "注册用户"
            }
            else -> supportDisplayName(displayName)!!
        }

    private fun supportDisplayName(raw: String?): String? {
        var value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        value = value.replace("捐赠者", "支持者")
            .replace("捐赠", "支持")
            .replace("会员", "支持")
        while (value.contains("支持者支持者")) {
            value = value.replace("支持者支持者", "支持者")
        }
        return if (value == "基本支持") "基本支持者" else value
    }
}
