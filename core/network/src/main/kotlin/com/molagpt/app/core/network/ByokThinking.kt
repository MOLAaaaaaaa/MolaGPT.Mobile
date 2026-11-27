package com.molagpt.app.core.network

import com.molagpt.app.core.model.ByokProvider
import com.molagpt.app.core.model.ReasoningOff
import com.molagpt.app.core.model.ThinkingKinds
import com.molagpt.app.core.model.ThinkingParamKind
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

internal fun JsonObjectBuilder.addOpenAiThinking(
    provider: ByokProvider, modelId: String, requestedThinking: Boolean, reasoningEffort: String,
    includeReasoningText: Boolean = false,
) {
    val config = provider.thinkingConfigFor(modelId) ?: return
    val kind = ThinkingKinds.wireKind(config.kind, provider.baseUrl)
    if (kind == ThinkingParamKind.NONE) return
    if (!requestedThinking && !ThinkingKinds.isAlwaysOn(config, provider.baseUrl)) {
        when (ThinkingKinds.offFor(provider.baseUrl, kind)) {
            ReasoningOff.OMIT -> if (ThinkingKinds.supportsExplicitNone(modelId, provider.baseUrl)) {
                put("reasoning_effort", "none")
            } else Unit
            ReasoningOff.UNSUPPORTED -> Unit
            ReasoningOff.REASONING_DISABLED -> putJsonObject("reasoning") { put("enabled", false) }
            ReasoningOff.THINKING_DISABLED -> putJsonObject("thinking") { put("type", "disabled") }
            ReasoningOff.ENABLE_THINKING_FALSE -> put("enable_thinking", false)
        }
        return
    }
    val effort = ThinkingKinds.resolveEffort(config, reasoningEffort, provider.baseUrl)
    when (kind) {
        ThinkingParamKind.OPENAI_REASONING_EFFORT -> {
            if (ThinkingKinds.isAggregatingGateway(provider.baseUrl)) {
                putJsonObject("reasoning") {
                    put("effort", effort)
                    if (includeReasoningText) put("exclude", false)
                }
            } else {
                if (ThinkingKinds.qwenEffortConfig(modelId, provider.baseUrl) != null) put("enable_thinking", true)
                put("reasoning_effort", effort)
            }
        }
        ThinkingParamKind.DEEPSEEK_THINKING -> {
            putJsonObject("thinking") { put("type", "enabled") }
            put("reasoning_effort", effort)
        }
        ThinkingParamKind.KIMI -> putJsonObject("thinking") { put("type", "enabled") }
        ThinkingParamKind.QWEN_THINKING_BUDGET -> {
            put("enable_thinking", true)
            put("thinking_budget", ThinkingKinds.budgetFor(kind, effort))
        }
        ThinkingParamKind.GEMINI, ThinkingParamKind.GEMINI_LEVEL -> put("reasoning_effort", effort)
        else -> Unit
    }
    if (includeReasoningText && provider.host == "generativelanguage.googleapis.com") {
        putJsonObject("extra_body") {
            putJsonObject("google") {
                putJsonObject("thinking_config") { put("include_thoughts", true) }
            }
        }
    }
}

internal fun JsonObjectBuilder.addResponsesThinking(
    provider: ByokProvider, modelId: String, requestedThinking: Boolean, requestedEffort: String,
    showSummary: Boolean,
) {
    val config = provider.thinkingConfigFor(modelId) ?: return
    if (config.kind == ThinkingParamKind.NONE) return
    if (requestedThinking || ThinkingKinds.isAlwaysOn(config, provider.baseUrl)) {
        putJsonObject("reasoning") {
            put("effort", ThinkingKinds.resolveEffort(config, requestedEffort, provider.baseUrl))
            if (showSummary && provider.isOfficialOpenAiResponses) put("summary", "auto")
        }
    } else if (ThinkingKinds.offFor(provider.baseUrl, config.kind) == ReasoningOff.REASONING_DISABLED) {
        putJsonObject("reasoning") { put("enabled", false) }
    } else if (ThinkingKinds.supportsExplicitNone(modelId, provider.baseUrl)) {
        putJsonObject("reasoning") { put("effort", "none") }
    }
}

internal fun JsonObjectBuilder.addAnthropicThinking(
    provider: ByokProvider, modelId: String, requestedThinking: Boolean, requestedEffort: String, maxTokens: Int,
) {
    val config = provider.thinkingConfigFor(modelId) ?: return
    if (!requestedThinking && !ThinkingKinds.isAlwaysOn(config, provider.baseUrl)) return
    val effort = ThinkingKinds.resolveEffort(config, requestedEffort, provider.baseUrl)
    when (config.kind) {
        ThinkingParamKind.CLAUDE_BUDGET -> {
            val budget = ThinkingKinds.budgetFor(config.kind, effort)
            put("max_tokens", maxOf(maxTokens, budget + 1024))
            putJsonObject("thinking") {
                put("type", "enabled")
                put("budget_tokens", budget)
            }
        }
        ThinkingParamKind.CLAUDE_ADAPTIVE -> {
            putJsonObject("thinking") { put("type", "adaptive") }
            putJsonObject("output_config") { put("effort", effort) }
        }
        else -> Unit
    }
}

internal fun JsonObjectBuilder.addGeminiThinking(
    provider: ByokProvider, modelId: String, requestedThinking: Boolean, requestedEffort: String, showThoughts: Boolean,
) {
    val config = provider.thinkingConfigFor(modelId) ?: return
    if (!requestedThinking && !ThinkingKinds.isAlwaysOn(config, provider.baseUrl)) return
    if (config.kind != ThinkingParamKind.GEMINI && config.kind != ThinkingParamKind.GEMINI_LEVEL) return
    val effort = ThinkingKinds.resolveEffort(config, requestedEffort, provider.baseUrl)
    putJsonObject("thinkingConfig") {
        if (config.kind == ThinkingParamKind.GEMINI_LEVEL) put("thinkingLevel", effort)
        else put("thinkingBudget", ThinkingKinds.budgetFor(config.kind, effort))
        if (showThoughts) put("includeThoughts", true)
    }
}
