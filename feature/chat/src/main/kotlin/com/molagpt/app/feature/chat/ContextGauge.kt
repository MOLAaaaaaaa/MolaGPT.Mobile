package com.molagpt.app.feature.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.molagpt.app.core.model.ContextCompactionMark
import com.molagpt.app.core.model.ContextCompactionPolicy
import com.molagpt.app.core.model.ContextCompactionProgress
import com.molagpt.app.core.model.formatCost
import com.molagpt.app.core.render.MolaMotion
import kotlinx.coroutines.launch

/**
 * 输入框旁的上下文用量。
 *
 * 数字来自服务商实测（上一次回答时的上下文大小），不是边打字边估的——用户能据此判断的是
 * 「对话已经多满」。刚压缩完、还没有新回答时只有估算值，标「约」；从没有过回答时不显示，
 * 空环会被读成「还很空」，而那时其实什么都没量过。
 */
@Immutable
data class ContextUsage(
    /** 当前上下文大小；null 表示还没有可显示的数字。 */
    val tokens: Int?,
    val window: Int,
    /** [tokens] 是压缩后的估算值，还没有被新回答实测过。 */
    val approximate: Boolean,
    val autoCompaction: Boolean,
    val compactions: List<ContextCompactionMark>,
    /** 最近一次压缩由摘要代替的消息条数。 */
    val latestCoveredMessages: Int? = null,
    /** 本对话生成摘要的花费（美元）。 */
    val compactionCostUsd: Double = 0.0,
    /** 正在压缩（自动或手动），可以取消。 */
    val compacting: Boolean,
    /** 压缩进度；手动压缩还在准备时为 null。 */
    val progress: ContextCompactionProgress? = null,
    /** 正在手动压缩：输入框暂不可用。 */
    val manualCompacting: Boolean,
    /** 正在生成回答，这时不能手动压缩。 */
    val replying: Boolean = false,
) {
    val threshold: Int get() = ContextCompactionPolicy.threshold(window)

    val fraction: Float get() = tokens?.let { (it.toFloat() / window).coerceIn(0f, 1f) } ?: 0f

    val percent: Int get() = tokens?.let { (it * 100L / window).toInt().coerceAtMost(100) } ?: 0

    val pressure: Pressure
        get() {
            val value = tokens ?: return Pressure.NORMAL
            return when {
                value >= threshold -> Pressure.CRITICAL
                value >= window * WARNING_RATIO -> Pressure.WARNING
                else -> Pressure.NORMAL
            }
        }

    enum class Pressure { NORMAL, WARNING, CRITICAL }

    private companion object {
        const val WARNING_RATIO = 0.7
    }
}

/** 环点开后显示哪张面板。 */
private sealed interface GaugeSheet {
    data object Usage : GaugeSheet
    data class Summary(val mark: ContextCompactionMark) : GaugeSheet
}

@Composable
internal fun ContextGaugeButton(
    usage: ContextUsage,
    onCompact: () -> Unit,
    onCancelCompact: () -> Unit,
    onSetAutoCompaction: (Boolean) -> Unit,
    /** 打开当前模型的设置页（上下文窗口在那里改）；没有时不显示入口。 */
    onOpenModelSettings: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var sheet by remember { mutableStateOf<GaugeSheet?>(null) }
    val cs = MaterialTheme.colorScheme
    // 输入栏上常态用灰色，不跟发送键抢眼；接近上限才变色。
    val arcColor = pressureColor(usage, normal = cs.onSurfaceVariant)
    // 压缩完回落是一段看得见的收缩，而不是一跳。
    val fraction by animateFloatAsState(
        targetValue = usage.fraction,
        animationSpec = MolaMotion.emphasized(MolaMotion.Long),
        label = "contextArc",
    )
    Box(
        modifier = modifier
            .size(36.dp)
            .clip(CircleShape)
            .clickable { sheet = GaugeSheet.Usage }
            .semantics { contentDescription = "上下文用量 ${usage.percent}%" },
        contentAlignment = Alignment.Center,
    ) {
        if (usage.compacting) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = cs.onSurfaceVariant,
            )
        } else {
            val track = cs.outlineVariant
            Canvas(modifier = Modifier.size(18.dp)) {
                val stroke = 2.5.dp.toPx()
                val inset = stroke / 2
                val arcSize = Size(size.width - stroke, size.height - stroke)
                drawArc(
                    color = track,
                    startAngle = 0f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(width = stroke),
                )
                if (fraction > 0f) {
                    drawArc(
                        color = arcColor,
                        startAngle = -90f,
                        sweepAngle = 360f * fraction,
                        useCenter = false,
                        topLeft = Offset(inset, inset),
                        size = arcSize,
                        style = Stroke(width = stroke, cap = StrokeCap.Round),
                    )
                }
            }
        }
    }

    when (val current = sheet) {
        GaugeSheet.Usage -> ContextSheet(
            usage = usage,
            onCompact = onCompact,
            onCancelCompact = onCancelCompact,
            onSetAutoCompaction = onSetAutoCompaction,
            onOpenSummary = { sheet = GaugeSheet.Summary(it) },
            onOpenModelSettings = onOpenModelSettings,
            onDismiss = { sheet = null },
        )
        is GaugeSheet.Summary -> ContextCompactionSheet(
            mark = current.mark,
            coveredMessages = usage.latestCoveredMessages
                .takeIf { current.mark.id == usage.compactions.maxByOrNull { it.createdAt }?.id },
            onDismiss = { sheet = null },
        )
        null -> Unit
    }
}

/**
 * 上下文面板：用量、自动压缩开关、最近的摘要、立即压缩。
 * 与「推理强度」同一套底部面板布局：居中标题，右侧齿轮进模型设置。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContextSheet(
    usage: ContextUsage,
    onCompact: () -> Unit,
    onCancelCompact: () -> Unit,
    onSetAutoCompaction: (Boolean) -> Unit,
    onOpenSummary: (ContextCompactionMark) -> Unit,
    onOpenModelSettings: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        contentWindowInsets = { WindowInsets(0) },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp)
                .windowInsetsPadding(WindowInsets.navigationBars),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 40.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("上下文", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                if (onOpenModelSettings != null) {
                    IconButton(
                        onClick = {
                            onDismiss()
                            onOpenModelSettings()
                        },
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .size(40.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = "模型设置",
                            tint = cs.onSurfaceVariant,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }
            Text(
                "模型回答时可用的对话内容",
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp, bottom = 20.dp),
            )

            UsageOverview(usage)

            Column(
                modifier = Modifier
                    .padding(top = 20.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(cs.surfaceVariant.copy(alpha = 0.45f)),
            ) {
                AutoCompactionRow(usage, onSetAutoCompaction)
                val latest = usage.compactions.maxByOrNull { it.createdAt }
                if (latest != null) {
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = cs.outlineVariant.copy(alpha = 0.5f),
                    )
                    SummaryRow(usage, latest) {
                        // 先收起这张再打开摘要，两张面板不叠在一起。
                        scope.launch { sheetState.hide() }.invokeOnCompletion { onOpenSummary(latest) }
                    }
                }
            }

            CompactAction(
                usage = usage,
                onCompact = onCompact,
                onCancel = onCancelCompact,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
    }
}

/** 大号用量数字 + 横条（带自动压缩刻度）+ 余量说明。 */
@Composable
private fun UsageOverview(usage: ContextUsage) {
    val cs = MaterialTheme.colorScheme
    val accent = pressureColor(usage, normal = cs.primary)
    val secondary = SpanStyle(fontSize = MaterialTheme.typography.titleMedium.fontSize, color = cs.onSurfaceVariant)
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        Text(
            text = buildAnnotatedString {
                val tokens = usage.tokens
                if (tokens == null) {
                    append("—")
                    return@buildAnnotatedString
                }
                if (usage.approximate) withStyle(secondary) { append("约 ") }
                append(formatTokenCount(tokens))
                withStyle(secondary) { append(" / ${formatTokenCount(usage.window)}") }
            },
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
            color = cs.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (usage.tokens != null) {
            Text(
                "${usage.percent}%",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = accent,
                modifier = Modifier.padding(bottom = 3.dp),
            )
        }
    }
    ContextBar(
        fraction = usage.fraction,
        marker = ContextCompactionPolicy.TRIGGER_RATIO.toFloat().takeIf { usage.autoCompaction },
        color = accent,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
    )
    Text(
        text = headroomLine(usage),
        style = MaterialTheme.typography.labelMedium,
        color = if (usage.pressure == ContextUsage.Pressure.CRITICAL && usage.autoCompaction) cs.error else cs.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )
    if (usage.approximate) {
        Text(
            "当前为估算值，回答后更新",
            style = MaterialTheme.typography.labelSmall,
            color = cs.onSurfaceVariant.copy(alpha = 0.8f),
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/** 横向用量条；[marker] 处画一道刻度，标出自动压缩的位置。 */
@Composable
private fun ContextBar(fraction: Float, marker: Float?, color: Color, modifier: Modifier = Modifier) {
    val animated by animateFloatAsState(
        targetValue = fraction,
        animationSpec = MolaMotion.emphasized(MolaMotion.Long),
        label = "contextBar",
    )
    val track = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.14f)
    val markerColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
    Canvas(modifier = modifier.height(14.dp)) {
        val barHeight = 8.dp.toPx()
        val top = (size.height - barHeight) / 2
        val radius = CornerRadius(barHeight / 2)
        drawRoundRect(color = track, topLeft = Offset(0f, top), size = Size(size.width, barHeight), cornerRadius = radius)
        if (animated > 0f) {
            drawRoundRect(
                color = color,
                topLeft = Offset(0f, top),
                size = Size((size.width * animated).coerceAtLeast(barHeight), barHeight),
                cornerRadius = radius,
            )
        }
        if (marker != null) {
            val x = size.width * marker
            drawLine(
                color = markerColor,
                start = Offset(x, 0f),
                end = Offset(x, size.height),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
    }
}

@Composable
private fun AutoCompactionRow(usage: ContextUsage, onChange: (Boolean) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = usage.autoCompaction, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("自动压缩", style = MaterialTheme.typography.bodyLarge, color = cs.onSurface)
            Text(
                "接近上下文上限时压缩较早对话，会增加模型用量",
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Switch(
            checked = usage.autoCompaction,
            // 整行可点，开关本身不再单独响应。
            onCheckedChange = null,
            modifier = Modifier.padding(start = 12.dp),
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                uncheckedThumbColor = cs.onSurfaceVariant,
                checkedTrackColor = cs.primary,
                uncheckedTrackColor = cs.surfaceVariant,
                uncheckedBorderColor = cs.outline,
            ),
        )
    }
}

@Composable
private fun SummaryRow(usage: ContextUsage, latest: ContextCompactionMark, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val detail = listOfNotNull(
        "累计压缩 ${usage.compactions.size} 次",
        latest.tokensSaved.takeIf { it > 0 }?.let { "最近节省约 ${formatTokenCount(it)}" },
        usage.compactionCostUsd.takeIf { it > 0.0 }?.let { "费用 ${formatCost(it)}" },
    ).joinToString(" · ")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("对话摘要", style = MaterialTheme.typography.bodyLarge, color = cs.onSurface)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = cs.onSurfaceVariant,
        )
    }
}

/** 底部的主操作：立即压缩；压缩中换成进度与取消，高度不变。 */
@Composable
private fun CompactAction(
    usage: ContextUsage,
    onCompact: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    AnimatedContent(
        targetState = usage.compacting,
        transitionSpec = {
            fadeIn(MolaMotion.standard(MolaMotion.Medium)) togetherWith fadeOut(MolaMotion.standard(MolaMotion.Short))
        },
        modifier = modifier.fillMaxWidth(),
        label = "compactAction",
    ) { compacting ->
        if (compacting) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = cs.primary)
                Text(
                    text = compactionProgressText(usage.progress),
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurfaceVariant,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 12.dp),
                )
                OutlinedButton(onClick = onCancel) { Text("取消") }
            }
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                FilledTonalButton(
                    onClick = onCompact,
                    enabled = !usage.replying,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                ) { Text("立即压缩") }
                if (usage.replying) {
                    Text(
                        "回答完成后可压缩",
                        style = MaterialTheme.typography.labelSmall,
                        color = cs.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
    }
}

private fun headroomLine(usage: ContextUsage): String {
    if (!usage.autoCompaction) return "自动压缩已关闭"
    val tokens = usage.tokens ?: return "接近上下文上限时自动压缩"
    val headroom = usage.threshold - tokens
    return if (headroom <= 0) "下次发送前自动压缩" else "自动压缩还需约 ${formatTokenCount(headroom)}"
}

/** 70% 起琥珀、到自动压缩阈值变红；常态用 [normal]。 */
@Composable
private fun pressureColor(usage: ContextUsage, normal: Color): Color {
    val cs = MaterialTheme.colorScheme
    val color by animateColorAsState(
        targetValue = when (usage.pressure) {
            ContextUsage.Pressure.CRITICAL -> cs.error
            ContextUsage.Pressure.WARNING -> warningColor()
            ContextUsage.Pressure.NORMAL -> normal
        },
        animationSpec = MolaMotion.standard(MolaMotion.Long),
        label = "contextPressure",
    )
    return color
}

/** 主题里没有警示色，按明暗各取一个琥珀色。 */
@Composable
private fun warningColor(): Color =
    if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) Color(0xFFF2B33D) else Color(0xFFD9870B)
