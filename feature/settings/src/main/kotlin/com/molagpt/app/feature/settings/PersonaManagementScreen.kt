package com.molagpt.app.feature.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.platform.LocalContext
import com.molagpt.app.core.model.Lorebook
import com.molagpt.app.core.model.Persona
import com.molagpt.app.core.model.PersonaProfile
import com.molagpt.app.core.model.SystemPromptComposer
import com.molagpt.app.core.render.ImeDismissBackHandler
import com.molagpt.app.core.render.PersonaAvatar
import com.molagpt.app.core.render.PersonaIcons
import com.molagpt.app.core.storage.PersonaAvatarStore
import com.molagpt.app.core.storage.LorebookRepository
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
    avatars: PersonaAvatarStore,
    onOpenView: (String) -> Unit,
    onOpenEdit: (String) -> Unit,
    onNewPersona: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val personas by repository.observeAll().collectAsStateWithLifecycle(initialValue = emptyList())
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var importing by remember { mutableStateOf(false) }

    // 角色卡有三种容器（PNG / charX / JSON），厂商对 MIME 的标注又不统一，
    // 所以放开到 */* 由解析器去判断这到底是不是一张卡。
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        importing = true
        scope.launch {
            when (val result = importCharacterCard(context, uri, repository, avatars)) {
                is CardImportResult.Success -> {
                    val notes = result.notes.takeIf { it.isNotEmpty() }?.joinToString("；")
                    snackbar.showSnackbar(
                        listOfNotNull("已导入「${result.persona.name}」", notes).joinToString(" · "),
                    )
                }
                is CardImportResult.Failure -> snackbar.showSnackbar(result.message)
            }
            importing = false
        }
    }

    PersonaListContent(
        personas = personas,
        avatars = avatars,
        snackbar = snackbar,
        importing = importing,
        onBack = onBack,
        onOpen = { persona -> if (persona.isBuiltin) onOpenView(persona.id) else onOpenEdit(persona.id) },
        onNew = onNewPersona,
        onImport = { picker.launch(arrayOf("*/*")) },
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PersonaListContent(
    personas: List<Persona>,
    avatars: PersonaAvatarStore,
    snackbar: SnackbarHostState,
    importing: Boolean,
    onBack: () -> Unit,
    onOpen: (Persona) -> Unit,
    onNew: () -> Unit,
    onImport: () -> Unit,
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
                actions = {
                    TextButton(onClick = onImport, enabled = !importing) {
                        Text(if (importing) "导入中…" else "导入角色卡")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
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
                    PersonaRow(persona = persona, avatars = avatars, onClick = { onOpen(persona) })
                }
            }
            item { SectionLabel("我的角色") }
            if (mine.isEmpty()) {
                item { EmptyMineHint() }
            } else {
                items(mine, key = { it.id }) { persona ->
                    PersonaRow(persona = persona, avatars = avatars, onClick = { onOpen(persona) })
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
    avatars: PersonaAvatarStore,
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
        PersonaAvatar(
            file = avatars.resolve(persona.avatarPath),
            fallbackIcon = PersonaIcons.resolve(persona.icon),
            size = 42.dp,
        )
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
                if (persona.isRolePlay) {
                    Spacer(Modifier.width(6.dp))
                    RolePlayBadge()
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

/** 导入过角色卡、且卡里确实有人设内容的角色，走的是完整的角色扮演组装。 */
@Composable
private fun RolePlayBadge() {
    val cs = MaterialTheme.colorScheme
    Text(
        text = "角色扮演",
        style = MaterialTheme.typography.labelSmall,
        color = cs.onSurfaceVariant,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(cs.onSurfaceVariant.copy(alpha = 0.12f))
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
    lorebooks: LorebookRepository,
    personaId: String?,
    copyFromId: String?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val personas by repository.observeAll().collectAsStateWithLifecycle(initialValue = emptyList())
    val sharedBooks by lorebooks.observeAll().collectAsStateWithLifecycle(initialValue = emptyList())

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
    // 角色卡资料。字段太多，不用 rememberSaveable（Bundle 放不下也不该放），
    // 配置变化后由下面的 LaunchedEffect 从库里重新灌一次即可。
    var profile by remember(personaId, copyFromId) { mutableStateOf<PersonaProfile?>(null) }
    var deleting by remember { mutableStateOf(false) }

    // 键盘弹着时返回先收键盘，不退页面（三星等未启用预测式返回的机型会穿透到 NavHost）。
    ImeDismissBackHandler()

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
                profile = base.profile
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
                        // ime∪navigationBars：键盘弹出时保存/取消栏跟随上浮，Scaffold inner 随之增高把正文顶出键盘。
                        .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
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
                            val finalProfile = profile
                            val toSave = when {
                                personaId != null && base != null ->
                                    base.copy(name = finalName.ifEmpty { base.name }, systemPrompt = finalPrompt, icon = finalIcon)
                                copyFromId != null && base != null ->
                                    repository.draftCopy(base, personas.size)
                                        .copy(name = finalName, systemPrompt = finalPrompt, icon = finalIcon)
                                else ->
                                    repository.blankDraft(personas.size)
                                        .copy(name = finalName, systemPrompt = finalPrompt, icon = finalIcon)
                            }.copy(profile = finalProfile)
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
                placeholder = { Text("角色的身份、语气与行为准则") },
                minLines = 5,
                maxLines = 12,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                modifier = Modifier.fillMaxWidth(),
            )
            VariableHintRow(onPick = { token ->
                val cur = prompt.orEmpty()
                prompt = if (cur.isBlank()) token else cur.trimEnd() + " " + token
            })
            if (SystemPromptComposer.breaksPrefixCache(prompt)) {
                Text(
                    text = SystemPromptComposer.PREFIX_CACHE_WARNING,
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            // 人设与世界书默认不出现：普通助手角色用不到，摊开只会拉长页面。
            // 导入的卡自带 profile，直接展开；自建角色启用后同样是全套。
            Spacer(Modifier.height(10.dp))
            RoleplaySection(
                profile = profile,
                sharedBooks = sharedBooks,
                onChange = { profile = it },
            )
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

/**
 * 角色扮演设定区。三种形态共用一个「展开 / 收起」概念，收起从不丢数据：
 * - 未启用（`profile == null`）：只有一张说明卡。
 * - 已启用但收起：卡上显示已填内容摘要。
 * - 已启用且展开：摊开全部分组。
 *
 * 收起时若内容为空就直接退回未启用，省掉一个「关闭」按钮——两者在界面上是同一件事。
 */
@Composable
private fun RoleplaySection(
    profile: PersonaProfile?,
    sharedBooks: List<Lorebook>,
    onChange: (PersonaProfile?) -> Unit,
) {
    // null = 沿用默认：有内容的卡（导入来的）直接展开，空白角色收着。用户点过之后以他的选择为准。
    var choice by rememberSaveable { mutableStateOf<Boolean?>(null) }
    val expanded = choice ?: (profile != null && !profile.isEmpty)

    if (profile != null && expanded) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 2.dp, end = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "角色扮演设定",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = {
                    choice = false
                    if (profile.isEmpty) onChange(null)
                },
            ) { Text("收起") }
        }
        PersonaCardSections(profile = profile, onChange = onChange, sharedBooks = sharedBooks)
        return
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.30f),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp, end = 8.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                Text("角色扮演设定", style = MaterialTheme.typography.bodyLarge)
                Text(
                    profile?.contentSummary() ?: "人设、开场白与世界书",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            TextButton(
                onClick = {
                    if (profile == null) onChange(PersonaProfile())
                    choice = true
                },
            ) { Text(if (profile == null) "启用" else "展开") }
        }
    }
}

/** 收起状态下的一行摘要：让用户不展开也知道里面有什么。 */
private fun PersonaProfile.contentSummary(): String = buildList {
    if (description.isNotBlank() || personality.isNotBlank() || scenario.isNotBlank()) add("人设")
    if (greeting.isNotBlank() || alternateGreetings.isNotEmpty()) add("开场白")
    if (exampleDialogue.isNotBlank()) add("对话示例")
    if (characterNote.isNotBlank() || postHistoryInstructions.isNotBlank()) add("补充指令")
    lorebooks.sumOf { it.entries.size }.takeIf { it > 0 }?.let { add("世界书 $it 条") }
    sharedLorebookIds.size.takeIf { it > 0 }?.let { add("引用 $it 本") }
}.joinToString(" · ").ifBlank { "尚未填写" }

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
        text = "发送时会变量将会被自动替换为对应的值。",
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
