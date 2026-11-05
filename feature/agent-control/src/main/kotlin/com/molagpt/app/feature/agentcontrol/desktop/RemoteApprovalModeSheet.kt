package com.molagpt.app.feature.agentcontrol.desktop

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.molagpt.app.core.model.RemoteApprovalMode
import com.molagpt.app.core.render.ToolChip
import com.molagpt.app.feature.agentcontrol.agentPalette

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RemoteApprovalModeControl(
    approval: RemoteApprovalMode,
    machineName: String,
    enabled: Boolean,
    changing: Boolean,
    onSelect: (String) -> Unit,
) {
    var showSheet by remember { mutableStateOf(false) }
    var confirmFull by remember { mutableStateOf(false) }
    val cs = MaterialTheme.colorScheme
    ToolChip(
        label = approval.label,
        checked = false,
        enabled = enabled && !changing,
        onClick = { showSheet = true },
        leadingIcon = Icons.Outlined.Shield,
        trailingIcon = Icons.Filled.ArrowDropDown,
        role = Role.Button,
        accentColor = if (approval.mode == "full") agentPalette().amber else null,
    )
    if (showSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            contentWindowInsets = { WindowInsets(0) },
        ) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp).padding(bottom = 20.dp).navigationBarsPadding(),
            ) {
                Text("审批模式", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    "应用于「$machineName」上的所有对话",
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp, bottom = 12.dp),
                )
                val choices = listOf(
                    Triple("approval", "按需审批", "需要授权的操作由你确认"),
                    Triple("auto", "自动审批", "由电脑上配置的审批模型判断"),
                    Triple("full", "完全访问", "工具操作无需逐次确认"),
                )
                choices.forEach { (mode, title, description) ->
                    val selectable = enabled && !changing && (mode != "auto" || approval.autoSelectable || approval.mode == "auto")
                    Row(
                        Modifier.fillMaxWidth().selectable(
                            selected = mode == approval.mode,
                            enabled = selectable,
                            role = Role.RadioButton,
                            onClick = {
                                showSheet = false
                                if (mode != approval.mode) {
                                    if (mode == "full") confirmFull = true else onSelect(mode)
                                }
                            },
                        ).padding(vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(title, style = MaterialTheme.typography.titleSmall, color = if (selectable) cs.onSurface else cs.onSurface.copy(alpha = 0.38f))
                            Text(
                                if (mode == "auto") approval.reviewMissing ?: description else description,
                                style = MaterialTheme.typography.bodySmall,
                                color = cs.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        RadioButton(selected = mode == approval.mode, onClick = null, enabled = selectable)
                    }
                }
                approval.reviewModel?.takeIf { it.isNotBlank() }?.let {
                    Text("审批模型 · $it", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }
    if (confirmFull) {
        AlertDialog(
            onDismissRequest = { confirmFull = false },
            icon = { Icon(Icons.Outlined.Shield, contentDescription = null) },
            title = { Text("启用完全访问？") },
            text = { Text("「$machineName」上的工具操作将无需逐次确认，包括写入文件和执行命令。此设置影响电脑上的所有对话。") },
            confirmButton = {
                TextButton(onClick = { confirmFull = false; onSelect("full") }, enabled = enabled && !changing) {
                    Text("启用", color = cs.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmFull = false }) { Text("取消") } },
        )
    }
}
