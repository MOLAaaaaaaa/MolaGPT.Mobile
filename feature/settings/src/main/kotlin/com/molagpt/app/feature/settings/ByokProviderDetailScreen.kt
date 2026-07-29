package com.molagpt.app.feature.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Checkbox
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.molagpt.app.core.model.ByokProvider
import com.molagpt.app.core.model.ByokProviderType
import com.molagpt.app.core.model.CustomBodyParam
import com.molagpt.app.core.model.ProviderKind
import com.molagpt.app.core.model.ProviderModel
import com.molagpt.app.core.model.ThinkingBehavior
import com.molagpt.app.core.model.ThinkingConfig
import com.molagpt.app.core.model.ThinkingDetectSource
import com.molagpt.app.core.model.ThinkingKinds
import com.molagpt.app.core.model.ThinkingParamKind
import com.molagpt.app.core.render.ImeDismissBackHandler
import com.molagpt.app.core.render.SegmentedControl
import kotlinx.coroutines.launch

/**
 * BYOK 提供商详情页：配置 / 模型 两个分区（胶囊分段控件切换）。
 * 配置区编辑协议、地址、密钥、路径并保存/删除；模型区列出模型、自动获取、手动增删改（底部弹层编辑），
 * 以及已添加模型的多选 / 全选 / 批量删除。
 * 表单态以 [ByokProvider] 为单一来源，编辑直接 copy 后调 saveByokProvider 落库。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ByokProviderDetailScreen(
    providerId: String,
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** 非空时进入页面即打开该模型的编辑弹层（推理参数设置深链）。 */
    initialEditModelId: String? = null,
) {
    val providers by viewModel.byokProviderList.collectAsStateWithLifecycle()
    val status by viewModel.byokStatus.collectAsStateWithLifecycle()
    val provider = providers.firstOrNull { it.id == providerId }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(0) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var editingModel by remember { mutableStateOf<ModelDraft?>(null) }
    // 自动获取：拉取后进入选择 sheet，用户勾选再添加（不再全量自动添加）。
    var fetching by remember { mutableStateOf(false) }
    var fetchResult by remember { mutableStateOf<List<com.molagpt.app.core.model.ProviderModel>?>(null) }
    // 模型多选态提到页面级，才能挂 Scaffold FAB（与提供商列表加号同位置）。
    var selectingModels by remember(providerId) { mutableStateOf(false) }
    var selectedModelIds by remember(providerId) { mutableStateOf(emptySet<String>()) }
    var showBatchDeleteConfirm by remember { mutableStateOf(false) }
    var consumedInitialEdit by remember(providerId, initialEditModelId) { mutableStateOf(false) }

    // 键盘弹着时返回先收键盘，不退页面（三星等未启用预测式返回的机型会穿透到 NavHost）。
    ImeDismissBackHandler()

    LaunchedEffect(status) {
        status?.let {
            snackbar.showSnackbar(it)
            viewModel.clearByokStatus()
        }
    }
    // 深链：provider 就绪后切到「模型」Tab 并打开对应模型编辑弹层。
    LaunchedEffect(provider, initialEditModelId, consumedInitialEdit) {
        if (consumedInitialEdit) return@LaunchedEffect
        val targetId = initialEditModelId?.trim().orEmpty()
        if (targetId.isEmpty() || provider == null) return@LaunchedEffect
        val model = provider.models.firstOrNull { it.id == targetId } ?: return@LaunchedEffect
        tab = 1
        editingModel = ModelDraft.from(model)
        consumedInitialEdit = true
    }
    // 离开「模型」Tab 时退出多选，避免配置页仍露出删除 FAB。
    LaunchedEffect(tab) {
        if (tab != 1) {
            selectingModels = false
            selectedModelIds = emptySet()
            showBatchDeleteConfirm = false
        }
    }

    // 配置页未保存的草稿（用途/类型等改动）。切出配置页做联网操作前先落库，
    // 否则模型页的「自动获取」会读到旧用途 → 返回全量模型而非图像模型。
    val saveDraftRef = remember { mutableStateOf<com.molagpt.app.core.model.ByokProvider?>(null) }

    suspend fun persistDraft(): com.molagpt.app.core.model.ByokProvider? {
        val p = provider ?: return null
        val draft = saveDraftRef.value
        if (draft != null && draft != p) {
            viewModel.saveByokProvider(draft)
            delay(150) // 等 flow emit 再返回，让 fetchByokModels 读到最新用途
            saveDraftRef.value = null
            return draft
        }
        return p
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.systemBars.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
        topBar = {
            TopAppBar(
                title = { Text(provider?.name ?: "服务详情", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            // 与 ByokProvidersScreen 加号同款：右下角圆角 FAB + navigationBarsPadding。
            if (tab == 1 && selectingModels && selectedModelIds.isNotEmpty()) {
                FloatingActionButton(
                    onClick = { showBatchDeleteConfirm = true },
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.navigationBarsPadding(),
                ) {
                    Icon(Icons.Filled.Delete, contentDescription = "删除已选")
                }
            }
        },
    ) { inner ->
        if (provider == null) {
            Box(Modifier.fillMaxSize().padding(inner), contentAlignment = Alignment.Center) {
                Text("服务不存在或已删除", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@Scaffold
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner),
        ) {
            SegmentedControl(
                options = listOf("config" to "配置", "models" to "模型 (${provider.models.size})"),
                selected = if (tab == 0) "config" else "models",
                onSelect = { tab = if (it == "config") 0 else 1 },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            )
            Column(
                modifier = Modifier
                    // weight(1f) 吃掉分段控件下方剩余高度，视口贴到屏幕底（含手势条区域），背景才能真正沉浸。
                    // 不要 fillMaxSize：在 Column 里会按父级全高测量，叠在分段控件下溢出，底部 inset 对不齐。
                    .weight(1f)
                    .fillMaxWidth()
                    // 仅 ime 在 scroll 前消费：键盘弹出时缩视口，焦点框 bringIntoView 才能顶上来。
                    // navigationBars 放 scroll 后（与设置页一致）：内容尾部让位，视口仍铺满手势条区域 → 小白条沉浸。
                    // 若把 ime∪nav 都放 scroll 前，无键盘时视口被抬高，底部会留出一条不沉浸的空白带。
                    .windowInsetsPadding(WindowInsets.ime)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (tab == 0) {
                    ConfigSection(
                        provider = provider,
                        onSave = viewModel::saveByokProvider,
                        onDraftChanged = { saveDraftRef.value = it },
                        onTest = { current ->
                            // 测试连接：先落库草稿，再用最新配置拉取模型验证连通性，不重复落库。
                            scope.launch {
                                fetching = true
                                val probe = current
                                val result = runCatching { viewModel.fetchByokModels(probe) }
                                fetching = false
                                result.onSuccess { models ->
                                    snackbar.showSnackbar(
                                        if (models.isEmpty()) "连接成功，但未返回可用模型"
                                        else "连接成功，发现 ${models.size} 个模型",
                                    )
                                }.onFailure { e ->
                                    snackbar.showSnackbar("连接失败：${e.message ?: "未知错误"}")
                                }
                            }
                        },
                        onDelete = { showDeleteDialog = true },
                    )
                } else {
                    ModelsSection(
                        provider = provider,
                        fetching = fetching,
                        selecting = selectingModels,
                        selectedIds = selectedModelIds,
                        onSelectingChange = { selectingModels = it },
                        onSelectedIdsChange = { selectedModelIds = it },
                        onAutoFetch = {
                            scope.launch {
                                // 切到模型页前若有未保存的配置草稿（用途改动等），先落库再检测。
                                val probe = persistDraft()
                                fetching = true
                                val result = runCatching { viewModel.fetchByokModels(probe!!) }
                                fetching = false
                                result.onSuccess { models ->
                                    if (models.isEmpty()) {
                                        snackbar.showSnackbar("未获取到可用模型")
                                    } else {
                                        fetchResult = models
                                    }
                                }.onFailure { e ->
                                    snackbar.showSnackbar("获取失败：${e.message ?: "未知错误"}")
                                }
                            }
                        },
                        onAddModel = { editingModel = ModelDraft() },
                        onEditModel = { editingModel = ModelDraft.from(it) },
                    )
                }
                Box(Modifier.padding(bottom = 16.dp))
            }
        }
    }

    if (showDeleteDialog && provider != null) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("删除服务") },
            text = { Text("确定删除「${provider.name}」？该服务下的所有模型与密钥将一并移除。") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    viewModel.deleteByokProvider(provider.id)
                    onBack()
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showDeleteDialog = false }) { Text("取消") } },
        )
    }

    if (showBatchDeleteConfirm && provider != null) {
        val count = selectedModelIds.size
        val total = provider.models.size
        AlertDialog(
            onDismissRequest = { showBatchDeleteConfirm = false },
            title = { Text(if (count == total) "删除全部模型" else "删除已选模型") },
            text = {
                Text(
                    if (count == total) "确定删除该服务下全部 $count 个模型？此操作不可撤销。"
                    else "确定删除已选的 $count 个模型？此操作不可撤销。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val ids = selectedModelIds
                        showBatchDeleteConfirm = false
                        val base = saveDraftRef.value ?: provider
                        viewModel.saveByokProvider(
                            base.copy(models = base.models.filterNot { it.id in ids }),
                        )
                        saveDraftRef.value = null
                        selectingModels = false
                        selectedModelIds = emptySet()
                        scope.launch {
                            snackbar.showSnackbar(
                                if (ids.size == 1) "已删除 1 个模型"
                                else "已删除 ${ids.size} 个模型",
                            )
                        }
                    },
                ) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showBatchDeleteConfirm = false }) { Text("取消") }
            },
        )
    }

    val draft = editingModel
    if (draft != null && provider != null) {
        val isImageProvider = provider.purpose == com.molagpt.app.core.model.ByokPurpose.IMAGE
        ModelEditSheet(
            draft = draft,
            isImageProvider = isImageProvider,
            providerBaseUrl = provider.baseUrl,
            onDismiss = { editingModel = null },
            onSave = { saved ->
                val base = (saveDraftRef.value ?: provider!!)
                val model = saved.toModel(base)
                val merged = base.models.filterNot { it.id == model.id } + model
                viewModel.saveByokProvider(base.copy(models = merged.sortedBy { it.id }))
                saveDraftRef.value = null
                editingModel = null
            },
            onDelete = if (draft.isExisting) {
                {
                    val base = (saveDraftRef.value ?: provider!!)
                    viewModel.saveByokProvider(base.copy(models = base.models.filterNot { it.id == draft.modelId }))
                    saveDraftRef.value = null
                    editingModel = null
                }
            } else null,
        )
    }

    // 自动获取后的选择 sheet：用户勾选要添加的模型，确认后落库。
    val fetched = fetchResult
    if (fetched != null && provider != null) {
        val base = saveDraftRef.value ?: provider
        ModelFetchSheet(
            models = fetched,
            existingIds = base.models.map { it.id }.toSet(),
            onDismiss = { fetchResult = null },
            onAdd = { selected ->
                viewModel.addByokModels(base, selected)
                saveDraftRef.value = null
                fetchResult = null
            },
        )
    }
}

private fun defaultChatPathFor(type: ByokProviderType): String = when (type) {
    ByokProviderType.OPENAI_COMPAT -> "v1/chat/completions"
    ByokProviderType.OPENAI_RESPONSE -> "v1/responses"
    ByokProviderType.ANTHROPIC -> "v1/messages"
    ByokProviderType.GEMINI -> "models/{model}:streamGenerateContent"
}

@Composable
private fun ConfigSection(
    provider: ByokProvider,
    onSave: (ByokProvider) -> Unit,
    onDraftChanged: (ByokProvider) -> Unit,
    onTest: (ByokProvider) -> Unit,
    onDelete: () -> Unit,
) {
    var name by remember(provider.id) { mutableStateOf(provider.name) }
    var type by remember(provider.id) { mutableStateOf(provider.type) }
    var purpose by remember(provider.id) { mutableStateOf(provider.purpose) }
    var imageFormat by remember(provider.id) { mutableStateOf(provider.imageFormat) }
    var baseUrl by remember(provider.id) { mutableStateOf(provider.baseUrl) }
    var apiKey by remember(provider.id) { mutableStateOf(provider.apiKey.orEmpty()) }
    var chatPath by remember(provider.id) { mutableStateOf(provider.chatPath) }
    var modelsPath by remember(provider.id) { mutableStateOf(provider.modelsPath) }
    var imagePath by remember(provider.id) { mutableStateOf(provider.imagePath) }
    var imageEditPath by remember(provider.id) { mutableStateOf(provider.imageEditPath) }
    var keyVisible by remember { mutableStateOf(false) }
    var showAdvanced by remember { mutableStateOf(false) }
    var customHeaders by remember(provider.id) { mutableStateOf(provider.customHeaders) }

    val isImage = purpose == com.molagpt.app.core.model.ByokPurpose.IMAGE
    // 图像用途兼容的服务类型：OpenAI 兼容（chat/completions 出图或 /v1/images/generations）与 Gemini（:generateContent 出图）。
    // OpenAI Response 与 Anthropic 不支持原生图像生成，故选「图像」用途时这两类会被禁用。
    val typeSupportsImage = type == com.molagpt.app.core.model.ByokProviderType.OPENAI_COMPAT ||
        type == com.molagpt.app.core.model.ByokProviderType.GEMINI

    fun current(): ByokProvider = provider.copy(
        name = name.trim().ifBlank { "未命名服务" },
        type = type,
        baseUrl = baseUrl.trim(),
        apiKey = apiKey,
        chatPath = chatPath.trim(),
        modelsPath = modelsPath.trim(),
        imagePath = imagePath.trim(),
        purpose = purpose,
        imageFormat = imageFormat,
        imageEditPath = imageEditPath.trim(),
        customHeaders = customHeaders.filter { it.name.isNotBlank() },
    )

    fun reportDraft() = onDraftChanged(current())

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("服务类型", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ByokProviderType.entries.forEach { t ->
                SelectPill(label = protocolLabel(t), selected = type == t, onClick = {
                    type = t
                    // 切到不支持图像的服务类型（Response/Anthropic）时，若当前用途是图像则回落到对话。
                    val imgOk = t == com.molagpt.app.core.model.ByokProviderType.OPENAI_COMPAT ||
                        t == com.molagpt.app.core.model.ByokProviderType.GEMINI
                    if (!imgOk && purpose == com.molagpt.app.core.model.ByokPurpose.IMAGE) {
                        purpose = com.molagpt.app.core.model.ByokPurpose.CHAT
                    }
                    // 协议切换时，若对话/模型路径仍是某个已知默认值（或空），重填为新协议的默认路径；
                    // 用户自定义过的路径保留。与 Desktop ProviderTypeChanged 的已知默认值守卫一致。
                    val knownChatPaths = setOf(
                        "v1/chat/completions", "v1/responses", "v1/messages",
                        "models/{model}:streamGenerateContent",
                    )
                    if (chatPath.isBlank() || chatPath.trim() in knownChatPaths) {
                        chatPath = defaultChatPathFor(t)
                    }
                    val knownModelsPaths = setOf("v1/models", "models")
                    if (modelsPath.isBlank() || modelsPath.trim() in knownModelsPaths) {
                        modelsPath = if (t == com.molagpt.app.core.model.ByokProviderType.GEMINI) "models" else "v1/models"
                    }
                    reportDraft()
                })
            }
        }
        Text("用途", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            com.molagpt.app.core.model.ByokPurpose.entries.forEach { p ->
                // 仅在类型支持图像时才可选「图像」用途。
                val allowed = p == com.molagpt.app.core.model.ByokPurpose.CHAT || typeSupportsImage
                SelectPill(
                    label = if (p == com.molagpt.app.core.model.ByokPurpose.CHAT) "对话" else "图像",
                    selected = purpose == p,
                    enabled = allowed,
                    onClick = {
                        purpose = p
                        reportDraft()
                    },
                )
            }
        }
        // 图像用途 + OpenAI 兼容才显示接口格式选择（Gemini 固定 :generateContent 出图，无需选择）。
        if (isImage && type == com.molagpt.app.core.model.ByokProviderType.OPENAI_COMPAT) {
            Text("接口格式", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                com.molagpt.app.core.model.ByokImageFormat.entries.forEach { f ->
                    SelectPill(
                        label = if (f == com.molagpt.app.core.model.ByokImageFormat.OPENAI_IMAGES) "OpenAI 图像接口" else "对话补全出图",
                        selected = imageFormat == f,
                        onClick = {
                            imageFormat = f
                            reportDraft()
                        },
                    )
                }
            }
        }
        // OpenRouter 图像提示：OpenRouter 出图走 /v1/chat/completions 而非 /v1/images/generations。
        // 检测到 OpenRouter 时自动展示（不占用焦点、非弹窗），并引导切换到「对话补全出图」格式。
        if (isImage && baseUrl.contains("openrouter.ai", ignoreCase = true)) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)),
            ) {
                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
                    Icon(
                        Icons.Filled.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 2.dp).size(18.dp),
                    )
                    Column(modifier = Modifier.padding(start = 10.dp)) {
                        Text(
                            "OpenRouter 图像使用 /v1/chat/completions",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            "OpenRouter 的图像生成走对话补全端点而非 /v1/images/generations，请将接口格式选为「对话补全出图」。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        if (imageFormat != com.molagpt.app.core.model.ByokImageFormat.OPENAI_CHAT_IMAGE) {
                            TextButton(onClick = {
                                imageFormat = com.molagpt.app.core.model.ByokImageFormat.OPENAI_CHAT_IMAGE
                                reportDraft()
                            }) { Text("切换为「对话补全出图」") }
                        }
                    }
                }
            }
        }
        OutlinedTextField(
            value = name, onValueChange = { name = it; reportDraft() },
            label = { Text("显示名称") }, singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = baseUrl, onValueChange = { baseUrl = it; reportDraft() },
            label = { Text("API 地址 (Base URL)") }, singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = apiKey, onValueChange = { apiKey = it; reportDraft() },
            label = { Text("API 密钥（仅存于本机）") }, singleLine = true,
            visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                TextButton(onClick = { keyVisible = !keyVisible }) {
                    Text(if (keyVisible) "隐藏" else "显示")
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )

        TextButton(onClick = { showAdvanced = !showAdvanced }) {
            Text(if (showAdvanced) "收起高级设置" else "展开高级设置（路径）")
        }
        if (showAdvanced) {
            OutlinedTextField(
                value = chatPath, onValueChange = { chatPath = it; reportDraft() },
                label = { Text(if (isImage && imageFormat == com.molagpt.app.core.model.ByokImageFormat.OPENAI_CHAT_IMAGE) "出图路径（chat/completions）" else "对话路径") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = modelsPath, onValueChange = { modelsPath = it; reportDraft() },
                label = { Text("模型列表路径") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (isImage && imageFormat == com.molagpt.app.core.model.ByokImageFormat.OPENAI_IMAGES) {
                OutlinedTextField(
                    value = imagePath, onValueChange = { imagePath = it; reportDraft() },
                    label = { Text("图像生成路径") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = imageEditPath, onValueChange = { imageEditPath = it; reportDraft() },
                    label = { Text("图像编辑路径") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Text("自定义请求头", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "附加到该服务全部请求（对话 / 模型列表 / 测试），auth 之后追加。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            customHeaders.forEachIndexed { index, header ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = header.name,
                        onValueChange = { v ->
                            customHeaders = customHeaders.toMutableList().also { it[index] = header.copy(name = v) }
                            reportDraft()
                        },
                        label = { Text("名称") }, singleLine = true, modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = header.value,
                        onValueChange = { v ->
                            customHeaders = customHeaders.toMutableList().also { it[index] = header.copy(value = v) }
                            reportDraft()
                        },
                        label = { Text("值") }, singleLine = true, modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = {
                        customHeaders = customHeaders.toMutableList().also { it.removeAt(index) }
                        reportDraft()
                    }) { Text("删除") }
                }
            }
            TextButton(onClick = {
                customHeaders = customHeaders + com.molagpt.app.core.model.CustomHeader()
                reportDraft()
            }) { Text("+ 添加请求头") }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { onTest(current()) }, modifier = Modifier.weight(1f)) {
                Text("测试连接")
            }
            Button(onClick = { onSave(current()) }, modifier = Modifier.weight(1f)) {
                Text("保存")
            }
        }
        OutlinedButton(
            onClick = onDelete,
            modifier = Modifier.fillMaxWidth(),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
        ) {
            Text("删除此服务", color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun ModelsSection(
    provider: ByokProvider,
    fetching: Boolean,
    selecting: Boolean,
    selectedIds: Set<String>,
    onSelectingChange: (Boolean) -> Unit,
    onSelectedIdsChange: (Set<String>) -> Unit,
    onAutoFetch: () -> Unit,
    onAddModel: () -> Unit,
    onEditModel: (ProviderModel) -> Unit,
) {
    val modelIds = remember(provider.models) { provider.models.map { it.id }.toSet() }
    // 列表变化时清掉已不存在的勾选；删空后退出选择模式。
    LaunchedEffect(modelIds, selecting) {
        val pruned = selectedIds.intersect(modelIds)
        if (pruned != selectedIds) onSelectedIdsChange(pruned)
        if (modelIds.isEmpty() && selecting) onSelectingChange(false)
    }
    val allSelected = modelIds.isNotEmpty() && modelIds.all { it in selectedIds }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (!selecting) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = onAutoFetch, enabled = !fetching, modifier = Modifier.weight(1f)) {
                    Text(if (fetching) "获取中…" else "自动获取")
                }
                OutlinedButton(onClick = onAddModel, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.padding(end = 4.dp))
                    Text("手动添加")
                }
            }
        }
        if (provider.models.isEmpty()) {
            Text(
                "还没有模型，点击「自动获取」或「手动添加」。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 24.dp),
            )
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (selecting) "已选 ${selectedIds.size} · 共 ${provider.models.size}"
                    else "已添加 ${provider.models.size} 个",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (selecting) {
                        TextButton(
                            onClick = {
                                onSelectedIdsChange(if (allSelected) emptySet() else modelIds)
                            },
                        ) {
                            Text(if (allSelected) "取消全选" else "全选")
                        }
                        TextButton(
                            onClick = {
                                onSelectingChange(false)
                                onSelectedIdsChange(emptySet())
                            },
                        ) { Text("取消") }
                    } else {
                        TextButton(onClick = { onSelectingChange(true) }) { Text("选择") }
                    }
                }
            }
            provider.models.forEach { model ->
                ModelCard(
                    model = model,
                    selectionMode = selecting,
                    selected = model.id in selectedIds,
                    onClick = {
                        if (selecting) {
                            onSelectedIdsChange(
                                if (model.id in selectedIds) selectedIds - model.id
                                else selectedIds + model.id,
                            )
                        } else {
                            onEditModel(model)
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun ModelCard(
    model: ProviderModel,
    onClick: () -> Unit,
    selectionMode: Boolean = false,
    selected: Boolean = false,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(15.dp),
        color = if (selectionMode && selected) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
        },
        border = BorderStroke(
            1.dp,
            if (selectionMode && selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(model.displayName, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    model.id,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
                val tags = buildList {
                    if (model.supportsThinking || model.supportsReasoningEffort) add("推理")
                    if (model.supportsToolCalling) add("工具")
                    if (model.supportsVision) add("视觉")
                    if (model.supportsImageGeneration) add("图像")
                    if (model.supportsImageEdit) add("图像编辑")
                }
                if (tags.isNotEmpty()) {
                    Row(
                        modifier = Modifier.padding(top = 7.dp),
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        tags.forEach { AbilityTag(it) }
                    }
                }
            }
            if (selectionMode) {
                // onCheckedChange = null：勾选交互统一由整行 clickable 处理，避免 Checkbox 再触发一次导致连点抵消。
                Checkbox(checked = selected, onCheckedChange = null)
            } else {
                ForwardChevron()
            }
        }
    }
}

@Composable
private fun AbilityTag(label: String) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ModelEditSheet(
    draft: ModelDraft,
    onDismiss: () -> Unit,
    onSave: (ModelDraft) -> Unit,
    onDelete: (() -> Unit)?,
    isImageProvider: Boolean = false,
    providerBaseUrl: String = "",
) {
    var modelId by remember { mutableStateOf(draft.modelId) }
    var displayName by remember { mutableStateOf(draft.displayName) }
    var isImage by remember { mutableStateOf(if (isImageProvider) true else draft.isImage) }
    var imageEdit by remember { mutableStateOf(draft.imageEdit) }
    var vision by remember { mutableStateOf(draft.vision) }
    var thinking by remember { mutableStateOf(draft.thinking) }
    var thinkingKind by remember { mutableStateOf(draft.thinkingKind) }
    var alwaysOn by remember { mutableStateOf(draft.alwaysOn) }
    var detectSource by remember { mutableStateOf(draft.detectSource) }
    var manualOverride by remember { mutableStateOf(draft.manualOverride) }
    var manualOpen by remember {
        mutableStateOf(draft.manualOverride || !ThinkingKinds.isHighConfidence(draft.detectSource))
    }
    var effortLevels by remember {
        mutableStateOf(
            draft.effortLevels.ifEmpty { ThinkingKinds.effortLevelsFor(draft.thinkingKind) },
        )
    }
    var defaultEffort by remember {
        mutableStateOf(
            draft.defaultEffort.ifBlank { ThinkingKinds.defaultEffortFor(draft.thinkingKind) },
        )
    }
    var customEffortInput by remember { mutableStateOf("") }
    var tools by remember { mutableStateOf(draft.tools) }
    var customBody by remember { mutableStateOf(draft.customBody) }

    fun applyAutoDetect() {
        val auto = ThinkingKinds.autoConfigFor(modelId, providerBaseUrl, supportedParams = null)
            ?: ThinkingKinds.configFor(
                ThinkingKinds.inferFromModelId(modelId).takeIf { it != ThinkingParamKind.NONE }
                    ?: ThinkingKinds.hostInferredKind(providerBaseUrl)
                    ?: ThinkingParamKind.OPENAI_REASONING_EFFORT,
                alwaysOn = ThinkingKinds.isKimiK3(modelId),
                detectSource = ThinkingDetectSource.HEURISTIC,
            )
        thinkingKind = auto.kind
        alwaysOn = auto.alwaysOn
        detectSource = auto.detectSource
        manualOverride = false
        effortLevels = ThinkingKinds.resolveEffortLevels(auto)
        defaultEffort = ThinkingKinds.resolveDefaultEffort(auto)
        thinking = auto.kind != ThinkingParamKind.NONE
        manualOpen = !ThinkingKinds.isHighConfidence(auto.detectSource)
    }

    fun applyBehavior(behavior: ThinkingBehavior) {
        if (behavior == ThinkingBehavior.NONE) {
            thinkingKind = ThinkingParamKind.NONE
            alwaysOn = false
            effortLevels = emptyList()
            defaultEffort = ""
            manualOverride = true
            detectSource = ThinkingDetectSource.OVERRIDE
            return
        }
        val kind = ThinkingKinds.kindForBehavior(behavior, preferred = thinkingKind)
        thinkingKind = kind
        alwaysOn = ThinkingKinds.isKimiK3(modelId) && behavior == ThinkingBehavior.EFFORT
        detectSource = ThinkingDetectSource.OVERRIDE
        manualOverride = true
        effortLevels = if (alwaysOn) listOf("low", "high", "max") else ThinkingKinds.effortLevelsFor(kind)
        defaultEffort = if (alwaysOn) "max" else ThinkingKinds.defaultEffortFor(kind).let { d ->
            if (d in effortLevels) d else effortLevels.firstOrNull().orEmpty()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        contentWindowInsets = { WindowInsets(0) },
    ) {
        // 键盘弹着时返回先收键盘，不关弹层（丢输入内容）。
        ImeDismissBackHandler()
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
                .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars)),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(if (draft.isExisting) "编辑模型" else "添加模型", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                value = modelId, onValueChange = { modelId = it },
                label = { Text("模型 ID") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                enabled = !draft.isExisting,
            )
            OutlinedTextField(
                value = displayName, onValueChange = { displayName = it },
                label = { Text("显示名称") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (isImageProvider) {
                Text("图像能力", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SelectPill("支持编辑", selected = imageEdit, onClick = { imageEdit = !imageEdit })
                }
                Text(
                    "图像用途模型用于图像生成/编辑；是否支持编辑将影响图像编辑功能可用性。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text("能力", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SelectPill("推理", selected = thinking, onClick = {
                        thinking = !thinking
                        if (thinking && thinkingKind == ThinkingParamKind.NONE) applyAutoDetect()
                    })
                    SelectPill("工具", selected = tools, onClick = { tools = !tools })
                    SelectPill("视觉", selected = vision, onClick = { vision = !vision })
                }
                if (thinking) {
                    Text("推理方式", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val effectiveKind = ThinkingKinds.wireKind(thinkingKind, providerBaseUrl)
                    val behavior = ThinkingKinds.behaviorOf(effectiveKind)
                    val highConf = ThinkingKinds.isHighConfidence(detectSource) || manualOverride
                    ThinkingDetectCard(
                        behaviorLabel = ThinkingKinds.behaviorLabel(behavior),
                        source = if (manualOverride) ThinkingDetectSource.OVERRIDE else detectSource,
                        highConfidence = highConf,
                        aggregating = ThinkingKinds.isAggregatingGateway(providerBaseUrl),
                        nativeBudget = ThinkingKinds.isBudgetKind(thinkingKind),
                        onRestoreAuto = { applyAutoDetect() },
                    )
                    TextButton(onClick = { manualOpen = !manualOpen }) {
                        Text(
                            if (manualOpen) "收起手动指定" else "手动指定（高级）",
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                    AnimatedVisibility(
                        visible = manualOpen,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically(),
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            BehaviorOption(
                                title = "自动（推荐）",
                                subtitle = "按服务商能力与模型识别",
                                selected = !manualOverride,
                                onClick = { applyAutoDetect() },
                            )
                            BehaviorOption(
                                title = "强度档位",
                                subtitle = "低 / 中 / 高，常出现于 OpenAI 及其兼容 API",
                                selected = manualOverride && behavior == ThinkingBehavior.EFFORT,
                                onClick = { applyBehavior(ThinkingBehavior.EFFORT) },
                            )
                            BehaviorOption(
                                title = "思考预算",
                                subtitle = "按档位分配固定思考 token budget，常出现于 Qwen API",
                                selected = manualOverride && behavior == ThinkingBehavior.BUDGET,
                                onClick = { applyBehavior(ThinkingBehavior.BUDGET) },
                            )
                            BehaviorOption(
                                title = "仅开关",
                                subtitle = "只支持开 / 关，无强度档位",
                                selected = manualOverride && behavior == ThinkingBehavior.TOGGLE,
                                onClick = { applyBehavior(ThinkingBehavior.TOGGLE) },
                            )
                        }
                    }

                    val showEffortEditor = effectiveKind != ThinkingParamKind.NONE &&
                        effectiveKind != ThinkingParamKind.KIMI
                    if (showEffortEditor) {
                        val isBudget = ThinkingKinds.showAsBudget(
                            ThinkingConfig(kind = thinkingKind),
                            providerBaseUrl,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "可选强度",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(
                                onClick = {
                                    if (alwaysOn) {
                                        effortLevels = listOf("low", "high", "max")
                                        defaultEffort = "max"
                                    } else {
                                        effortLevels = ThinkingKinds.effortLevelsFor(thinkingKind)
                                        defaultEffort = ThinkingKinds.defaultEffortFor(thinkingKind).let { d ->
                                            if (d in effortLevels) d else effortLevels.firstOrNull().orEmpty()
                                        }
                                    }
                                },
                            ) { Text("重置") }
                        }
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            effortLevels.forEach { level ->
                                EffortLevelChip(
                                    level = level,
                                    isDefault = level == defaultEffort,
                                    budgetTokens = if (isBudget) ThinkingKinds.budgetFor(thinkingKind, level) else null,
                                    removable = effortLevels.size > 1,
                                    onSetDefault = { defaultEffort = level },
                                    onRemove = {
                                        val next = effortLevels.filter { it != level }
                                        effortLevels = next
                                        if (defaultEffort !in next) defaultEffort = next.firstOrNull().orEmpty()
                                    },
                                )
                            }
                        }
                        Text(
                            "默认推理强度设置",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (!isBudget) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                OutlinedTextField(
                                    value = customEffortInput,
                                    onValueChange = { customEffortInput = it },
                                    label = { Text("添加档位") },
                                    placeholder = { Text("ultra …") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f),
                                )
                                OutlinedButton(
                                    onClick = {
                                        val next = ThinkingKinds.normalizeEffortLevels(
                                            effortLevels + customEffortInput.trim().lowercase(),
                                        )
                                        if (next != effortLevels) {
                                            effortLevels = next
                                            if (defaultEffort !in next) {
                                                defaultEffort = next.firstOrNull().orEmpty()
                                            }
                                        }
                                        customEffortInput = ""
                                    },
                                    enabled = customEffortInput.trim().isNotEmpty(),
                                ) { Text("添加") }
                            }
                        }
                        ReasoningNoteCard(
                            text = when {
                                ThinkingKinds.isAggregatingGateway(providerBaseUrl) &&
                                    ThinkingKinds.isBudgetKind(thinkingKind) && manualOverride ->
                                    "该服务商会统一按强度处理，预算 token 不会精确生效。"
                                ThinkingKinds.isAggregatingGateway(providerBaseUrl) &&
                                    ThinkingKinds.isBudgetKind(thinkingKind) && !manualOverride ->
                                    "已按 OpenRouter 自动折算为强度档位。"
                                alwaysOn -> "该模型始终开启推理，不可关闭。"
                                isBudget -> "强度越高，分配的思考额度越多。"
                                else -> "对话时可在这些强度间快速切换。可添加服务商支持的自定义档位。"
                            },
                            warn = ThinkingKinds.isAggregatingGateway(providerBaseUrl) &&
                                ThinkingKinds.isBudgetKind(thinkingKind) && manualOverride,
                        )
                    } else if (effectiveKind == ThinkingParamKind.KIMI) {
                        ReasoningNoteCard(
                            text = "该模型只支持开 / 关推理，不可调整推理强度。",
                        )
                    }
                }
            }

            Text("参数覆写（高级）", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            customBody.forEachIndexed { index, param ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = param.key,
                            onValueChange = { v ->
                                customBody = customBody.toMutableList().also { it[index] = param.copy(key = v) }
                            },
                            label = { Text("键") }, singleLine = true, modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = param.value,
                            onValueChange = { v ->
                                customBody = customBody.toMutableList().also { it[index] = param.copy(value = v) }
                            },
                            label = { Text("值") }, singleLine = true, modifier = Modifier.weight(1f),
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val types = listOf("string", "number", "boolean", "json")
                        OutlinedButton(onClick = {
                            val next = types[(types.indexOf(param.type).coerceAtLeast(0) + 1) % types.size]
                            customBody = customBody.toMutableList().also { it[index] = param.copy(type = next) }
                        }) { Text("类型：${param.type.ifBlank { "string" }}", style = MaterialTheme.typography.labelSmall) }
                        TextButton(onClick = {
                            customBody = customBody.toMutableList().also { it.removeAt(index) }
                        }) { Text("删除") }
                    }
                }
            }
            TextButton(onClick = { customBody = customBody + CustomBodyParam() }) { Text("+ 添加参数") }
            Button(
                onClick = {
                    if (modelId.isNotBlank()) {
                        onSave(
                            draft.copy(
                                modelId = modelId.trim(),
                                displayName = displayName.trim().ifBlank { modelId.trim() },
                                isImage = isImage,
                                imageEdit = imageEdit,
                                vision = vision,
                                thinking = thinking,
                                thinkingKind = thinkingKind,
                                effortLevels = effortLevels,
                                defaultEffort = defaultEffort,
                                alwaysOn = alwaysOn,
                                detectSource = detectSource,
                                manualOverride = manualOverride,
                                tools = tools,
                                customBody = customBody,
                            ),
                        )
                    }
                },
                enabled = modelId.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (draft.isExisting) "保存" else "添加") }
            if (onDelete != null) {
                OutlinedButton(
                    onClick = onDelete,
                    modifier = Modifier.fillMaxWidth(),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                ) { Text("删除此模型", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

@Composable
private fun ThinkingDetectCard(
    behaviorLabel: String,
    source: ThinkingDetectSource?,
    highConfidence: Boolean,
    aggregating: Boolean,
    nativeBudget: Boolean,
    onRestoreAuto: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val isOverride = source == ThinkingDetectSource.OVERRIDE
    val icon = if (!highConfidence) Icons.Filled.Warning else Icons.Filled.Check
    val tint = if (!highConfidence) cs.tertiary else cs.primary
    val bg = if (!highConfidence) cs.tertiaryContainer.copy(alpha = 0.55f) else cs.primaryContainer.copy(alpha = 0.45f)
    val sourceText = when (source) {
        ThinkingDetectSource.CAPABILITY -> "已读取服务商能力表识别推理配置"
        ThinkingDetectSource.HOST -> "已按服务商识别推理配置"
        ThinkingDetectSource.HEURISTIC -> "自动推测推理配置"
        ThinkingDetectSource.OVERRIDE -> "已手动指定推理配置"
        null -> "未标注来源"
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(bg)
            .padding(13.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
            Text(
                if (isOverride) "手动：$behaviorLabel" else "识别为 $behaviorLabel",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = cs.onSurface,
            )
        }
        Text(
            text = buildString {
                append(sourceText)
                if (!highConfidence && !isOverride) append(" → 如有异常请手动指定")
                if (aggregating && nativeBudget && !isOverride) append(" · 已折算为强度")
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (highConfidence) tint else cs.onTertiaryContainer,
        )
        if (isOverride) {
            TextButton(onClick = onRestoreAuto) { Text("恢复自动") }
        }
    }
}

@Composable
private fun BehaviorOption(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) cs.primary.copy(alpha = 0.12f) else cs.surfaceVariant.copy(alpha = 0.55f))
            .border(
                1.dp,
                if (selected) cs.primary.copy(alpha = 0.45f) else cs.outline.copy(alpha = 0.14f),
                RoundedCornerShape(12.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(modifier = Modifier.width(4.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
    }
}

/**
 * 推理档位 chip：点本体 = 设为默认（星标 + 主色高亮），独立 × 移除（仅剩一档时锁定）。
 * 预算类 kind 附带映射 token 短格式（如 8K）。
 */
@Composable
private fun EffortLevelChip(
    level: String,
    isDefault: Boolean,
    budgetTokens: Int?,
    removable: Boolean,
    onSetDefault: () -> Unit,
    onRemove: () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(11.dp)
    val containerColor by animateColorAsState(
        targetValue = if (isDefault) colorScheme.primary.copy(alpha = 0.14f) else colorScheme.surfaceVariant.copy(alpha = 0.72f),
        label = "effortChipContainer",
    )
    val contentColor = if (isDefault) colorScheme.primary else colorScheme.onSurface
    val borderColor = if (isDefault) colorScheme.primary.copy(alpha = 0.4f) else colorScheme.outline.copy(alpha = 0.16f)

    Row(
        modifier = Modifier
            .clip(shape)
            .background(containerColor)
            .border(1.dp, borderColor, shape)
            .clickable { onSetDefault() }
            .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (isDefault) {
            Icon(
                imageVector = Icons.Filled.Star,
                contentDescription = "默认档位",
                tint = contentColor,
                modifier = Modifier.size(13.dp),
            )
        }
        Text(
            text = ThinkingKinds.effortLabel(level),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = contentColor,
        )
        Text(
            text = level,
            style = MaterialTheme.typography.labelSmall,
            color = contentColor.copy(alpha = 0.55f),
        )
        if (budgetTokens != null) {
            Text(
                text = ThinkingKinds.formatBudgetShort(budgetTokens),
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = contentColor.copy(alpha = if (isDefault) 0.85f else 0.6f),
            )
        }
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable(enabled = removable) { onRemove() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "移除档位",
                tint = colorScheme.onSurfaceVariant.copy(alpha = if (removable) 0.8f else 0.28f),
                modifier = Modifier.size(12.dp),
            )
        }
    }
}

/** 推理段说明卡（精简人话；warn 时用警告色）。 */
@Composable
private fun ReasoningNoteCard(text: String, warn: Boolean = false) {
    val cs = MaterialTheme.colorScheme
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = if (warn) cs.onTertiaryContainer else cs.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (warn) cs.tertiaryContainer.copy(alpha = 0.55f)
                else cs.surfaceVariant.copy(alpha = 0.5f),
            )
            .padding(horizontal = 13.dp, vertical = 11.dp),
    )
}

/**
 * 自动获取后的模型选择 sheet：列出拉取到的模型，勾选添加。
 * 默认不勾选任何模型；顶部搜索框按 id/显示名/能力过滤；全选作用于过滤后可见且未存在的模型。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelFetchSheet(
    models: List<ProviderModel>,
    existingIds: Set<String>,
    onDismiss: () -> Unit,
    onAdd: (List<ProviderModel>) -> Unit,
) {
    // 默认不勾选——用户必须主动选要添加的模型。
    var selected by remember(models) { mutableStateOf(emptySet<String>()) }
    var query by remember { mutableStateOf("") }

    fun capabilitySummary(m: ProviderModel): String {
        val parts = mutableListOf<String>()
        if (m.supportsChat) parts.add("对话")
        if (m.supportsVision) parts.add("视觉")
        if (m.supportsThinking) parts.add("思考")
        if (m.supportsToolCalling) parts.add("工具")
        if (m.supportsImageGeneration) parts.add("图像")
        if (m.supportsImageEdit) parts.add("图像编辑")
        return if (parts.isEmpty()) "基础" else parts.joinToString("·")
    }

    val filtered = remember(models, query) {
        val q = query.trim()
        if (q.isBlank()) models
        else models.filter {
            it.id.contains(q, ignoreCase = true) ||
                it.displayName.contains(q, ignoreCase = true) ||
                capabilitySummary(it).contains(q, ignoreCase = true)
        }
    }
    val selectable = filtered.filter { it.id !in existingIds }
    val allSelectableSelected = selectable.isNotEmpty() && selectable.all { it.id in selected }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        // 自带 inset 置 0，底部留白只由内容的 navigationBarsPadding 处理一次（否则全屏展开时底部多一条白条把按钮顶出屏幕）。
        contentWindowInsets = { WindowInsets(0) },
    ) {
        // 键盘弹着时返回先收键盘，不关弹层（搜索框在这里）。
        ImeDismissBackHandler()
        Column(
            modifier = Modifier
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
                .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars)),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("选择要添加的模型", style = MaterialTheme.typography.titleLarge)
            Text(
                "已勾选 ${selected.size} 个 · 共 ${models.size} 个（${existingIds.intersect(models.map { it.id }.toSet()).size} 个已存在）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("搜索模型") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "过滤后 ${filtered.size} 个",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(
                    onClick = {
                        selected = if (allSelectableSelected) {
                            selected - selectable.map { it.id }.toSet()
                        } else {
                            selected + selectable.map { it.id }.toSet()
                        }
                    },
                    enabled = selectable.isNotEmpty(),
                ) {
                    Text(if (allSelectableSelected) "取消全选" else "全选可见")
                }
            }
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .weight(1f, fill = false),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                filtered.forEach { model ->
                    val alreadyExists = model.id in existingIds
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(model.displayName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                model.id,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                capabilitySummary(model),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            )
                        }
                        if (alreadyExists) {
                            Text("跳过", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                        } else {
                            androidx.compose.material3.Checkbox(
                                checked = model.id in selected,
                                onCheckedChange = { checked ->
                                    selected = if (checked) selected + model.id else selected - model.id
                                },
                            )
                        }
                    }
                }
                if (filtered.isEmpty()) {
                    Text(
                        "无匹配模型",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("取消") }
                Button(
                    onClick = {
                        onAdd(models.filter { it.id in selected && it.id !in existingIds })
                    },
                    enabled = selected.any { it !in existingIds },
                    modifier = Modifier.weight(1f),
                ) { Text("添加已选") }
            }
        }
    }
}

/** 模型编辑表单的中间态。与 [ProviderModel] 互转，避免直接在 UI 持有全部字段。 */
private data class ModelDraft(
    val modelId: String = "",
    val displayName: String = "",
    val isImage: Boolean = false,
    val imageEdit: Boolean = false,
    val vision: Boolean = false,
    val thinking: Boolean = false,
    val thinkingKind: ThinkingParamKind = ThinkingParamKind.NONE,
    val effortLevels: List<String> = emptyList(),
    val defaultEffort: String = "",
    val alwaysOn: Boolean = false,
    val detectSource: ThinkingDetectSource? = null,
    val manualOverride: Boolean = false,
    val tools: Boolean = true,
    val customBody: List<CustomBodyParam> = emptyList(),
    val isExisting: Boolean = false,
) {
    fun toModel(provider: ByokProvider): ProviderModel = ProviderModel(
        id = modelId,
        displayName = displayName.ifBlank { modelId },
        apiUrl = provider.chatPath,
        supportsVision = vision,
        supportsThinking = thinking,
        supportsReasoningEffort = thinking && ThinkingKinds.effortLevelsFor(thinkingKind).isNotEmpty(),
        supportsToolCalling = tools,
        supportsImageGeneration = isImage,
        supportsImageEdit = isImage && imageEdit,
        supportsChat = !isImage,
        providerId = provider.id,
        providerName = provider.name,
        providerKind = ProviderKind.BYOK,
        thinkingConfig = if (!isImage && thinking) {
            val cfg = ThinkingConfig(
                kind = thinkingKind,
                effortLevels = ThinkingKinds.normalizeEffortLevels(effortLevels)
                    .ifEmpty { ThinkingKinds.effortLevelsFor(thinkingKind) },
                defaultEffort = defaultEffort,
                alwaysOn = alwaysOn || ThinkingKinds.isKimiK3(modelId),
                detectSource = detectSource,
                manualOverride = manualOverride,
            )
            cfg.copy(defaultEffort = ThinkingKinds.resolveDefaultEffort(cfg))
        } else null,
        customBody = customBody.filter { it.key.isNotBlank() },
    )

    companion object {
        fun from(model: ProviderModel): ModelDraft {
            val tcfg = model.thinkingConfig
            return ModelDraft(
                modelId = model.id,
                displayName = model.displayName.takeIf { it != model.id }.orEmpty(),
                isImage = !model.supportsChat,
                imageEdit = model.supportsImageEdit,
                vision = model.supportsVision,
                thinking = model.supportsThinking || model.supportsReasoningEffort,
                thinkingKind = tcfg?.kind ?: ThinkingParamKind.NONE,
                effortLevels = tcfg?.let { ThinkingKinds.resolveEffortLevels(it) }.orEmpty(),
                defaultEffort = tcfg?.let { ThinkingKinds.resolveDefaultEffort(it) }.orEmpty(),
                alwaysOn = tcfg?.alwaysOn == true || ThinkingKinds.isKimiK3(model.id),
                detectSource = tcfg?.detectSource,
                manualOverride = tcfg?.manualOverride == true,
                tools = model.supportsToolCalling,
                customBody = model.customBody,
                isExisting = true,
            )
        }
    }
}
