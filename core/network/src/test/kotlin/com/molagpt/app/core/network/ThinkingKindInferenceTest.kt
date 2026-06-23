package com.molagpt.app.core.network

import com.molagpt.app.core.model.ThinkingKinds
import com.molagpt.app.core.model.ThinkingParamKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 锁死 [ThinkingKinds.inferFromModelId]（core/model）与 [looksLikeByokReasoningModel]（core/network）的一致性：
 * 凡后者判为推理模型的代表性 id，前者都不能返回 NONE——否则该模型会显示推理 UI 但请求体不带推理参数。
 */
class ThinkingKindInferenceTest {

    /** [looksLikeByokReasoningModel] 认得的代表性推理模型，应全部能推断出非 NONE 的 kind。 */
    private val reasoningIds = listOf(
        "anthropic/claude-sonnet-4", "claude-3-7-sonnet", "opus-4", "haiku-4",
        "deepseek/deepseek-reasoner", "deepseek-chat", "deepseek-r1",
        "moonshotai/kimi-k2-thinking", "kimi-k2.5",
        "qwen3-235b-a22b", "qwq-32b",
        "google/gemini-2.5-flash", "gemini-3-pro",
        "openai/o1", "o3-mini", "o4-mini", "gpt-5", "openai/gpt-oss-120b",
        "x-ai/grok-4", "grok-3-mini",
        "hunyuan-t1", "glm-4.6", "glm-5", "minimax-m2", "minimax-m3",
        "mimo-v2-flash", "magistral-small", "seed-oss-36b", "gemma-4-27b", "pangu-pro-moe",
    )

    @Test
    fun everyReasoningModelInfersNonNoneKind() {
        reasoningIds.forEach { id ->
            assertEquals(
                "looksLikeByokReasoningModel('$id') 应为 true",
                true,
                looksLikeByokReasoningModel(id),
            )
            assertNotEquals(
                "inferFromModelId('$id') 不应为 NONE（否则推理 UI 显示但请求体不带参数）",
                ThinkingParamKind.NONE,
                ThinkingKinds.inferFromModelId(id),
            )
        }
    }

    @Test
    fun familySpecificKindsAreInferredCorrectly() {
        assertEquals(ThinkingParamKind.CLAUDE_ADAPTIVE, ThinkingKinds.inferFromModelId("anthropic/claude-sonnet-4"))
        assertEquals(ThinkingParamKind.DEEPSEEK_THINKING, ThinkingKinds.inferFromModelId("deepseek/deepseek-reasoner"))
        assertEquals(ThinkingParamKind.KIMI, ThinkingKinds.inferFromModelId("moonshotai/kimi-k2-thinking"))
        assertEquals(ThinkingParamKind.QWEN_THINKING_BUDGET, ThinkingKinds.inferFromModelId("qwen3-235b-a22b"))
        assertEquals(ThinkingParamKind.GEMINI, ThinkingKinds.inferFromModelId("google/gemini-2.5-flash"))
        assertEquals(ThinkingParamKind.OPENAI_REASONING_EFFORT, ThinkingKinds.inferFromModelId("openai/o3-mini"))
    }

    /** 非思考版 Qwen 不应误判为 QWEN_THINKING_BUDGET（避免对不支持的模型发 enable_thinking）。 */
    @Test
    fun nonThinkingQwenIsNotInferredAsThinking() {
        assertEquals(ThinkingParamKind.NONE, ThinkingKinds.inferFromModelId("qwen-max"))
        assertEquals(ThinkingParamKind.NONE, ThinkingKinds.inferFromModelId("qwen-turbo"))
        assertEquals(ThinkingParamKind.NONE, ThinkingKinds.inferFromModelId("qwen2.5-vl-72b-instruct"))
    }

    @Test
    fun plainModelsInferNone() {
        assertEquals(ThinkingParamKind.NONE, ThinkingKinds.inferFromModelId("gpt-4o"))
        assertEquals(ThinkingParamKind.NONE, ThinkingKinds.inferFromModelId("meta-llama/llama-3.3-70b-instruct"))
    }
}
