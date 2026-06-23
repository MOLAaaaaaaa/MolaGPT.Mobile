package com.molagpt.app.core.network

import com.molagpt.app.core.model.ProviderKind
import com.molagpt.app.core.model.ProviderModel
import org.junit.Assert.assertEquals
import org.junit.Test

class ModelRegistryTest {
    @Test
    fun apiUrlForUsesProviderIdWhenByokModelsShareModelId() {
        val registry = ModelRegistry()

        registry.updateByok(
            listOf(
                byokModel(providerId = "openrouter", apiUrl = "/openrouter/chat"),
                byokModel(providerId = "deepseek", apiUrl = "/deepseek/chat"),
            ),
        )

        assertEquals("/openrouter/chat", registry.apiUrlFor("openrouter", "deepseek-chat"))
        assertEquals("/deepseek/chat", registry.apiUrlFor("deepseek", "deepseek-chat"))
        assertEquals("/openrouter/chat", registry.apiUrlFor("deepseek-chat"))
    }

    private fun byokModel(providerId: String, apiUrl: String) = ProviderModel(
        id = "deepseek-chat",
        displayName = "DeepSeek Chat",
        apiUrl = apiUrl,
        providerId = providerId,
        providerName = providerId,
        providerKind = ProviderKind.BYOK,
    )
}
