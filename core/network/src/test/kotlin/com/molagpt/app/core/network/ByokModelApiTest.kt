package com.molagpt.app.core.network

import com.molagpt.app.core.model.ByokImageFormat
import com.molagpt.app.core.model.ByokProvider
import com.molagpt.app.core.model.ByokProviderType
import com.molagpt.app.core.model.ByokPurpose
import com.molagpt.app.core.model.ProviderKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ByokModelApiTest {
    @Test
    fun openRouterStyleChatModelIdsAreDetected() {
        assertTrue(looksLikeByokChatModel("anthropic/claude-sonnet-4"))
        assertTrue(looksLikeByokChatModel("openai/gpt-4.1"))
        assertTrue(looksLikeByokChatModel("google/gemini-2.5-flash"))
        assertTrue(looksLikeByokChatModel("deepseek/deepseek-reasoner"))
        assertTrue(looksLikeByokChatModel("meta-llama/llama-3.3-70b-instruct"))
    }

    @Test
    fun nonChatAndImageModelIdsAreExcludedFromChat() {
        assertFalse(looksLikeByokChatModel("openai/text-embedding-3-large"))
        assertFalse(looksLikeByokChatModel("openai/whisper-1"))
        assertFalse(looksLikeByokChatModel("openai/gpt-image-1"))
        assertFalse(looksLikeByokChatModel("google/imagen-4.0-generate-preview"))
        assertFalse(looksLikeByokChatModel("openai/gpt-realtime-2"))
        assertFalse(looksLikeByokChatModel("google/veo-3.1-generate-preview"))
    }

    @Test
    fun capabilityHintsSurviveProviderPrefixes() {
        assertTrue(looksLikeByokReasoningModel("openai/o3-mini"))
        assertTrue(looksLikeByokReasoningModel("openai/gpt-5.4"))
        assertTrue(looksLikeByokReasoningModel("deepseek/deepseek-reasoner"))
        assertTrue(looksLikeByokReasoningModel("anthropic/claude-sonnet-4"))
        assertTrue(looksLikeByokReasoningModel("anthropic/claude-3-7-sonnet-latest"))
        // 新模型：DeepSeek V3.2、Kimi K2.5、GLM-5、MiniMax-M3、MiMo 等。
        assertTrue(looksLikeByokReasoningModel("deepseek/deepseek-v3.2"))
        assertTrue(looksLikeByokReasoningModel("deepseek/deepseek-chat"))
        assertTrue(looksLikeByokReasoningModel("moonshotai/kimi-k2.5"))
        assertTrue(looksLikeByokReasoningModel("moonshotai/kimi-k2-thinking"))
        assertTrue(looksLikeByokReasoningModel("zhipuai/glm-5"))
        assertTrue(looksLikeByokReasoningModel("minimax/minimax-m3"))
        assertTrue(looksLikeByokReasoningModel("x-ai/grok-4"))
        assertFalse(looksLikeByokReasoningModel("openai/gpt-4o-non-reasoning"))
        assertTrue(looksLikeByokVisionModel("anthropic/claude-sonnet-4"))
        assertTrue(looksLikeByokImageModel("openai/gpt-image-1"))
        assertTrue(looksLikeByokImageModel("google/gemini-2.5-flash-image-preview"))
        assertTrue(looksLikeByokImageEditModel("openai/gpt-image-1"))
        assertFalse(looksLikeByokImageEditModel("openai/dall-e-3"))
    }

    @Test
    fun openRouterImageCatalogIsFullyDetected() {
        // OpenRouter 全量图像输出模型清单（35 个家族），逐个应为图像模型。
        val openRouterImageIds = listOf(
            "black-forest-labs/flux.2-flex",
            "black-forest-labs/flux.2-klein-4b",
            "black-forest-labs/flux.2-max",
            "black-forest-labs/flux.2-pro",
            "bytedance-seed/seedream-4.5",
            "google/gemini-2.5-flash-image",
            "google/gemini-3-pro-image",
            "google/gemini-3-pro-image-preview",
            "google/gemini-3.1-flash-image",
            "google/gemini-3.1-flash-image-preview",
            "microsoft/mai-image-2.5",
            "openai/gpt-5-image",
            "openai/gpt-5-image-mini",
            "openai/gpt-5.4-image-2",
            "recraft/recraft-v3",
            "recraft/recraft-v4",
            "recraft/recraft-v4-pro",
            "recraft/recraft-v4-pro-vector",
            "recraft/recraft-v4-vector",
            "recraft/recraft-v4.1",
            "recraft/recraft-v4.1-pro",
            "recraft/recraft-v4.1-pro-vector",
            "recraft/recraft-v4.1-utility",
            "recraft/recraft-v4.1-utility-pro",
            "recraft/recraft-v4.1-vector",
            "sourceful/riverflow-v2-fast",
            "sourceful/riverflow-v2-fast-preview",
            "sourceful/riverflow-v2-max-preview",
            "sourceful/riverflow-v2-pro",
            "sourceful/riverflow-v2-standard-preview",
            "sourceful/riverflow-v2.5-fast",
            "sourceful/riverflow-v2.5-pro",
            "x-ai/grok-imagine-image-quality",
            "openai/gpt-image-1",
            "openai/dall-e-3",
        )
        openRouterImageIds.forEach { id ->
            assertTrue("expected image: $id", looksLikeByokImageModel(id))
        }
    }

    @Test
    fun imageEditHeuristicMarksNanoBananaAndGptImageButNotDalle() {
        assertTrue(looksLikeByokImageEditModel("openai/gpt-image-1"))
        assertTrue(looksLikeByokImageEditModel("openai/gpt-5-image"))
        assertTrue(looksLikeByokImageEditModel("google/gemini-2.5-flash-image"))
        assertTrue(looksLikeByokImageEditModel("google/gemini-3-pro-image"))
        assertTrue(looksLikeByokImageEditModel("recraft/recraft-v4-pro"))
        assertTrue(looksLikeByokImageEditModel("x-ai/grok-imagine-image-quality"))
        assertTrue(looksLikeByokImageEditModel("byteflux/img-edit-v1"))
        assertFalse(looksLikeByokImageEditModel("openai/dall-e-3"))
        assertFalse(looksLikeByokImageEditModel("recraft/recraft-v2"))
        assertFalse(looksLikeByokImageEditModel("random-co/chatter-edited"))
    }

    @Test
    fun imageModelNamesDoNotOverlapChatModels() {
        // 防回归：聊天/视觉模型不应被判为图像模型（即使 id 含 image 一词的特殊边角）。
        // gpt-4o 是视觉对话模型，不应进图像列表。
        assertFalse(looksLikeByokImageModel("openai/gpt-4o"))
        assertFalse(looksLikeByokImageModel("anthropic/claude-sonnet-4"))
        assertFalse(looksLikeByokImageModel("deepseek/deepseek-chat"))
    }

    @Test
    fun openAiCompatibleModelResponseKeepsChatOnlyForChatPurpose() {
        // chat 用途：图像模型不再混入聊天列表，交给 image 用途 provider。
        val models = api.parseByokModels(
            json.parseToJsonElement(
                """
                {
                  "data": [
                    {"id": "openai/gpt-4.1"},
                    {"id": "openai/gpt-image-1"},
                    {"id": "openai/text-embedding-3-large"}
                  ]
                }
                """.trimIndent(),
            ).jsonObject,
            provider("openrouter", ByokProviderType.OPENAI_COMPAT, ByokPurpose.CHAT),
        )

        assertEquals(1, models.size)
        val chat = models.single { it.id == "openai/gpt-4.1" }
        assertTrue(chat.supportsChat)
        assertTrue(chat.supportsToolCalling)
        assertFalse(chat.supportsImageGeneration)
        assertEquals(ProviderKind.BYOK, chat.providerKind)
    }

    @Test
    fun imagePurposeOnlyReturnsImageModels() {
        val models = api.parseByokModels(
            json.parseToJsonElement(
                """
                {
                  "data": [
                    {"id": "openai/gpt-4.1"},
                    {"id": "openai/gpt-image-1"},
                    {"id": "openai/dall-e-3"}
                  ]
                }
                """.trimIndent(),
            ).jsonObject,
            provider("openai-images", ByokProviderType.OPENAI_COMPAT, ByokPurpose.IMAGE),
        )

        assertEquals(2, models.size)
        models.forEach {
            assertFalse(it.supportsChat)
            assertTrue(it.supportsImageGeneration)
        }
        val gptImage = models.single { it.id == "openai/gpt-image-1" }
        assertTrue(gptImage.supportsImageEdit)
        val dalle = models.single { it.id == "openai/dall-e-3" }
        assertFalse(dalle.supportsImageEdit)
    }

    @Test
    fun anthropicModelResponseMarksVisionThinkingAndEffort() {
        val models = api.parseByokModels(
            json.parseToJsonElement(
                """
                {
                  "data": [
                    {"id": "claude-sonnet-4-20250514", "display_name": "Claude Sonnet 4"}
                  ]
                }
                """.trimIndent(),
            ).jsonObject,
            provider("anthropic", ByokProviderType.ANTHROPIC, ByokPurpose.CHAT),
        )

        val model = models.single()
        assertEquals("Claude Sonnet 4", model.displayName)
        assertTrue(model.supportsChat)
        assertTrue(model.supportsVision)
        assertTrue(model.supportsThinking)
        assertTrue(model.supportsReasoningEffort)
        assertTrue(model.supportsToolCalling)
    }

    @Test
    fun geminiModelResponseChatPurposeExcludesImagenAndAudio() {
        val models = api.parseByokModels(
            json.parseToJsonElement(
                """
                {
                  "models": [
                    {
                      "name": "models/gemini-2.5-flash",
                      "displayName": "Gemini 2.5 Flash",
                      "supportedGenerationMethods": ["generateContent", "streamGenerateContent"]
                    },
                    {
                      "name": "models/gemini-2.5-flash-image-preview",
                      "displayName": "Nano Banana",
                      "supportedGenerationMethods": ["generateContent", "streamGenerateContent"]
                    },
                    {
                      "name": "models/imagen-4.0-generate-preview",
                      "displayName": "Imagen 4",
                      "supportedGenerationMethods": ["predict"]
                    }
                  ]
                }
                """.trimIndent(),
            ).jsonObject,
            provider("gemini", ByokProviderType.GEMINI, ByokPurpose.CHAT),
        )

        // gemini-2.5-flash-image-preview 命中 looksLikeByokImageModel（含 flash-image），
        // 但 chat 用途 parseGeminiModels 不按名称过滤——它靠 supportedGenerationMethods 过滤 imagen。
        // 故 flash-image 仍会出现在 chat 列表里（Gemini 出图靠 image 用途 provider）；这里只校验 imagen 被排除。
        assertTrue(models.any { it.id == "gemini-2.5-flash" })
        assertFalse(models.any { it.id == "imagen-4.0-generate-preview" })
        assertTrue(models.all { it.supportsChat })
        assertTrue(models.all { !it.supportsImageGeneration })
    }

    @Test
    fun geminiImagePurposeOnlyReturnsImagenAndImageModels() {
        val models = api.parseByokModels(
            json.parseToJsonElement(
                """
                {
                  "models": [
                    {"name": "models/gemini-2.5-flash", "displayName": "Gemini 2.5 Flash"},
                    {"name": "models/gemini-2.5-flash-image-preview", "displayName": "Nano Banana"},
                    {"name": "models/imagen-4.0-generate-preview", "displayName": "Imagen 4"}
                  ]
                }
                """.trimIndent(),
            ).jsonObject,
            provider("gemini-image", ByokProviderType.GEMINI, ByokPurpose.IMAGE),
        )

        // image 用途按名称 looksLikeByokImageModel 过滤；flash-image 与 imagen 命中，gemini-2.5-flash 不命中。
        assertTrue(models.any { it.id == "gemini-2.5-flash-image-preview" })
        assertTrue(models.any { it.id == "imagen-4.0-generate-preview" })
        assertFalse(models.any { it.id == "gemini-2.5-flash" })
        models.forEach {
            assertTrue(it.supportsImageGeneration)
            assertFalse(it.supportsChat)
        }
    }

    @Test
    fun openAiChatImageFormatUsesChatPathForApiUrl() {
        val provider = provider(
            "openrouter-images",
            ByokProviderType.OPENAI_COMPAT,
            ByokPurpose.IMAGE,
        ).copy(imageFormat = ByokImageFormat.OPENAI_CHAT_IMAGE)
        val models = api.parseByokModels(
            json.parseToJsonElement("""{"data":[{"id":"openai/gpt-image-1"}]}""").jsonObject,
            provider,
        )
        val model = models.single()
        assertEquals("v1/chat/completions", model.apiUrl)
    }

    private fun provider(
        id: String,
        type: ByokProviderType,
        purpose: ByokPurpose = ByokPurpose.CHAT,
    ) = ByokProvider(
        id = id,
        name = id,
        type = type,
        baseUrl = "https://example.com/",
        purpose = purpose,
    )

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
        val api = ByokModelApi(MolaHttp(userAgent = "test"))
    }
}
