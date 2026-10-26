package com.molagpt.app.feature.agentcontrol.desktop

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.DesktopWindows
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.molagpt.app.core.model.AttachmentMime
import com.molagpt.app.core.model.EnabledTools
import com.molagpt.app.core.model.RemoteApproval
import com.molagpt.app.core.model.RemoteApprovalDecision
import com.molagpt.app.core.model.RemoteAttachmentRules
import com.molagpt.app.core.render.ImeDismissBackHandler
import com.molagpt.app.core.render.MolaBottomEdge
import com.molagpt.app.core.render.MolaMotion
import com.molagpt.app.core.render.MolaScaffold
import com.molagpt.app.core.render.MolaTopBar
import com.molagpt.app.core.render.molaGlass
import com.molagpt.app.feature.agentcontrol.agentPalette
import com.molagpt.app.feature.chat.Composer
import com.molagpt.app.feature.chat.ComposerDraft
import com.molagpt.app.core.model.Persona
import com.molagpt.app.feature.chat.ModelMenuHeader
import com.molagpt.app.feature.chat.persona.PersonaPickerSheet
import com.molagpt.app.feature.chat.persona.PersonaWelcome
import com.molagpt.app.feature.chat.ModelMenuItem
import com.molagpt.app.feature.chat.ReadOnlyMessageList
import com.molagpt.app.feature.chat.LocalSubagentLinks
import com.molagpt.app.feature.chat.SubagentLinks
import com.molagpt.app.feature.chat.topBarOverlap
import com.molagpt.app.feature.file.CameraCapture
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * 一个电脑上的对话。和本地对话一样显示在主页上（不是二级页）：左上角打开会话侧边栏，
 * 顶栏是标题和模型选择，底部是同一个输入区；模型、网络访问、推理都按电脑上这个对话的设置显示，改动交给电脑执行。
 * 阵营徽章里带一枚电脑图标，和本地对话区分开。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteConversationScreen(
    vm: RemoteConversationViewModel,
    machineName: String,
    enterToSend: Boolean,
    onVisible: (Boolean) -> Unit,
    onOpenDrawer: () -> Unit,
    /** 在同一台电脑上起草新对话，沿用当前模型。 */
    onNewConversation: (providerId: String?, modelId: String?) -> Unit,
    onOpenSettings: () -> Unit,
    /** 侧边栏打开时，返回键交给侧边栏。 */
    drawerOpen: Boolean = false,
    /**
     * 新对话发出首条消息前的模型菜单内容，与首页模型菜单是同一份：停在这台电脑的模型页，
     * 返回上一级就能改选手机上的模型或别的电脑。为 null 时只列这台电脑的模型。
     */
    draftModelMenu: (@Composable (close: () -> Unit) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var modelMenuOpen by remember { mutableStateOf(false) }
    var personaSheetOpen by remember { mutableStateOf(false) }
    var restoreDraft by remember { mutableStateOf<ComposerDraft?>(null) }
    val agentOpen = state.agentPage != null
    val pageProgress = remember { Animatable(if (agentOpen) 1f else 0f) }
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var retainedAgentPage by remember { mutableStateOf(state.agentPage) }
    SideEffect { state.agentPage?.let { retainedAgentPage = it } }
    LaunchedEffect(agentOpen) {
        if (agentOpen) {
            focusManager.clearFocus()
            keyboard?.hide()
        }
        pageProgress.animateTo(if (agentOpen) 1f else 0f, MolaMotion.standardDecelerate())
        if (!agentOpen) retainedAgentPage = null
    }
    val settings = state.settings
    val homeModelMenu = draftModelMenu?.takeIf { state.messages.isEmpty() }
    val listState = rememberLazyListState()
    val overlapPx = with(LocalDensity.current) { 24.dp.toPx() }
    val backContext = LocalContext.current
    // 与聊天页一致：已有消息的对话按返回开启新对话。须先于子 Agent 页和收键盘的处理注册。
    BackHandler(enabled = !drawerOpen && !agentOpen && state.draft == null && state.messages.isNotEmpty()) {
        Toast.makeText(backContext, "已开启新对话", Toast.LENGTH_SHORT).show()
        onNewConversation(settings?.providerId, settings?.modelId)
    }
    PredictiveBackHandler(enabled = agentOpen && !drawerOpen) { progress ->
        val start = pageProgress.value
        try {
            progress.collect { event ->
                pageProgress.snapTo(start * (1f - MolaMotion.StandardDecelerate.transform(event.progress)))
            }
            vm.closeAgent()
        } catch (error: CancellationException) {
            // 和侧栏一样，由独立协程回弹，不切换进出场角色。
            scope.launch {
                pageProgress.animateTo(
                    if (vm.state.value.agentPage != null) 1f else 0f,
                    MolaMotion.standardDecelerate(durationMillis = 260),
                )
            }
            throw error
        }
    }
    ImeDismissBackHandler(enabled = !drawerOpen)
    DisposableEffect(state.conversationId) {
        onVisible(true)
        onDispose { onVisible(false) }
    }

    // 附件三条路径与聊天页相同：拍照交给系统相机、图片走 Photo Picker、文件走 SAF，都不需要权限。
    val context = LocalContext.current
    /** 相机在另一个进程，本进程可能被回收重建，拍照落点必须可保存。 */
    var pendingPhotoUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val target = pendingPhotoUri
        pendingPhotoUri = null
        if (saved && target != null) vm.attach(target)
    }
    val pickImages = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MAX_IMAGES_PER_PICK),
    ) { uris -> vm.attach(uris) }
    val pickDocuments = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> vm.attach(uris) }
    val launchPicker = { what: String, block: () -> Unit ->
        runCatching(block).onFailure { Toast.makeText(context, "无法打开$what", Toast.LENGTH_SHORT).show() }
        Unit
    }

    val shownAgentPage = state.agentPage ?: retainedAgentPage
    Box(modifier.fillMaxSize().clipToBounds()) {
        // 聊天页保持原实例；每帧只移动图层，不重新创建正文和输入区。
        MolaScaffold(
            modifier = Modifier.fillMaxSize()
                .graphicsLayer {
                    translationX = -size.width * 0.25f * pageProgress.value
                    alpha = if (pageProgress.value == 1f) 0f else 1f
                }
                .then(if (shownAgentPage != null) Modifier.clearAndSetSemantics {} else Modifier),
            contentWindowInsets = WindowInsets.systemBars.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
            topBar = {
                MolaTopBar(
                    overlap = { listState.topBarOverlap(overlapPx) },
                    title = {
                        Column {
                            Text(
                                text = state.title.ifBlank { "新对话" },
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            // 副标题「阵营徽章 模型名 ▾」即模型选择器入口，与聊天页一致。
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    // 新对话里电脑离线也能打开：可以退回手机上的模型。
                                    .clickable(enabled = homeModelMenu != null || (settings != null && state.live)) {
                                        modelMenuOpen = true
                                        if (homeModelMenu == null) vm.loadModels()
                                    }
                                    .padding(end = 4.dp),
                            ) {
                                // 徽章前缀电脑图标：这是在电脑上运行的对话。
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))
                                        .padding(start = 3.dp, end = 4.dp, top = 1.dp, bottom = 1.dp)
                                        .semantics(mergeDescendants = true) { contentDescription = "在 $machineName 上运行" },
                                ) {
                                    Icon(
                                        Icons.Outlined.DesktopWindows,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(12.dp),
                                    )
                                    settings?.familyLabel?.let { family ->
                                        Spacer(Modifier.width(3.dp))
                                        Text(
                                            text = family,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary,
                                            maxLines = 1,
                                        )
                                    }
                                }
                                Spacer(Modifier.width(5.dp))
                                Text(
                                    text = state.modelLabel ?: machineName,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                                if (settings != null || homeModelMenu != null) {
                                    Icon(
                                        Icons.Filled.ArrowDropDown,
                                        contentDescription = "切换模型",
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }
                        if (homeModelMenu != null) {
                            DropdownMenu(
                                expanded = modelMenuOpen,
                                onDismissRequest = { modelMenuOpen = false },
                                properties = PopupProperties(focusable = false),
                            ) {
                                homeModelMenu { modelMenuOpen = false }
                            }
                        } else {
                            RemoteModelMenu(
                                expanded = modelMenuOpen,
                                state = state,
                                onDismiss = { modelMenuOpen = false },
                                onSelect = { group, model ->
                                    modelMenuOpen = false
                                    vm.selectModel(group, model)
                                },
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onOpenDrawer) {
                            Icon(Icons.AutoMirrored.Filled.List, contentDescription = "会话列表")
                        }
                    },
                    actions = {
                        if (state.draft == null && state.messages.isNotEmpty()) {
                            IconButton(onClick = { onNewConversation(settings?.providerId, settings?.modelId) }) {
                                Icon(Icons.Filled.Add, contentDescription = "新对话")
                            }
                        }
                        IconButton(onClick = onOpenSettings) {
                            Icon(Icons.Filled.Settings, contentDescription = "设置")
                        }
                    },
                )
            },
            bottomBar = {
                Box {
                    MolaBottomEdge(Modifier.matchParentSize())
                    // 底栏内边距取 ime 与导航栏的并集，与聊天页相同。
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars)),
                    ) {
                        StatusLine(state, onRetry = vm::reconnect, onDismiss = vm::dismissNotice)
                        val truncated = state.messages.lastOrNull { it.metadata.containsKey(DesktopMessageMapper.TRUNCATED_KEY) }
                        if (truncated != null && state.live) {
                            TextButton(
                                onClick = { vm.loadFull(truncated.messageId) },
                                modifier = Modifier.align(Alignment.CenterHorizontally),
                            ) { Text("查看完整内容") }
                        }
                        state.approvals.forEach { approval ->
                            ApprovalCard(approval, enabled = state.live) { decision -> vm.approve(approval.requestId, decision) }
                        }
                        if (state.tasksSupported && (state.agents.isNotEmpty() || state.runningTasks.isNotEmpty())) {
                            RemoteTasksPanel(
                                tasks = state.runningTasks,
                                agents = state.agents,
                                enabled = state.live,
                                onOpenAgent = vm::openAgent,
                                onStop = vm::stopTask,
                            )
                        }
                        val readOnly = state.readOnly
                        if (readOnly != null) {
                            Text(
                                readOnly,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                            )
                        } else {
                            val rules = settings?.attachments
                            Composer(
                                glass = true,
                                enabled = state.live,
                                isStreaming = state.busy,
                                enterToSend = enterToSend,
                                enabledTools = EnabledTools(network = settings?.networkOn == true),
                                selectedModel = settings?.composerModel(),
                                useThinking = settings?.thinkingOn == true,
                                reasoningEffort = settings?.effort.orEmpty(),
                                providerBaseUrl = "",
                                pendingAttachments = state.pendingAttachments,
                                activePersona = null,
                                showPersonaChip = false,
                                showMemoryChip = false,
                                memoryEnabled = false,
                                onSetMemory = {},
                                onSetWebAccess = vm::setNetwork,
                                onToggleThinking = vm::setThinking,
                                onSetReasoningEffort = vm::setEffort,
                                onOpenPersonaPicker = {},
                                onTakePhoto = {
                                    if (rules?.images != true) vm.refuseImages()
                                    else launchPicker("相机") {
                                        val target = CameraCapture.newPhotoUri(context) ?: error("无法创建拍照文件")
                                        pendingPhotoUri = target
                                        takePhoto.launch(target)
                                    }
                                },
                                onPickImages = {
                                    if (rules?.images != true) vm.refuseImages()
                                    else launchPicker("相册") {
                                        pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                    }
                                },
                                onPickFiles = {
                                    launchPicker("文件选择器") { pickDocuments.launch(pickerTypes(rules)) }
                                },
                                onRemoveAttachment = vm::removeAttachment,
                                onRetryAttachment = vm::retryAttachment,
                                holdSendForAttachments = true,
                                onSend = { text ->
                                    vm.send(text) { failed ->
                                        restoreDraft = ComposerDraft(failed, System.nanoTime())
                                    }
                                },
                                onStop = vm::stop,
                                showAttachments = rules != null,
                                showTools = settings != null,
                                sendWhileStreaming = true,
                                showReasoningWireDetail = false,
                                restoreDraft = restoreDraft,
                            )
                        }
                    }
                }
            },
        ) { inner ->
            val draft = state.draft
            if (draft != null && state.messages.isEmpty()) {
                // 只用来显示：提示词留在电脑上，这里放的是电脑给的预览；顺序沿用电脑的排列。
                val personas = remember(draft.personas) {
                    draft.personas.mapIndexed { index, it ->
                        Persona(id = it.id, name = it.name, systemPrompt = it.preview, sortOrder = index)
                    }
                }
                PersonaWelcome(
                    activePersona = personas.firstOrNull { it.id == draft.personaId },
                    isByok = personas.isNotEmpty(),
                    onOpenPersonaPicker = { personaSheetOpen = true },
                    subtitle = "对话保存在电脑上，手机可随时继续",
                    location = "在 $machineName 上运行",
                    modifier = Modifier.padding(inner),
                )
                if (personaSheetOpen) {
                    PersonaPickerSheet(
                        personas = personas,
                        selectedPersona = personas.firstOrNull { it.id == draft.personaId },
                        onSelect = { persona ->
                            personaSheetOpen = false
                            vm.selectPersona(persona.id)
                        },
                        onManage = null,
                        onDismiss = { personaSheetOpen = false },
                    )
                }
                return@MolaScaffold
            }
            val agentIds = state.agents.map { it.id }
            val subagentLinks = remember(state.tasksSupported, agentIds) {
                if (state.tasksSupported) SubagentLinks(agentIds.toSet(), vm::openAgent) else null
            }
            CompositionLocalProvider(LocalSubagentLinks provides subagentLinks) {
                ReadOnlyMessageList(
                    messages = state.messages,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = inner.calculateTopPadding() + 12.dp,
                        bottom = inner.calculateBottomPadding() + 16.dp,
                    ),
                    bottomOverlayPadding = inner.calculateBottomPadding(),
                    listState = listState,
                    header = if (state.hasOlder && state.live) {
                        {
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                TextButton(onClick = vm::loadOlder, enabled = !state.loadingOlder) {
                                    Text(if (state.loadingOlder) "加载中…" else "加载历史消息")
                                }
                            }
                        }
                    } else {
                        null
                    },
                )
            }
        }
        if (shownAgentPage != null) {
            // 阻止详情空白区域的点击穿透到底下的输入区。
            Box(Modifier.fillMaxSize().clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            ).clearAndSetSemantics {})
            key(shownAgentPage.agentId) {
                RemoteAgentDetailScreen(
                    agent = state.agents.firstOrNull { it.id == shownAgentPage.agentId },
                    page = shownAgentPage,
                    live = state.live,
                    statusLine = { StatusLine(state, onRetry = vm::reconnect, onDismiss = vm::dismissNotice) },
                    onBack = vm::closeAgent,
                    onStop = vm::stopTask,
                    modifier = Modifier.fillMaxSize().graphicsLayer {
                        translationX = size.width * (1f - pageProgress.value)
                    },
                )
            }
        }
    }
}

private const val MAX_IMAGES_PER_PICK = 9

/**
 * 文件选择器的类型：电脑能处理任意文件时不限制；否则只列出电脑能抽取文字的文本、PDF 与 Office 文档，
 * 模型能看图时再加上图片。
 */
private fun pickerTypes(rules: RemoteAttachmentRules?): Array<String> {
    if (rules?.files == true) return arrayOf("*/*")
    val types = AttachmentMime.BYOK_PICKER_MIME_TYPES.toMutableList()
    types += listOf(AttachmentMime.XLSX, AttachmentMime.PPTX)
    if (rules?.images != true) types -= "image/*"
    return types.distinct().toTypedArray()
}

/** 模型菜单：电脑上可切换到的模型，按服务商分组，样式与聊天页的模型菜单相同。 */
@Composable
private fun RemoteModelMenu(
    expanded: Boolean,
    state: RemoteConversationUiState,
    onDismiss: () -> Unit,
    onSelect: (com.molagpt.app.core.model.RemoteModelGroup, com.molagpt.app.core.model.RemoteModelChoice) -> Unit,
) {
    val settings = state.settings
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = false),
    ) {
        if (state.modelGroups.isEmpty()) {
            DropdownMenuItem(
                text = { Text(if (state.loadingModels) "正在获取模型..." else "暂无可用模型") },
                onClick = {},
                enabled = false,
            )
            return@DropdownMenu
        }
        state.modelGroups.forEachIndexed { index, group ->
            if (index > 0) HorizontalDivider()
            ModelMenuHeader(if (group.family == "work") "Work" else "BYOK · ${group.provider}")
            group.models.forEach { model ->
                val selected = settings?.providerId == group.providerId && settings.modelId == model.id
                ModelMenuItem(
                    label = model.label,
                    selected = selected,
                    // 电脑正在回答时不换模型，与电脑端一致。
                    enabled = state.live && !state.busy,
                    onClick = { if (selected) onDismiss() else onSelect(group, model) },
                )
            }
        }
    }
}

/** 状态行要显示的内容；null 即不显示。 */
internal data class StatusModel(
    val text: String,
    val error: Boolean = false,
    val progress: Boolean = false,
    val action: String? = null,
)

/**
 * 输入区上方的状态提示：连接中、离线、错误、配对失效。
 * 底栏顶部是透明渐变，正文会从提示后面滚过，所以提示自带一枚玻璃胶囊底，不直接压在正文上。
 *
 * 进入页面到电脑回出快照通常不到一秒，这段时间不提示；超过 [SLOW_CONNECT_MS] 或用户点了重试，
 * 才显示「正在连接」，让重试有看得见的反馈。
 */
@Composable
private fun StatusLine(state: RemoteConversationUiState, onRetry: () -> Unit, onDismiss: () -> Unit) {
    val waiting = state.connecting || state.opening
    var slow by remember { mutableStateOf(false) }
    LaunchedEffect(waiting) {
        slow = false
        if (waiting) {
            delay(SLOW_CONNECT_MS)
            slow = true
        }
    }
    val model = when {
        state.unpaired -> StatusModel("配对已失效，请重新配对", error = true)
        state.notice != null -> StatusModel(state.notice, error = true, action = "关闭")
        waiting && (slow || state.retrying) -> StatusModel("正在连接电脑…", progress = true)
        waiting || state.live -> null
        else -> {
            val reason = if (state.linkUp) "电脑离线" else "无法连接，请检查网络"
            val synced = state.cachedAtMs?.let { relativeTime(it) }?.takeIf { it.isNotEmpty() }
            StatusModel(if (synced != null) "$reason · ${synced}同步" else reason, action = "重试")
        }
    }
    // 退场动画期间沿用最后一份内容，胶囊不会在淡出时变空。
    var shown by remember { mutableStateOf(model) }
    if (model != null) shown = model
    AnimatedVisibility(
        visible = model != null,
        enter = fadeIn() + expandVertically(expandFrom = Alignment.Bottom),
        exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Bottom),
    ) {
        shown?.let { current ->
            Box(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                StatusPill(
                    model = current,
                    onAction = if (current.error) onDismiss else onRetry,
                )
            }
        }
    }
}

@Composable
internal fun StatusPill(
    model: StatusModel,
    onAction: () -> Unit = {},
    icon: ImageVector? = null,
    onClick: (() -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .molaGlass(RoundedCornerShape(50), elevation = 2.dp)
            .heightIn(min = 34.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(start = 12.dp, end = if (model.action != null) 4.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (model.progress) {
            CircularProgressIndicator(strokeWidth = 1.5.dp, modifier = Modifier.size(12.dp))
        } else if (icon != null) {
            Icon(icon, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(14.dp))
        } else {
            Box(
                Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(if (model.error) cs.error else cs.onSurfaceVariant.copy(alpha = 0.5f)),
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            model.text,
            style = MaterialTheme.typography.labelMedium,
            color = if (model.error) cs.error else cs.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        model.action?.let { label ->
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (model.error) cs.onSurfaceVariant else cs.primary,
                modifier = Modifier
                    .padding(start = 4.dp)
                    .clip(RoundedCornerShape(50))
                    .clickable(onClick = onAction)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            )
        }
    }
}

private const val SLOW_CONNECT_MS = 1_500L

@Composable
private fun ApprovalCard(approval: RemoteApproval, enabled: Boolean, onDecide: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    var expanded by remember(approval.requestId) { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = cs.surface,
        border = BorderStroke(1.dp, agentPalette().amber.copy(alpha = 0.6f)),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("工具调用审批：${approval.displayName}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            approval.summary?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
            approval.resolvedPath?.takeIf { it.isNotBlank() }?.let {
                Text("路径：$it", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
            val detail = approval.codePreview ?: approval.argumentsPreview
            detail?.takeIf { it.isNotBlank() }?.let { body ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = if (expanded) 320.dp else 120.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(cs.surfaceVariant.copy(alpha = 0.6f))
                        .clickable { expanded = !expanded }
                        .padding(10.dp),
                ) {
                    Text(
                        body,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()),
                    )
                }
            }
            if (approval.codePreview != null) {
                approval.argumentsPreview?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                OutlinedButton(onClick = { onDecide(RemoteApprovalDecision.DENY) }, enabled = enabled) { Text("拒绝") }
                if (RemoteApprovalDecision.SESSION in approval.options) {
                    OutlinedButton(onClick = { onDecide(RemoteApprovalDecision.SESSION) }, enabled = enabled) { Text("本次会话内允许") }
                }
                FilledTonalButton(onClick = { onDecide(RemoteApprovalDecision.ONCE) }, enabled = enabled) {
                    Text(if (approval.isPython) "运行" else "仅允许本次")
                }
            }
        }
    }
}
