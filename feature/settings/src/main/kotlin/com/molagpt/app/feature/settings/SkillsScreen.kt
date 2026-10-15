package com.molagpt.app.feature.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.molagpt.app.core.model.SkillInfo
import com.molagpt.app.core.render.ImeDismissBackHandler
import com.molagpt.app.core.render.MolaScaffold
import com.molagpt.app.core.render.MolaTopBar
import com.molagpt.app.core.storage.AppSettings
import com.molagpt.app.core.storage.SettingsStore
import com.molagpt.app.core.storage.SkillRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * 技能管理页。技能默认全部启用，模型在对话里按需读取，不需要在聊天中逐个选择；
 * 这里只负责导入和单个开关；查看与删除在详情页。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillsScreen(
    repository: SkillRepository,
    settingsStore: SettingsStore,
    onBack: () -> Unit,
    onOpenSkill: (String) -> Unit,
) {
    val skills by repository.skills.collectAsStateWithLifecycle()
    val settings by settingsStore.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val enabledCount = skills.count { it.id !in settings.disabledSkillIds }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var importing by rememberSaveable { mutableStateOf(false) }
    var pasting by rememberSaveable { mutableStateOf(false) }
    var draft by rememberSaveable { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var importError by remember { mutableStateOf<String?>(null) }

    fun notify(message: String) {
        scope.launch { snackbar.showSnackbar(message) }
    }

    fun perform(action: suspend () -> Unit) {
        if (working) return
        working = true
        importError = null
        scope.launch {
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val message = error.message ?: "操作失败，请重试"
                if (pasting) importError = message else notify(message)
            } finally {
                working = false
            }
        }
    }

    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) perform {
            val input = context.contentResolver.openInputStream(uri) ?: error("无法读取该文件")
            val skill = input.use { repository.importFile(it) }
            notify("已导入「${skill.name}」")
        }
    }

    ImeDismissBackHandler()
    MolaScaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.systemBars.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            MolaTopBar(
                title = { Text("技能") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { if (!working) importing = true },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.navigationBarsPadding(),
            ) {
                Icon(Icons.Filled.Add, contentDescription = "导入技能")
            }
        },
    ) { inner ->
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(inner)
                .padding(horizontal = 14.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IntroCard()
            if (skills.isEmpty()) {
                EmptySkills(
                    enabled = !working,
                    onPickFile = { pickFile.launch(arrayOf("*/*")) },
                    onPaste = {
                        importError = null
                        pasting = true
                    },
                )
            } else {
                SectionTitle("已安装 ${skills.size} 项 · ${enabledCount} 项启用")
                skills.forEach { skill ->
                    SkillCard(
                        skill = skill,
                        enabled = skill.id !in settings.disabledSkillIds,
                        onOpen = { onOpenSkill(skill.id) },
                        onToggle = { on -> scope.launch { settingsStore.setSkillEnabled(skill.id, on) } },
                    )
                }
            }
            Spacer(Modifier.size(88.dp))
        }
    }

    if (importing) {
        ModalBottomSheet(
            onDismissRequest = { importing = false },
            contentWindowInsets = { WindowInsets(0) },
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .navigationBarsPadding()
                    .padding(bottom = 16.dp),
            ) {
                Text("导入技能", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
                ImportOption(
                    icon = Icons.Outlined.FolderOpen,
                    title = "选择文件",
                    subtitle = "SKILL.md 或包含它的 ZIP 压缩包",
                    onClick = {
                        importing = false
                        pickFile.launch(arrayOf("*/*"))
                    },
                )
                ImportOption(
                    icon = Icons.Outlined.ContentPaste,
                    title = "粘贴内容",
                    subtitle = "直接粘贴 SKILL.md 的全文",
                    onClick = {
                        importing = false
                        importError = null
                        pasting = true
                    },
                )
            }
        }
    }

    if (pasting) {
        ModalBottomSheet(
            onDismissRequest = { pasting = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            contentWindowInsets = { WindowInsets(0) },
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars)),
            ) {
                Text("粘贴技能", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 12.dp))
                OutlinedTextField(
                    value = draft,
                    onValueChange = {
                        draft = it
                        importError = null
                    },
                    enabled = !working,
                    isError = importError != null,
                    placeholder = { Text(SKILL_TEMPLATE, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) },
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    supportingText = { Text(importError ?: "开头需要包含 name 和 description") },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp, max = 320.dp),
                )
                Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 20.dp)) {
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { pasting = false }) { Text("取消") }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            perform {
                                val skill = repository.importMarkdown(draft)
                                draft = ""
                                pasting = false
                                notify("已导入「${skill.name}」")
                            }
                        },
                        enabled = draft.isNotBlank() && !working,
                    ) { Text(if (working) "导入中…" else "导入") }
                }
            }
        }
    }
}

private const val SKILL_TEMPLATE = "---\nname: my-skill\ndescription: 说明何时使用这个技能\n---\n\n具体步骤和注意事项"

/** 一句话说明技能在哪里生效、怎么被使用，常驻显示。 */
@Composable
private fun IntroCard() {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.07f),
    ) {
        Text(
            "仅对支持工具调用的 BYOK 模型生效，模型会在合适的时机自动读取技能。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Composable
private fun EmptySkills(enabled: Boolean, onPickFile: () -> Unit, onPaste: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 48.dp, bottom = 24.dp, start = 24.dp, end = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(84.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)),
            contentAlignment = Alignment.Center,
        ) {
            SkillIllustration(MaterialTheme.colorScheme.primary, Modifier.size(44.dp))
        }
        Text("还没有技能", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 18.dp))
        Text(
            "导入 SKILL.md 或 ZIP 后，模型会在合适的时机使用它。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp, bottom = 20.dp),
        )
        Button(onClick = onPickFile, enabled = enabled) { Text("选择文件") }
        TextButton(onClick = onPaste, enabled = enabled) { Text("粘贴内容") }
    }
}

/** 一页写着步骤的说明书，右下角一颗星表示它能被模型调用。线条风格与设置页的 Canvas 图标一致。 */
@Composable
private fun SkillIllustration(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = 2.dp.toPx()
        drawRoundRect(
            color = color,
            topLeft = Offset(w * 0.08f, h * 0.06f),
            size = Size(w * 0.52f, h * 0.84f),
            cornerRadius = CornerRadius(w * 0.09f),
            style = Stroke(stroke),
        )
        listOf(0.28f to 0.40f, 0.44f to 0.40f, 0.60f to 0.26f).forEach { (y, length) ->
            drawLine(color, Offset(w * 0.20f, h * y), Offset(w * (0.20f + length), h * y), stroke, StrokeCap.Round)
        }
        fun sparkle(cx: Float, cy: Float, r: Float) {
            val path = Path().apply {
                moveTo(cx, cy - r)
                quadraticTo(cx, cy, cx + r, cy)
                quadraticTo(cx, cy, cx, cy + r)
                quadraticTo(cx, cy, cx - r, cy)
                quadraticTo(cx, cy, cx, cy - r)
                close()
            }
            drawPath(path, color)
        }
        sparkle(w * 0.80f, h * 0.68f, w * 0.17f)
        sparkle(w * 0.84f, h * 0.24f, w * 0.09f)
    }
}

@Composable
private fun SkillCard(
    skill: SkillInfo,
    enabled: Boolean,
    onOpen: () -> Unit,
    onToggle: (Boolean) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = cs.surfaceVariant.copy(alpha = 0.30f),
        onClick = onOpen,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp, end = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    skill.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = if (enabled) cs.onSurface else cs.onSurface.copy(alpha = 0.5f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    skill.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.7f),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
                skill.compatibility?.let {
                    Text(
                        "环境要求：$it",
                        style = MaterialTheme.typography.labelSmall,
                        color = cs.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = enabled,
                onCheckedChange = onToggle,
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
private fun ImportOption(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingsRowIcon(icon)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        ForwardChevron()
    }
}
