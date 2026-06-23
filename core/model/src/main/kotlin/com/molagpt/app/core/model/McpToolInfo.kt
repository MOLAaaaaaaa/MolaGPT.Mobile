package com.molagpt.app.core.model

import kotlinx.serialization.json.JsonElement

/** MCP 服务器单个工具的结构化信息（来自 JSON-RPC tools/list）。供设置页展示 + per-tool 开关。 */
data class McpToolInfo(
    val name: String,
    val description: String?,
    /** 工具入参 schema（原始 JSON）；详情页可折叠展示。 */
    val inputSchema: JsonElement?,
)