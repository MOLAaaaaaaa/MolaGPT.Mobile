package com.molagpt.app.feature.settings

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
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
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.molagpt.app.core.model.ConfidenceTier
import com.molagpt.app.core.model.ConversationStyle
import com.molagpt.app.core.model.InsightCategory
import com.molagpt.app.core.model.MemoryCandidate
import com.molagpt.app.core.model.MemoryEntry
import com.molagpt.app.core.model.MemoryProjection
import com.molagpt.app.core.model.MemoryRating
import com.molagpt.app.core.model.MemorySection
import com.molagpt.app.core.model.MemoryStatus
import com.molagpt.app.core.render.ImeDismissBackHandler
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

// 记忆条目会持续增长；默认只展示少量高权重项，保证后续模块无需长距离滚动也能到达。
private const val COLLAPSED_ENTRY_COUNT = 6

/** 服务端 add/update 的文本长度上限。 */
private const val MEMORY_TEXT_MAX = 300

private fun confidenceColor(t: ConfidenceTier): Color = when (t) {
    ConfidenceTier.CORE -> CObrand
    ConfidenceTier.KNOWN -> CblueT
    ConfidenceTier.VAGUE -> Cgray
}

private fun statusColor(s: MemoryStatus): Color = when (s) {
    MemoryStatus.ACTIVE, MemoryStatus.GROWING -> Cgreen
    MemoryStatus.STABLE -> Ccyan
    MemoryStatus.FADING -> Corange
    MemoryStatus.WEAK -> Cgray
    MemoryStatus.QUESTIONED -> Cred
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
    InsightCategory.EXPLICIT_INSTRUCTION -> Cred
}

private fun ratingColor(r: MemoryRating): Color = when (r) {
    MemoryRating.AGREE -> Cgreen
    MemoryRating.DOUBT -> Corange
    MemoryRating.REJECT -> Cred
}

/**
 * 记忆中心。从「设置 → MolaGPT 账户 → 管理个性化回答」进入。
 *
 * 展示服务端夜间「做梦管线」维护的长期记忆：待确认候选（用户裁决）→ 记忆条目（按分节分组）
 * → 对话风格 → 数据清除。条目最终投影成 MEMORY.md 注入 system prompt，故顶部给出 token 预算占用。
 *
 * 本页不单独注册返回拦截，返回行为交给 NavHost 统一处理；顶栏返回箭头仅调 [onBack]。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonalizationScreen(
    viewModel: PersonalizationViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val enabled by viewModel.enabled.collectAsStateWithLifecycle()
    val sections by viewModel.entriesBySection.collectAsStateWithLifecycle()
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    val candidates by viewModel.candidates.collectAsStateWithLifecycle()
    val projection by viewModel.projection.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    val togglingMaster by viewModel.togglingMaster.collectAsStateWithLifecycle()
    val addingEntry by viewModel.addingEntry.collectAsStateWithLifecycle()
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
    var editing by remember { mutableStateOf<MemoryEntry?>(null) }
    var adding by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<ConfirmAction?>(null) }
    var entriesExpanded by rememberSaveable { mutableStateOf(false) }

    // 键盘弹着时返回先收键盘，不退页面（三星等未启用预测式返回的机型会穿透到 NavHost）。
    ImeDismissBackHandler()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.systemBars.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
        topBar = {
            TopAppBar(
                title = { Text("记忆中心") },
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

            // —— 待确认候选：夜间管线抽出但证据不足，交给用户裁决 ——
            if (candidates.isNotEmpty()) {
                SectionHeader(title = "待确认 · ${candidates.size}")
                candidates.forEach { c ->
                    CandidateCard(
                        candidate = c,
                        now = now,
                        onAccept = { viewModel.acceptCandidate(c) },
                        onDismiss = { viewModel.dismissCandidate(c.id) },
                    )
                }
            }

            // —— 记忆条目 ——
            SectionHeader(
                title = "记忆条目",
                trailing = { RefreshButton(spinning = refreshing, onClick = viewModel::refresh) },
            )
            when {
                loading -> LoadingEntries()
                entries.isEmpty() -> EmptyEntries()
                else -> {
                    ProjectionRow(projection)
                    // 折叠时按分节顺序取前 N 条，展开后全量分组展示。
                    val visibleSections = if (entriesExpanded) {
                        sections
                    } else {
                        var budget = COLLAPSED_ENTRY_COUNT
                        sections.mapNotNull { (section, list) ->
                            if (budget <= 0) return@mapNotNull null
                            val take = list.take(budget)
                            budget -= take.size
                            section to take
                        }
                    }
                    visibleSections.forEach { (section, list) ->
                        SectionDivider(section.label, list.size)
                        list.forEach { entry ->
                            MemoryEntryCard(
                                entry = entry,
                                now = now,
                                onRate = { r ->
                                    // 再次点击已选中项 = 撤销评分。
                                    viewModel.rateEntry(entry.id, if (entry.userRating == r) null else r)
                                },
                                onEdit = { editing = entry },
                                onDelete = { confirm = ConfirmAction.DeleteEntry(entry.id) },
                            )
                        }
                    }
                    if (entries.size > COLLAPSED_ENTRY_COUNT) {
                        TextButton(
                            onClick = { entriesExpanded = !entriesExpanded },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                if (entriesExpanded) "收起记忆条目" else "查看全部 ${entries.size} 条记忆",
                            )
                        }
                    }
                }
            }
            AddMemoryButton(enabled = !loading && !addingEntry, onClick = { adding = true })

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
            DangerZone(onClearAll = { confirm = ConfirmAction.ClearAll })

            Text(
                "记忆由 MolaGPT 在夜间自动整理生成，可能不完全准确；你可随时评分、修正、删除或自行添加，帮助它更懂你。",
                style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
            )
        }
    }

    if (showPrivacy) PrivacySheet(onDismiss = { showPrivacy = false })

    editing?.let { entry ->
        EditMemorySheet(
            initial = entry.text,
            onDismiss = { editing = null },
            onSave = { viewModel.updateEntry(entry.id, it); editing = null },
        )
    }

    if (adding) {
        AddMemorySheet(
            onDismiss = { adding = false },
            onSave = { text, section -> viewModel.addEntry(text, section); adding = false },
        )
    }

    confirm?.let { action ->
        ConfirmDialog(
            action = action,
            onDismiss = { confirm = null },
            onConfirm = {
                when (action) {
                    is ConfirmAction.DeleteEntry -> viewModel.deleteEntry(action.id)
                    ConfirmAction.ClearAll -> viewModel.clearAllMemories()
                }
                confirm = null
            },
        )
    }
}

private sealed interface ConfirmAction {
    data class DeleteEntry(val id: String) : ConfirmAction
    data object ClearAll : ConfirmAction
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
            .padding(top = 10.dp, bottom = 6.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(cs.primary.copy(alpha = 0.08f))
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "个性化记忆",
                    style = androidx.compose.material3.MaterialTheme.typography.titleSmall,
                    color = cs.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    if (enabled) "MolaGPT 会记住关键信息，让回答更贴合你" else "已关闭 · 不再学习与使用长期记忆",
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (toggling) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
            }
            Switch(
                checked = enabled,
                onCheckedChange = onChange,
                enabled = !toggling,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = cs.primary,
                ),
            )
        }
    }
}

@Composable
private fun SectionHeader(title: String, trailing: @Composable (() -> Unit)? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = androidx.compose.material3.MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.weight(1f))
        trailing?.invoke()
    }
}

/** 分节小标题：记忆按服务端固定的 5 个分节归类，MEMORY.md 也按此顺序渲染。 */
@Composable
private fun SectionDivider(label: String, count: Int) {
    val cs = androidx.compose.material3.MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(4.dp).clip(RoundedCornerShape(50)).background(cs.primary))
        Spacer(Modifier.width(7.dp))
        Text(
            label,
            style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
            color = cs.primary,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            "$count",
            style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
            color = cs.onSurfaceVariant,
        )
    }
}

/**
 * MEMORY.md 投影占用。服务端按 token 预算裁剪：超预算的条目**不会进入** system prompt，
 * 所以 `skipped > 0` 必须显式告知——否则用户以为列表里的每条都在生效。
 */
@Composable
private fun ProjectionRow(projection: MemoryProjection) {
    if (projection.budget <= 0) return
    val cs = androidx.compose.material3.MaterialTheme.colorScheme
    val over = projection.skipped > 0
    Column(modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "已注入 ${projection.entries} 条",
                style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                color = cs.onSurface,
            )
            Spacer(Modifier.weight(1f))
            Text(
                "${projection.tokens} / ${projection.budget} tokens",
                style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                color = if (over) Corange else cs.onSurfaceVariant,
            )
        }
        LinearProgressIndicator(
            progress = { projection.usage },
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(50)),
            color = if (over) Corange else cs.primary,
            trackColor = cs.surfaceVariant,
        )
        if (over) {
            Text(
                "${projection.skipped} 条因超出预算未注入 · 删除或降低低价值记忆可让其生效",
                style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                color = Corange,
                modifier = Modifier.padding(top = 5.dp),
            )
        }
    }
}

/**
 * 待确认候选卡。
 *
 * [MemoryCandidate.text] 是 LLM 规范化后的第三人称事实，[MemoryCandidate.quote] 是用户逐字原话；
 * 两者都要展示——只看改写后的文本，用户无法确认自己是否真说过这句。
 */
@Composable
private fun CandidateCard(
    candidate: MemoryCandidate,
    now: Long,
    onAccept: () -> Unit,
    onDismiss: () -> Unit,
) {
    val cs = androidx.compose.material3.MaterialTheme.colorScheme
    // 底色与边框二选一：primary 底色已经把候选与下方的记忆条目卡区分开，无需再描边。
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(cs.primary.copy(alpha = 0.07f))
            .padding(16.dp),
    ) {
        Text(candidate.text, style = androidx.compose.material3.MaterialTheme.typography.bodyMedium, color = cs.onSurface)

        candidate.quote?.let { quote ->
            Row(modifier = Modifier.fillMaxWidth().padding(top = 9.dp)) {
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .height(if (quote.length > 40) 34.dp else 17.dp)
                        .clip(RoundedCornerShape(50))
                        .background(cs.outline.copy(alpha = 0.5f)),
                )
                Text(
                    "「$quote」",
                    style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                    color = cs.onSurfaceVariant,
                    fontStyle = FontStyle.Italic,
                    modifier = Modifier.padding(start = 8.dp).weight(1f),
                )
            }
        }

        Row(modifier = Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${candidate.section.label} · ${relativeDays(candidate.observedTs, now)}",
                style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                color = cs.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onDismiss) { Text("忽略", color = cs.onSurfaceVariant) }
            Spacer(Modifier.width(4.dp))
            Button(onClick = onAccept) { Text("记住") }
        }
    }
}

@Composable
private fun MemoryEntryCard(
    entry: MemoryEntry,
    now: Long,
    onRate: (MemoryRating) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val cs = androidx.compose.material3.MaterialTheme.colorScheme
    val tier = entry.confidenceTier
    val confColor = confidenceColor(tier)
    val status = entry.status(now)

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
                Text("${(entry.confidence * 100).toInt()}%", style = androidx.compose.material3.MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onEdit, modifier = Modifier.size(34.dp)) {
                    Icon(Icons.Filled.Settings, contentDescription = "编辑", tint = cs.onSurfaceVariant, modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(34.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = "删除", tint = cs.onSurfaceVariant, modifier = Modifier.size(18.dp))
                }
            }

            Text(
                entry.text,
                style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                color = cs.onSurface,
                modifier = Modifier.padding(top = 8.dp, bottom = 10.dp),
            )

            // 标签：分类 + 状态 + 长期 / 手动 / 已过期
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                InsightCategory.fromWire(entry.category)?.let { cat ->
                    TagChip(text = cat.label, color = categoryColor(cat), filled = true)
                }
                TagChip(text = status.label, color = statusColor(status), filled = true)
                if (entry.permanent) TagChip(text = "长期", color = CblueT, filled = false)
                if (entry.userSet) TagChip(text = "手动添加", color = Cpurple, filled = false)
                if (entry.isExpired(now)) {
                    TagChip(text = "已过期", color = Cgray, filled = false)
                } else if (status.nearExpiry && !entry.permanent) {
                    TagChip(text = "即将过期", color = Corange, filled = false)
                }
            }

            RatingRow(selected = entry.userRating, onRate = onRate, modifier = Modifier.padding(top = 12.dp))

            SourceRow(entry, now, modifier = Modifier.padding(top = 10.dp))
        }
    }
}

@Composable
private fun TagChip(text: String, color: Color, filled: Boolean) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(color.copy(alpha = if (filled) 0.14f else 0.10f))
            .padding(horizontal = 9.dp, vertical = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(6.dp).clip(RoundedCornerShape(50)).background(color))
            Spacer(Modifier.width(5.dp))
            Text(text, style = androidx.compose.material3.MaterialTheme.typography.labelSmall, color = color, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** 3 档评分。再次点击已选中项 = 撤销（由调用方转成 null）。 */
@Composable
private fun RatingRow(selected: MemoryRating?, onRate: (MemoryRating) -> Unit, modifier: Modifier = Modifier) {
    val cs = androidx.compose.material3.MaterialTheme.colorScheme
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            if (selected == null) "这条记忆准确吗？" else "已评分 · 再次点击可撤销",
            style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
            color = cs.onSurfaceVariant,
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            MemoryRating.entries.forEach { r ->
                val sel = selected == r
                val accent = ratingColor(r)
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (sel) accent.copy(alpha = 0.16f) else Color.Transparent)
                        .border(1.dp, if (sel) accent else cs.outline.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
                        .clickable { onRate(r) }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
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

/** 溯源行：这条记忆从哪来、被观察过几次、多久没被强化。 */
@Composable
private fun SourceRow(entry: MemoryEntry, now: Long, modifier: Modifier = Modifier) {
    val cs = androidx.compose.material3.MaterialTheme.colorScheme
    val style = androidx.compose.material3.MaterialTheme.typography.labelSmall
    FlowRow(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        if (entry.sources.isNotEmpty()) {
            Text("来自 ${entry.sources.size} 次对话", style = style, color = cs.onSurfaceVariant)
        }
        if (entry.recurrence > 1) {
            Text("提及 ${entry.recurrence} 次", style = style, color = cs.onSurfaceVariant)
        }
        val ref = if (entry.lastTs > 0) entry.lastTs else entry.createdTs
        if (ref > 0) {
            Text("${relativeDays(ref, now)}更新", style = style, color = cs.onSurfaceVariant)
        }
    }
}

@Composable
private fun AddMemoryButton(enabled: Boolean, onClick: () -> Unit) {
    val cs = androidx.compose.material3.MaterialTheme.colorScheme
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
        Text("添加记忆", style = androidx.compose.material3.MaterialTheme.typography.bodyMedium, color = cs.primary)
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

/**
 * 数据清除。
 *
 * 只保留一个按钮：服务端已把「人格洞察」与「事件记忆」合并成统一的长期记忆，
 * 两个旧 action 现在指向同一处理器，再摆两个按钮会让用户以为能分别清除。
 */
@Composable
private fun DangerZone(onClearAll: () -> Unit) {
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
        DangerButton("清除全部记忆", "上方所有记忆条目与主题档案", onClearAll)
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
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp),
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
private fun EmptyEntries() {
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
        Text("还没有形成记忆", style = androidx.compose.material3.MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 14.dp))
        Text(
            "多与 MolaGPT 对话，它会在夜间自动整理，逐渐了解你的特点与偏好；也可以直接添加你想让它记住的事。",
            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
            color = cs.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@Composable
private fun LoadingEntries() {
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
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        // 自带 inset 置 0，底部留白只由内容的 navigationBarsPadding 处理一次（否则全屏展开时底部多一条白条把内容顶出屏幕）。
        contentWindowInsets = { WindowInsets(0) },
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            Text("记忆中心 · 隐私说明", style = androidx.compose.material3.MaterialTheme.typography.titleLarge)
            PrivacyBlock("功能概述", "通过安全地存储和分析你的对话，让 MolaGPT 记住关键信息、理解你的偏好，提供更连贯贴心的回答，并在多设备间无缝衔接。")
            PrivacyBlock(
                "学习机制",
                "每晚分三步：先从你的消息中抽取候选事实，再聚类反思、与既有记忆比对后写入长期记忆，每周还会做一次全库整理（合并近义、清理过期）。" +
                    "只从你说的话里学习，不会把 AI 的回复当成事实。",
            )
            PrivacyBlock(
                "记忆如何生效",
                "记忆按分节整理成一份档案，随每次提问一并提供给模型；受 token 预算限制，超出部分不会注入。" +
                    "长尾细节则由模型按需检索。",
            )
            PrivacyBlock("你的控制权", "随时启停总开关；证据不足的事实会先问过你再记；每条记忆可评分、修正或单独删除；也可一键清空服务器上的全部记忆。")
            Box(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(13.dp)).background(cs.primary.copy(alpha = 0.1f)).padding(13.dp),
            ) {
                Text(
                    "隐私承诺：严格遵循「数据最小化」与「用户可控」原则，健康、信仰等敏感信息不会进入长期记忆。",
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
private fun EditMemorySheet(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        contentWindowInsets = { WindowInsets(0) },
    ) {
        // 键盘弹着时返回先收键盘，不关弹层（丢输入内容）。
        ImeDismissBackHandler()
        Column(modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp).navigationBarsPadding().imePadding()) {
            Text("调整记忆", style = androidx.compose.material3.MaterialTheme.typography.titleLarge)
            Text(
                "若你觉得 MolaGPT 记得不够准确，可在此修正。修改后它会按新的描述调整回答。",
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp, bottom = 12.dp),
            )
            OutlinedTextField(
                value = text,
                onValueChange = { if (it.length <= MEMORY_TEXT_MAX) text = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("请输入你认为更准确的描述…") },
                minLines = 3,
                supportingText = {
                    Text("${text.length}/$MEMORY_TEXT_MAX", style = androidx.compose.material3.MaterialTheme.typography.labelSmall)
                },
            )
            Row(modifier = Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("取消") }
                Button(onClick = { onSave(text) }, enabled = text.isNotBlank(), modifier = Modifier.weight(1f)) { Text("保存修改") }
            }
        }
    }
}

/** 手动添加记忆。分节由用户选（服务端可能按内容改判并回落到推导值）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddMemorySheet(onDismiss: () -> Unit, onSave: (String, MemorySection) -> Unit) {
    var text by remember { mutableStateOf("") }
    var section by remember { mutableStateOf(MemorySection.CONTEXT) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        contentWindowInsets = { WindowInsets(0) },
    ) {
        ImeDismissBackHandler()
        Column(modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp).navigationBarsPadding().imePadding()) {
            Text("添加记忆", style = androidx.compose.material3.MaterialTheme.typography.titleLarge)
            Text(
                "写下你希望 MolaGPT 长期记住的事。它会被整理成简洁的事实并在后续对话中生效。",
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp, bottom = 12.dp),
            )
            OutlinedTextField(
                value = text,
                onValueChange = { if (it.length <= MEMORY_TEXT_MAX) text = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("例如：我在做一个 Android 记忆管理应用。") },
                minLines = 3,
                supportingText = {
                    Text("${text.length}/$MEMORY_TEXT_MAX", style = androidx.compose.material3.MaterialTheme.typography.labelSmall)
                },
            )
            Text(
                "归入分节",
                style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 12.dp, bottom = 8.dp),
            )
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MemorySection.entries.forEach { s ->
                    FilterChip(
                        selected = section == s,
                        onClick = { section = s },
                        label = { Text(s.label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = androidx.compose.material3.MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                            selectedLabelColor = androidx.compose.material3.MaterialTheme.colorScheme.primary,
                        ),
                    )
                }
            }
            Row(modifier = Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("取消") }
                Button(onClick = { onSave(text, section) }, enabled = text.isNotBlank(), modifier = Modifier.weight(1f)) { Text("添加") }
            }
        }
    }
}

@Composable
private fun ConfirmDialog(action: ConfirmAction, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val (title, msg) = when (action) {
        is ConfirmAction.DeleteEntry -> "删除此记忆" to "确定删除这条记忆吗？删除后 MolaGPT 将不再基于它回答，且不会再次学习到相同内容。"
        ConfirmAction.ClearAll -> "清除全部记忆" to "将永久删除服务器上的全部长期记忆与主题档案，此操作不可撤销。"
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
        Icon(
            imageVector = Icons.Filled.Refresh,
            contentDescription = null,
            tint = cs.primary,
            modifier = Modifier.size(16.dp).rotate(if (spinning) spin else 0f),
        )
        Spacer(Modifier.width(6.dp))
        Text(if (spinning) "刷新中…" else "刷新")
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
