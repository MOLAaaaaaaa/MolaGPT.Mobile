package com.molagpt.app.feature.agentcontrol

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.molagpt.app.core.model.AgentBackendIds
import com.molagpt.app.core.model.displayWorkspace
import com.molagpt.app.core.model.isBusy
import com.molagpt.app.core.render.MolaMotion

/**
 * Agent 控制主屏：Hub（会话列表）⇄ 单会话两态。
 * 两态各自是一个完整 Scaffold，由 [AnimatedContent] 用**统一的二级页转场声明**
 * （[MolaMotion.PushEnter] 等）整页推入/滑出——含顶栏，和全局 NavHost 转场完全一致。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentControlScreen(
    vm: AgentControlViewModel,
    onExit: () -> Unit,
) {
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val selectedId by vm.selectedId.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val connectionState by vm.connectionState.collectAsStateWithLifecycle()

    var showNew by remember { mutableStateOf(false) }
    var showModel by remember { mutableStateOf(false) }
    var showMode by remember { mutableStateOf(false) }

    val inSession = selectedId != null

    // 会话态下系统返回（含侧滑手势）先回到 Hub，而不是直接弹出整个 Agent 路由跳到上一级。
    BackHandler(enabled = inSession) { vm.deselect() }

    AnimatedContent(
        targetState = inSession,
        transitionSpec = {
            // 复用全局二级页转场声明，与 NavHost push/pop 完全一致。
            // 目标层置顶进入；返回时旧层（会话）保持在上向右滑出，露出下面的 Hub。
            val ct = if (targetState) MolaMotion.PushEnter togetherWith MolaMotion.PushExit
            else MolaMotion.PopEnter togetherWith MolaMotion.PopExit
            ct.apply { targetContentZIndex = if (targetState) 1f else 0f }
        },
        label = "agentHubSession",
        modifier = Modifier.fillMaxSize(),
    ) { showSession ->
        if (showSession) {
            SessionScaffold(
                vm = vm,
                state = selected,
                onBack = { vm.deselect() },
                onOpenModel = { showModel = true },
                onOpenMode = { showMode = true },
            )
        } else {
            HubScaffold(
                sessions = sessions,
                connectionState = connectionState,
                refreshing = refreshing,
                onSelect = vm::select,
                onReconnect = vm::reconnect,
                onRefresh = { vm.refresh() },
                onExit = onExit,
                onNew = { showNew = true },
            )
        }
    }

    val meta = selected.meta
    if (showNew) {
        NewSessionSheet(
            sessions = sessions,
            onDismiss = { showNew = false },
            onCreate = { backendId, cwd, title ->
                showNew = false
                vm.newSession(backendId = backendId, workingDirectory = cwd, title = title)
            },
        )
    }
    if (showModel && meta != null) {
        ModelSheet(meta = meta, options = selected.modelOptions, onDismiss = { showModel = false }, onPick = { vm.switchModel(it); showModel = false })
    }
    if (showMode && meta != null) {
        ModeSheet(meta = meta, onDismiss = { showMode = false }, onSetPermissionMode = vm::switchPermissionMode, onSetApprovalPolicy = vm::switchApprovalPolicy)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HubScaffold(
    sessions: List<com.molagpt.app.core.model.RelaySessionMeta>,
    connectionState: com.molagpt.app.core.model.RelayConnectionState,
    refreshing: Boolean,
    onSelect: (com.molagpt.app.core.model.RelaySessionMeta) -> Unit,
    onReconnect: () -> Unit,
    onRefresh: () -> Unit,
    onExit: () -> Unit,
    onNew: () -> Unit,
) {
    Scaffold(
        contentWindowInsets = WindowInsets.systemBars.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                title = { Text("远程 Agent", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onExit) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
                },
                actions = {
                    if (refreshing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    IconButton(onClick = onRefresh) { Icon(Icons.Filled.Refresh, contentDescription = "刷新") }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onNew,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.navigationBarsPadding(),
            ) { Icon(Icons.Filled.Add, contentDescription = "新建会话") }
        },
    ) { inner ->
        AgentHub(
            sessions = sessions,
            connectionState = connectionState,
            onSelect = onSelect,
            onReconnect = onReconnect,
            modifier = Modifier.padding(inner),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionScaffold(
    vm: AgentControlViewModel,
    state: AgentSessionUiState,
    onBack: () -> Unit,
    onOpenModel: () -> Unit,
    onOpenMode: () -> Unit,
) {
    val meta = state.meta
    Scaffold(
        contentWindowInsets = WindowInsets.systemBars.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                title = {
                    Column {
                        Text(meta?.title?.ifBlank { "会话" } ?: "会话", maxLines = 1, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
                        meta?.let {
                            Text(
                                "${backendShort(it.backendId)} · ${it.displayWorkspace}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
                },
                actions = {
                    if (meta?.isBusy == true) TextButton(onClick = { vm.interrupt() }) { Text("停止") }
                },
            )
        },
    ) { inner ->
        SessionPane(
            vm = vm,
            state = state,
            onOpenModel = onOpenModel,
            onOpenMode = onOpenMode,
            modifier = Modifier.padding(inner),
        )
    }
}

@Composable
private fun SessionPane(
    vm: AgentControlViewModel,
    state: AgentSessionUiState,
    onOpenModel: () -> Unit,
    onOpenMode: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val meta = state.meta
    Column(modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                state.loading && state.blocks.isEmpty() ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                state.blocks.isEmpty() ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("暂无可显示的历史", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                else -> AgentTranscriptView(blocks = state.blocks, onPermissionChoice = vm::approvePermission)
            }
        }
        // 输入区跟随键盘 + 浮在导航栏上方（与对话页同一 insets 处理）。
        Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))) {
            AgentComposer(
                value = state.input,
                busy = meta?.isBusy == true,
                meta = meta,
                onValueChange = vm::updateInput,
                onSend = vm::send,
                onStop = vm::interrupt,
                onOpenModel = onOpenModel,
                onOpenMode = onOpenMode,
                onSetReasoningEffort = vm::switchReasoningEffort,
            )
        }
    }
}

private fun backendShort(backendId: String): String = when (backendId) {
    AgentBackendIds.ClaudeCode -> "CC"
    AgentBackendIds.Codex -> "CX"
    else -> "AI"
}
