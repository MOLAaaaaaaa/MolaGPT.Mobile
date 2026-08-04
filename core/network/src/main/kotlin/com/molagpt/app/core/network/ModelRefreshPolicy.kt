package com.molagpt.app.core.network

import com.molagpt.app.core.model.ProviderIds
import com.molagpt.app.core.model.ProviderKind
import com.molagpt.app.core.model.ProviderModel

/** 新对话打开时已经对失效设置做过回退的完整模型选择。 */
data class OpeningModelSelection(
    val modelId: String,
    val providerKind: ProviderKind,
    val providerId: String?,
)

/**
 * 用已加载的本地 BYOK 聊天模型解析新对话默认值。
 *
 * - 显式 MolaGPT：保留模型 id，按需加载官方列表。
 * - 显式 BYOK：优先精确模型，其次同 provider 的首个模型，再回退任意 BYOK。
 * - 旧设置：model id 能在 BYOK 中命中则迁移到 BYOK，否则按 MolaGPT 处理。
 * - 已保存 BYOK 失效且本地已无 BYOK：回退 MolaGPT，避免停在无模型阵营。
 */
fun resolveOpeningModelSelection(
    defaultModelId: String?,
    defaultProviderKind: ProviderKind?,
    defaultProviderId: String?,
    byokChatModels: List<ProviderModel>,
): OpeningModelSelection {
    val requestedId = normalizeDefaultModelId(defaultModelId)
    val byok = byokChatModels.filter { it.providerKind == ProviderKind.BYOK && it.supportsChat }
    val exactProviderModel = byok.firstOrNull {
        !defaultProviderId.isNullOrBlank() && it.providerId == defaultProviderId && it.id == requestedId
    }
    val exactByok = exactProviderModel ?: byok.firstOrNull { it.id == requestedId }

    fun mola(modelId: String = requestedId) = OpeningModelSelection(
        modelId = modelId,
        providerKind = ProviderKind.MOLAGPT,
        providerId = ProviderIds.MOLAGPT,
    )

    fun selectedByok(model: ProviderModel) = OpeningModelSelection(
        modelId = model.id,
        providerKind = ProviderKind.BYOK,
        providerId = model.providerId,
    )

    return when (defaultProviderKind) {
        ProviderKind.MOLAGPT -> mola()
        ProviderKind.BYOK -> {
            val selected = exactProviderModel
                ?: byok.firstOrNull { !defaultProviderId.isNullOrBlank() && it.providerId == defaultProviderId }
                ?: exactByok
                ?: byok.firstOrNull()
            selected?.let(::selectedByok) ?: mola(AUTO_MODEL_ID)
        }
        null -> exactByok?.let(::selectedByok) ?: mola()
    }
}

private fun normalizeDefaultModelId(modelId: String?): String {
    val id = modelId?.trim().orEmpty()
    return if (id.isEmpty() || id.equals("default", ignoreCase = true) ||
        id.equals("autoLLM", ignoreCase = true)
    ) {
        AUTO_MODEL_ID
    } else {
        id
    }
}

private const val AUTO_MODEL_ID = "auto"
