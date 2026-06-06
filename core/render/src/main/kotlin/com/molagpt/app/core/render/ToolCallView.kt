package com.molagpt.app.core.render

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.molagpt.app.core.model.ToolStatus

@Composable
fun ToolCallView(
    name: String,
    status: ToolStatus,
    label: String?,
    resultPreview: String?,
    argsJson: String? = null,
    provider: String? = null,
    modifier: Modifier = Modifier,
) {
    val title = label ?: readableToolName(name)
    val meta = provider?.takeIf { it.isNotBlank() } ?: readableToolName(name)
    val preview = resultPreview?.takeIf { it.isNotBlank() }
        ?: argsJson?.takeIf { it.isNotBlank() }?.let { "参数：\n```json\n$it\n```" }

    var collapsed by remember { mutableStateOf(status != ToolStatus.RUNNING) }
    LaunchedEffect(status) {
        collapsed = status != ToolStatus.RUNNING
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f))
            .padding(12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { collapsed = !collapsed },
        ) {
            ToolStatusIcon(status)
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 8.dp),
            )
            Text(
                text = statusText(status),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
            )
        }

        Text(
            text = meta,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
            modifier = Modifier.padding(top = 4.dp),
        )

        AnimatedVisibility(visible = !collapsed && preview != null) {
            StreamingMarkdownView(
                markdown = preview!!.trim(),
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun ToolStatusIcon(status: ToolStatus) {
    when (status) {
        ToolStatus.RUNNING -> CircularProgressIndicator(
            modifier = Modifier.size(16.dp),
            strokeWidth = 2.dp,
        )
        ToolStatus.SUCCESS -> PopIn {
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        ToolStatus.FAILED -> PopIn {
            Icon(
                Icons.Filled.Warning,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/** 完成/失败图标弹入（spring 回弹）。 */
@Composable
private fun PopIn(content: @Composable () -> Unit) {
    val scale = remember { Animatable(0f) }
    LaunchedEffect(Unit) { scale.animateTo(1f, MolaMotion.springy()) }
    Box(modifier = Modifier.graphicsLayer { scaleX = scale.value; scaleY = scale.value }) { content() }
}

private fun readableToolName(name: String): String = when (name) {
    "search_web", "web_search" -> "联网搜索"
    "steel_browser", "browser" -> "网页访问"
    "execute_python_code", "python" -> "Python 执行"
    "mcp" -> "连接器调用"
    "image-gen" -> "图片生成"
    "image-analyze" -> "图片分析"
    "image-action" -> "图片处理"
    else -> name.ifBlank { "工具调用" }
}

private fun statusText(status: ToolStatus): String = when (status) {
    ToolStatus.RUNNING -> "进行中"
    ToolStatus.SUCCESS -> "完成"
    ToolStatus.FAILED -> "失败"
}
