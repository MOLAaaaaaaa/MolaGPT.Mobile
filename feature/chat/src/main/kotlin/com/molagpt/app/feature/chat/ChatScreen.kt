package com.molagpt.app.feature.chat

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.molagpt.app.core.model.ProviderKind
import com.molagpt.app.core.model.ProviderModel

/**
 * 聊天页（单 Activity 内的主屏）。顶栏：菜单(打开会话抽屉) + 模型选择下拉；
 * 中间消息列表；底部输入框。主聊天体验使用原生 Compose。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    enterToSend: Boolean,
    onOpenDrawer: () -> Unit,
    onOpenSettings: () -> Unit,
    onAuthExpired: () -> Unit,
    onNewChatWithModel: (modelId: String, providerId: String?, kind: ProviderKind) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var modelMenuOpen by remember { mutableStateOf(false) }
    var pendingCrossModel by remember { mutableStateOf<ProviderModel?>(null) }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val density = LocalDensity.current
    val imeVisible = WindowInsets.ime.getBottom(density) > 0
    val modelPrefix = if (state.providerKind == ProviderKind.BYOK) "BYOK" else "MolaGPT"
    // 副标题：服务商 · 模型（BYOK 含服务商名）。主标题改显对话标题，模型选择器入口移到此副标题。
    val modelSubtitle = state.selectedModel?.let { model ->
        if (state.providerKind == ProviderKind.BYOK) {
            "$modelPrefix · ${model.providerName} · ${model.displayName}"
        } else {
            "$modelPrefix · ${model.displayName}"
        }
    } ?: if (state.isModelRefreshing) {
        "正在获取模型"
    } else {
        "选择模型"
    }
    val conversationTitle = state.title.ifBlank { "新对话" }
    val pickFile = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(viewModel::attachFile) }

    if (state.authExpired) {
        androidx.compose.runtime.LaunchedEffect(Unit) { onAuthExpired() }
    }

    BackHandler(enabled = imeVisible || modelMenuOpen) {
        if (modelMenuOpen) {
            modelMenuOpen = false
        } else {
            keyboard?.hide()
            focusManager.clearFocus()
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
                    onNewChatWithModel(model.id, model.providerId, model.providerKind)
                }) { Text("新建对话") }
            },
            dismissButton = { TextButton(onClick = { pendingCrossModel = null }) { Text("取消") } },
        )
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        // 底部 inset 交给 bottomBar 内部的 windowInsetsPadding(ime ∪ navigationBars) 全权处理，
        // 这里只保留顶部/侧边的 systemBars，避免 Scaffold 再给 bottomBar 垫一次导航栏（双重 padding）。
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
                        // 副标题「服务商 · 模型 ▾」即模型选择器入口：点击弹模型菜单。
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    modelMenuOpen = true
                                    if (state.modelGroups.isEmpty()) viewModel.refreshModels()
                                }
                                .padding(end = 4.dp),
                        ) {
                            Text(
                                text = modelSubtitle,
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
                    DropdownMenu(expanded = modelMenuOpen, onDismissRequest = { modelMenuOpen = false }) {
                        if (state.modelGroups.isEmpty()) {
                            DropdownMenuItem(
                                text = {
                                    Text(if (state.isModelRefreshing) "正在获取模型列表..." else "未获取到模型列表")
                                },
                                onClick = {},
                                enabled = false,
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
                                    DropdownMenuItem(
                                        text = { Text(model.displayName) },
                                        onClick = {
                                            modelMenuOpen = false
                                            if (sameKind) {
                                                viewModel.selectModel(model.id, model.providerId)
                                            } else {
                                                // 跨阵营：历史不互通，弹确认后新建对话。
                                                pendingCrossModel = model
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onOpenDrawer) {
                        Icon(Icons.AutoMirrored.Filled.List, contentDescription = "会话列表")
                    }
                },
                actions = {
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
                HorizontalDivider()
                Composer(
                    enabled = state.inputEnabled,
                    isStreaming = state.isStreaming,
                    enterToSend = enterToSend,
                    enabledTools = state.enabledTools,
                    selectedModel = state.selectedModel,
                    hasMcpServers = state.hasMcpServers,
                    useThinking = state.useThinking,
                    reasoningEffort = state.reasoningEffort,
                    pendingAttachments = state.pendingAttachments,
                    onSetNetwork = viewModel::setNetworkTool,
                    onSetSteel = viewModel::setSteelTool,
                    onSetMcp = viewModel::setMcpTool,
                    onSetVision = viewModel::setVisionTool,
                    onSetImageGeneration = viewModel::setImageGenerationTool,
                    onToggleThinking = viewModel::setUseThinking,
                    onSetReasoningEffort = viewModel::setReasoningEffort,
                    onPickImage = { pickFile.launch(arrayOf("*/*")) },
                    onRemoveAttachment = viewModel::removeAttachment,
                    onSend = viewModel::send,
                    onStop = viewModel::stop,
                )
            }
        },
    ) { inner ->
        Box(modifier = Modifier.fillMaxSize().padding(inner)) {
            MessageList(
                messages = state.messages,
                models = state.models,
                onRegenerate = viewModel::regenerateLast,
                onNavVersion = viewModel::navVersion,
            )
            // 占位会话从云端拉取消息正文时，居中转圈（消息到位后 isLoadingHistory 翻 false、列表渲染）。
            if (state.isLoadingHistory && state.messages.isEmpty()) {
                androidx.compose.material3.CircularProgressIndicator(
                    modifier = Modifier.align(androidx.compose.ui.Alignment.Center),
                )
            }
            if (state.isModelRefreshing) {
                androidx.compose.material3.LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().align(androidx.compose.ui.Alignment.TopCenter),
                )
            }
        }
    }
}
