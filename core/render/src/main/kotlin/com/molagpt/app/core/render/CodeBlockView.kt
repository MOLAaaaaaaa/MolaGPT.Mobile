package com.molagpt.app.core.render

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * 代码块：等宽字体 + 词法高亮 + 语言标签 + 复制按钮，横向可滚。
 * 整体作为独立可重组单元，长代码不阻塞其它 fragment；高亮按内容 LRU 缓存，超大块后台着色。
 *
 * [headerStart] 为空时左侧显示语言名；传入自定义内容（如 Mermaid 的「图表 / 代码」切换）时替换左侧。
 * [actions] 放在复制按钮前面，给网页代码块的「运行」「收起为卡片」等操作用。
 */
@Composable
fun CodeBlockView(
    language: String?,
    code: String,
    modifier: Modifier = Modifier,
    headerStart: (@Composable RowScope.() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val clipboard = LocalClipboardManager.current
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(10.dp)
    val highlighted = rememberHighlightedCode(code = code, language = language)
    var copied by remember(code) { mutableStateOf(false) }

    LaunchedEffect(copied) {
        if (copied) {
            delay(1500)
            copied = false
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(cs.surfaceVariant.copy(alpha = 0.5f)),
    ) {
        // 顶栏按钮不进入外层 SelectionContainer 的文本选区。
        DisableSelection {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (headerStart != null) {
                    headerStart()
                    Spacer(Modifier.weight(1f))
                } else {
                    Text(
                        text = language?.takeIf { it.isNotBlank() } ?: "code",
                        style = MaterialTheme.typography.labelSmall,
                        color = cs.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
                actions()
                CopyCodeButton(
                    copied = copied,
                    onClick = {
                        clipboard.setText(AnnotatedString(code))
                        copied = true
                    },
                )
            }
        }
        HorizontalDivider(color = cs.outlineVariant.copy(alpha = 0.45f))
        Text(
            text = highlighted,
            color = cs.onSurface,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                lineHeight = 19.sp,
                fontFeatureSettings = CODE_FONT_FEATURES,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 10.dp),
        )
    }
}

@Composable
internal fun CopyCodeButton(
    copied: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val tint = if (copied) cs.primary else cs.onSurfaceVariant
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (copied) Icons.Filled.Check else Icons.Filled.ContentCopy,
            contentDescription = if (copied) "已复制" else "复制代码",
            tint = tint,
            modifier = Modifier.size(15.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = if (copied) "已复制" else "复制",
            style = MaterialTheme.typography.labelSmall,
            color = tint,
        )
    }
}

private const val CODE_FONT_FEATURES = "'calt' 0, 'liga' 0, 'clig' 0"
