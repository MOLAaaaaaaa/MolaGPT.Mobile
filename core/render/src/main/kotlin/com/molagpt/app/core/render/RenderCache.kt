package com.molagpt.app.core.render

import androidx.collection.LruCache
import com.molagpt.app.core.markdown.MarkdownParser
import com.molagpt.app.core.markdown.MdBlock

/**
 * 渲染缓存。当前主要缓存 **Markdown 源串 → 块列表** 的解析结果（解析是 CPU 热点，
 * 流式期间同一消息会被反复解析）。key = 内容 hash。代码/LaTeX/Mermaid 的视觉产物
 * 由各自组件按需缓存（如 LaTeX 的 JLaTeXMath Drawable）。
 */
object RenderCache {
    private val blockCache = LruCache<Int, List<MdBlock>>(256)

    /** 解析 Markdown 为块列表（带缓存）。**调用方应在后台线程触发解析**。 */
    fun blocks(markdown: String): List<MdBlock> {
        val key = markdown.hashCode()
        blockCache.get(key)?.let { return it }
        val parsed = MarkdownParser.parse(markdown)
        blockCache.put(key, parsed)
        return parsed
    }

    fun clear() = blockCache.evictAll()
}
