package com.molagpt.app.feature.settings

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
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.molagpt.app.core.model.ByokMemoryCandidate
import com.molagpt.app.core.model.ByokMemoryEntry
import com.molagpt.app.core.model.ByokMemoryOrigin
import com.molagpt.app.core.model.ByokProfileKey
import com.molagpt.app.core.model.ConfidenceTier
import com.molagpt.app.core.model.MemorySection
import com.molagpt.app.core.storage.ByokMemoryProjector
import com.molagpt.app.core.render.ImeDismissBackHandler

/**
 * BYOK 本地记忆页。从「设置 → 自定义模型 → 本地记忆」进入。
 *
 * 与服务端记忆中心的区别只有一句话：这里的数据在本机，但**开启后会随对话发给用户自己配置的 API 服务**。
 * 顶部的数据边界卡常驻，不做成可关闭的提示——「本地」两个字容易被读成「不出设备」。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ByokMemoryScreen(
    viewModel: ByokMemoryViewModel,
    onBack: () -> Unit,
    onOpenByokProviders: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val switches by viewModel.switches.collectAsStateWithLifecycle()
    val sections by viewModel.entriesBySection.collectAsStateWithLifecycle()
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    val candidates by viewModel.candidates.collectAsStateWithLifecycle()
    val projection by viewModel.projection.collectAsStateWithLifecycle()
    val profile by viewModel.profileFields.collectAsStateWithLifecycle()
    val modelOptions by viewModel.modelOptions.collectAsStateWithLifecycle()
    val autoLearnBlocked by viewModel.autoLearnBlocked.collectAsStateWithLifecycle()
    val consolidating by viewModel.consolidating.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(message) {
        message?.let { snackbar.showSnackbar(it); viewModel.clearMessage() }
    }

    val now = remember(entries) { System.currentTimeMillis() }
    var editing by remember { mutableStateOf<ByokMemoryEntry?>(null) }
    var adding by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var entriesExpanded by rememberSaveable { mutableStateOf(false) }

    ImeDismissBackHandler()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.systemBars.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
        topBar = {
            TopAppBar(
                title = { Text("本地记忆") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
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
            DataBoundaryCard()

            SwitchesCard(
                switches = switches,
                onMaster = viewModel::setMasterEnabled,
                onMemory = viewModel::setMemoryEnabled,
                onRecall = viewModel::setRecallEnabled,
                onAutoLearn = viewModel::setAutoLearn,
                onAllowSensitive = viewModel::setAllowSensitive,
            )

            if (switches.masterEnabled && switches.autoLearn) {
                MemoryModelCard(
                    modelKey = switches.modelKey,
                    blocked = autoLearnBlocked,
                    options = modelOptions,
                    onChange = viewModel::setModelKey,
                    onOpenByokProviders = onOpenByokProviders,
                )
                ScanScopeCard(
                    fullScan = switches.fullScan,
                    onChange = viewModel::setFullScan,
                )
                ConsolidateCard(
                    lastAt = switches.lastConsolidatedAt,
                    running = consolidating,
                    enabled = !autoLearnBlocked,
                    onRun = viewModel::consolidateNow,
                )
            }

            if (profile.isNotEmpty()) {
                SectionHeader(title = "个人信息")
                ProfileCard(fields = profile, onSetName = viewModel::setPreferredName)
            }

            if (candidates.isNotEmpty()) {
                SectionHeader(title = "待确认 · ${candidates.size}")
                candidates.take(MAX_CANDIDATES_SHOWN).forEach { candidate ->
                    ByokCandidateCard(
                        candidate = candidate,
                        now = now,
                        onAccept = { viewModel.confirmCandidate(candidate.id) },
                        onDismiss = { viewModel.dismissCandidate(candidate.id) },
                    )
                }
            }

            SectionHeader(title = "记忆条目")
            BudgetCard(
                budgetTokens = switches.budgetTokens,
                onChange = viewModel::setBudgetTokens,
            )
            if (switches.masterEnabled && switches.memoryEnabled) {
                MemoryProjectionRow(
                    injected = projection.injected.size,
                    skipped = projection.skipped,
                    tokens = projection.tokens,
                    budget = projection.budget,
                    overflowHint = "另有 ${projection.skipped} 条暂未使用",
                )
            }

            if (sections.isEmpty()) {
                EmptyMemory()
            } else {
                val visibleSections = if (entriesExpanded) {
                    sections
                } else {
                    var budget = COLLAPSED_MEMORY_ENTRY_COUNT
                    sections.mapNotNull { (section, list) ->
                        if (budget <= 0) return@mapNotNull null
                        val visible = list.take(budget)
                        budget -= visible.size
                        section to visible
                    }
                }
                visibleSections.forEach { (section, list) ->
                    SectionDivider(section.label, list.size)
                    list.forEach { entry ->
                        ByokMemoryEntryCard(
                            entry = entry,
                            now = now,
                            onEdit = { editing = entry },
                            onDelete = { viewModel.deleteEntry(entry.id) },
                        )
                    }
                }
                if (entries.size > COLLAPSED_MEMORY_ENTRY_COUNT) {
                    TextButton(
                        onClick = { entriesExpanded = !entriesExpanded },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (entriesExpanded) "收起记忆条目" else "查看全部 ${entries.size} 条记忆")
                    }
                }
            }

            AddMemoryRow(onClick = { adding = true })

            ClearAllCard(onClearAll = { confirmClear = true })

            Spacer(Modifier.size(24.dp))
        }
    }

    editing?.let { entry ->
        EditMemorySheet(
            initialText = entry.text,
            initialSection = entry.section,
            initialProfileKey = entry.profileKey,
            onDismiss = { editing = null },
            onSave = { text, section, key ->
                viewModel.updateEntry(entry.id, text, section, key)
                editing = null
            },
        )
    }

    if (adding) {
        EditMemorySheet(
            initialText = "",
            initialSection = MemorySection.IDENTITY,
            initialProfileKey = null,
            onDismiss = { adding = false },
            onSave = { text, section, key ->
                viewModel.addEntry(text, section, key)
                adding = false
            },
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清除全部记忆") },
            text = { Text("所有记忆、待确认内容和来源记录都将删除。此操作无法撤销。") },
            confirmButton = {
                TextButton(onClick = { viewModel.clearAll(); confirmClear = false }) {
                    Text("清除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } },
        )
    }
}

private const val MAX_CANDIDATES_SHOWN = 6

/**
 * 数据边界。只写「本地」会误导：条目一旦启用就会离开设备，
 * 用户需要在开开关之前就知道这件事。
 */
@Composable
private fun DataBoundaryCard() {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.07f),
    ) {
        Text(
            "记忆保存在本机。使用记忆或回忆对话时，相关内容会发送给您配置的模型服务。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Composable
private fun SwitchesCard(
    switches: ByokMemoryViewModel.Switches,
    onMaster: (Boolean) -> Unit,
    onMemory: (Boolean) -> Unit,
    onRecall: (Boolean) -> Unit,
    onAutoLearn: (Boolean) -> Unit,
    onAllowSensitive: (Boolean) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.30f),
    ) {
        // 「记忆」是总开关，下面三项是平级的子功能，各管一段：读、检索、写。
        // 关掉总开关却留着「主动回忆」可用，等于关了功能还在翻用户的旧对话。
        val on = switches.masterEnabled
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
            ToggleRow(
                label = "记忆",
                checked = on,
                subtitle = "在对话间保留有用信息",
                onChange = onMaster,
            )
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                modifier = Modifier.padding(vertical = 6.dp),
            )
            ToggleRow(
                label = "使用记忆",
                checked = on && switches.memoryEnabled,
                enabled = on,
                subtitle = "在新对话中使用已保存的信息",
                onChange = onMemory,
            )
            ToggleRow(
                label = "回忆对话",
                checked = on && switches.recallEnabled,
                enabled = on,
                subtitle = "需要时查找历史对话",
                onChange = onRecall,
            )
            ToggleRow(
                label = "自动整理",
                checked = on && switches.autoLearn,
                enabled = on,
                subtitle = "从新对话中更新记忆",
                onChange = onAutoLearn,
            )
            ToggleRow(
                label = "敏感信息",
                checked = on && switches.allowSensitive,
                enabled = on,
                subtitle = "允许记忆包含敏感内容",
                onChange = onAllowSensitive,
            )
        }
    }
}

/**
 * 整理模型必须显式选择，没有「跟随当前对话模型」这一项。
 *
 * 自动学习是在用户视线之外发起的额外请求。跟随会话模型意味着换一个贵模型聊天，
 * 整理就跟着用贵模型跑，用户既没选过也看不见——这类计费必须由用户点头。
 */
@Composable
private fun MemoryModelCard(
    modelKey: String?,
    blocked: Boolean,
    options: List<SettingsViewModel.ModelOption>,
    onChange: (String?) -> Unit,
    onOpenByokProviders: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = options.firstOrNull { it.key == modelKey }?.label
        ?: if (modelKey == null) "选择模型" else "模型不可用"

    Surface(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.30f),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
            Text("自动整理模型", style = MaterialTheme.typography.bodyLarge)
            Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = { expanded = true },
                    enabled = options.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(selectedLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                TextButton(onClick = onOpenByokProviders) { Text("管理模型") }
            }
            androidx.compose.material3.DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { opt ->
                    DropdownMenuItem(
                        text = { Text(opt.label) },
                        onClick = { onChange(opt.key); expanded = false },
                    )
                }
            }
            if (blocked) {
                Text(
                    when {
                        options.isEmpty() -> "暂无可用模型"
                        modelKey == null -> "请选择自动整理模型"
                        else -> "自动整理模型不可用"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = Corange,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

/**
 * 整理节奏对用户不可见（攒够回合数或超过间隔自动跑），这里只给一个手动出口。
 *
 * 不把阈值做成设置项：那是个没人会调的旋钮。用户真正需要的是「我刚说了句重要的，现在就记下来」，
 * 这一个按钮就够了。
 */
@Composable
private fun ConsolidateCard(
    lastAt: Long,
    running: Boolean,
    enabled: Boolean,
    onRun: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.30f),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("整理记忆", style = MaterialTheme.typography.bodyLarge)
                Text(
                    if (lastAt > 0) "上次整理：${relativeDaysMillis(lastAt, System.currentTimeMillis())}" else "暂无整理记录",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            OutlinedButton(onClick = onRun, enabled = enabled && !running) {
                Text(if (running) "整理中…" else "开始整理")
            }
        }
    }
}

/**
 * 自动整理使用的历史范围。近期内容会放弃每个旧对话较早的未整理消息；
 * 全部历史会从水位线开始持续处理，手动整理时一次完成。
 */
@Composable
private fun ScanScopeCard(fullScan: Boolean, onChange: (Boolean) -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.30f),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
            Text("历史范围", style = MaterialTheme.typography.bodyLarge)
            Text(
                if (fullScan) "整理所有尚未处理的内容，用量较高" else "跳过较早的未整理内容",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SelectPill(label = "近期内容", selected = !fullScan, onClick = { onChange(false) })
                SelectPill(label = "全部历史", selected = fullScan, onClick = { onChange(true) })
            }
        }
    }
}

/**
 * 注入上限。给档位而不是滑块：这个值没有精细调节的意义，
 * 用户真正要做的判断只有「记忆占多少上下文算合适」。
 */
@Composable
private fun BudgetCard(budgetTokens: Int, onChange: (Int) -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 4.dp),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.30f),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
            Text("对话记忆量", style = MaterialTheme.typography.bodyLarge)
            Text(
                "控制每次对话使用的记忆量",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ByokMemoryProjector.BUDGET_OPTIONS.forEach { option ->
                    SelectPill(
                        label = when (option) {
                            1000 -> "少"
                            2000 -> "标准"
                            4000 -> "多"
                            else -> "最多"
                        },
                        selected = option == budgetTokens,
                        onClick = { onChange(option) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ProfileCard(
    fields: List<ByokMemoryViewModel.ProfileField>,
    onSetName: (String) -> Unit,
) {
    var editingName by remember { mutableStateOf(false) }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.30f),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
            fields.forEachIndexed { index, field ->
                if (index > 0) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (field.editable) Modifier.clickable { editingName = true } else Modifier)
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        field.label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        field.value.ifBlank { "未设置" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (field.value.isBlank()) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
            }
        }
    }

    if (editingName) {
        val current = fields.firstOrNull { it.editable }?.value.orEmpty()
        SingleLineSheet(
            title = "称呼",
            initial = current,
            placeholder = "例如：阿罗",
            onDismiss = { editingName = false },
            onSave = { onSetName(it); editingName = false },
        )
    }
}

/**
 * 候选卡。规范化文本与逐字原话都要展示——只看改写后的文本，
 * 用户无法确认自己是否真说过这句话。
 */
@Composable
private fun ByokCandidateCard(
    candidate: ByokMemoryCandidate,
    now: Long,
    onAccept: () -> Unit,
    onDismiss: () -> Unit,
) {
    MemoryCandidateCard(
        text = candidate.text,
        quote = candidate.quote,
        meta = "${candidate.section.label} · ${relativeDaysMillis(candidate.createdAt, now)}",
        onAccept = onAccept,
        onDismiss = onDismiss,
    )
}

@Composable
private fun ByokMemoryEntryCard(
    entry: ByokMemoryEntry,
    now: Long,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val effective = entry.effectiveConfidence(now)
    val tier = ConfidenceTier.of(effective)
    val accent = confidenceColor(tier)
    val dropped = !entry.isInjectable(now)

    MemoryEntryCardFrame(
        accent = accent,
        tierLabel = tier.label,
        confidencePercent = (effective * 100).toInt(),
        text = entry.text,
        onEdit = onEdit,
        onDelete = onDelete,
    ) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                entry.profileKey?.let { TagChip(text = it.label, color = Cpurple, filled = true) }
                when (entry.origin) {
                    ByokMemoryOrigin.MANUAL -> TagChip(text = "手动", color = Cpurple, filled = false)
                    ByokMemoryOrigin.CONFIRMED -> TagChip(text = "已确认", color = Cgreen, filled = false)
                    ByokMemoryOrigin.TOOL -> TagChip(text = "对话记录", color = CblueT, filled = false)
                    ByokMemoryOrigin.AUTOMATIC -> TagChip(text = "自动", color = Cgray, filled = false)
                }
                if (entry.permanent) TagChip(text = "长期", color = CblueT, filled = false)
                if (entry.recurrence > 1) TagChip(text = "提及 ${entry.recurrence} 次", color = Cgreen, filled = false)
                if (dropped) TagChip(text = "暂未使用", color = Cgray, filled = false)
            }

            val ref = if (entry.lastReinforcedAt > 0) entry.lastReinforcedAt else entry.createdAt
            if (ref > 0) {
                Text(
                    "${relativeDaysMillis(ref, now)}更新",
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
    }
}

@Composable
private fun AddMemoryRow(onClick: () -> Unit) {
    MemoryAddButton(onClick = onClick)
}

@Composable
private fun EmptyMemory() {
    EmptyMemoryState(
        title = "还没有形成记忆",
        description = "添加希望长期保留的信息，或开启自动整理。",
    )
}

@Composable
private fun ClearAllCard(onClearAll: () -> Unit) {
    MemoryDangerZone(
        description = null,
        actionSubtitle = "同时删除待确认内容和来源记录",
        onClearAll = onClearAll,
    )
}

// ── 编辑面板 ────────────────────────────────────────────────────────────────
//
// 两个面板都带输入框，必须让位给键盘：只写 navigationBarsPadding 时输入框会被键盘盖住。

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditMemorySheet(
    initialText: String,
    initialSection: MemorySection,
    initialProfileKey: ByokProfileKey?,
    onDismiss: () -> Unit,
    onSave: (String, MemorySection, ByokProfileKey?) -> Unit,
) {
    var text by remember { mutableStateOf(initialText) }
    var section by remember { mutableStateOf(initialSection) }
    var profileKey by remember { mutableStateOf(initialProfileKey) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars)),
        ) {
            Text(
                if (initialText.isEmpty()) "添加记忆" else "编辑记忆",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            OutlinedTextField(
                value = text,
                onValueChange = { if (it.length <= ByokMemoryEntry.MAX_TEXT_CHARS) text = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("例如：偏好简洁的中文回答") },
                minLines = 3,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                supportingText = { Text("${text.length}/${ByokMemoryEntry.MAX_TEXT_CHARS}") },
            )

            Text("分类", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MemorySection.entries.forEach { s ->
                    SelectPill(label = s.label, selected = s == section, onClick = { section = s })
                }
            }

            Text("个人信息（可选）", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SelectPill(label = "无", selected = profileKey == null, onClick = { profileKey = null })
                ByokProfileKey.entries.forEach { key ->
                    SelectPill(
                        label = key.label,
                        selected = profileKey == key,
                        onClick = { profileKey = if (profileKey == key) null else key },
                    )
                }
            }
            if (profileKey != null) {
                Text(
                    "填写内容即可，例如“阿罗”",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }

            Row(modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 20.dp)) {
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("取消") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = { onSave(text, section, profileKey) }, enabled = text.isNotBlank()) { Text("保存") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SingleLineSheet(
    title: String,
    initial: String,
    placeholder: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars)),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 12.dp))
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(60) },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(placeholder) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            )
            Row(modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 20.dp)) {
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("取消") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = { onSave(text) }) { Text("保存") }
            }
        }
    }
}
