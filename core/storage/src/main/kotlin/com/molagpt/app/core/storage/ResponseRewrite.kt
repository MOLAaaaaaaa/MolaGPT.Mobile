package com.molagpt.app.core.storage

import com.molagpt.app.core.model.ChatMessage
import com.molagpt.app.core.model.ChatMessageMetadataKeys
import com.molagpt.app.core.model.MessageFragment
import com.molagpt.app.core.network.TextReplacement
import com.molagpt.app.core.network.WireHistoryRewriter

/**
 * 把一次正文改写同时落到消息的**两处正文**上。
 *
 * 一条助手消息其实存了两份正文：
 * 1. [MessageFragment.Text] —— 屏幕上看到的、复制走的、落库的那份；
 * 2. metadata 里的 provider 原生协议快照 —— 下一轮原样回放给上游的那份。
 *
 * 只改第一份，下一轮就会把用户根本没看到的原文发出去。
 *
 * **规则只跑一次**，跑在显示片段上；快照拿的是同一批结果。两边分段并不一致（Gemini 可能分
 * 两个文本块回 `"甲,"` 和 `"乙"`，屏幕上是一条 `"甲,乙"`），各跑一遍必然得出不同结果——
 * 这也是失败与超时只会出现一次、不会重复上报的原因。
 *
 * 快照的分段与显示片段对不上时（块数不等，或合并后的原文逐字不同），把快照整个丢掉，退回按
 * 可见正文重建。少一点协议保真，好过发出一段对不上的文本。
 */
internal object ResponseRewrite {

    suspend fun apply(msg: ChatMessage, transform: suspend (String) -> String): ChatMessage {
        val texts = msg.fragments
            .filterIsInstance<MessageFragment.Text>()
            .filter { it.markdown.isNotEmpty() }
        if (texts.isEmpty()) return msg

        val replacements = texts.map { TextReplacement(it.markdown, transform(it.markdown)) }
        if (replacements.none { it.before != it.after }) return msg

        val processed = texts.indices.associate { texts[it].id to replacements[it].after }
        val fragments = msg.fragments.map { fragment ->
            val next = (fragment as? MessageFragment.Text)?.let { processed[it.id] }
            if (next == null) fragment else (fragment as MessageFragment.Text).copy(markdown = next)
        }
        return msg.copy(fragments = fragments, metadata = rewriteWireHistory(msg.metadata, replacements))
    }

    private fun rewriteWireHistory(
        metadata: Map<String, String>,
        replacements: List<TextReplacement>,
    ): Map<String, String> {
        var out = metadata
        for (key in ChatMessageMetadataKeys.WIRE_HISTORY) {
            val raw = metadata[key] ?: continue
            val rewritten = runCatching { WireHistoryRewriter.rewrite(raw, replacements) }.getOrNull()
            out = if (rewritten != null) out + (key to rewritten) else out - key
        }
        return out
    }
}
