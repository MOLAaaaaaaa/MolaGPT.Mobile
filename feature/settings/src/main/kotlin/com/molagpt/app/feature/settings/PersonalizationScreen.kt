package com.molagpt.app.feature.settings

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.molagpt.app.core.model.ConfidenceTier
import com.molagpt.app.core.model.ConversationStyle
import com.molagpt.app.core.model.Insight
import com.molagpt.app.core.model.InsightCategory
import com.molagpt.app.core.model.InsightRating
import com.molagpt.app.core.model.InsightStatus
import com.molagpt.app.core.render.fadeScaleIn
import com.molagpt.app.core.render.shimmer

/* —— 语义色（置信度 / 状态 / 分类）。固定语义色保持中等饱和度，兼顾亮色与暗色可读性。 —— */
private val CObrand = Color(0xFFBE727F)
private val CblueT = Color(0xFF3D8FD1)
private val Cgray = Color(0xFF95A5A6)
private val Cgreen = Color(0xFF2E9E5B)
private val Ccyan = Color(0xFF1FA6BC)
private val Corange = Color(0xFFE0902B)
private val Cred = Color(0xFFE5615F)
private val Cpurple = Color(0xFF9B6BC4)
private val CblueWork = Color(0xFF3D8FD1)
private val CtealG = Color(0xFF1BAE94)
private val CgreenH = Color(0xFF35B36A)

// 用户画像可能持续增长；默认只展示少量高权重项，保证后续模块无需长距离滚动也能到达。
private const val COLLAPSED_INSIGHT_COUNT = 6

private fun confidenceColor(t: ConfidenceTier): Color = when (t) {
    ConfidenceTier.CORE -> CObrand
    ConfidenceTier.KNOWN -> CblueT
    ConfidenceTier.VAGUE -> Cgray
}

private fun statusColor(s: InsightStatus): Color = when (s) {
    InsightStatus.ACTIVE, InsightStatus.GROWING -> Cgreen
    InsightStatus.STABLE -> Ccyan
    InsightStatus.FADING -> Corange
    InsightStatus.WEAK -> Cgray
    InsightStatus.QUESTIONED -> Cred
}

private fun categoryColor(c: InsightCategory): Color = when (c) {
    InsightCategory.BIOGRAPHICAL_IDENTITY -> Cpurple
    InsightCategory.CORE_PERSONAL_VALUE -> Color(0xFF6C7A89)
    InsightCategory.LONG_TERM_INTEREST -> CtealG
    InsightCategory.HABIT_PATTERN -> CgreenH
    InsightCategory.WORK_STYLE -> CblueWork
    InsightCategory.PROJECT_FOCUS -> Corange
    InsightCategory.SITUATIONAL_CONTEXT -> Color(0xFF4AA3E0)
    InsightCategory.EPHEMERAL -> Cgray
}

/**
 * 个性化回答管理页。从「设置 → 个性化记忆 → 管理」进入。
 *
 * 本页不单独注册返回拦截，返回行为交给 NavHost 统一处理；顶栏返回箭头仅调 [onBack]。
 * 刷新、演化展开等局部动效使用 [shimmer]/[fadeScaleIn] 与 Compose animation 原语。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonalizationScreen(
    viewModel: PersonalizationViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val enabled by viewModel.enabled.collectAsStateWithLifecycle()
    val insights by viewModel.insights.collectAsStateWithLifecycle()
    val ratings by viewModel.ratings.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    val togglingMaster by viewModel.togglingMaster.collectAsStateWithLifecycle()
    val style by viewModel.style.collectAsStateWithLifecycle()
    val styleDirty by viewModel.styleDirty.collectAsStateWithLifecycle()
    val savingStyle by viewModel.savingStyle.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(message) {
        message?.let { snackbar.showSnackbar(it); viewModel.clearMessage() }
    }

    val now = remember { System.currentTimeMillis() / 1000L }

    var showPrivacy by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Insight?>(null) }
    var confirm by remember { mutableStateOf<ConfirmAction?>(null) }
    var insightsExpanded by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.systemBars.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
        topBar = {
            TopAppBar(
                title = { Text("个性化回答") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(onClick = { showPrivacy = true }) { Text("隐私说明") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            MasterToggleCard(enabled, togglingMaster, viewModel::setEnabled)

            // —— 用户画像 ——
            SectionHeader(
                title = "用户画像",
                trailing = {
                    RefreshButton(spinning = refreshing, onClick = viewModel::refresh)
                },
            )
            when {
                loading -> LoadingInsights()
                insights.isEmpty() -> EmptyInsights()
                else -> {
                    InsightsOverview(insights)
                    val visibleInsights = if (insightsExpanded) {
                        insights
                    } else {
                        insights.take(COLLAPSED_INSIGHT_COUNT)
                    }
                    visibleInsights.forEach { ins ->
                        InsightCard(
                            insight = ins,
                            now = now,
                            rating = ratings[ins.id],
                            onRate = { viewModel.rate(ins.id, it) },
                            onEdit = { editing = ins },
                            onDelete = { confirm = ConfirmAction.DeleteInsight(ins.id) },
                        )
                    }
                    if (insights.size > COLLAPSED_INSIGHT_COUNT) {
                        TextButton(
                            onClick = { insightsExpanded = !insightsExpanded },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                if (insightsExpanded) {
                                    "收起用户画像"
                                } else {
                                    "查看全部 ${insights.size} 条画像"
                                },
                            )
                        }
                    }
                }
            }

            // —— 对话风格 ——
            SectionHeader(title = "对话风格")
            StyleSection(
                style = style,
                dirty = styleDirty,
                saving = savingStyle,
                onToggle = viewModel::toggleStyle,
                onCustom = viewModel::setCustomInstruction,
                onSave = viewModel::saveStyle,
            )

            // —— 数据清除 ——
            DangerZone(
                onClearInsights = { confirm = ConfirmAction.ClearInsights },
                onClearMemories = { confirm = ConfirmAction.ClearMemories },
            )

            Text(
                "洞察由 MolaGPT 在后台自动分析生成，可能不完全准确；你可随时评分、修正或删除，帮助它更懂你。",
                style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
            )
        }
    }

    if (showPrivacy) PrivacySheet(onDismiss = { showPrivacy = false })

    editing?.let { ins ->
        EditInsightSheet(
            initial = ins.text,
            onDismiss = { editing = null },
            onSave = { viewModel.updateText(ins.id, it); editing = null },
        )
    }

    confirm?.let { action ->
        ConfirmDialog(
            action = action,
            onDismiss = { confirm = null },
            onConfirm = {
                when (action) {
                    is ConfirmAction.DeleteInsight -> viewModel.delete(action.id)
                    ConfirmAction.ClearInsights -> viewModel.clearInsights()
                    ConfirmAction.ClearMemories -> viewModel.clearMemories()
                }
                confirm = null
            },
        )
    }
}

private sealed interface ConfirmAction {
    data class DeleteInsight(val id: String) : ConfirmAction
    data object ClearInsights : ConfirmAction
    data object ClearMemories : ConfirmAction
}

@Composable
private fun MasterToggleCard(
    enabled: Boolean,
    toggling: Boolean,
    onChange: (Boolean) -> Unit,
) {
    val cs = androidx.compose.material3.MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(cs.surfaceVariant.copy(alpha = 0.5f))
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                Text("启用个性化记忆", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                Text(
                    if (enabled) "MolaGPT Tracks · 基于历史对话提供更连贯的回答"
                    else "已停用 · 不再存储新对话，也不使用历史记忆",
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = { onChange(it) },
                enabled = !toggling,
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
}

@Composable
private fun SectionHeader(title: String, trailing: @Composable (() -> Unit)? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 6.dp, top = 18.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = androidx.compose.material3.MaterialTheme.typography.titleSmall,
            color = androidx.compose.material3.MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
    }
}

@Composable
private fun InsightsOverview(insights: List<Insight>) {
    val core = insights.count { it.confidenceTier == ConfidenceTier.CORE }
    val known = insights.count { it.confidenceTier == ConfidenceTier.KNOWN }
    val vague = insights.size - core - known
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OverviewStat(core, "核心印象", CObrand, Modifier.weight(1f))
        OverviewStat(known, "初步了解", CblueT, Modifier.weight(1f))
        OverviewStat(vague, "模糊猜测", Cgray, Modifier.weight(1f))
    }
}

@Composable
private fun OverviewStat(count: Int, label: String, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(androidx.compose.material3.MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$count", style = androidx.compose.material3.MaterialTheme.typography.titleLarge, color = color, fontWeight = FontWeight.Bold)
            Text(label, style = androidx.compose.material3.MaterialTheme.typography.labelSmall, color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun InsightCard(
    insight: Insight,
    now: Long,
    rating: InsightRating?,
    onRate: (InsightRating) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val cs = androidx.compose.material3.MaterialTheme.colorScheme
    val tier = insight.confidenceTier
    val confColor = confidenceColor(tier)
    val status = insight.status(now)
    var evoOpen by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(cs.surfaceVariant.copy(alpha = 0.4f))
            .drawBehind { drawRect(color = confColor, size = Size(4.dp.toPx(), size.height)) },
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(start = 18.dp, top = 14.dp, end = 12.dp, bottom = 12.dp)) {
            // 头部：置信度 + 操作
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(9.dp).clip(RoundedCornerShape(50)).background(confColor))
                Spacer(Modifier.width(8.dp))
                Text(tier.label, style = androidx.compose.material3.MaterialTheme.typography.labelLarge, color = confColor, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(6.dp))
                Text("${(insight.confidence * 100).toInt()}%", style = androidx.compose.material3.MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onEdit, modifier = Modifier.size(34.dp)) {
                    Icon(Icons.Filled.Settings, contentDescription = "编辑", tint = cs.onSurfaceVariant, modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(34.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = "删除", tint = cs.onSurfaceVariant, modifier = Modifier.size(18.dp))
                }
            }

            Text(
                insight.text,
                style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                color = cs.onSurface,
                modifier = Modifier.padding(top = 8.dp, bottom = 10.dp),
            )

            // 标签：分类 + 状态 + 即将过期
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                InsightCategory.fromWire(insight.category)?.let { cat ->
                    TagChip(text = cat.label, color = categoryColor(cat), filled = true)
                }
                TagChip(text = status.label, color = statusColor(status), filled = true)
                if (status.nearExpiry && !insight.permanent) {
                    TagChip(text = "即将过期", color = Corange, filled = false)
                }
            }

            // 认同度评分
            RatingRow(selected = rating, onRate = onRate, modifier = Modifier.padding(top = 12.dp))

            // 时间线
            Timeline(insight, now, modifier = Modifier.padding(top = 10.dp))

            // 演化历程
            if (insight.evidence.isNotEmpty()) {
                val arrow by animateFloatAsState(if (evoOpen) 180f else 0f, label = "evoArrow")
                Row(
                    modifier = Modifier
                        .padding(top = 10.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { evoOpen = !evoOpen }
                        .padding(vertical = 4.dp, horizontal = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "演化历程 (${insight.evidence.size})",
                        style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                        color = cs.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Icon(
                        Icons.Filled.ArrowDropDown,
                        contentDescription = null,
                        tint = cs.primary,
                        modifier = Modifier.size(18.dp).rotate(arrow),
                    )
                }
                if (evoOpen) {
                    Column(modifier = Modifier.fillMaxWidth().fadeScaleIn(visible = true)) {
                        insight.evidence.asReversed().take(5).forEach { ev ->
                            Row(modifier = Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
                                Box(
                                    modifier = Modifier.padding(top = 5.dp, end = 8.dp).size(6.dp)
                                        .clip(RoundedCornerShape(50)).background(cs.outline),
                                )
                                Column(Modifier.weight(1f)) {
                                    Row {
                                        Text(ev.action.label, style = androidx.compose.material3.MaterialTheme.typography.labelMedium, color = cs.onSurface, fontWeight = FontWeight.SemiBold)
                                        Spacer(Modifier.weight(1f))
                                        Text(relativeDays(ev.ts, now), style = androidx.compose.material3.MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                                    }
                                    ev.evidence?.takeIf { it.isNotBlank() }?.let {
                                        Text(it, style = androidx.compose.material3.MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 1.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TagChip(text: String, color: Color, filled: Boolean) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(color.copy(alpha = if (filled) 0.14f else 0.10f))
            .border(1.dp, color.copy(alpha = 0.4f), RoundedCornerShape(9.dp))
            .padding(horizontal = 9.dp, vertical = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(6.dp).clip(RoundedCornerShape(50)).background(color))
            Spacer(Modifier.width(5.dp))
            Text(text, style = androidx.compose.material3.MaterialTheme.typography.labelSmall, color = color, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun RatingRow(selected: InsightRating?, onRate: (InsightRating) -> Unit, modifier: Modifier = Modifier) {
    val cs = androidx.compose.material3.MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(cs.surface)
            .padding(10.dp),
    ) {
        Text("这条印象准确吗？", style = androidx.compose.material3.MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            InsightRating.entries.forEach { r ->
                val sel = selected == r
                val accent = if (r.positive) Cgreen else Cred
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (sel) accent.copy(alpha = 0.16f) else Color.Transparent)
                        .border(1.dp, if (sel) accent else cs.outline.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
                        .clickable { onRate(r) }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Text(
                        r.label,
                        style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                        color = if (sel) accent else cs.onSurfaceVariant,
                        fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
        }
    }
}

@Composable
private fun Timeline(insight: Insight, now: Long, modifier: Modifier = Modifier) {
    val cs = androidx.compose.material3.MaterialTheme.colorScheme
    FlowRow(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        if (insight.createdTs > 0) {
            Text("创建于 ${relativeDays(insight.createdTs, now)}", style = androidx.compose.material3.MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
        }
        if (insight.lastReinforcedTs > insight.createdTs && insight.lastReinforcedTs > 0) {
            Text("${relativeDays(insight.lastReinforcedTs, now)}强化", style = androidx.compose.material3.MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
        }
        if (insight.sourceConversationCount > 0) {
            Text("基于 ${insight.sourceConversationCount} 次对话", style = androidx.compose.material3.MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
        }
    }
}

@Composable
private fun StyleSection(
    style: com.molagpt.app.core.model.StylePreferences,
    dirty: Boolean,
    saving: Boolean,
    onToggle: (ConversationStyle) -> Unit,
    onCustom: (String) -> Unit,
    onSave: () -> Unit,
) {
    val cs = androidx.compose.material3.MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(cs.surfaceVariant.copy(alpha = 0.4f))
            .padding(16.dp),
    ) {
        Text("选择你喜欢的风格（可多选）", style = androidx.compose.material3.MaterialTheme.typography.bodyMedium, color = cs.onSurface)
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ConversationStyle.entries.forEach { s ->
                val selected = style.hasStyle(s)
                FilterChip(
                    selected = selected,
                    onClick = { onToggle(s) },
                    label = { Text(s.label) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = cs.primary.copy(alpha = 0.16f),
                        selectedLabelColor = cs.primary,
                    ),
                )
            }
        }
        Text("自定义指令（可选）", style = androidx.compose.material3.MaterialTheme.typography.bodyMedium, color = cs.onSurface, modifier = Modifier.padding(top = 14.dp, bottom = 8.dp))
        OutlinedTextField(
            value = style.customInstruction,
            onValueChange = onCustom,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("例如：希望你回答更直接，先给结论再展开。") },
            minLines = 3,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
            supportingText = {
                Text(
                    "${style.customInstruction.length}/${com.molagpt.app.core.model.StylePreferences.CUSTOM_INSTRUCTION_MAX} · 影响后续回复的语气与组织方式",
                    style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                )
            },
        )
        Button(
            onClick = onSave,
            enabled = dirty && !saving,
            modifier = Modifier.align(Alignment.End).padding(top = 10.dp),
        ) {
            if (saving) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = cs.onPrimary)
                Spacer(Modifier.width(8.dp))
            }
            Text(if (saving) "保存中…" else "保存风格")
        }
    }
}

@Composable
private fun DangerZone(onClearInsights: () -> Unit, onClearMemories: () -> Unit) {
    val cs = androidx.compose.material3.MaterialTheme.colorScheme
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
            Text("数据清除", style = androidx.compose.material3.MaterialTheme.typography.titleSmall, color = cs.error, fontWeight = FontWeight.SemiBold)
        }
        Text(
            "以下操作将永久删除服务器上的数据，删除后无法恢复，请谨慎操作。",
            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
            color = cs.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp, bottom = 6.dp),
        )
        DangerButton("清除所有人格洞察", "上方展示的画像（短期记忆）", onClearInsights)
        DangerButton("清除所有对话记忆", "用于检索的历史片段（长期记忆）", onClearMemories)
    }
}

@Composable
private fun DangerButton(title: String, subtitle: String, onClick: () -> Unit) {
    val cs = androidx.compose.material3.MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .clip(RoundedCornerShape(13.dp))
            .border(1.dp, cs.outline, RoundedCornerShape(13.dp))
            .clickable(onClick = onClick)
            .padding(13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Delete, contentDescription = null, tint = cs.error, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = androidx.compose.material3.MaterialTheme.typography.bodyMedium, color = cs.onSurface)
            Text(subtitle, style = androidx.compose.material3.MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

@Composable
private fun EmptyInsights() {
    val cs = androidx.compose.material3.MaterialTheme.colorScheme
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 36.dp, horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier.size(60.dp).clip(RoundedCornerShape(18.dp)).background(cs.primary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Text("Aa", color = cs.primary, fontWeight = FontWeight.Bold, style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
        }
        Text("还没有形成印象", style = androidx.compose.material3.MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 14.dp))
        Text(
            "多与 MolaGPT 对话，它会在后台自动分析，逐渐了解你的特点与偏好。",
            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
            color = cs.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@Composable
private fun LoadingInsights() {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        repeat(3) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(96.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .shimmer(),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PrivacySheet(onDismiss: () -> Unit) {
    val cs = androidx.compose.material3.MaterialTheme.colorScheme
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState())) {
            Text("个性化记忆 · 隐私说明", style = androidx.compose.material3.MaterialTheme.typography.titleLarge)
            PrivacyBlock("功能概述", "通过安全地存储和分析你的对话，让 MolaGPT 记住关键信息、理解你的偏好，提供更连贯贴心的回答，并在多设备间无缝衔接。")
            PrivacyBlock("学习机制", "对话以加密形式存储；提问时检索最相关历史片段（事件记忆）；定期异步分析提炼高级洞察（人格理解）；二者融合生成贴合你风格的回复。")
            PrivacyBlock("你的控制权", "随时启停总开关；每条画像可评分、修正或单独删除；可一键清空服务器上的全部洞察与对话记忆。")
            Box(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(13.dp)).background(cs.primary.copy(alpha = 0.1f)).padding(13.dp),
            ) {
                Text(
                    "隐私承诺：严格遵循「数据最小化」与「用户可控」原则，采用行业标准安全措施保护你的对话隐私。",
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    color = cs.onSurface,
                )
            }
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End).padding(top = 8.dp)) { Text("关闭") }
        }
    }
}

@Composable
private fun PrivacyBlock(title: String, body: String) {
    Column(modifier = Modifier.padding(top = 16.dp)) {
        Text(title, style = androidx.compose.material3.MaterialTheme.typography.titleSmall, color = androidx.compose.material3.MaterialTheme.colorScheme.primary)
        Text(body, style = androidx.compose.material3.MaterialTheme.typography.bodySmall, color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 5.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditInsightSheet(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text("调整印象", style = androidx.compose.material3.MaterialTheme.typography.titleLarge)
            Text(
                "若你觉得 MolaGPT 理解得不够准确，可在此修正。修改后它会按新的描述调整对话风格。",
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp, bottom = 12.dp),
            )
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("请输入你认为更准确的印象描述…") },
                minLines = 3,
            )
            Row(modifier = Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("取消") }
                Button(onClick = { onSave(text) }, enabled = text.isNotBlank(), modifier = Modifier.weight(1f)) { Text("保存修改") }
            }
        }
    }
}

@Composable
private fun ConfirmDialog(action: ConfirmAction, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val (title, msg) = when (action) {
        is ConfirmAction.DeleteInsight -> "删除此印象" to "确定删除这条画像吗？删除后将不再基于此信息调整回复风格。"
        ConfirmAction.ClearInsights -> "清除所有人格洞察" to "将永久删除上方全部画像（短期记忆），此操作不可撤销。"
        ConfirmAction.ClearMemories -> "清除所有对话记忆" to "将永久删除用于检索的全部历史对话片段（长期记忆），此操作不可撤销。"
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(msg) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("确认删除", color = androidx.compose.material3.MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 刷新按钮：刷新中持续旋转（复用 animation.core 的无限过渡）。InfiniteTransition 无条件创建，仅按状态决定是否应用旋转角。 */
@Composable
private fun RefreshButton(spinning: Boolean, onClick: () -> Unit) {
    val cs = androidx.compose.material3.MaterialTheme.colorScheme
    val transition = rememberInfiniteTransition(label = "refresh")
    val spin by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(800, easing = LinearEasing)),
        label = "refreshAngle",
    )
    TextButton(onClick = onClick, enabled = !spinning) {
        RefreshGlyph(color = cs.primary, modifier = Modifier.size(16.dp).rotate(if (spinning) spin else 0f))
        Spacer(Modifier.width(6.dp))
        Text(if (spinning) "刷新中…" else "刷新")
    }
}

/** 自绘环形箭头（material-icons-core 无 Refresh，用 Canvas 画 3/4 圆弧 + 箭头）。 */
@Composable
private fun RefreshGlyph(color: Color, modifier: Modifier = Modifier) {
    androidx.compose.foundation.Canvas(modifier = modifier) {
        val sw = size.minDimension * 0.12f
        val pad = sw
        drawArc(
            color = color,
            startAngle = -60f,
            sweepAngle = 300f,
            useCenter = false,
            topLeft = Offset(pad, pad),
            size = Size(size.width - pad * 2, size.height - pad * 2),
            style = Stroke(width = sw, cap = StrokeCap.Round),
        )
        // 箭头：弧线起点处一个小三角
        val r = (size.minDimension - pad * 2) / 2f
        val cx = size.width / 2f
        val cy = size.height / 2f
        val tip = Offset(cx + r, cy)
        val s = size.minDimension * 0.18f
        drawLine(color, tip, Offset(tip.x - s, tip.y - s), strokeWidth = sw, cap = StrokeCap.Round)
        drawLine(color, tip, Offset(tip.x + s * 0.4f, tip.y - s), strokeWidth = sw, cap = StrokeCap.Round)
    }
}

/** 粗粒度相对天数（unix 秒）。 */
private fun relativeDays(ts: Long, nowSeconds: Long): String {
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
