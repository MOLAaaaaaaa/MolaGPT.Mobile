package com.molagpt.app.feature.agentcontrol

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.molagpt.app.core.model.AgentBackendIds
import com.molagpt.app.core.model.AgentPermissionMode
import com.molagpt.app.core.model.AgentPhase
import com.molagpt.app.core.model.CodexApprovalPolicy
import com.molagpt.app.core.model.RelayConnectionState
import com.molagpt.app.core.model.RelaySessionMeta
import com.molagpt.app.core.model.approvalPolicyEnum
import com.molagpt.app.core.model.displayWorkspace
import com.molagpt.app.core.model.isBusy
import com.molagpt.app.core.model.isQuickChat
import com.molagpt.app.core.model.permissionModeEnum
import com.molagpt.app.core.model.phaseEnum
import com.molagpt.app.core.model.sortAtMs

/**
 * Agent Hub —— 一级页面的主体。桌面端状态 hero + 全部/待处理分页 + 项目/快聊分组的富会话卡。
 * 复用 relay 元信息（backend 徽标、phase 徽标、模型·模式标签、attention 点）。
 */
@Composable
fun AgentHub(
    sessions: List<RelaySessionMeta>,
    connectionState: RelayConnectionState,
    onSelect: (RelaySessionMeta) -> Unit,
    onReconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val projects = sessions.filter { !it.isQuickChat }
        .groupBy { it.workspaceKey ?: it.workingDirectory }
        .toList()
        .sortedByDescending { (_, list) -> list.maxOf { it.sortAtMs } }
    val chats = sessions.filter { it.isQuickChat }.sortedByDescending { it.sortAtMs }

    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "hero") {
            DesktopStatusHero(connectionState, sessions.size, projects.size, onReconnect)
        }

        if (sessions.isEmpty()) {
            item(key = "empty") {
                Box(Modifier.fillMaxWidth().padding(top = 60.dp), contentAlignment = Alignment.Center) {
                    Text(
                        "暂无 Agent 会话\n在 PC 上安装 MolaGPT，或点右下按钮来新建一个会话",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        projects.forEach { (key, list) ->
            item(key = "ph-$key") { GroupHeader("项目", list.first().displayWorkspace) }
            items(list.sortedByDescending { it.sortAtMs }, key = { it.conversationId }) { meta ->
                SessionCard(meta, onSelect)
            }
        }
        if (chats.isNotEmpty()) {
            item(key = "ch-head") { GroupHeader("快聊", "Quick Chat") }
            items(chats, key = { it.conversationId }) { meta -> SessionCard(meta, onSelect) }
        }
    }
}

@Composable
private fun DesktopStatusHero(
    state: RelayConnectionState,
    sessionCount: Int,
    projectCount: Int,
    onReconnect: () -> Unit,
) {
    val p = agentPalette()
    val cs = MaterialTheme.colorScheme
    val online = state == RelayConnectionState.Connected
    val connecting = state == RelayConnectionState.Connecting || state == RelayConnectionState.Reconnecting
    val bg = when { online -> p.greenSoft; connecting -> cs.surfaceVariant; else -> p.redSoft }
    val dotColor = when { online -> p.green; connecting -> p.amber; else -> p.red }
    val statusText = when { online -> "已连接"; connecting -> "连接中…"; else -> "未连接" }
    val sub = when {
        online -> "$sessionCount 个会话 · $projectCount 个项目"
        connecting -> "正在建立连接"
        else -> "远程控制不可用"
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(bg).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(42.dp).clip(RoundedCornerShape(13.dp)).background(cs.surface),
            contentAlignment = Alignment.Center,
        ) { MonitorIcon(cs.onSurface) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(statusText, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
                Dot(dotColor, 9.dp)
            }
            Text(sub, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
        if (online) {
            Pill("在线", p.green, p.greenSoft)
        } else if (!connecting) {
            TextButton(onClick = onReconnect) { Text("重连") }
        }
    }
}

/** 显示器图标（Canvas 绘制，替代 emoji）。 */
@Composable
internal fun MonitorIcon(tint: Color, dimen: androidx.compose.ui.unit.Dp = 22.dp) {
    androidx.compose.foundation.Canvas(Modifier.size(dimen)) {
        val w = size.width
        val h = size.height
        val sw = 2.dp.toPx()
        val cap = androidx.compose.ui.graphics.StrokeCap.Round
        val st = androidx.compose.ui.graphics.drawscope.Stroke(width = sw, cap = cap, join = androidx.compose.ui.graphics.StrokeJoin.Round)
        drawRoundRect(
            color = tint,
            topLeft = androidx.compose.ui.geometry.Offset(w * 0.12f, h * 0.20f),
            size = androidx.compose.ui.geometry.Size(w * 0.76f, h * 0.46f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()),
            style = st,
        )
        drawLine(tint, androidx.compose.ui.geometry.Offset(w * 0.5f, h * 0.66f), androidx.compose.ui.geometry.Offset(w * 0.5f, h * 0.80f), strokeWidth = sw, cap = cap)
        drawLine(tint, androidx.compose.ui.geometry.Offset(w * 0.34f, h * 0.84f), androidx.compose.ui.geometry.Offset(w * 0.66f, h * 0.84f), strokeWidth = sw, cap = cap)
    }
}

@Composable
private fun GroupHeader(kind: String, name: String) {
    Row(
        Modifier.padding(start = 4.dp, top = 8.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(kind, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(7.dp))
        Text("· $name", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SessionCard(meta: RelaySessionMeta, onSelect: (RelaySessionMeta) -> Unit) {
    Surface(
        onClick = { onSelect(meta) },
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(13.dp), verticalAlignment = Alignment.Top) {
            BackendBadge(meta.backendId)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    meta.title.ifBlank { "未命名会话" },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
                Text(
                    meta.displayWorkspace,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                Spacer(Modifier.size(7.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    PhasePill(meta.phaseEnum)
                    Text(
                        modeLabel(meta),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
            if (meta.needsAttention) {
                Spacer(Modifier.width(8.dp))
                Dot(MaterialTheme.colorScheme.error, 9.dp)
            }
        }
    }
}

// ---- shared small components ------------------------------------------------

@Composable
internal fun BackendBadge(backendId: String) {
    val (label, color) = when (backendId) {
        AgentBackendIds.ClaudeCode -> "CC" to Color(0xFFD97757)
        AgentBackendIds.Codex -> "CX" to Color(0xFF10A37F)
        else -> "AI" to MaterialTheme.colorScheme.secondary
    }
    Box(
        Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(color.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
    ) { Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = color) }
}

@Composable
internal fun PhasePill(phase: AgentPhase) {
    val p = agentPalette()
    val (text, fg, bg) = when (phase) {
        AgentPhase.Running, AgentPhase.Spawning -> Triple("● 运行中", p.blue, p.blueSoft)
        AgentPhase.Waiting -> Triple("⏸ 等待审批", p.amber, p.amberSoft)
        AgentPhase.Completed -> Triple("✓ 已完成", p.green, p.greenSoft)
        AgentPhase.Failed -> Triple("✕ 失败", p.red, p.redSoft)
        AgentPhase.Idle -> Triple("○ 空闲", MaterialTheme.colorScheme.onSurfaceVariant, MaterialTheme.colorScheme.surfaceVariant)
    }
    Pill(text, fg, bg)
}

@Composable
internal fun Pill(text: String, fg: Color, bg: Color) {
    Box(
        Modifier.clip(RoundedCornerShape(999.dp)).background(bg).padding(horizontal = 9.dp, vertical = 3.dp),
    ) { Text(text, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = fg) }
}

@Composable
private fun Dot(color: Color, size: androidx.compose.ui.unit.Dp) {
    Box(Modifier.size(size).clip(CircleShape).background(color))
}

/** 短模式标签，仿桌面 ModeLabel：模型 · 姿态(· 审批)。 */
internal fun modeLabel(meta: RelaySessionMeta): String {
    val isCodex = meta.backendId == AgentBackendIds.Codex
    val model = meta.model?.takeIf { it.isNotBlank() && it != AgentDefaultModelLabel } ?: "默认"
    val posture = when (meta.permissionModeEnum) {
        AgentPermissionMode.Plan -> if (isCodex) "只读" else "计划"
        AgentPermissionMode.AcceptEdits -> if (isCodex) "可写" else "自动编辑"
        AgentPermissionMode.BypassPermissions -> "完全放行"
        AgentPermissionMode.Default -> if (isCodex) "可写" else "逐项询问"
    }
    if (!isCodex) return "$model · $posture"
    val approval = when (meta.approvalPolicyEnum ?: CodexApprovalPolicy.OnRequest) {
        CodexApprovalPolicy.Untrusted -> "严格审批"
        CodexApprovalPolicy.Never -> "免审批"
        CodexApprovalPolicy.OnRequest -> "按需审批"
    }
    return "$model · $posture · $approval"
}

// ---- semantic palette (demo tokens, light/dark) -----------------------------

internal data class AgentPalette(
    val green: Color, val greenSoft: Color,
    val blue: Color, val blueSoft: Color,
    val amber: Color, val amberSoft: Color,
    val red: Color, val redSoft: Color,
)

@Composable
internal fun agentPalette(): AgentPalette = if (isSystemInDarkTheme()) {
    AgentPalette(
        green = Color(0xFF68C598), greenSoft = Color(0xFF18352A),
        blue = Color(0xFF8FB5FF), blueSoft = Color(0xFF1D2B44),
        amber = Color(0xFFE6B45F), amberSoft = Color(0xFF3B2C16),
        red = Color(0xFFFF8D89), redSoft = Color(0xFF3A1918),
    )
} else {
    AgentPalette(
        green = Color(0xFF23865F), greenSoft = Color(0xFFE6F5EF),
        blue = Color(0xFF3F74C8), blueSoft = Color(0xFFE9F0FB),
        amber = Color(0xFFA56A1B), amberSoft = Color(0xFFFFF4DF),
        red = Color(0xFFC2413D), redSoft = Color(0xFFFDEBEA),
    )
}
