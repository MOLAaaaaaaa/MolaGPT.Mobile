package com.molagpt.app.feature.chat

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.molagpt.app.core.model.AttachmentMime
import com.molagpt.app.core.model.Persona
import com.molagpt.app.core.model.ProviderKind
import com.molagpt.app.core.model.ProviderModel
import com.molagpt.app.feature.chat.persona.PersonaPickerSheet
import com.molagpt.app.feature.chat.persona.PersonaWelcome
import com.molagpt.app.feature.file.CameraCapture
import com.molagpt.app.feature.file.ImagePreviewOverlay
import com.molagpt.app.feature.file.LocalAnimatedVisibilityScope
import com.molagpt.app.feature.file.LocalImagePreviewUrl
import com.molagpt.app.feature.file.LocalSharedTransitionScope

/**
 * 一次多选图片的上限。系统 Photo Picker 自身也有平台上限
 * （`MediaStore.getPickImagesMaxLimit()`），取两者较小值生效，这里只是别让用户一次塞太多。
 */
private const val MAX_IMAGES_PER_PICK = 9

/**
 * 聊天页（单 Activity 内的主屏）。顶栏：菜单(打开会话抽屉) + 模型选择下拉；
 * 中间消息列表；底部输入框。主聊天体验使用原生 Compose。
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.animation.ExperimentalSharedTransitionApi::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    enterToSend: Boolean,
    showAgentControlShortcut: Boolean,
    showImageWorkbenchShortcut: Boolean,
    onOpenDrawer: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenPersonaManagement: () -> Unit,
    onAuthExpired: () -> Unit,
    onNewChatWithModel: (modelId: String, providerId: String?, kind: ProviderKind, personaId: String?) -> Unit,
    onNewChat: () -> Unit,
    onOpenAgentControl: () -> Unit = {},
    onOpenImageWorkbench: () -> Unit = {},
    /** 打开 BYOK 当前模型的推理参数编辑页。 */
    onOpenByokModelSettings: (providerId: String, modelId: String) -> Unit = { _, _ -> },
    drawerOpen: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val activePersona by viewModel.activePersona.collectAsStateWithLifecycle()
    val personas by viewModel.personas.collectAsStateWithLifecycle()
    var modelMenuOpen by remember { mutableStateOf(false) }
    var personaSheetOpen by remember { mutableStateOf(false) }
    var pendingCrossModel by remember { mutableStateOf<ProviderModel?>(null) }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val density = LocalDensity.current
    val imeVisible = WindowInsets.ime.getBottom(density) > 0
    // 阵营做成徽章，副标题只留模型名：顶栏 title 区被汉堡和 3~4 个图标夹住，
    // 360dp 下仅约 152dp，再拼提供商名（下拉分组标题里已有）会把模型名挤成「ge…」。
    val modelKindLabel = if (state.providerKind == ProviderKind.BYOK) "BYOK" else "MolaGPT"
    // 已有明确选中模型时不覆盖为「正在获取」；仅当前阵营是 MolaGPT 且尚未选中时才提示。
    val modelNameText = state.selectedModel?.displayName
        ?: if (state.providerKind == ProviderKind.MOLAGPT && state.isModelRefreshing) {
            "正在获取模型"
        } else {
            "选择模型"
        }
    val conversationTitle = state.title.ifBlank { "新对话" }
    /** 空白会话：无消息、无未发送附件、且不在加载历史中；此时跨阵营切换模型不弹确认窗。 */
    val isBlankConversation = state.messages.isEmpty() &&
        state.pendingAttachments.isEmpty() &&
        !state.isLoadingHistory
    /** 已加载具体对话：有消息历史。此时返回应开启新对话，顶栏显示新建按钮。 */
    val isActiveConversation = state.messages.isNotEmpty() && !state.isLoadingHistory
    val context = LocalContext.current
    val startNewChat = {
        Toast.makeText(context, "已开启新对话", Toast.LENGTH_SHORT).show()
        onNewChat()
    }
    // —— 附件三条路径 ——
    // 都不需要任何权限：Photo Picker 零权限、SAF 自己就是授权入口、拍照委托系统相机 app。
    // 见 AndroidManifest 里的说明。

    /**
     * 拍照的目标文件。必须 [rememberSaveable]：相机是另一个进程的 Activity，本进程在低内存
     * 机器上会被回收重建，普通 remember 回来就是 null，照片明明拍好了却取不到。
     */
    var pendingPhotoUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    val takePhoto = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { saved ->
        val target = pendingPhotoUri
        pendingPhotoUri = null
        // saved=false 表示用户在相机里取消了，空文件留给 CameraCapture 下次清理。
        if (saved && target != null) viewModel.attachFile(target)
    }
    val pickImages = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MAX_IMAGES_PER_PICK),
    ) { uris -> viewModel.attachFiles(uris) }
    val pickDocuments = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> viewModel.attachFiles(uris) }

    /** 系统选择器在个别定制 ROM 上会缺失或被禁用，起不来就提示而不是崩掉。 */
    val launchPicker = { what: String, block: () -> Unit ->
        runCatching(block).onFailure {
            Toast.makeText(context, "无法打开$what", Toast.LENGTH_SHORT).show()
        }
        Unit
    }

    if (state.authExpired) {
        androidx.compose.runtime.LaunchedEffect(Unit) { onAuthExpired() }
    }

    BackHandler(enabled = !drawerOpen && (imeVisible || modelMenuOpen || isActiveConversation)) {
        if (modelMenuOpen) {
            modelMenuOpen = false
        } else if (imeVisible) {
            keyboard?.hide()
            focusManager.clearFocus()
        } else if (isActiveConversation) {
            startNewChat()
        }
    }

    pendingCrossModel?.let { model ->
        val targetLabel = if (model.providerKind == ProviderKind.BYOK) "自定义 API · ${model.providerName}" else "MolaGPT"
        AlertDialog(
            onDismissRequest = { pendingCrossModel = null },
            title = { Text("切换到 $targetLabel") },
            text = { Text("切换到该模型将开始一个新对话，当前对话会保留在历史里。") },
            confirmButton = {
                TextButton(onClick = {
                    pendingCrossModel = null
                    onNewChatWithModel(
                        model.id,
                        model.providerId,
                        model.providerKind,
                        activePersona?.id.takeIf { model.providerKind == ProviderKind.BYOK },
                    )
                }) { Text("新建对话") }
            },
            dismissButton = { TextButton(onClick = { pendingCrossModel = null }) { Text("取消") } },
        )
    }

    if (personaSheetOpen) {
        PersonaPickerSheet(
            personas = personas,
            selectedPersona = activePersona,
            onSelect = { viewModel.selectPersona(it.id) },
            onManage = onOpenPersonaManagement,
            onDismiss = { personaSheetOpen = false },
        )
    }

    // SharedTransitionLayout 包在最外层（Scaffold 外），使图片全屏 overlay 能覆盖顶栏/输入框区域，
    // 缩略图（RemoteImage 内各自 AnimatedVisibility）与全屏图用同 key（img-$url）在两端 bounds 间非线性过渡。
    SharedTransitionLayout(modifier = modifier.fillMaxSize()) {
        val sharedScope = this
        val previewHolder = rememberPreviewUrlHolder()
        CompositionLocalProvider(
            LocalSharedTransitionScope provides sharedScope,
            LocalImagePreviewUrl provides previewHolder,
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    contentWindowInsets = WindowInsets.systemBars.only(
                        WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
                    ),
                    topBar = {
                        TopAppBar(
                title = {
                    Column {
                        Text(
                            text = conversationTitle,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        // 副标题「阵营徽章 模型名 ▾」即模型选择器入口：点击弹模型菜单。
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    modelMenuOpen = true
                                    // 当前会话确实属于 MolaGPT 时自动补拉；BYOK 用户仅打开菜单不发官方请求。
                                    if (state.providerKind == ProviderKind.MOLAGPT &&
                                        !state.isMolaModelConfigLoaded
                                    ) {
                                        viewModel.ensureMolaModelsLoaded()
                                    }
                                }
                                .padding(end = 4.dp),
                        ) {
                            // 徽章宽度固定，不参与压缩，把剩余空间全留给模型名。
                            if (state.selectedModel != null) {
                                Text(
                                    text = modelKindLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))
                                        .padding(horizontal = 4.dp, vertical = 1.dp),
                                )
                                Spacer(Modifier.width(5.dp))
                            }
                            Text(
                                text = modelNameText,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            Icon(
                                Icons.Filled.ArrowDropDown,
                                contentDescription = "切换模型",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    DropdownMenu(
                        expanded = modelMenuOpen,
                        onDismissRequest = { modelMenuOpen = false },
                        properties = PopupProperties(focusable = false),
                    ) {
                        if (state.modelGroups.isEmpty()) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        when {
                                            state.isModelRefreshing -> "正在获取 MolaGPT 模型..."
                                            state.isMolaModelConfigLoaded -> "暂无可用 MolaGPT 模型 · 点此刷新"
                                            else -> "未获取到 MolaGPT 模型 · 点此重试"
                                        },
                                    )
                                },
                                onClick = {
                                    if (!state.isModelRefreshing) {
                                        if (state.isMolaModelConfigLoaded) viewModel.refreshModels()
                                        else viewModel.ensureMolaModelsLoaded()
                                    }
                                },
                                enabled = !state.isModelRefreshing,
                            )
                        } else {
                            state.modelGroups.forEachIndexed { index, group ->
                                if (index > 0) HorizontalDivider()
                                // 分组标题（不可点）。
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            group.title,
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    },
                                    onClick = {},
                                    enabled = false,
                                )
                                group.models.forEach { model ->
                                    val sameKind = group.kind == state.providerKind
                                    val selected = state.selectedModel?.let {
                                        it.id == model.id && it.providerId == model.providerId
                                    } == true
                                    DropdownMenuItem(
                                        text = {
                                            Column(modifier = Modifier.fillMaxWidth()) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    modifier = Modifier.fillMaxWidth(),
                                                ) {
                                                    Text(
                                                        model.displayName,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                        modifier = Modifier.weight(1f),
                                                    )
                                                    // 点数档位：$ 越多扣得越狠。服务端给什么画什么。
                                                    model.creditSymbol?.takeIf { it.isNotEmpty() }?.let { sym ->
                                                        Spacer(Modifier.width(8.dp))
                                                        Text(
                                                            sym,
                                                            style = MaterialTheme.typography.labelMedium,
                                                            fontFamily = FontFamily.Monospace,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        )
                                                    }
                                                    if (selected) {
                                                        Spacer(Modifier.width(12.dp))
                                                        Icon(
                                                            Icons.Filled.Check,
                                                            contentDescription = "当前模型",
                                                            tint = MaterialTheme.colorScheme.primary,
                                                            modifier = Modifier.size(18.dp),
                                                        )
                                                    }
                                                }
                                                // 挡住的原因直接用服务端文案。首行是标题（如「今日点数已用完」），
                                                // 菜单项里只放得下这一行。
                                                model.quotaMessage
                                                    ?.lineSequence()
                                                    ?.firstOrNull { it.isNotBlank() }
                                                    ?.let { msg ->
                                                        Text(
                                                            msg,
                                                            style = MaterialTheme.typography.labelSmall,
                                                            color = MaterialTheme.colorScheme.error,
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis,
                                                        )
                                                    }
                                            }
                                        },
                                        // 额度/风控挡住的模型留在列表里但不可选（与 Web 一致）。
                                        // 直接从列表删掉会让「点数用完」表现为模型凭空消失。
                                        enabled = !model.quotaBlocked,
                                        onClick = {
                                            modelMenuOpen = false
                                            if (sameKind || isBlankConversation) {
                                                // 同阵营直接切换；空白会话跨阵营切换也直接生效，不弹新建确认。
                                                viewModel.selectModel(model.id, model.providerId)
                                            } else {
                                                // 跨阵营：历史不互通，弹确认后新建对话。
                                                pendingCrossModel = model
                                            }
                                        },
                                        colors = if (selected) {
                                            MenuDefaults.itemColors(
                                                textColor = MaterialTheme.colorScheme.primary,
                                            )
                                        } else {
                                            MenuDefaults.itemColors()
                                        },
                                        modifier = if (selected) {
                                            Modifier.background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f))
                                        } else {
                                            Modifier
                                        },
                                    )
                                }
                            }
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        when {
                                            state.isModelRefreshing -> "正在获取 MolaGPT 模型..."
                                            state.isMolaModelConfigLoaded -> "刷新 MolaGPT 模型列表"
                                            else -> "加载 MolaGPT 模型"
                                        },
                                    )
                                },
                                onClick = {
                                    if (!state.isModelRefreshing) {
                                        if (state.isMolaModelConfigLoaded) viewModel.refreshModels()
                                        else viewModel.ensureMolaModelsLoaded()
                                    }
                                },
                                enabled = !state.isModelRefreshing,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onOpenDrawer) {
                        Icon(Icons.AutoMirrored.Filled.List, contentDescription = "会话列表")
                    }
                },
                actions = {
                    if (isActiveConversation) {
                        IconButton(onClick = onNewChat) {
                            Icon(Icons.Filled.Add, contentDescription = "新对话")
                        }
                    }
                    if (showAgentControlShortcut) {
                        IconButton(onClick = onOpenAgentControl) {
                            AgentMonitorIcon(MaterialTheme.colorScheme.onSurface)
                        }
                    }
                    if (showImageWorkbenchShortcut) {
                        IconButton(onClick = onOpenImageWorkbench) {
                            Icon(Icons.Filled.Palette, contentDescription = "图像工作台")
                        }
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "设置")
                    }
                },
            )
        },
        bottomBar = {
            // 底栏内边距 = ime 与 navigationBars 的**并集**（取较大者），再用 windowInsetsPadding 一次性消费：
            //  - 无键盘：等于导航栏高度，输入框浮在导航栏上方；
            //  - 键盘弹出：ime 已含底部系统栏区域，union 取 ime，逐帧跟随键盘动画，零额外动画层。
            // 不能把 navigationBarsPadding()+imePadding() 链式叠加——那是两段 padding 相加，键盘弹出时会多垫一个导航栏高度。
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars)),
            ) {
                state.error?.let { err ->
                    Text(
                        text = err,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                state.reasoningMissHint?.let { hint ->
                    ReasoningMissCard(
                        lowConfidence = hint.lowConfidence,
                        canTurnOff = hint.canTurnOff,
                        onManual = {
                            viewModel.dismissReasoningMissHint()
                            onOpenSettings()
                        },
                        onTurnOff = viewModel::applyReasoningMissOff,
                        onDismiss = viewModel::dismissReasoningMissHint,
                    )
                }
                HorizontalDivider()
                Composer(
                    enabled = state.inputEnabled,
                    isStreaming = state.isStreaming,
                    enterToSend = enterToSend,
                    enabledTools = state.enabledTools,
                    selectedModel = state.selectedModel,
                    useThinking = state.useThinking,
                    reasoningEffort = state.reasoningEffort,
                    providerBaseUrl = state.providerBaseUrl,
                    pendingAttachments = state.pendingAttachments,
                    activePersona = activePersona,
                    showPersonaChip = isActiveConversation,
                    onSetWebAccess = viewModel::setWebAccessTools,
                    onSetNetwork = viewModel::setNetworkTool,
                    onSetSteel = viewModel::setSteelTool,
                    onToggleThinking = viewModel::setUseThinking,
                    onSetReasoningEffort = viewModel::setReasoningEffort,
                    onOpenPersonaPicker = { personaSheetOpen = true },
                    onTakePhoto = {
                        launchPicker("相机") {
                            val target = CameraCapture.newPhotoUri(context)
                                ?: error("无法创建拍照文件")
                            pendingPhotoUri = target
                            takePhoto.launch(target)
                        }
                    },
                    onPickImages = {
                        launchPicker("相册") {
                            pickImages.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                            )
                        }
                    },
                    onPickFiles = {
                        launchPicker("文件选择器") {
                            // MolaGPT 账户的附件进服务端沙箱，什么格式都收，不能收窄；
                            // BYOK 全在本机解析，收不了的直接在选择器里置灰。
                            val types = if (state.providerKind == ProviderKind.BYOK) {
                                AttachmentMime.BYOK_PICKER_MIME_TYPES.toTypedArray()
                            } else {
                                arrayOf("*/*")
                            }
                            pickDocuments.launch(types)
                        }
                    },
                    onRemoveAttachment = viewModel::removeAttachment,
                    onSend = viewModel::send,
                    onStop = viewModel::stop,
                    editingMessage = state.editingMessage,
                    onCancelEdit = viewModel::cancelEdit,
                    onOpenModelReasoningSettings = {
                        val model = state.selectedModel ?: return@Composer
                        if (model.providerKind == ProviderKind.BYOK) {
                            onOpenByokModelSettings(model.providerId, model.id)
                        }
                    },
                )
            }
        },
    ) { inner ->
        // 消息列表区域仅撑满 Scaffold 内容区（顶栏/底栏之间的区域），加上 inner 内边距。
        Box(modifier = Modifier.fillMaxSize().padding(inner)) {
            if (!state.isLoadingHistory && state.messages.isEmpty()) {
                PersonaWelcome(
                    activePersona = activePersona,
                    isByok = state.providerKind == ProviderKind.BYOK,
                    onOpenPersonaPicker = { personaSheetOpen = true },
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                MessageList(
                    messages = state.messages,
                    models = state.models,
                    onRegenerate = { viewModel.regenerateLast() },
                    onRegenerateWithModel = { viewModel.regenerateLast(it) },
                    onEditUser = viewModel::startEditUser,
                    canEdit = !state.isStreaming,
                    onNavVersion = viewModel::navVersion,
                    onNavEditSnapshot = viewModel::navEditSnapshot,
                )
            }
            if (state.isLoadingHistory && state.messages.isEmpty()) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            // 仅当前阵营依赖官方列表时显示顶栏进度，避免 BYOK 会话被无关刷新打扰。
            if (state.isModelRefreshing && state.providerKind == ProviderKind.MOLAGPT) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter),
                )
            }
        }
    } // Scaffold content lambda

                // ── 全屏图片预览 overlay ──
                // 位于 SharedTransitionLayout 的顶层 Box 中、Scaffold 外 → 覆盖顶栏/输入框区域的全屏沉浸。
                // AnimatedVisibility 驱动显隐；其 scope（this@AnimatedVisibility）下发给 overlay 内的全屏图，
                // 与缩略图（RemoteImage 内各自的 AnimatedVisibility）用同 key（img-$url）配对过渡。
                val previewUrl = previewHolder.current
                with(sharedScope) {
                    AnimatedVisibility(
                        visible = previewUrl != null,
                        enter = fadeIn(),
                        exit = fadeOut(),
                    ) {
                        CompositionLocalProvider(
                            LocalAnimatedVisibilityScope provides this@AnimatedVisibility,
                        ) {
                            previewUrl?.let { url ->
                                sharedScope.ImagePreviewOverlay(
                                    url = url,
                                    onDismiss = { previewHolder.request(null) },
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                    }
                }
            } // outer Box
        } // CompositionLocalProvider
    } // SharedTransitionLayout
}
@Composable
private fun rememberPreviewUrlHolder(): com.molagpt.app.feature.file.ImagePreviewUrlHolder {
    var url by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    BackHandler(enabled = url != null) { url = null }
    return androidx.compose.runtime.remember(url) {
        object : com.molagpt.app.feature.file.ImagePreviewUrlHolder {
            override val current: String? get() = url
            override fun request(value: String?) { url = value }
        }
    }
}

/** 顶栏「远程 Agent」入口图标——一个显示器轮廓，把 Agent 控制提到首屏一级。 */
@Composable
private fun ReasoningMissCard(
    lowConfidence: Boolean,
    canTurnOff: Boolean,
    onManual: () -> Unit,
    onTurnOff: () -> Unit,
    onDismiss: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(cs.tertiaryContainer.copy(alpha = 0.55f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.Warning,
                contentDescription = null,
                tint = cs.onTertiaryContainer,
                modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                "本次回复未检测到推理",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = cs.onTertiaryContainer,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Filled.Close, contentDescription = "关闭", modifier = Modifier.size(16.dp))
            }
        }
        Text(
            text = if (lowConfidence) {
                "当前推理方式是推测得到的，且本次未产生思考内容。很可能识别有误，建议手动指定。"
            } else {
                "已按当前格式发送请求，但未返回思考内容。可能该模型这次未触发思考，或服务端暂不支持。"
            },
            style = MaterialTheme.typography.bodySmall,
            color = cs.onTertiaryContainer.copy(alpha = 0.9f),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onManual) { Text("去设置") }
            // 常开推理模型（如 Kimi K3）无法关闭，隐藏该操作以免误导。
            if (canTurnOff) {
                OutlinedButton(onClick = onTurnOff) { Text("关闭推理") }
            }
        }
    }
}

@Composable
private fun AgentMonitorIcon(tint: androidx.compose.ui.graphics.Color) {
    androidx.compose.foundation.Canvas(modifier = Modifier.size(22.dp)) {
        val w = size.width
        val h = size.height
        val sw = 2.dp.toPx()
        val cap = androidx.compose.ui.graphics.StrokeCap.Round
        val stroke = androidx.compose.ui.graphics.drawscope.Stroke(
            width = sw, cap = cap, join = androidx.compose.ui.graphics.StrokeJoin.Round,
        )
        drawRoundRect(
            color = tint,
            topLeft = androidx.compose.ui.geometry.Offset(w * 0.12f, h * 0.20f),
            size = androidx.compose.ui.geometry.Size(w * 0.76f, h * 0.46f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()),
            style = stroke,
        )
        drawLine(tint, androidx.compose.ui.geometry.Offset(w * 0.5f, h * 0.66f), androidx.compose.ui.geometry.Offset(w * 0.5f, h * 0.80f), strokeWidth = sw, cap = cap)
        drawLine(tint, androidx.compose.ui.geometry.Offset(w * 0.34f, h * 0.84f), androidx.compose.ui.geometry.Offset(w * 0.66f, h * 0.84f), strokeWidth = sw, cap = cap)
    }
}
