package com.molagpt.app.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.molagpt.app.core.render.MolaScaffold
import com.molagpt.app.core.render.MolaTopBar
import com.molagpt.app.core.storage.AppSettings
import com.molagpt.app.core.storage.SettingsStore
import com.molagpt.app.core.storage.SkillRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * 技能详情页。开关放在最上面，删除放在顶栏，长说明和 SKILL.md 默认折叠，
 * 这样不管内容多长，常用操作都不需要往下滑。
 */
@Composable
fun SkillDetailScreen(
    skillId: String,
    repository: SkillRepository,
    settingsStore: SettingsStore,
    onBack: () -> Unit,
) {
    val skills by repository.skills.collectAsStateWithLifecycle()
    val settings by settingsStore.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val skill = skills.firstOrNull { it.id == skillId }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var confirmDelete by remember { mutableStateOf(false) }
    var content by remember(skillId) { mutableStateOf<String?>(null) }
    var descriptionExpanded by rememberSaveable { mutableStateOf(false) }
    var contentExpanded by rememberSaveable { mutableStateOf(false) }

    // 技能被删除后回到列表，只在缺失的那一刻触发一次。
    LaunchedEffect(skill == null) {
        if (skill == null) onBack()
    }
    LaunchedEffect(skillId) {
        content = try {
            repository.contents(skillId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            "无法读取：${error.message ?: "未知错误"}"
        }
    }
    if (skill == null) return

    val enabled = skill.id !in settings.disabledSkillIds

    MolaScaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.systemBars.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            MolaTopBar(
                title = { Text(skill.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { confirmDelete = true }) {
                        Icon(
                            Icons.Outlined.Delete,
                            contentDescription = "删除技能",
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                },
            )
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
            Surface(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.30f),
            ) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    ToggleRow(
                        label = "启用",
                        subtitle = if (enabled) "模型将在合适的时机读取这个技能" else "已关闭，模型将不会使用这个技能",
                        checked = enabled,
                        onChange = { on -> scope.launch { settingsStore.setSkillEnabled(skill.id, on) } },
                    )
                }
            }

            SectionTitle("说明")
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.30f),
            ) {
                var overflowing by remember(skill.description) { mutableStateOf(false) }
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(
                        skill.description,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = if (descriptionExpanded) Int.MAX_VALUE else DESCRIPTION_LINES,
                        overflow = TextOverflow.Ellipsis,
                        onTextLayout = { if (!descriptionExpanded) overflowing = it.hasVisualOverflow },
                    )
                    skill.compatibility?.let {
                        Text(
                            "环境要求：$it",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    if (overflowing || descriptionExpanded) {
                        ExpandToggle(descriptionExpanded) { descriptionExpanded = !descriptionExpanded }
                    }
                }
            }

            SectionTitle("SKILL.md")
            val full = content
            val preview = full?.let { collapsedPreview(it) }
            val truncated = full != null && preview != full
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.30f),
            ) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    SelectionContainer {
                        Text(
                            when {
                                full == null -> "读取中…"
                                contentExpanded || !truncated -> full
                                else -> preview.orEmpty() + "\n…"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                    if (truncated) {
                        ExpandToggle(contentExpanded) { contentExpanded = !contentExpanded }
                    }
                }
            }
            Spacer(Modifier.size(24.dp))
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除技能") },
            text = { Text("确定删除「${skill.name}」吗？此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        try {
                            repository.delete(skill.id)
                            settingsStore.removeSkillPreferences(skill.id)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            snackbar.showSnackbar(error.message ?: "删除失败，请重试")
                        }
                    }
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun ExpandToggle(expanded: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        TextButton(onClick = onClick) { Text(if (expanded) "收起" else "展开全部") }
    }
}

private const val DESCRIPTION_LINES = 4
private const val PREVIEW_LINES = 12
private const val PREVIEW_CHARS = 1200

/** 折叠时只取开头一小段，避免上百 KB 的文本也要整份排版。 */
private fun collapsedPreview(text: String): String {
    val byLines = text.lineSequence().take(PREVIEW_LINES).joinToString("\n")
    return if (byLines.length > PREVIEW_CHARS) byLines.take(PREVIEW_CHARS) else byLines
}
