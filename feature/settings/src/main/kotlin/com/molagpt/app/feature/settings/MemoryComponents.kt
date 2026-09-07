package com.molagpt.app.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.molagpt.app.core.model.ConfidenceTier
import com.molagpt.app.core.model.InsightCategory
import com.molagpt.app.core.model.MemoryStatus
import com.molagpt.app.core.render.shimmer

internal const val COLLAPSED_MEMORY_ENTRY_COUNT = 6

/**
 * 记忆界面的共享零件。服务端 Tracks 的记忆中心（[PersonalizationScreen]）与
 * BYOK 本地记忆页（[ByokMemoryScreen]）共用这些叶子组件与语义色。
 *
 * 只放**与数据模型无关**的部分：两侧的条目类型不同（服务端 `MemoryEntry` vs 本地 `ByokMemoryEntry`），
 * 强行统一条目卡会把两套业务语义（评分、夜间管线 vs 证据、压制）绞在一起。
 */

/* —— 语义色（置信度 / 状态 / 分类）。固定语义色保持中等饱和度，兼顾亮色与暗色可读性。 —— */
internal val CObrand = Color(0xFFBE727F)
internal val CblueT = Color(0xFF3D8FD1)
internal val Cgray = Color(0xFF95A5A6)
internal val Cgreen = Color(0xFF2E9E5B)
internal val Ccyan = Color(0xFF1FA6BC)
internal val Corange = Color(0xFFE0902B)
internal val Cred = Color(0xFFE5615F)
internal val Cpurple = Color(0xFF9B6BC4)
internal val CblueWork = Color(0xFF3D8FD1)
internal val CtealG = Color(0xFF1BAE94)
internal val CgreenH = Color(0xFF35B36A)

internal fun confidenceColor(t: ConfidenceTier): Color = when (t) {
    ConfidenceTier.CORE -> CObrand
    ConfidenceTier.KNOWN -> CblueT
    ConfidenceTier.VAGUE -> Cgray
}

internal fun statusColor(s: MemoryStatus): Color = when (s) {
    MemoryStatus.ACTIVE, MemoryStatus.GROWING -> Cgreen
    MemoryStatus.STABLE -> Ccyan
    MemoryStatus.FADING -> Corange
    MemoryStatus.WEAK -> Cgray
    MemoryStatus.QUESTIONED -> Cred
}

internal fun categoryColor(c: InsightCategory): Color = when (c) {
    InsightCategory.BIOGRAPHICAL_IDENTITY -> Cpurple
    InsightCategory.CORE_PERSONAL_VALUE -> Color(0xFF6C7A89)
    InsightCategory.LONG_TERM_INTEREST -> CtealG
    InsightCategory.HABIT_PATTERN -> CgreenH
    InsightCategory.WORK_STYLE -> CblueWork
    InsightCategory.PROJECT_FOCUS -> Corange
    InsightCategory.SITUATIONAL_CONTEXT -> Color(0xFF4AA3E0)
    InsightCategory.EPHEMERAL -> Cgray
    InsightCategory.EXPLICIT_INSTRUCTION -> Cred
}

@Composable
internal fun SectionHeader(title: String, trailing: @Composable (() -> Unit)? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.weight(1f))
        trailing?.invoke()
    }
}

/** 分节小标题：记忆按固定的 5 个分节归类，注入块也按此顺序渲染。 */
@Composable
internal fun SectionDivider(label: String, count: Int) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(4.dp).clip(RoundedCornerShape(50)).background(cs.primary))
        Spacer(Modifier.width(7.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = cs.primary,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.width(6.dp))
        Text("$count", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
    }
}

/**
 * 注入预算占用。超预算的条目**不会进入** system prompt，
 * 所以 [skipped] > 0 必须显式告知——否则用户以为列表里的每条都在生效。
 */
@Composable
internal fun MemoryProjectionRow(
    injected: Int,
    skipped: Int,
    tokens: Int,
    budget: Int,
    overflowHint: String,
) {
    if (budget <= 0) return
    val cs = MaterialTheme.colorScheme
    val over = skipped > 0
    val usage = (tokens.toFloat() / budget).coerceIn(0f, 1f)
    Column(modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("已使用 $injected 条", style = MaterialTheme.typography.labelMedium, color = cs.onSurface)
            Spacer(Modifier.weight(1f))
            Text(
                "已用 ${(usage * 100).toInt()}%",
                style = MaterialTheme.typography.labelSmall,
                color = if (over) Corange else cs.onSurfaceVariant,
            )
        }
        LinearProgressIndicator(
            progress = { usage },
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(50)),
            color = if (over) Corange else cs.primary,
            trackColor = cs.surfaceVariant,
        )
        if (over) {
            Text(
                overflowHint,
                style = MaterialTheme.typography.labelSmall,
                color = Corange,
                modifier = Modifier.padding(top = 5.dp),
            )
        }
    }
}

@Composable
internal fun TagChip(text: String, color: Color, filled: Boolean) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(color.copy(alpha = if (filled) 0.14f else 0.10f))
            .padding(horizontal = 9.dp, vertical = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(6.dp).clip(RoundedCornerShape(50)).background(color))
            Spacer(Modifier.width(5.dp))
            Text(text, style = MaterialTheme.typography.labelSmall, color = color, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** 两套记忆共用的待确认卡。 */
@Composable
internal fun MemoryCandidateCard(
    text: String,
    quote: String?,
    meta: String,
    onAccept: () -> Unit,
    onDismiss: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(cs.primary.copy(alpha = 0.07f))
            .padding(16.dp),
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = cs.onSurface)
        quote?.takeIf { it.isNotBlank() }?.let { source ->
            Row(modifier = Modifier.fillMaxWidth().padding(top = 9.dp)) {
                Box(
                    modifier = Modifier
                        .size(width = 2.dp, height = if (source.length > 40) 34.dp else 17.dp)
                        .clip(RoundedCornerShape(50))
                        .background(cs.outline.copy(alpha = 0.5f)),
                )
                Text(
                    "「$source」",
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.onSurfaceVariant,
                    fontStyle = FontStyle.Italic,
                    modifier = Modifier.padding(start = 8.dp).weight(1f),
                )
            }
        }
        Row(modifier = Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(meta, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onDismiss) { Text("忽略", color = cs.onSurfaceVariant) }
            Spacer(Modifier.width(4.dp))
            Button(onClick = onAccept) { Text("记住") }
        }
    }
}

/** 两套记忆共用的条目外框；评分、来源和本地状态由调用方填入。 */
@Composable
internal fun MemoryEntryCardFrame(
    accent: Color,
    tierLabel: String,
    confidencePercent: Int,
    text: String,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(cs.surfaceVariant.copy(alpha = 0.4f))
            .drawBehind { drawRect(color = accent, size = Size(4.dp.toPx(), size.height)) },
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(start = 18.dp, top = 14.dp, end = 12.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(9.dp).clip(RoundedCornerShape(50)).background(accent))
                Spacer(Modifier.width(8.dp))
                Text(
                    tierLabel,
                    style = MaterialTheme.typography.labelLarge,
                    color = accent,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "$confidencePercent%",
                    style = MaterialTheme.typography.labelMedium,
                    color = cs.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onEdit, modifier = Modifier.size(34.dp)) {
                    Icon(Icons.Filled.Settings, contentDescription = "编辑", tint = cs.onSurfaceVariant, modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(34.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = "删除", tint = cs.onSurfaceVariant, modifier = Modifier.size(18.dp))
                }
            }
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = cs.onSurface,
                modifier = Modifier.padding(top = 8.dp, bottom = 10.dp),
            )
            content()
        }
    }
}

@Composable
internal fun MemoryAddButton(enabled: Boolean = true, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
            .clip(RoundedCornerShape(14.dp))
            .border(1.dp, cs.outline.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Add, contentDescription = null, tint = cs.primary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text("添加记忆", style = MaterialTheme.typography.bodyMedium, color = cs.primary)
    }
}

@Composable
internal fun EmptyMemoryState(title: String, description: String) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 36.dp, horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier.size(60.dp).clip(RoundedCornerShape(18.dp)).background(cs.primary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Text("Aa", color = cs.primary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
        }
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 14.dp))
        Text(
            description,
            style = MaterialTheme.typography.bodySmall,
            color = cs.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
internal fun MemoryDangerZone(
    description: String?,
    actionSubtitle: String,
    onClearAll: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 18.dp)
            .clip(RoundedCornerShape(20.dp))
            .border(1.dp, cs.error.copy(alpha = 0.3f), RoundedCornerShape(20.dp))
            .background(cs.error.copy(alpha = 0.05f))
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Warning, contentDescription = null, tint = cs.error, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("数据清除", style = MaterialTheme.typography.titleSmall, color = cs.error, fontWeight = FontWeight.SemiBold)
        }
        description?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp, bottom = 6.dp),
            )
        }
        DangerButton("清除全部记忆", actionSubtitle, onClearAll)
    }
}

@Composable
internal fun DangerButton(title: String, subtitle: String, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .clip(RoundedCornerShape(13.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Delete, contentDescription = null, tint = cs.error, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = cs.onSurface)
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = cs.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
internal fun LoadingEntries() {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        repeat(3) {
            Box(modifier = Modifier.fillMaxWidth().height(96.dp).clip(RoundedCornerShape(20.dp)).shimmer())
        }
    }
}

/** 粗粒度相对天数（unix 秒）。 */
internal fun relativeDays(ts: Long, nowSeconds: Long): String {
    if (ts <= 0) return "未知"
    val days = ((nowSeconds - ts) / 86_400L).toInt()
    return when {
        days <= 0 -> "今天"
        days == 1 -> "昨天"
        days < 7 -> "${days}天前"
        days < 30 -> "${days / 7}周前"
        else -> "${days / 30}个月前"
    }
}

/** 毫秒版本。本地记忆全程用 ms 时间戳，避免在调用处到处除 1000。 */
internal fun relativeDaysMillis(tsMillis: Long, nowMillis: Long): String =
    relativeDays(tsMillis / 1000L, nowMillis / 1000L)
