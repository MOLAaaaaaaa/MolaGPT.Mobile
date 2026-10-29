package com.molagpt.app.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.molagpt.app.core.model.RemoteModelChoice
import com.molagpt.app.core.model.RemoteModelGroup

/** 模型下拉菜单的分组标题（不可点）。 */
@Composable
fun ModelMenuHeader(title: String) {
    DropdownMenuItem(
        text = {
            Text(
                title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        },
        onClick = {},
        enabled = false,
    )
}

/**
 * 模型下拉菜单的一项。选中项用主色、浅底和对勾；[trailing] 放在名称与对勾之间，
 * [note] 是名称下方的一行说明。
 */
@Composable
fun ModelMenuItem(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true,
    trailing: @Composable RowScope.() -> Unit = {},
    note: (@Composable () -> Unit)? = null,
) {
    DropdownMenuItem(
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        label,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    trailing()
                    if (selected) {
                        Spacer(Modifier.width(12.dp))
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = "当前模型",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                note?.invoke()
            }
        },
        enabled = enabled,
        onClick = onClick,
        colors = if (selected) {
            MenuDefaults.itemColors(textColor = MaterialTheme.colorScheme.primary)
        } else {
            MenuDefaults.itemColors()
        },
        modifier = if (selected) {
            Modifier.background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f))
        } else {
            Modifier
        },
    )
}

/** 首页模型菜单「在电脑上运行」里的一台电脑。 */
data class DesktopRunTarget(
    val machineId: String,
    val name: String,
    val online: Boolean,
    /** 未配对的点开时先用账号配对。 */
    val paired: Boolean,
)

/** 首页模型菜单里点开的那台电脑：它的模型，或读取中、读取失败。 */
data class DesktopModelsState(
    val machineId: String,
    val loading: Boolean = false,
    val groups: List<RemoteModelGroup> = emptyList(),
    val error: String? = null,
)

/** 「在电脑上运行」的一行：电脑名、在线状态，点进去选模型。离线不可点。 */
@Composable
fun DesktopTargetMenuItem(target: DesktopRunTarget, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    DropdownMenuItem(
        leadingIcon = { AgentMonitorIcon(if (target.online) cs.primary else cs.onSurfaceVariant) },
        text = {
            Column {
                Text(target.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    when {
                        !target.online -> "离线"
                        !target.paired -> "在线（未配对）"
                        else -> "在线"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.onSurfaceVariant,
                )
            }
        },
        trailingIcon = {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = cs.onSurfaceVariant)
        },
        enabled = target.online,
        onClick = onClick,
    )
}

/** 点开一台电脑后的菜单内容：返回行，然后是它的 Work / BYOK 模型。 */
@Composable
fun DesktopModelMenuPage(
    target: DesktopRunTarget,
    state: DesktopModelsState?,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onPick: (RemoteModelGroup, RemoteModelChoice) -> Unit,
    selected: (RemoteModelGroup, RemoteModelChoice) -> Boolean = { _, _ -> false },
) {
    DropdownMenuItem(
        leadingIcon = { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") },
        text = { Text(target.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        onClick = onBack,
    )
    HorizontalDivider()
    when {
        state == null || state.loading -> DropdownMenuItem(
            text = { Text("正在读取电脑上的模型…") },
            onClick = {},
            enabled = false,
        )
        state.error != null -> DropdownMenuItem(
            text = { Text("${state.error} · 点此重试") },
            onClick = onRetry,
        )
        state.groups.isEmpty() -> DropdownMenuItem(
            text = { Text("电脑上没有可用的 Work / BYOK 模型") },
            onClick = {},
            enabled = false,
        )
        else -> state.groups.forEach { group ->
            ModelMenuHeader(if (group.family == "work") "Work" else "BYOK · ${group.provider}")
            group.models.forEach { model ->
                ModelMenuItem(label = model.label, selected = selected(group, model), onClick = { onPick(group, model) })
            }
        }
    }
}
