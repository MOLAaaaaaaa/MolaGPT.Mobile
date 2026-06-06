package com.molagpt.app.core.network

/**
 * 把内联 `<think>...</think>` 从正文里拆出来当推理显示。
 * 部分模型会把推理混在 delta.content 里，而非走独立 reasoning_content 通道。
 *
 * 状态跨 SSE chunk 持续：用 [carry] 缓冲可能被切断的标签（如某 chunk 以 "<thi" 结尾）。
 * 每个流单独 new 一个实例。
 */
class InlineThinkSplitter {
    private val open = "<think>"
    private val close = "</think>"
    private var inThink = false
    private val carry = StringBuilder()

    data class Split(val visible: String, val thinking: String)

    fun feed(text: String): Split {
        carry.append(text)
        val vis = StringBuilder()
        val think = StringBuilder()
        while (true) {
            val s = carry.toString()
            val tag = if (inThink) close else open
            val idx = s.indexOf(tag)
            if (idx >= 0) {
                val before = s.substring(0, idx)
                if (inThink) think.append(before) else vis.append(before)
                carry.setLength(0)
                carry.append(s.substring(idx + tag.length))
                inThink = !inThink
            } else {
                // 无完整标签：保留可能是“半个标签”的尾部，其余冲刷。
                val keep = longestSuffixPrefix(s, tag)
                val flushLen = s.length - keep
                val flushPart = s.substring(0, flushLen)
                if (inThink) think.append(flushPart) else vis.append(flushPart)
                carry.setLength(0)
                carry.append(s.substring(flushLen))
                break
            }
        }
        return Split(vis.toString(), think.toString())
    }

    fun flush(): Split {
        val s = carry.toString()
        carry.setLength(0)
        return if (inThink) Split("", s) else Split(s, "")
    }

    /** s 的最长后缀，且该后缀是 tag 的前缀（用于跨 chunk 的半标签保留）。 */
    private fun longestSuffixPrefix(s: String, tag: String): Int {
        val max = minOf(s.length, tag.length - 1)
        for (len in max downTo 1) {
            if (s.regionMatches(s.length - len, tag, 0, len)) return len
        }
        return 0
    }
}
