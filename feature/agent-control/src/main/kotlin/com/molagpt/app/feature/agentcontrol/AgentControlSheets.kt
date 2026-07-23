package com.molagpt.app.feature.agentcontrol

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.molagpt.app.core.model.AgentBackendIds
import com.molagpt.app.core.model.AgentPermissionMode
import com.molagpt.app.core.model.CodexApprovalPolicy
import com.molagpt.app.core.model.RelayMachine
import com.molagpt.app.core.model.RelaySessionMeta
import com.molagpt.app.core.model.approvalPolicyEnum
import com.molagpt.app.core.model.displayName
import com.molagpt.app.core.model.displayWorkspace
import com.molagpt.app.core.model.isOnline
import com.molagpt.app.core.model.isQuickChat
import com.molagpt.app.core.model.permissionModeEnum
import com.molagpt.app.core.model.sortAtMs
import com.molagpt.app.core.render.SegmentedControl

internal fun effortLabel(value: String?): String = when (value?.lowercase()) {
    "low" -> "低"
    "high" -> "高"
    "max" -> "满"
    else -> "中"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NewSessionSheet(
    sessions: List<RelaySessionMeta>,
    machines: List<RelayMachine>,
    onDismiss: () -> Unit,
    onCreate: (backendId: String, cwd: String?, title: String?, machineId: String?) -> Unit,
) {
    val now = System.currentTimeMillis()
    val selectableMachines = remember(machines, sessions) {
        val fromRegistry = machines.sortedByDescending { it.lastSeenAtMs }
        if (fromRegistry.isNotEmpty()) fromRegistry
        else sessions.mapNotNull { meta ->
            val id = meta.machineId?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            RelayMachine(id = id, name = meta.machineName, lastSeenAtMs = meta.updatedAtMs)
        }.distinctBy { it.id }
    }
    val defaultMachineId = remember(selectableMachines) {
        val online = selectableMachines.filter { it.isOnline(now) }
        when {
            online.size == 1 -> online.first().id
            selectableMachines.size == 1 -> selectableMachines.first().id
            online.isNotEmpty() -> online.first().id
            else -> selectableMachines.firstOrNull()?.id
        }
    }
    var machineId by remember(defaultMachineId) { mutableStateOf(defaultMachineId) }
    val hasTargetMachine = !machineId.isNullOrBlank()
    var backendId by remember { mutableStateOf(AgentBackendIds.ClaudeCode) }
    var cwd by remember { mutableStateOf<String?>(null) }
    var title by remember { mutableStateOf("") }

    // 工作区按「目标机器 + 后端」区分：路径在不同电脑上不可互通。
    val workspaces = remember(sessions, backendId, machineId) {
        sessions.filter {
            !it.isQuickChat &&
                it.backendId == backendId &&
                (machineId.isNullOrBlank() || it.machineId == machineId)
        }
            .sortedByDescending { it.sortAtMs }
            .distinctBy { it.workspaceKey ?: it.workingDirectory }
            .map { it.displayWorkspace to it.workingDirectory }
    }
    // 切换后端/机器后，若已选工作区不属于新范围则回退到 Quick Chat。
    LaunchedEffect(backendId, machineId) {
        if (cwd != null && workspaces.none { it.second == cwd }) cwd = null
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        // 自带 inset 置 0，底部留白只由内容的 navigationBarsPadding 处理一次（仿 BYOK 添加服务抽屉，避免内容被顶出/遮挡）。
        contentWindowInsets = { WindowInsets(0) },
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp)
                .padding(bottom = 20.dp)
                .imePadding()
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SheetTitle("新建远程会话")
            if (selectableMachines.isNotEmpty()) {
                SheetLabel("电脑")
                selectableMachines.forEach { machine ->
                    val online = machine.isOnline(now)
                    SheetOption(
                        title = machine.displayName,
                        sub = if (online) "在线" else "离线（命令可能无法送达）",
                        selected = machineId == machine.id,
                    ) { machineId = machine.id }
                }
            } else {
                Text(
                    "尚未检测到桌面桥接。请先在电脑上登录并启用 Agent 桥接。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            SheetLabel("后端")
            SegmentedControl(
                options = listOf(AgentBackendIds.ClaudeCode to "Claude Code", AgentBackendIds.Codex to "Codex"),
                selected = backendId,
                onSelect = { backendId = it },
                modifier = Modifier.fillMaxWidth(),
            )
            SheetLabel("工作区")
            SheetOption("Quick Chat（无目录）", "不绑定项目，临时对话", cwd == null) { cwd = null }
            workspaces.forEach { (name, dir) ->
                SheetOption(name, dir, cwd == dir) { cwd = dir }
            }
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("标题（可选）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { onCreate(backendId, cwd, title.ifBlank { null }, machineId) },
                enabled = hasTargetMachine,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("在桌面端创建")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ModelSheet(
    meta: RelaySessionMeta,
    options: List<AgentModelOption>,
    onDismiss: () -> Unit,
    onPick: (String?) -> Unit,
) {
    val current = meta.model
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        contentWindowInsets = { WindowInsets(0) },
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp)
                .padding(bottom = 20.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SheetTitle("模型")
            Text("所有可用模型", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            options.forEach { opt ->
                val selected = (opt.id == null && (current.isNullOrBlank() || current == AgentDefaultModelLabel)) ||
                    (opt.id != null && opt.id == current)
                SheetOption(opt.label, opt.description, selected) { onPick(opt.id) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ModeSheet(
    meta: RelaySessionMeta,
    onDismiss: () -> Unit,
    onSetPermissionMode: (Int) -> Unit,
    onSetApprovalPolicy: (Int) -> Unit,
) {
    val isCodex = meta.backendId == AgentBackendIds.Codex
    val current = meta.permissionModeEnum
    val modes = if (isCodex) listOf(
        AgentPermissionMode.Plan to ("只读" to "只读模式"),
        AgentPermissionMode.AcceptEdits to ("可写" to "可改文件/跑命令"),
        AgentPermissionMode.BypassPermissions to ("全权限" to "无任何限制（高风险）"),
    ) else listOf(
        AgentPermissionMode.Plan to ("计划（只读）" to "只读模式"),
        AgentPermissionMode.Default to ("逐项询问" to "审批每个操作"),
        AgentPermissionMode.AcceptEdits to ("自动编辑" to "执行命令时仍需审批"),
        AgentPermissionMode.BypassPermissions to ("完全放行" to "不再询问（仅可信项目，高风险）"),
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        contentWindowInsets = { WindowInsets(0) },
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp)
                .padding(bottom = 20.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SheetTitle("权限模式")
            Text(
                if (isCodex) "Codex 沙箱姿态" else "Claude Code · 决定哪些操作需要你在手机上批准",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            modes.forEach { (mode, label) ->
                SheetOption(label.first, label.second, mode == current) { onSetPermissionMode(mode.ordinal) }
            }
            if (isCodex) {
                Spacer(Modifier.width(2.dp))
                SheetLabel("审批")
                SegmentedControl(
                    options = listOf(
                        CodexApprovalPolicy.Untrusted.ordinal.toString() to "请求批准",
                        CodexApprovalPolicy.OnRequest.ordinal.toString() to "替我审批",
                        CodexApprovalPolicy.Never.ordinal.toString() to "不询问",
                    ),
                    selected = (meta.approvalPolicyEnum ?: CodexApprovalPolicy.OnRequest).ordinal.toString(),
                    onSelect = { onSetApprovalPolicy(it.toInt()) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

// ---- shared sheet bits ------------------------------------------------------

@Composable
private fun SheetTitle(text: String) =
    Text(text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)

@Composable
private fun SheetLabel(text: String) =
    Text(text, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun SheetOption(title: String, sub: String?, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                )
                sub?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
            if (selected) Text("✓", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}
