package com.molagpt.app.core.render.visual

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.molagpt.app.core.markdown.visual.CardGridSpec
import com.molagpt.app.core.markdown.visual.CardItem

/**
 * 条目卡片：竖屏一列，够宽时两列；两种以上 tag 时可按 tag 筛选。
 *
 * 带链接的卡片整张可点，并在页脚写明要去哪个网站。只有 http(s) 链接会让卡片可点——
 * 模型写的 `javascript:` 之类一律当作没有链接。
 */
@Composable
internal fun CardGridView(spec: CardGridSpec, modifier: Modifier = Modifier) {
    val palette = rememberVisualPalette()
    val filtered = spec.tags.size > 1
    var tag by rememberSaveable { mutableStateOf<String?>(null) }
    val shown = spec.items.filter { tag == null || it.tag == tag }
    VisualFrame(
        title = spec.title,
        meta = if (filtered) "${shown.size} 项" else null,
        modifier = modifier,
        imageName = "条目",
    ) {
        if (filtered) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp)
                    .padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                FilterChip(selected = tag == null, onClick = { tag = null }, label = { Text("全部") })
                spec.tags.forEach { t ->
                    FilterChip(selected = tag == t, onClick = { tag = if (tag == t) null else t }, label = { Text(t) })
                }
            }
        }
        BoxWithConstraints(modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 12.dp)) {
            val columns = if (maxWidth >= 520.dp) 2 else 1
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                shown.chunked(columns).forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        row.forEach { item -> Card(item, palette, Modifier.weight(1f).fillMaxHeight()) }
                        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Card(item: CardItem, palette: VisualPalette, modifier: Modifier) {
    val uriHandler = LocalUriHandler.current
    val host = item.url?.takeIf(::isWebLink)
        ?.let { runCatching { Uri.parse(it.trim()).host }.getOrNull() }
        ?.takeIf { it.isNotBlank() }
        ?.removePrefix("www.")
    val url = item.url?.trim()?.takeIf { host != null }
    // 来源正好就是链接的域名时不重复写。
    val source = item.source?.takeIf { !it.trim().trimEnd('/').equals(host, ignoreCase = true) }
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier = modifier
            .clip(shape)
            .border(1.dp, palette.border, shape)
            .then(if (url != null) Modifier.clickable { runCatching { uriHandler.openUri(url) } } else Modifier)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        if (item.tag != null || source != null) {
            Row(modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                item.tag?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.accent,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(palette.accent.copy(alpha = 0.12f))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
                source?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(start = 8.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.End,
                    )
                }
            }
        }
        Text(
            text = item.title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = palette.text,
        )
        item.summary?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = palette.muted,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        if (host != null) {
            Spacer(Modifier.weight(1f))
            Row(modifier = Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Link, contentDescription = null, tint = palette.muted, modifier = Modifier.size(13.dp))
                Text(
                    text = host,
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        }
    }
}

private fun isWebLink(url: String): Boolean {
    val scheme = runCatching { Uri.parse(url.trim()).scheme }.getOrNull()?.lowercase()
    return scheme == "http" || scheme == "https"
}
