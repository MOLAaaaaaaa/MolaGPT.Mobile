package com.molagpt.app.core.render

import androidx.collection.LruCache
import com.molagpt.app.core.markdown.MarkdownParser
import com.molagpt.app.core.markdown.MdBlock

/**
 * 渲染缓存。当前主要缓存 **Markdown 源串 → 块列表** 的解析结果（解析是 CPU 热点，
 * 流式期间同一消息会被反复解析）。代码/LaTeX/Mermaid 的视觉产物
 * 由各自组件按需缓存（如 LaTeX 的 JLaTeXMath Drawable）。
 */
object RenderCache {
    private val blockCache = LruCache<String, List<MdBlock>>(256)
    private data class StreamBlocks(val markdown: String, val blocks: List<MdBlock>)
    private val streams = LruCache<String, StreamBlocks>(4)

    /** 解析 Markdown 为块列表（带缓存）。**调用方应在后台线程触发解析**。 */
    fun blocks(markdown: String): List<MdBlock> {
        blockCache.get(markdown)?.let { return it }
        val parsed = MarkdownParser.parse(markdown)
        blockCache.put(markdown, parsed)
        return parsed
    }

    /** 每条流只保留最新前缀，避免历史前缀占满普通缓存。 */
    fun streamingBlocks(key: String, markdown: String): List<MdBlock> {
        val previous = streams.get(key)
        if (previous?.markdown == markdown) return previous.blocks
        val parsed = MarkdownParser.parse(markdown)
        streams.put(key, StreamBlocks(markdown, parsed))
        return parsed
    }

    fun clear() {
        blockCache.evictAll()
        streams.evictAll()
    }
}
