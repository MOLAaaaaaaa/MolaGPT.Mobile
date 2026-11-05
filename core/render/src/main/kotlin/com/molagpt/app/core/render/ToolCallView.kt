package com.molagpt.app.core.render

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.molagpt.app.core.model.ToolStatus
import com.molagpt.app.core.model.ServerToolInfo

@Composable
fun ToolCallView(
    name: String,
    status: ToolStatus,
    label: String?,
    resultPreview: String?,
    argsJson: String? = null,
    provider: String? = null,
    expandWhileRunning: Boolean = true,
    statusLabel: String? = null,
    serverTool: ServerToolInfo? = null,
    onRetry: (() -> Unit)? = null,
    onDownload: (() -> Unit)? = null,
    actionBusy: Boolean = false,
    actionError: String? = null,
    modifier: Modifier = Modifier,
) {
    val title = label ?: readableToolName(name)
    val meta = provider?.takeIf { it.isNotBlank() } ?: readableToolName(name)
    val preview = resultPreview?.takeIf { it.isNotBlank() }
        ?: argsJson?.takeIf { it.isNotBlank() }?.let { "参数：\n```json\n$it\n```" }

    var collapsed by remember { mutableStateOf(!expandWhileRunning || status != ToolStatus.RUNNING) }
    LaunchedEffect(status, expandWhileRunning) {
        if (expandWhileRunning) collapsed = status != ToolStatus.RUNNING
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
            if (status == ToolStatus.RUNNING) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                ToolIcon(name, serverTool?.icon, status)
            }
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
                text = statusLabel ?: statusText(status),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
            )
        }

        Text(
            text = serverTool?.metadata ?: meta,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
            modifier = Modifier.padding(top = 4.dp),
        )
        (actionError ?: serverTool?.error)?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 6.dp))
        }
        if (onRetry != null) androidx.compose.material3.TextButton(onClick = onRetry, enabled = !actionBusy) {
            Text(if (actionBusy) "正在重试" else "重试")
        }
        if (onDownload != null) androidx.compose.material3.TextButton(onClick = onDownload, enabled = !actionBusy) { Text("下载文件") }

        AnimatedVisibility(visible = !collapsed && preview != null) {
            StreamingMarkdownView(
                markdown = preview!!.trim(),
                // 比思考块（0.78）还小一档：工具结果是给模型读的原始产物，人只需要扫一眼确认拿到了东西。
                // 字号压下来同时也压住了 mcp_call / execute_python_code 这类没有专门裁剪的长输出的高度。
                textScale = 0.72f,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun ToolIcon(name: String, icon: String?, status: ToolStatus) {
    val glyph = when (icon ?: name) {
        "book", "read_document" -> Icons.AutoMirrored.Filled.MenuBook
        "search", "code-search", "search_document", "search_web", "web_search", "visual_search", "molagpt_fast_codebase_search" -> Icons.Filled.Search
        "code", "python", "execute_python_code" -> Icons.Filled.Code
        "eye", "analyze_image", "view_image", "vision_proxy", "image-analyze" -> Icons.Filled.Visibility
        "crop", "image_file_process", "image-action" -> Icons.Filled.Crop
        "image", "image-gen", "generate_image", "image_generation_and_editing" -> Icons.Filled.Image
        "skill", "activate_skill" -> Icons.Filled.AutoAwesome
        "memory", "recall_memory" -> Icons.Filled.Psychology
        "erase", "forget_memories_by_description" -> Icons.Filled.DeleteOutline
        "import", "molagpt_import_sandbox_to_project" -> Icons.AutoMirrored.Filled.DriveFileMove
        "camera" -> Icons.Filled.PhotoCamera
        "download" -> Icons.Filled.FileDownload
        "click" -> Icons.Filled.TouchApp
        "list" -> Icons.AutoMirrored.Filled.FormatListBulleted
        "pdf" -> Icons.Filled.PictureAsPdf
        "web", "steel_browser", "browser", "fetch_url", "web_fetch" -> Icons.Filled.Language
        "mcp", "mcp_call", "mcp_list_tools" -> Icons.Filled.Extension
        "spawn_agent", "wait_agents", "server_subagents" -> Icons.Filled.Groups
        else -> Icons.Filled.Build
    }
    Icon(glyph, contentDescription = null, modifier = Modifier.size(18.dp),
        tint = if (status == ToolStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun readableToolName(name: String): String = when (name) {
    "spawn_agent" -> "派发子 Agent"
    "followup_agent" -> "继续派活"
    "send_agent_message" -> "发送消息"
    "wait_agents" -> "等待子 Agent"
    "task_status" -> "查看任务"
    "stop_task" -> "停止任务"
    "search_web", "web_search" -> "联网搜索"
    "fetch_url" -> "阅读网页"
    "view_image", "vision_proxy" -> "视觉理解"
    "generate_image" -> "图像生成"
    "mcp_list_tools" -> "MCP 工具列表"
    "mcp_call" -> "MCP 服务器"
    "steel_browser", "browser" -> "网页访问"
    "execute_python_code", "python" -> "Python 执行"
    "mcp" -> "连接器调用"
    "image-gen" -> "图片生成"
    "image-analyze" -> "图片分析"
    "image-action" -> "图片处理"
    "read_document" -> "读取文档"
    "search_document" -> "检索文档"
    "molagpt_fast_codebase_search", "code-search" -> "项目代码检索"
    "activate_skill" -> "加载技能"
    "recall_memory" -> "查找记忆"
    "forget_memories_by_description" -> "删除记忆"
    "molagpt_import_sandbox_to_project" -> "导入项目文件"
    else -> name.ifBlank { "工具调用" }
}

private fun statusText(status: ToolStatus): String = when (status) {
    ToolStatus.RUNNING -> "进行中"
    ToolStatus.SUCCESS -> "完成"
    ToolStatus.FAILED -> "失败"
    ToolStatus.CANCELLED -> "已停止"
}
