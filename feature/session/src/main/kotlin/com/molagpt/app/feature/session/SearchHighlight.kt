package com.molagpt.app.feature.session

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight

/**
 * 搜索命中区间。大小写不敏感，与 DAO 侧 `instr(lower(x), lower(q))` 的判定口径一致
 * （Kotlin 的 ignoreCase 覆盖面更广，只会多标不会漏标）。
 *
 * @param limit 病态输入（如单字符搜索词命中几十处）时的上限，避免堆出过多 span。
 */
internal fun matchRanges(text: String, query: String, limit: Int = 8): List<IntRange> {
    if (query.isEmpty() || text.isEmpty()) return emptyList()
    val ranges = mutableListOf<IntRange>()
    var from = 0
    while (ranges.size < limit) {
        val index = text.indexOf(query, from, ignoreCase = true)
        if (index < 0) break
        ranges += index until (index + query.length)
        from = index + query.length
    }
    return ranges
}

/**
 * 正文片段规整。rawText 带换行，而片段行是 maxLines = 1 —— 不折叠的话遇到 \n 会直接断行留下大片空白。
 */
internal fun normalizeSnippet(raw: String): String = raw.replace(WHITESPACE_RUN, " ").trim()

private val WHITESPACE_RUN = Regex("\\s+")

/** 命中处套主题色 + 加粗；无命中时返回纯文本，非搜索态因此与改动前完全一致。 */
@Composable
internal fun rememberHighlighted(text: String, query: String): AnnotatedString {
    val accent = MaterialTheme.colorScheme.primary
    return remember(text, query, accent) {
        val ranges = matchRanges(text, query)
        if (ranges.isEmpty()) {
            AnnotatedString(text)
        } else {
            buildAnnotatedString {
                append(text)
                val style = SpanStyle(color = accent, fontWeight = FontWeight.SemiBold)
                ranges.forEach { range -> addStyle(style, range.first, range.last + 1) }
            }
        }
    }
}
