package com.molagpt.app.core.network

import com.molagpt.app.core.model.SourceReference

/** search_web 的一条命中结果，尚未编号。 */
internal data class WebSearchHit(
    val title: String,
    val url: String,
    val snippet: String? = null,
    val publishedDate: String? = null,
)

/**
 * 一条 assistant 回答里所有 `search_web` 的来源账本。
 *
 * 编号必须**跨调用连续**：模型一轮里搜两次，第二次的结果若又从 1 开始，正文里的
 * `<ref source="1" />` 就同时指向了两条不同的来源。所以计数器跟着整条回答走，
 * 而不是跟着单次调用。同一个 URL 再次搜到时复用原编号，模型不会看到两个号指同一页。
 *
 * MolaGPT 自家后端（`chatcli.php`）做的是同一件事——编号、拼上下文、把来源表另发一路。
 * BYOK 没有后端，这一份就得在客户端做，两边的正文协议才对得上：见 core:markdown 的
 * `<ref>` 解析与 core:render 的引用胶囊。
 */
internal class WebSearchCitations {
    private val sources = ArrayList<SourceReference>()
    private val byUrl = HashMap<String, Int>()
    private val queries = LinkedHashSet<String>()

    val isEmpty: Boolean get() = sources.isEmpty()

    /** 累计来源全量。`SetSources` 是整体替换，所以每次都发全量而不是增量。 */
    fun snapshot(): List<SourceReference> = sources.toList()

    /** 本轮搜过的词，给来源抽屉当副标题。 */
    fun queryLabel(): String = queries.joinToString(" / ")

    /** 收下一次搜索的结果，返回被编号的命中（丢掉没有 URL 的，重复 URL 复用旧号）。 */
    fun absorb(query: String, hits: List<WebSearchHit>): List<Pair<WebSearchHit, Int>> {
        query.trim().takeIf { it.isNotEmpty() }?.let(queries::add)
        return hits.mapNotNull { hit ->
            val url = hit.url.trim()
            if (url.isEmpty()) return@mapNotNull null
            byUrl[url]?.let { return@mapNotNull hit to it }
            val id = sources.size + 1
            sources.add(
                SourceReference(
                    title = hit.title.ifBlank { url },
                    url = url,
                    snippet = hit.snippet?.takeIf { it.isNotBlank() },
                    index = id,
                ),
            )
            byUrl[url] = id
            hit to id
        }
    }

    /**
     * 回给模型的工具结果。
     *
     * 引用规则写在工具结果里而不是 system prompt：编号范围只有搜完才知道，而且规则贴着
     * 被编号的来源一起出现，模型照做的概率明显更高——自家后端也是这么拼的。代价是一轮搜
     * 多次会重复这段，所以它被压到了最短。
     */
    fun toolResult(numbered: List<Pair<WebSearchHit, Int>>): String {
        if (numbered.isEmpty()) return "未获取到搜索结果。"
        val blocks = numbered.joinToString("\n") { (hit, id) ->
            buildString {
                append("### [来源 ").append(id).append("]\n")
                append("**标题:** ").append(hit.title.ifBlank { hit.url }).append('\n')
                append("**网址:** ").append(hit.url).append('\n')
                hit.publishedDate?.takeIf { it.isNotBlank() }
                    ?.let { append("**发布日期:** ").append(it).append('\n') }
                hit.snippet?.takeIf { it.isNotBlank() }
                    ?.let { append("**内容:** ").append(it.replace('\n', ' ')).append('\n') }
            }
        }
        return "引用规则：直接用到某条来源的信息时，在该句**句末**写 <ref source=\"N\" />，" +
            "N 取下面的来源编号；多条来源支持同一句可写 <ref source=\"1,3\" />。" +
            "常识性、总结性、过渡性的句子不要加。可用编号 1-${sources.size}。\n\n" +
            blocks
    }
}
