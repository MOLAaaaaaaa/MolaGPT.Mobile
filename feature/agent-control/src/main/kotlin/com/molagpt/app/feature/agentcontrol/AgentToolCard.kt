package com.molagpt.app.feature.agentcontrol

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Claude Code（及部分 Codex）工具调用的友好渲染：把 `argumentsJson` 解析成"读取/写入/编辑
 * 了哪个文件、跑了什么命令"，并把 `TodoWrite` 渲染成真正的待办清单。数据本就随 toolProgress
 * 的 name + argumentsJson 同步过来，这里只负责展示。
 */
private val ToolJson = Json { ignoreUnknownKeys = true; isLenient = true }

private fun argObj(argsJson: String?): JsonObject? =
    argsJson?.takeIf { it.isNotBlank() }?.let {
        runCatching { ToolJson.parseToJsonElement(it).jsonObject }.getOrNull()
    }

internal fun isAgentTodoTool(name: String): Boolean = name == "TodoWrite"

/** 友好标题（含关键参数）。返回 null 表示用默认渲染（原始工具名）。 */
internal fun agentToolLabel(name: String, argsJson: String?): String? {
    val o = argObj(argsJson)
    fun str(vararg keys: String): String? =
        keys.firstNotNullOfOrNull { o?.get(it)?.jsonPrimitive?.contentOrNull?.takeIf { s -> s.isNotBlank() } }
    fun base(p: String?): String? =
        p?.trim()?.trimEnd('/', '\\')?.substringAfterLast('/')?.substringAfterLast('\\')?.takeIf { it.isNotBlank() }

    val path = base(str("file_path", "notebook_path", "path"))
    return when (name) {
        "Read" -> "读取 " + (path ?: "文件")
        "Write" -> "写入 " + (path ?: "文件")
        "Edit", "MultiEdit" -> "编辑 " + (path ?: "文件")
        "NotebookEdit" -> "编辑 " + (path ?: "Notebook")
        "Bash", "BashOutput" -> str("command")?.let { "终端 · " + it.trim().lineSequence().firstOrNull().orEmpty().take(50) } ?: "终端"
        "Glob" -> "查找 " + (str("pattern") ?: "文件")
        "Grep" -> "搜索 " + (str("pattern") ?: "")
        "LS" -> ("列目录 " + (path ?: "")).trim()
        "Task" -> "子代理 · " + (str("description", "subagent_type") ?: "")
        "WebFetch" -> "阅读网页"
        "WebSearch" -> "联网搜索 " + (str("query") ?: "")
        "TodoWrite" -> "更新待办清单"
        // 部分 Codex 工具
        "commandExecution", "command" -> "终端"
        "fileChange", "patchApply" -> "编辑文件"
        else -> null
    }
}

private data class TodoItem(val text: String, val status: String)

private fun parseTodos(argsJson: String?): List<TodoItem> {
    val arr = argObj(argsJson)?.get("todos") as? JsonArray ?: return emptyList()
    return arr.mapNotNull { el ->
        val obj = el as? JsonObject ?: return@mapNotNull null
        val status = obj["status"]?.jsonPrimitive?.contentOrNull ?: "pending"
        val text = (if (status == "in_progress") obj["activeForm"]?.jsonPrimitive?.contentOrNull else null)
            ?: obj["content"]?.jsonPrimitive?.contentOrNull
            ?: return@mapNotNull null
        TodoItem(text, status)
    }
}

/** 待办清单卡片：复选符号 + 进行中高亮 + 完成项删除线。 */
@Composable
internal fun AgentTodoCard(argsJson: String?, modifier: Modifier = Modifier) {
    val todos = remember(argsJson) { parseTodos(argsJson) }
    val cs = MaterialTheme.colorScheme
    val done = todos.count { it.status == "completed" }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(cs.surfaceVariant.copy(alpha = 0.42f))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("待办清单", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = cs.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            if (todos.isNotEmpty()) {
                Text("$done/${todos.size}", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant.copy(alpha = 0.72f))
            }
        }
        if (todos.isEmpty()) {
            Text("（空）", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant.copy(alpha = 0.6f))
        }
        todos.forEach { item ->
            val glyph = when (item.status) {
                "completed" -> "✓"
                "in_progress" -> "▸"
                else -> "○"
            }
            val glyphColor = when (item.status) {
                "completed" -> cs.primary
                "in_progress" -> cs.primary
                else -> cs.onSurfaceVariant.copy(alpha = 0.6f)
            }
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Text(glyph, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = glyphColor, modifier = Modifier.width(20.dp))
                Text(
                    item.text,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (item.status == "in_progress") FontWeight.SemiBold else FontWeight.Normal,
                    color = if (item.status == "completed") cs.onSurfaceVariant.copy(alpha = 0.55f) else cs.onSurface,
                    textDecoration = if (item.status == "completed") TextDecoration.LineThrough else null,
                )
            }
        }
    }
}
