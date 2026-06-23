package com.molagpt.app.core.network

import com.molagpt.app.core.model.ByokProvider
import com.molagpt.app.core.model.ProviderKind
import com.molagpt.app.core.model.ProviderModel
import org.junit.Assert.assertEquals
import org.junit.Test

class ByokVisionModelSelectionTest {
    @Test
    fun keepsCurrentModelWhenItSupportsVision() {
        val provider = provider(
            model("chat-vision", vision = true),
            model("vision-proxy", vision = true),
        )

        assertEquals("chat-vision", selectByokVisionModel(provider, "chat-vision"))
    }

    @Test
    fun usesFirstVisionModelWhenCurrentModelCannotSeeImages() {
        val provider = provider(
            model("tool-only", vision = false),
            model("vision-proxy", vision = true),
        )

        assertEquals("vision-proxy", selectByokVisionModel(provider, "tool-only"))
    }

    @Test
    fun fallsBackToCurrentModelWhenNoVisionModelIsConfigured() {
        val provider = provider(model("tool-only", vision = false))

        assertEquals("tool-only", selectByokVisionModel(provider, "tool-only"))
    }

    private fun provider(vararg models: ProviderModel) = ByokProvider(
        id = "openrouter",
        name = "OpenRouter",
        baseUrl = "https://openrouter.ai/api/",
        models = models.toList(),
    )

    private fun model(id: String, vision: Boolean) = ProviderModel(
        id = id,
        displayName = id,
        apiUrl = "v1/chat/completions",
        supportsVision = vision,
        supportsToolCalling = true,
        providerId = "openrouter",
        providerName = "OpenRouter",
        providerKind = ProviderKind.BYOK,
    )
}
