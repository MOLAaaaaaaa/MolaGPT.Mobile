package com.molagpt.app.feature.chat

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.unit.dp
import com.molagpt.app.core.model.ChatMessage
import com.molagpt.app.core.model.FileInfo
import com.molagpt.app.core.model.ProviderModel
import com.molagpt.app.core.model.UploadStatus
import com.molagpt.app.core.render.MolaMotion
import com.molagpt.app.core.render.SkeletonLines
import com.molagpt.app.feature.file.AttachmentStore
import com.molagpt.app.feature.file.AttachmentStrip

/** 用户气泡（助手消息由 MessageList 按块/片段成行渲染，不走这里）。 */
@Composable
fun MessageBubble(message: ChatMessage, modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.CenterEnd) {
        // 用户气泡使用淡品牌底色、细边框与非对称圆角。
        val bubbleShape = RoundedCornerShape(
            topStart = 16.dp, topEnd = 4.dp, bottomEnd = 16.dp, bottomStart = 16.dp,
        )
        Column(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .clip(bubbleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
                .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.22f), bubbleShape)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            val context = androidx.compose.ui.platform.LocalContext.current
            val files = message.attachments.map { attachment ->
                // 托管副本只存相对路径，显示用的 file:// 绝对路径在这里现算——不落库，
                // 免得 App 数据目录变动后库里留一堆失效的绝对路径。
                val localUrl = AttachmentStore.displayUrl(context, attachment.localPath)
                val missing = attachment.unavailable ||
                    (attachment.localPath != null && localUrl == null)
                FileInfo(
                    id = attachment.id,
                    name = attachment.name,
                    mimeType = attachment.mimeType,
                    sizeBytes = attachment.sizeBytes,
                    url = localUrl ?: attachment.thumbnailUrl ?: attachment.remoteUrl,
                    localPath = attachment.localPath,
                    sandboxPath = attachment.sandboxPath,
                    uploadStatus = if (missing) UploadStatus.MISSING else UploadStatus.UPLOADED,
                )
            }
            if (files.isNotEmpty()) {
                AttachmentStrip(files = files)
            }
            val text = message.rawText.orEmpty()
            if (text.isNotBlank()) {
                SelectionContainer {
                    Text(
                        text = text,
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }
    }
}

/**
 * 助手消息头部行。常态显示模型名；`pending` 元数据非空时改显示它——目前只有两类会写：
 * 断流重试进度（「正在恢复连接 · 第 N 次重试」）和工具执行中的命令标签。
 * 等首 token 不写 pending，那段由 [AssistantStreamingPlaceholder] 的骨架表达。
 */
@Composable
fun AssistantPendingText(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

/**
 * 等待首 token 的正文占位骨架。
 *
 * 用骨架而不是脉冲点：助手正文是无气泡纯文本，骨架把正文将要落的位置先占出来，
 * 首 token 到达时是「骨架让位给文字」而不是「一行小点凭空换成一段话」。
 *
 * 淡入刻意走匀速而不是 M3 的减速曲线：首 token 常常两三百毫秒就到，减速曲线开头冲得太快，
 * 骨架会完整闪一下再消失，反而比脉冲点更吵；匀速淡入让快响应只留下一抹淡影。
 */
@Composable
fun AssistantStreamingPlaceholder(modifier: Modifier = Modifier) {
    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { appeared = true }
    val alpha by animateFloatAsState(
        targetValue = if (appeared) 1f else 0f,
        animationSpec = tween(MolaMotion.Long, easing = LinearEasing),
        label = "skeletonFade",
    )
    SkeletonLines(
        // 高度从一开始就占住（只淡透明度不淡尺寸），所以淡入过程不会推动下方内容。
        modifier = modifier
            .padding(top = 2.dp, bottom = 6.dp)
            .graphicsLayer { this.alpha = alpha },
    )
}

/** 消息下方操作栏。重新生成仅助手最后一条；编辑仅用户消息。 */
@Composable
fun MessageActionBar(
    onCopy: () -> Unit,
    onRegenerate: (() -> Unit)? = null,
    onEdit: (() -> Unit)? = null,
    /** 长按「重新生成」可换模型重试（对齐 Web 的重试模型下拉）；为空则只保留普通重试。 */
    regenerateModels: List<ProviderModel> = emptyList(),
    onRegenerateWithModel: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var modelMenuOpen by remember { mutableStateOf(false) }
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        ActionChip("复制", onCopy)
        if (onEdit != null) ActionChip("编辑", onEdit)
        if (onRegenerate != null) {
            Box {
                ActionChip(
                    text = "重新生成",
                    onClick = onRegenerate,
                    onLongClick = if (regenerateModels.isNotEmpty() && onRegenerateWithModel != null) {
                        { modelMenuOpen = true }
                    } else {
                        null
                    },
                )
                DropdownMenu(
                    expanded = modelMenuOpen,
                    onDismissRequest = { modelMenuOpen = false },
                ) {
                    regenerateModels.forEach { model ->
                        DropdownMenuItem(
                            text = { Text(model.displayName) },
                            onClick = {
                                modelMenuOpen = false
                                onRegenerateWithModel?.invoke(model.id)
                            },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ActionChip(text: String, onClick: () -> Unit, onLongClick: (() -> Unit)? = null) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

/** 重试版本切换栏：‹ n/m ›。 */
@Composable
fun RetryBar(
    current: Int,
    total: Int,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        RetryArrow("‹", enabled = current > 0, onClick = onPrev)
        Text(
            text = "${current + 1}/$total",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        RetryArrow("›", enabled = current < total - 1, onClick = onNext)
    }
}

@Composable
private fun RetryArrow(glyph: String, enabled: Boolean, onClick: () -> Unit) {
    Text(
        text = glyph,
        style = MaterialTheme.typography.titleMedium,
        color = if (enabled) {
            MaterialTheme.colorScheme.onSurfaceVariant
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
        },
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}
