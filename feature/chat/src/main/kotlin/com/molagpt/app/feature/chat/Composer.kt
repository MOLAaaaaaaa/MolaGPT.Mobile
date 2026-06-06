package com.molagpt.app.feature.chat

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ripple
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.molagpt.app.core.model.EnabledTools
import com.molagpt.app.core.model.FileInfo
import com.molagpt.app.core.model.ProviderModel
import com.molagpt.app.core.model.UploadStatus

/**
 * 输入区。输入态用 [rememberSaveable] 持有在本 Composable 内，与消息列表状态分离——
 * 打字不触发列表重组，流式刷新也不重置输入框。
 *
 * 工具行（可横向滚动）：
 *  - 「网络访问」一键开关，同时控制联网搜索 + 网页阅读；
 *  - 「推理」开关：仅当所选模型 [ProviderModel.supportsThinking] 时显示；
 *  - 推理强度（低/中/高）：仅当开了推理且模型 [ProviderModel.supportsReasoningEffort] 时显示。
 * 底部加号 = 选图上传（代码执行等开关已移至设置页）。
 */
@Composable
fun Composer(
    enabled: Boolean,
    isStreaming: Boolean,
    enterToSend: Boolean,
    enabledTools: EnabledTools,
    selectedModel: ProviderModel?,
    useThinking: Boolean,
    reasoningEffort: String,
    pendingAttachments: List<FileInfo>,
    onSetNetworkAccess: (Boolean) -> Unit,
    onToggleThinking: (Boolean) -> Unit,
    onSetReasoningEffort: (String) -> Unit,
    onPickImage: () -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var text by rememberSaveable { mutableStateOf("") }
    val canSend = enabled && text.isNotBlank()
    val colorScheme = MaterialTheme.colorScheme
    val toolsEnabled = enabled && !isStreaming
    val showThinking = selectedModel?.supportsThinking == true
    val showEffort = showThinking && useThinking && selectedModel?.supportsReasoningEffort == true

    fun submit() {
        val content = text.trim()
        if (content.isNotEmpty() && enabled) {
            onSend(content)
            text = ""
        }
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        shape = RoundedCornerShape(22.dp),
        color = colorScheme.surface.copy(alpha = 0.96f),
        tonalElevation = 3.dp,
        shadowElevation = 10.dp,
        border = BorderStroke(1.dp, colorScheme.outline.copy(alpha = 0.14f)),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ToolChip(
                    label = "网络访问",
                    checked = enabledTools.network,
                    enabled = toolsEnabled,
                    onCheckedChange = onSetNetworkAccess,
                )
                if (showThinking) {
                    ToolChip(
                        label = "推理",
                        checked = useThinking,
                        enabled = toolsEnabled,
                        onCheckedChange = onToggleThinking,
                    )
                }
                if (showEffort) {
                    com.molagpt.app.core.render.SegmentedControl(
                        options = listOf("low" to "低", "medium" to "中", "high" to "高"),
                        selected = reasoningEffort,
                        onSelect = onSetReasoningEffort,
                        modifier = Modifier.width(132.dp),
                    )
                }
            }

            if (pendingAttachments.isNotEmpty()) {
                PendingAttachmentRow(files = pendingAttachments, onRemove = onRemoveAttachment)
            }

            BasicTextField(
                value = text,
                onValueChange = { text = it },
                enabled = enabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp, max = 126.dp)
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = colorScheme.onSurface),
                cursorBrush = SolidColor(colorScheme.primary),
                keyboardOptions = KeyboardOptions(
                    imeAction = if (enterToSend) ImeAction.Send else ImeAction.Default,
                ),
                keyboardActions = KeyboardActions(onSend = { submit() }),
                maxLines = 6,
                decorationBox = { innerTextField ->
                    Box(modifier = Modifier.fillMaxWidth()) {
                        if (text.isEmpty()) {
                            Text(
                                text = "输入消息...",
                                color = colorScheme.onSurfaceVariant.copy(alpha = 0.68f),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                        innerTextField()
                    }
                },
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RoundIconButton(
                    icon = Icons.Filled.Add,
                    contentDescription = "添加图片",
                    selected = false,
                    enabled = toolsEnabled,
                    onClick = onPickImage,
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = if (enterToSend) "Enter 发送" else "Enter 换行",
                    color = colorScheme.onSurfaceVariant.copy(alpha = 0.74f),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.weight(1f))
                Crossfade(
                    targetState = isStreaming,
                    animationSpec = com.molagpt.app.core.render.MolaMotion.standard(com.molagpt.app.core.render.MolaMotion.Medium),
                    label = "sendStop",
                ) { streaming ->
                    if (streaming) {
                        RoundIconButton(
                            icon = Icons.Filled.Clear,
                            contentDescription = "停止生成",
                            selected = true,
                            containerColor = colorScheme.error,
                            contentColor = colorScheme.onError,
                            onClick = onStop,
                        )
                    } else {
                        RoundIconButton(
                            icon = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "发送",
                            selected = canSend,
                            enabled = canSend,
                            containerColor = if (canSend) colorScheme.primary else colorScheme.surfaceVariant,
                            contentColor = if (canSend) colorScheme.onPrimary else colorScheme.onSurfaceVariant.copy(alpha = 0.54f),
                            onClick = { submit() },
                        )
                    }
                }
            }
        }
    }
}

/** 待发送图片条：上传中转圈、失败标红，右侧叉可移除。 */
@Composable
private fun PendingAttachmentRow(files: List<FileInfo>, onRemove: (String) -> Unit) {
    val colorScheme = MaterialTheme.colorScheme
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(files, key = { it.id }) { file ->
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(colorScheme.surfaceVariant.copy(alpha = 0.6f))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (file.uploadStatus == UploadStatus.UPLOADING) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(
                    text = file.name,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (file.uploadStatus == UploadStatus.FAILED) {
                        colorScheme.error
                    } else {
                        colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 120.dp),
                )
                if (file.uploadStatus == UploadStatus.FAILED) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "失败",
                        style = MaterialTheme.typography.labelSmall,
                        color = colorScheme.error,
                    )
                }
                Spacer(modifier = Modifier.width(6.dp))
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "移除",
                    modifier = Modifier
                        .size(16.dp)
                        .clip(CircleShape)
                        .clickable(
                            role = Role.Button,
                            interactionSource = remember { MutableInteractionSource() },
                            indication = ripple(),
                        ) { onRemove(file.id) },
                    tint = colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
        }
    }
}

@Composable
private fun ToolChip(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(50)
    val containerColor by animateColorAsState(
        targetValue = when {
            checked -> colorScheme.primary.copy(alpha = 0.14f)
            else -> colorScheme.surfaceVariant.copy(alpha = 0.72f)
        },
        label = "toolChipContainer",
    )
    val contentColor by animateColorAsState(
        targetValue = when {
            !enabled -> colorScheme.onSurfaceVariant.copy(alpha = 0.42f)
            checked -> colorScheme.primary
            else -> colorScheme.onSurfaceVariant
        },
        label = "toolChipContent",
    )
    val borderColor = if (checked) {
        colorScheme.primary.copy(alpha = 0.32f)
    } else {
        colorScheme.outline.copy(alpha = 0.12f)
    }

    Row(
        modifier = Modifier
            .heightIn(min = 32.dp)
            .clip(shape)
            .background(containerColor)
            .border(1.dp, borderColor, shape)
            .clickable(
                enabled = enabled,
                role = Role.Checkbox,
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(),
            ) { onCheckedChange(!checked) }
            .padding(horizontal = 11.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = contentColor,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (checked) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
        )
    }
}

@Composable
private fun RoundIconButton(
    icon: ImageVector,
    contentDescription: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    containerColor: Color? = null,
    contentColor: Color? = null,
    onClick: () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    val targetContainer = containerColor ?: if (selected) {
        colorScheme.primary.copy(alpha = 0.14f)
    } else {
        colorScheme.surfaceVariant.copy(alpha = 0.72f)
    }
    val targetContent = contentColor ?: if (selected) {
        colorScheme.primary
    } else {
        colorScheme.onSurfaceVariant
    }
    val animatedContainer by animateColorAsState(targetContainer, label = "roundButtonContainer")
    val animatedContent by animateColorAsState(
        if (enabled) targetContent else targetContent.copy(alpha = 0.48f),
        label = "roundButtonContent",
    )

    Box(
        modifier = modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(animatedContainer)
            .border(1.dp, colorScheme.outline.copy(alpha = 0.12f), CircleShape)
            .clickable(
                enabled = enabled,
                role = Role.Button,
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(),
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(18.dp),
            tint = animatedContent,
        )
    }
}
