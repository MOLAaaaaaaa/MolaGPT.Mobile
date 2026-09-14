package com.molagpt.app.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
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
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.molagpt.app.core.markdown.PostProcessResult
import com.molagpt.app.core.markdown.ResponsePostProcessor
import com.molagpt.app.core.model.ResponseRegexRule
import com.molagpt.app.core.model.ResponseRegexRules
import com.molagpt.app.core.render.ImeDismissBackHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/** 预览框的开箱示例：一次盖住正文、引号、代码块与行内公式四种情况。 */
private const val PREVIEW_SAMPLE =
    "春日晴朗,微风和煦。\n" +
        "窗边的书签上写着\"静候花开\"。\n\n" +
        "```python\n" +
        "d = {\"键\": 1,\"值\": 2}\n" +
        "```\n\n" +
        "行内代码：`a,b`\n公式：\$x_{甲,乙}\$"

/** 规则说明由用户维护；留空时以表达式和替换内容作为副标题。 */
private fun ruleSubtitle(rule: ResponseRegexRule): String =
    rule.description?.takeIf { it.isNotBlank() }
        ?: rule.pattern + " → " + rule.replacement.ifEmpty { "（删除）" }

/**
 * 设置 → 后处理。
 *
 * 规则在回答流结束后跑一次，处理结果直接落库，所以这里改的是「以后存下来的文本」而不是
 * 「屏幕上临时的样子」。预览框让用户在动真格之前先看清楚一条规则会碰到什么。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PostProcessingScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val rules = settings.responseRegexRules
    val enabled = settings.responsePostProcessingEnabled

    var editing by remember { mutableStateOf<RuleDraft?>(null) }
    var sample by rememberSaveable { mutableStateOf(PREVIEW_SAMPLE) }

    ImeDismissBackHandler()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.systemBars.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
        topBar = {
            TopAppBar(
                title = { Text("回答后处理") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars)),
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.30f),
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                    ToggleRow(
                        label = "替换回答内容",
                        checked = enabled,
                        onChange = viewModel::setResponsePostProcessingEnabled,
                        subtitle = "生成结束后，按顺序应用已启用的规则",
                    )
                }
            }

            SectionTitle("替换规则")
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.30f),
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    if (rules.isEmpty()) {
                        Text(
                            "暂无规则",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 22.dp),
                        )
                    }
                    rules.forEachIndexed { index, rule ->
                        if (index > 0) {
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 16.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
                            )
                        }
                        RuleRow(
                            rule = rule,
                            dimmed = !enabled || !rule.enabled,
                            onClick = { editing = RuleDraft.of(rule, index) },
                            onToggle = { on ->
                                viewModel.setResponseRegexRules(
                                    rules.toMutableList().apply { this[index] = rule.copy(enabled = on) },
                                )
                            },
                        )
                    }
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { editing = RuleDraft.blank(rules.size) }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                        Text(
                            "添加规则",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 10.dp),
                        )
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "仅处理正文，代码和公式保持原样",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f).padding(top = 8.dp),
                )
                if (rules != ResponseRegexRules.DEFAULTS) {
                    TextButton(onClick = viewModel::resetResponseRegexRules) { Text("恢复默认") }
                }
            }

            SectionTitle("预览")
            PreviewCard(
                sample = sample,
                onSampleChange = { sample = it },
                rules = if (enabled) rules else emptyList(),
                disabledHint = if (enabled) null else "替换已关闭",
            )
            Spacer(Modifier.height(28.dp))
        }
    }

    editing?.let { draft ->
        RuleEditorSheet(
            draft = draft,
            canMoveUp = draft.index > 0 && !draft.isNew,
            canMoveDown = draft.index < rules.lastIndex && !draft.isNew,
            onDismiss = { editing = null },
            onSave = { saved ->
                val next = rules.toMutableList()
                if (draft.isNew) next.add(saved) else next[draft.index] = saved
                viewModel.setResponseRegexRules(next)
                editing = null
            },
            onDelete = {
                viewModel.setResponseRegexRules(rules.toMutableList().apply { removeAt(draft.index) })
                editing = null
            },
            onMove = { delta ->
                val next = rules.toMutableList()
                val target = draft.index + delta
                next.add(target, next.removeAt(draft.index))
                viewModel.setResponseRegexRules(next)
                editing = null
            },
        )
    }
}

@Composable
private fun RuleRow(
    rule: ResponseRegexRule,
    dimmed: Boolean,
    onClick: () -> Unit,
    onToggle: (Boolean) -> Unit,
) {
    val invalid = remember(rule.pattern, rule.replacement) { ResponsePostProcessor.validate(rule) != null }
    val alpha = if (dimmed) 0.45f else 1f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, top = 11.dp, end = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    rule.name.ifBlank { "未命名规则" },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (invalid) {
                    Surface(
                        modifier = Modifier.padding(start = 8.dp),
                        shape = RoundedCornerShape(7.dp),
                        color = MaterialTheme.colorScheme.error.copy(alpha = 0.14f),
                    ) {
                        Text(
                            "无效",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                        )
                    }
                }
            }
            Text(
                ruleSubtitle(rule),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Switch(
            checked = rule.enabled,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                checkedTrackColor = MaterialTheme.colorScheme.primary,
                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                uncheckedBorderColor = MaterialTheme.colorScheme.outline,
            ),
        )
    }
}

/** 预览：输入样例 → 看到规则真正会做什么。用的就是聊天链路那一份 [ResponsePostProcessor]。 */
@Composable
private fun PreviewCard(
    sample: String,
    onSampleChange: (String) -> Unit,
    rules: List<ResponseRegexRule>,
    disabledHint: String?,
) {
    // 规则是用户写的，跑多久没法预先知道，绝不能占着主线程。produceState 在 key 变化时会取消
    // 上一次计算，所以慢的旧结果不会盖住新输入；还没算出来时先留空，不闪旧值。
    val result by produceState<PostProcessResult?>(initialValue = null, sample, rules) {
        value = null
        value = withContext(Dispatchers.Default) { ResponsePostProcessor.apply(sample, rules) }
    }
    val current = result
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.30f),
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(
                "示例文本",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = sample,
                onValueChange = onSampleChange,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                minLines = 3,
                textStyle = MaterialTheme.typography.bodyMedium,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
            )
            Text(
                "替换结果",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
            Surface(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface,
            ) {
                Text(
                    current?.text?.ifBlank { "（空）" } ?: "…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                )
            }
            val failure = current?.failures?.firstOrNull()
            Text(
                text = when {
                    failure != null -> "${failure.ruleName}：${failure.reason}"
                    current == null -> "正在预览…"
                    disabledHint != null -> disabledHint
                    current.changes > 0 -> "已替换 ${current.changes} 处"
                    else -> "无替换"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (failure != null) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}

/** 编辑中的一条规则。[index] 是它在列表里的位置，新建时等于末尾。 */
internal data class RuleDraft(
    val id: String,
    val index: Int,
    val isNew: Boolean,
    val name: String,
    val description: String,
    val pattern: String,
    val replacement: String,
    val enabled: Boolean,
) {
    companion object {
        fun of(rule: ResponseRegexRule, index: Int) =
            RuleDraft(
                rule.id,
                index,
                false,
                rule.name,
                rule.description.orEmpty(),
                rule.pattern,
                rule.replacement,
                rule.enabled,
            )

        fun blank(index: Int) =
            RuleDraft(UUID.randomUUID().toString(), index, true, "", "", "", "", true)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RuleEditorSheet(
    draft: RuleDraft,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onDismiss: () -> Unit,
    onSave: (ResponseRegexRule) -> Unit,
    onDelete: () -> Unit,
    onMove: (Int) -> Unit,
) {
    var name by remember(draft.id) { mutableStateOf(draft.name) }
    var description by remember(draft.id) { mutableStateOf(draft.description) }
    var pattern by remember(draft.id) { mutableStateOf(draft.pattern) }
    var replacement by remember(draft.id) { mutableStateOf(draft.replacement) }

    val candidate = ResponseRegexRule(
        id = draft.id,
        name = name.trim(),
        pattern = pattern,
        replacement = replacement,
        enabled = draft.enabled,
        description = description.trim().ifEmpty { "" },
    )
    val problem = remember(pattern, replacement) {
        if (pattern.isEmpty()) null else ResponsePostProcessor.validate(candidate)
    }
    val canSave = name.isNotBlank() && pattern.isNotEmpty() && problem == null

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars)),
        ) {
            Text(
                if (draft.isNew) "新建规则" else "编辑规则",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(24) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("规则名称") },
                placeholder = { Text("例如：中文破折号") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            )
            OutlinedTextField(
                value = description,
                onValueChange = { description = it.take(80) },
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                label = { Text("规则说明") },
                placeholder = { Text("可选") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            )
            OutlinedTextField(
                value = pattern,
                onValueChange = { pattern = it },
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                label = { Text("正则表达式") },
                placeholder = { Text("要匹配的内容") },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                isError = problem != null,
                supportingText = problem?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            )
            OutlinedTextField(
                value = replacement,
                onValueChange = { replacement = it },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                label = { Text("替换为") },
                placeholder = { Text("留空则删除匹配内容") },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                supportingText = { Text("\$1 引用第一个捕获组；\\\$ 表示美元符号") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            )

            if (canMoveUp || canMoveDown) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(onClick = { onMove(-1) }, enabled = canMoveUp) { Text("上移") }
                    TextButton(onClick = { onMove(1) }, enabled = canMoveDown) { Text("下移") }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!draft.isNew) {
                    TextButton(onClick = onDelete) {
                        Text("删除", color = MaterialTheme.colorScheme.error)
                    }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("取消") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = { onSave(candidate) }, enabled = canSave) { Text("保存") }
            }
        }
    }
}
