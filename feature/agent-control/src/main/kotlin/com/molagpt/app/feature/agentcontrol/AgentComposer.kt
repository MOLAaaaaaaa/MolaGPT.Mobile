package com.molagpt.app.feature.agentcontrol

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.remember
import com.molagpt.app.core.model.AgentBackendIds
import com.molagpt.app.core.model.AgentPhase
import com.molagpt.app.core.model.RelaySessionMeta
import com.molagpt.app.core.model.phaseEnum
import com.molagpt.app.core.render.SegmentedControl

/**
 * Agent 输入区——视觉与 `feature/chat` 的 [com.molagpt.app.feature.chat] Composer 一致：
 * 圆角 Surface + 可横滚的 chip 行（模型 / 模式触发 chip + 思考强度 SegmentedControl）+ 输入框 +
 * 圆形发送/停止按钮。不再自创上下文条与底部表样式。
 */
@Composable
fun AgentComposer(
    value: String,
    busy: Boolean,
    meta: RelaySessionMeta?,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onOpenModel: () -> Unit,
    onOpenMode: () -> Unit,
    onSetReasoningEffort: (String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    // 活动中（思考/执行/等待审批）隐藏模型·模式·思考强度切换：这些只能在下一轮的新进程生效，
    // 跑到一半切还会重建进程；只在会话空闲时显示。空闲时才允许发送。
    val active = busy || meta?.phaseEnum == AgentPhase.Waiting
    val canSend = value.isNotBlank() && !active

    val efforts = remember(meta?.backendId) {
        when (meta?.backendId) {
            AgentBackendIds.ClaudeCode -> listOf("low" to "低", "medium" to "中", "high" to "高", "max" to "满")
            AgentBackendIds.Codex -> listOf("low" to "低", "medium" to "中", "high" to "高")
            else -> emptyList()
        }
    }
    val modelText = meta?.model?.takeIf { it.isNotBlank() && it != AgentDefaultModelLabel } ?: "默认模型"
    val posture = meta?.let { modeLabel(it).split(" · ").getOrNull(1) } ?: "模式"

    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        shape = RoundedCornerShape(22.dp),
        color = cs.surface.copy(alpha = 0.96f),
        tonalElevation = 3.dp,
        shadowElevation = 10.dp,
        border = BorderStroke(1.dp, cs.outline.copy(alpha = 0.14f)),
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (meta != null && !active) {
                Row(
                    Modifier.fillMaxWidth().height(40.dp).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TriggerChip(modelText, onOpenModel)
                    TriggerChip(posture, onOpenMode)
                    if (efforts.isNotEmpty()) {
                        SegmentedControl(
                            options = efforts,
                            selected = meta.reasoningEffort ?: "medium",
                            onSelect = onSetReasoningEffort,
                            modifier = Modifier.width((efforts.size * 46).dp),
                        )
                    }
                }
            }

            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp, max = 126.dp).padding(horizontal = 4.dp, vertical = 2.dp),
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = cs.onSurface),
                cursorBrush = SolidColor(cs.primary),
                maxLines = 6,
                decorationBox = { inner ->
                    Box(Modifier.fillMaxWidth()) {
                        if (value.isEmpty()) {
                            Text("输入消息…", color = cs.onSurfaceVariant.copy(alpha = 0.68f), style = MaterialTheme.typography.bodyLarge)
                        }
                        inner()
                    }
                },
            )

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(" ", color = cs.onSurfaceVariant.copy(alpha = 0.74f), style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.weight(1f))
                Crossfade(targetState = active, label = "agentSendStop") { streaming ->
                    if (streaming) {
                        RoundIconButton(Icons.Filled.Clear, "停止", containerColor = cs.error, contentColor = cs.onError, onClick = onStop)
                    } else {
                        RoundIconButton(
                            Icons.AutoMirrored.Filled.Send, "发送",
                            containerColor = if (canSend) cs.primary else cs.surfaceVariant,
                            contentColor = if (canSend) cs.onPrimary else cs.onSurfaceVariant.copy(alpha = 0.54f),
                            enabled = canSend, onClick = onSend,
                        )
                    }
                }
            }
        }
    }
}

/** 触发 chip：仿 chat ToolChip 的中性态 + 下拉箭头，点开模型/模式选择。 */
@Composable
private fun TriggerChip(label: String, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(50)
    Row(
        Modifier
            .heightIn(min = 32.dp)
            .clip(shape)
            .background(cs.surfaceVariant.copy(alpha = 0.72f))
            .border(1.dp, cs.outline.copy(alpha = 0.12f), shape)
            .clickable(role = Role.Button, interactionSource = remember { MutableInteractionSource() }, indication = ripple(), onClick = onClick)
            .padding(start = 11.dp, end = 6.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = cs.onSurfaceVariant, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium, maxLines = 1)
        Icon(Icons.Filled.ArrowDropDown, contentDescription = null, modifier = Modifier.size(18.dp), tint = cs.onSurfaceVariant)
    }
}

/** 圆形图标按钮，复刻 chat Composer 的 RoundIconButton。 */
@Composable
private fun RoundIconButton(
    icon: ImageVector,
    contentDescription: String,
    containerColor: Color,
    contentColor: Color,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val animatedContainer by animateColorAsState(containerColor, label = "agentBtnContainer")
    val animatedContent by animateColorAsState(if (enabled) contentColor else contentColor.copy(alpha = 0.48f), label = "agentBtnContent")
    Box(
        Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(animatedContainer)
            .border(1.dp, cs.outline.copy(alpha = 0.12f), CircleShape)
            .clickable(enabled = enabled, role = Role.Button, interactionSource = remember { MutableInteractionSource() }, indication = ripple(), onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(18.dp), tint = animatedContent)
    }
}
