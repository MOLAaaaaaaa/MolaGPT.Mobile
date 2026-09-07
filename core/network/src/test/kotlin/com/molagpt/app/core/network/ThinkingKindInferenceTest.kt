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
        "moonshotai/kimi-k2-thinking", "kimi-k2.5", "kimi-k3",
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
        assertEquals(ThinkingParamKind.OPENAI_REASONING_EFFORT, ThinkingKinds.inferFromModelId("kimi-k3"))
        assertEquals(ThinkingParamKind.OPENAI_REASONING_EFFORT, ThinkingKinds.inferFromModelId("moonshotai/kimi-k3"))
        assertEquals(ThinkingParamKind.QWEN_THINKING_BUDGET, ThinkingKinds.inferFromModelId("qwen3-235b-a22b"))
        assertEquals(ThinkingParamKind.GEMINI, ThinkingKinds.inferFromModelId("google/gemini-2.5-flash"))
        assertEquals(ThinkingParamKind.OPENAI_REASONING_EFFORT, ThinkingKinds.inferFromModelId("openai/o3-mini"))
    }

    @Test
    fun kimiK3IsAlwaysOnEffortConfig() {
        assertEquals(true, ThinkingKinds.isKimiK3("kimi-k3"))
        assertEquals(true, ThinkingKinds.isKimiK3("moonshotai/kimi-k3"))
        assertEquals(false, ThinkingKinds.isKimiK3("kimi-k2.5"))
        val cfg = ThinkingKinds.KIMI_K3_CONFIG
        assertEquals(true, cfg.alwaysOn)
        assertEquals(listOf("low", "high", "max"), ThinkingKinds.resolveEffortLevels(cfg))
        assertEquals("max", ThinkingKinds.resolveDefaultEffort(cfg))
    }

    @Test
    fun openRouterFoldsBudgetKindsToEffort() {
        assertEquals(
            ThinkingParamKind.OPENAI_REASONING_EFFORT,
            ThinkingKinds.wireKind(ThinkingParamKind.QWEN_THINKING_BUDGET, "https://openrouter.ai/api/v1"),
        )
        assertEquals(
            ThinkingParamKind.QWEN_THINKING_BUDGET,
            ThinkingKinds.wireKind(ThinkingParamKind.QWEN_THINKING_BUDGET, "https://dashscope.aliyuncs.com/compatible-mode/v1"),
        )
    }

    /** 聚合网关下所有非 NONE 方言（含 Kimi/DeepSeek/Claude）都应折算为 reasoning:{effort}，家族私有参数不外发。 */
    @Test
    fun openRouterFoldsEveryDialectToEffort() {
        val or = "https://openrouter.ai/api/v1"
        listOf(
            ThinkingParamKind.KIMI,
            ThinkingParamKind.DEEPSEEK_THINKING,
            ThinkingParamKind.CLAUDE_ADAPTIVE,
            ThinkingParamKind.CLAUDE_BUDGET,
            ThinkingParamKind.GEMINI,
            ThinkingParamKind.QWEN_THINKING_BUDGET,
        ).forEach { kind ->
            assertEquals(
                "wireKind($kind, OpenRouter) 应折算为 EFFORT",
                ThinkingParamKind.OPENAI_REASONING_EFFORT,
                ThinkingKinds.wireKind(kind, or),
            )
        }
        // NONE 保持 NONE（关闭推理）。
        assertEquals(ThinkingParamKind.NONE, ThinkingKinds.wireKind(ThinkingParamKind.NONE, or))
        // 直连（非聚合网关）保持原生方言。
        assertEquals(ThinkingParamKind.KIMI, ThinkingKinds.wireKind(ThinkingParamKind.KIMI, "https://api.moonshot.cn/v1"))
    }

    /** 手动指定行为类别时应留在同一家族，而非一律折成 Qwen/OpenAI 代表 kind。 */
    @Test
    fun kindForBehaviorStaysWithinFamily() {
        // Claude 选「思考预算」→ budget_tokens 而非 Qwen。
        assertEquals(
            ThinkingParamKind.CLAUDE_BUDGET,
            ThinkingKinds.kindForBehavior(
                com.molagpt.app.core.model.ThinkingBehavior.BUDGET,
                preferred = ThinkingParamKind.CLAUDE_ADAPTIVE,
            ),
        )
        // Gemini 选「思考预算」→ thinkingBudget（保留自身预算方言）。
        assertEquals(
            ThinkingParamKind.GEMINI,
            ThinkingKinds.kindForBehavior(
                com.molagpt.app.core.model.ThinkingBehavior.BUDGET,
                preferred = ThinkingParamKind.GEMINI,
            ),
        )
        // Claude 选「强度档位」→ thinking.effort（CLAUDE_ADAPTIVE）而非 OpenAI reasoning_effort。
        assertEquals(
            ThinkingParamKind.CLAUDE_ADAPTIVE,
            ThinkingKinds.kindForBehavior(
                com.molagpt.app.core.model.ThinkingBehavior.EFFORT,
                preferred = ThinkingParamKind.CLAUDE_BUDGET,
            ),
        )
        // DeepSeek 选「强度档位」→ 保留原生开关+档位。
        assertEquals(
            ThinkingParamKind.DEEPSEEK_THINKING,
            ThinkingKinds.kindForBehavior(
                com.molagpt.app.core.model.ThinkingBehavior.EFFORT,
                preferred = ThinkingParamKind.DEEPSEEK_THINKING,
            ),
        )
        // 无 preferred 提示时取该类别通用代表 kind。
        assertEquals(
            ThinkingParamKind.QWEN_THINKING_BUDGET,
            ThinkingKinds.kindForBehavior(com.molagpt.app.core.model.ThinkingBehavior.BUDGET),
        )
    }

    /** OpenRouter 无 supported_parameters 但名字像推理时，来源应标 HOST（服务商）而非 CAPABILITY（谎称权威识别）。 */
    @Test
    fun autoConfigWithoutCapabilityTableMarksHostSource() {
        val cfg = ThinkingKinds.autoConfigFor(
            "qwen/qwen3-max",
            "https://openrouter.ai/api/v1",
            supportedParams = null,
        )
        assertEquals(ThinkingParamKind.OPENAI_REASONING_EFFORT, cfg?.kind)
        assertEquals(com.molagpt.app.core.model.ThinkingDetectSource.HOST, cfg?.detectSource)
    }

    @Test
    fun autoConfigUsesCapabilityTableOnOpenRouter() {
        val withReasoning = ThinkingKinds.autoConfigFor(
            "qwen/qwen3-max",
            "https://openrouter.ai/api/v1",
            supportedParams = setOf("reasoning", "temperature"),
        )
        assertEquals(ThinkingParamKind.OPENAI_REASONING_EFFORT, withReasoning?.kind)
        assertEquals(com.molagpt.app.core.model.ThinkingDetectSource.CAPABILITY, withReasoning?.detectSource)

        val without = ThinkingKinds.autoConfigFor(
            "qwen/qwen3-max",
            "https://openrouter.ai/api/v1",
            supportedParams = setOf("temperature", "tools"),
        )
        assertEquals(null, without)
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
