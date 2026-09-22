package com.molagpt.app.core.render

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.molagpt.app.core.model.FileInfo
import com.molagpt.app.core.model.SourceReference

/** 折叠胶囊上最多摞几个 favicon。 */
private const val SOURCE_STACK_MAX = 3

/**
 * 搜索来源片段：一枚折叠胶囊，点开是全部来源。
 *
 * 以前是「搜索词 + 编号条目列表」整段摊在消息末尾，一轮搜二十几条就比正文还长。
 * 编号也一并去掉：正文里的引用胶囊自己印着站点名，这份列表只是「这轮搜了什么」，
 * 跟正文引用不是一一对应的，编号只会让人以为对得上。
 */
@Composable
fun SearchResultView(query: String, refs: List<SourceReference>, modifier: Modifier = Modifier) {
    if (refs.isEmpty()) {
        // 搜了但没结果：留一行交代，否则这一轮的动作凭空消失。
        if (query.isNotBlank()) {
            Text(
                text = "联网搜索：$query",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = modifier.fillMaxWidth(),
            )
        }
        return
    }

    var open by remember(refs) { mutableStateOf(false) }
    val cs = MaterialTheme.colorScheme
    val favicon = LocalFaviconRenderer.current

    // 外面那层 Box 是为了不被调用方的 fillMaxWidth 撑开：胶囊要贴左边，宽度只够装内容。
    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(percent = 50))
                .background(cs.surfaceVariant.copy(alpha = 0.5f))
                .clickable { open = true }
                .padding(start = 10.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val stack = refs.take(SOURCE_STACK_MAX).mapNotNull { it.faviconEndpoint }
            if (stack.isNotEmpty()) {
                // 叠在一起而不是排开：胶囊要短，又想让人一眼看出是网页来源。
                Box(modifier = Modifier.height(16.dp).width((16 + (stack.size - 1) * 11).dp)) {
                    stack.forEachIndexed { i, endpoint ->
                        favicon(
                            endpoint,
                            Modifier
                                .offset(x = (i * 11).dp)
                                .size(16.dp)
                                .clip(CircleShape)
                                .background(cs.surface)
                                .padding(1.dp),
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text = "${refs.size} 个来源",
                style = MaterialTheme.typography.labelLarge,
                color = cs.onSurfaceVariant,
            )
            Spacer(Modifier.width(2.dp))
            Icon(
                imageVector = Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                tint = cs.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }
    }

    if (open) {
        SourceListSheet(query, refs) { open = false }
    }
}

/**
 * 全部来源：贴底 sheet，一条一行，无编号。
 *
 * 跟正文胶囊点开的来源卡用同一种容器（[ModalBottomSheet]）——那张卡一次只看一条所以翻页，
 * 这里是几十条，只能是可滚的列表。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SourceListSheet(query: String, refs: List<SourceReference>, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val uriHandler = LocalUriHandler.current
    val favicon = LocalFaviconRenderer.current

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = cs.surface) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp)) {
            Text(
                text = "${refs.size} 个来源",
                style = MaterialTheme.typography.titleMedium,
                color = cs.onSurface,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp),
            )
            if (query.isNotBlank()) {
                // 搜索词从正文挪进来：它是这轮搜索的记录，值得留着，但不值得占着正文。
                Text(
                    text = query,
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            LazyColumn(
                // sheet 里的列表必须自己有界，否则测量不出高度。
                modifier = Modifier.fillMaxWidth().heightIn(max = 460.dp),
            ) {
                // 不给 key：两轮搜索撞上同一个页面时 url+title 会重复，LazyColumn 见到
                // 重复 key 直接抛。这份列表打开后不会变，按下标定位就够。
                items(refs) { ref ->
                    SourceRow(ref, favicon) {
                        runCatching { uriHandler.openUri(ref.url) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceRow(
    ref: SourceReference,
    favicon: @Composable (String, Modifier) -> Unit,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = ref.url.isNotBlank(), onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
    ) {
        ref.faviconEndpoint?.let { endpoint ->
            favicon(endpoint, Modifier.size(16.dp).clip(RoundedCornerShape(3.dp)))
            Spacer(Modifier.width(10.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = ref.title.ifBlank { ref.url },
                style = MaterialTheme.typography.bodyMedium,
                color = cs.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = ref.site,
                style = MaterialTheme.typography.labelSmall,
                color = cs.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 文件卡片片段。 */
@Composable
fun FileCardView(file: FileInfo, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(12.dp),
    ) {
        Column {
            Text(file.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val sub = buildString {
                file.mimeType?.let { append(it) }
                file.sizeBytes?.let { if (isNotEmpty()) append(" · "); append(formatSize(it)) }
            }
            if (sub.isNotBlank()) {
                Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1 shl 20 -> "%.1f MB".format(bytes / 1048576.0)
    bytes >= 1 shl 10 -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
