package com.molagpt.app.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.molagpt.app.core.model.Persona
import com.molagpt.app.core.render.PersonaIcons
import com.molagpt.app.core.storage.PersonaRepository
import kotlinx.coroutines.launch

/**
 * 角色管理：列表页。每行只有一个点击目标——
 * 内置角色进只读查看页（[onOpenView]），自定义角色进编辑页（[onOpenEdit]）；FAB 新建。
 *
 * 查看 / 编辑各自是独立 Nav 目的地，转场与返回手势由 MolaNavHost 全局统一声明驱动，
 * 本页不自管转场、也不拦截系统返回。
 */
@Composable
fun PersonaManagementScreen(
    repository: PersonaRepository,
    onOpenView: (String) -> Unit,
    onOpenEdit: (String) -> Unit,
    onNewPersona: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val personas by repository.observeAll().collectAsStateWithLifecycle(initialValue = emptyList())
    PersonaListContent(
        personas = personas,
        onBack = onBack,
        onOpen = { persona -> if (persona.isBuiltin) onOpenView(persona.id) else onOpenEdit(persona.id) },
        onNew = onNewPersona,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PersonaListContent(
    personas: List<Persona>,
    onBack: () -> Unit,
    onOpen: (Persona) -> Unit,
    onNew: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val builtin = personas.filter { it.isBuiltin }
    val mine = personas.filter { !it.isBuiltin }
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("角色管理") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onNew,
                containerColor = MaterialTheme.colorScheme.primary,
            ) {
                Icon(Icons.Filled.Add, contentDescription = "新建角色", tint = MaterialTheme.colorScheme.onPrimary)
            }
        },
    ) { inner ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(horizontal = 16.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text(
                    "仅对自定义模型生效。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
            if (builtin.isNotEmpty()) {
                item { SectionLabel("内置角色") }
                items(builtin, key = { it.id }) { persona ->
                    PersonaRow(persona = persona, onClick = { onOpen(persona) })
                }
            }
            item { SectionLabel("我的角色") }
            if (mine.isEmpty()) {
                item { EmptyMineHint() }
            } else {
                items(mine, key = { it.id }) { persona ->
                    PersonaRow(persona = persona, onClick = { onOpen(persona) })
                }
            }
            item { Spacer(Modifier.height(80.dp)) }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 10.dp, bottom = 2.dp),
    )
}

@Composable
private fun PersonaRow(
    persona: Persona,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(cs.surfaceVariant.copy(alpha = 0.4f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(CircleShape)
                .background(cs.primary.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = PersonaIcons.resolve(persona.icon),
                contentDescription = null,
                tint = cs.primary,
                modifier = Modifier.size(24.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = persona.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = cs.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (persona.isBuiltin) {
                    Spacer(Modifier.width(6.dp))
                    BuiltinBadge()
                }
            }
            Text(
                text = persona.preview,
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        // 整行可点；尾部 chevron 仅作「进入」提示（core 图标集精简，用字符规避缺图标风险）。
        Text(
            text = "›",
            style = MaterialTheme.typography.titleLarge,
            color = cs.onSurfaceVariant.copy(alpha = 0.5f),
        )
    }
}

@Composable
private fun BuiltinBadge() {
    val cs = MaterialTheme.colorScheme
    Text(
        text = "内置",
        style = MaterialTheme.typography.labelSmall,
        color = cs.primary,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(cs.primary.copy(alpha = 0.12f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun EmptyMineHint() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .padding(vertical = 22.dp, horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "还没有自建角色。\n从内置角色复制一份，或点右下角 ＋ 新建。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** 内置角色只读查看页（独立 Nav 目的地）。底部「复制为副本并编辑」→ [onDuplicate]。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonaViewScreen(
    repository: PersonaRepository,
    personaId: String,
    onBack: () -> Unit,
    onDuplicate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val personas by repository.observeAll().collectAsStateWithLifecycle(initialValue = emptyList())
    val persona = personas.firstOrNull { it.id == personaId }
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("查看角色") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
        bottomBar = {
            if (persona != null) {
                Surface(color = cs.surface, shadowElevation = 8.dp) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(16.dp),
                    ) {
                        Button(
                            onClick = onDuplicate,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp),
                            shape = RoundedCornerShape(15.dp),
                        ) { Text("复制为副本并编辑") }
                    }
                }
            }
        },
    ) { inner ->
        if (persona == null) return@Scaffold
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(8.dp))
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .size(76.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(cs.primary.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = PersonaIcons.resolve(persona.icon),
                        contentDescription = null,
                        tint = cs.primary,
                        modifier = Modifier.size(40.dp),
                    )
                }
                Spacer(Modifier.height(14.dp))
                Text(
                    text = persona.name,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = cs.onSurface,
                )
                Spacer(Modifier.height(9.dp))
                Text(
                    text = "内置角色 · 只读",
                    style = MaterialTheme.typography.labelMedium,
                    color = cs.onSurfaceVariant,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(cs.surfaceVariant.copy(alpha = 0.6f))
                        .padding(horizontal = 12.dp, vertical = 5.dp),
                )
            }
            Spacer(Modifier.height(22.dp))
            Text("系统提示词", style = MaterialTheme.typography.labelLarge, color = cs.primary)
            Spacer(Modifier.height(9.dp))
            SelectionContainer {
                Text(
                    text = persona.systemPrompt.ifBlank { "（无提示词）" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(cs.surfaceVariant.copy(alpha = 0.4f))
                        .padding(16.dp),
                )
            }
            Spacer(Modifier.height(14.dp))
            Text(
                text = "内置角色不可直接修改。复制一份成为「我的角色」后即可自由编辑。",
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * 角色编辑页（独立 Nav 目的地）。三种来源：
 * - [personaId] 非空：编辑已有自定义角色（底部可删除）。
 * - [copyFromId] 非空：从该内置角色复制成可编辑副本（保存才落库）。
 * - 两者皆空：新建空白角色。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonaEditScreen(
    repository: PersonaRepository,
    personaId: String?,
    copyFromId: String?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val personas by repository.observeAll().collectAsStateWithLifecycle(initialValue = emptyList())

    val isNew = personaId == null
    val isBlankNew = personaId == null && copyFromId == null
    val base = remember(personas, personaId, copyFromId) {
        (personaId ?: copyFromId)?.let { id -> personas.firstOrNull { it.id == id } }
    }
    val fromBuiltinName = if (copyFromId != null) base?.name else null

    // 表单状态（rememberSaveable 跨配置变化/进程重建存活；null = 尚未初始化）。
    var name by rememberSaveable(personaId, copyFromId) { mutableStateOf<String?>(null) }
    var prompt by rememberSaveable(personaId, copyFromId) { mutableStateOf<String?>(null) }
    var selectedIcon by rememberSaveable(personaId, copyFromId) { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf(false) }

    // base 解析后一次性灌入表单初值（空白新建无需等待）。
    LaunchedEffect(base, isBlankNew) {
        if (name != null) return@LaunchedEffect
        when {
            isBlankNew -> {
                name = ""; prompt = ""; selectedIcon = PersonaIcons.DEFAULT_ICON
            }
            base != null -> {
                name = if (copyFromId != null) base.name + " 副本" else base.name
                prompt = base.systemPrompt
                selectedIcon = base.icon ?: PersonaIcons.DEFAULT_ICON
            }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(if (isNew) "新建角色" else "编辑角色") },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
        bottomBar = {
            Surface(color = cs.surface, shadowElevation = 8.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (!isNew) {
                        IconButton(onClick = { deleting = true }) {
                            Icon(Icons.Filled.Delete, contentDescription = "删除", tint = cs.error)
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onClose) { Text("取消") }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val finalName = name?.trim().orEmpty()
                            val finalPrompt = prompt?.trim().orEmpty()
                            val finalIcon = selectedIcon ?: PersonaIcons.DEFAULT_ICON
                            val toSave = when {
                                personaId != null && base != null ->
                                    base.copy(name = finalName.ifEmpty { base.name }, systemPrompt = finalPrompt, icon = finalIcon)
                                copyFromId != null && base != null ->
                                    repository.draftCopy(base, personas.size)
                                        .copy(name = finalName, systemPrompt = finalPrompt, icon = finalIcon)
                                else ->
                                    repository.blankDraft(personas.size)
                                        .copy(name = finalName, systemPrompt = finalPrompt, icon = finalIcon)
                            }
                            scope.launch { repository.save(toSave) }
                            onClose()
                        },
                        enabled = !name.isNullOrBlank(),
                    ) { Text("保存") }
                }
            }
        },
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            if (fromBuiltinName != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "复制自内置「$fromBuiltinName」",
                    style = MaterialTheme.typography.labelMedium,
                    color = cs.onSurfaceVariant,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(cs.surfaceVariant.copy(alpha = 0.5f))
                        .padding(horizontal = 11.dp, vertical = 5.dp),
                )
            }
            FieldLabel("图标")
            IconPicker(selected = selectedIcon ?: PersonaIcons.DEFAULT_ICON, onSelect = { selectedIcon = it })
            Spacer(Modifier.height(18.dp))
            FieldLabel("名称")
            OutlinedTextField(
                value = name.orEmpty(),
                onValueChange = { name = it },
                singleLine = true,
                placeholder = { Text("例如：产品文案") },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(18.dp))
            FieldLabel("系统提示词")
            OutlinedTextField(
                value = prompt.orEmpty(),
                onValueChange = { prompt = it },
                placeholder = { Text("描述这个角色的身份、语气和行为准则...") },
                minLines = 5,
                maxLines = 12,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                modifier = Modifier.fillMaxWidth(),
            )
            VariableHintRow(onPick = { token ->
                val cur = prompt.orEmpty()
                prompt = if (cur.isBlank()) token else cur.trimEnd() + " " + token
            })
            Spacer(Modifier.height(24.dp))
        }
    }

    if (deleting && personaId != null) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text("删除角色") },
            text = { Text("确定删除「${name.orEmpty()}」吗？此操作不可撤销。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch { repository.delete(personaId) }
                        deleting = false
                        onClose()
                    },
                ) { Text("删除", color = cs.error) }
            },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 9.dp),
    )
}

@Composable
private fun VariableHintRow(onPick: (String) -> Unit) {
    val tokens = remember { listOf("{{date}}", "{{time}}", "{{datetime}}", "{{model}}", "{{provider}}", "{{username}}") }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        tokens.forEach { token ->
            Text(
                text = token,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                    .clickable { onPick(token) }
                    .padding(horizontal = 8.dp, vertical = 5.dp),
            )
        }
    }
    Text(
        text = "发送时会自动替换变量；未识别占位符会保留原文。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun IconPicker(
    selected: String,
    onSelect: (String) -> Unit,
) {
    val icons = remember { PersonaIcons.entries }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (row in icons.chunked(8)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for ((key, vector) in row) {
                    val isSelected = selected == key
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(
                                if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            )
                            .clickable { onSelect(key) }
                            .padding(8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = vector,
                            contentDescription = key,
                            tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
    }
}
