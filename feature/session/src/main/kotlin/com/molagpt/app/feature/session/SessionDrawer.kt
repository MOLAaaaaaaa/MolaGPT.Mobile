package com.molagpt.app.feature.session

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.AddComment
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.DesktopWindows
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemContentType
import androidx.paging.compose.itemKey
import com.molagpt.app.core.model.Conversation
import com.molagpt.app.core.model.ConversationFilter
import com.molagpt.app.core.model.ProviderKind
import com.molagpt.app.core.model.isImageTask
import com.molagpt.app.core.render.ImeDismissBackHandler
import com.molagpt.app.core.render.MolaMotion
import kotlin.math.absoluteValue
import kotlinx.coroutines.launch

@Composable
fun SessionDrawer(
    sessions: SessionItemsSource,
    /** 角色名快照：BYOK 会话绑定了非默认角色时，行上时间前带出角色名。 */
    personaLabels: PersonaLabels,
    currentSessionId: String?,
    /** 主页正在显示的电脑对话（`机器 id/对话 id`）；高亮方式与当前本地会话相同。 */
    currentDesktopKey: String? = null,
    /** 抽屉是否可见。抽屉常驻 composition，返回键兜底必须按它收口，否则会抢走聊天页的返回。 */
    drawerOpen: Boolean,
    onNewChat: () -> Unit,
    onSelect: (String) -> Unit,
    /** 画图任务的行：不在聊天页打开，交给画图工作台。 */
    onOpenImageTask: (String) -> Unit,
    /** 删除统一走多选：长按会话行或点标题栏的多选按钮进入。 */
    onDeleteMany: (sessionIds: Set<String>, nextSessionId: String?) -> Unit,
    /** 「全选」向数据层取全量可见会话 id（不止 Paging 已加载的那部分）。 */
    onRequestAllIds: suspend () -> List<String>,
    /** 电脑 Work 会话由 [SessionItemsSource.setDesktopSessions] 提供，正文仍由远程对话页加载。 */
    onOpenDesktopConversation: (DesktopSidebarConversation) -> Unit = {},
    desktopLoadError: String? = null,
    desktopLoginRequired: Boolean = false,
    desktopPairingInProgress: Boolean = false,
    modifier: Modifier = Modifier,
) {
    // 抽屉自带搜索框，键盘弹起时窗口不会被系统缩放（edge-to-edge 下 decorFitsSystemWindows=false），
    // 底部让位得自己算 ime∪navigationBars：只取 navigationBars 的话，列表尾部会永远压在键盘下面滚不出来。
    // 走 contentPadding 而非 windowInsetsPadding，是为了保留键盘收起时内容滚过导航栏的观感。
    val listBottom = WindowInsets.ime.union(WindowInsets.navigationBars)
        .asPaddingValues()
        .calculateBottomPadding()
    val searchQuery by sessions.searchQuery.collectAsStateWithLifecycle()
    val appliedQuery by sessions.appliedQuery.collectAsStateWithLifecycle()
    val sessionFilter by sessions.filter.collectAsStateWithLifecycle()
    val searching = searchQuery.trim().isNotEmpty()
    val items = sessions.pagingData.collectAsLazyPagingItems()
    val desktopOnlyItems by sessions.desktopOnlyItems.collectAsStateWithLifecycle()
    val showDesktopError = desktopLoadError != null &&
        (sessionFilter == ConversationFilter.ALL || sessionFilter == ConversationFilter.DESKTOP_WORK)
    val showDesktopLogin = desktopLoginRequired && sessionFilter == ConversationFilter.DESKTOP_WORK

    var selecting by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(emptySet<String>()) }
    // 进入多选时取一次全量可见 id：既是「共 N」的分母，也是「全选」的选中集。
    // items.itemCount 含分组标题，不能当会话总数用。
    var allIds by remember { mutableStateOf<List<String>?>(null) }
    var confirmingBatch by remember { mutableStateOf(false) }

    fun exitSelection() {
        selecting = false
        selectedIds = emptySet()
    }

    LaunchedEffect(selecting) {
        if (selecting) sessions.setSearchQuery("")
        allIds = if (selecting) onRequestAllIds() else null
    }

    // 全量还没到位时先按已加载的会话行数显示，同样要排除分组标题。
    val totalCount = allIds?.size
        ?: items.itemSnapshotList.items.count { it is SessionListItem.Row }

    // 多选态下返回键先退出多选；抽屉的关闭手势由调用方的 PredictiveBackHandler 处理。
    BackHandler(enabled = selecting) { exitSelection() }

    // 搜索框弹起键盘时，返回先收键盘而不是关抽屉（三星不走预测式返回，见 ImeDismissBackHandler 文档）。
    // 必须排在上面那个 BackHandler 之后：dispatcher 是 LIFO，后注册的先收到。
    ImeDismissBackHandler(enabled = drawerOpen)

    Column(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 8.dp),
    ) {
        DrawerHeader(
            showActions = !selecting,
            canSelect = !searching && sessionFilter != ConversationFilter.DESKTOP_WORK && items.itemCount > 0,
            onSelect = { selecting = true },
            onNewChat = {
                sessions.setSearchQuery("")
                onNewChat()
            },
        )
        if (selecting) {
            SelectionBar(
                selectedCount = selectedIds.size,
                totalCount = totalCount,
                onSelectAll = { selectedIds = allIds.orEmpty().toSet() },
                onClearSelection = { selectedIds = emptySet() },
                onCancel = ::exitSelection,
                onDelete = { confirmingBatch = true },
            )
        } else {
            ConversationSearchField(
                query = searchQuery,
                onQueryChange = sessions::setSearchQuery,
            )
            ConversationFilterRow(
                selected = sessionFilter,
                onSelect = sessions::setFilter,
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            val listState = rememberLazyListState()
            val refreshState = items.loadState.refresh
            val refreshing = refreshState is LoadState.Loading
            val searchFailed = refreshState is LoadState.Error

            LaunchedEffect(searchQuery.trim(), sessionFilter) {
                listState.scrollToItem(0)
            }

            LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(1.dp),
                contentPadding = PaddingValues(top = 4.dp, bottom = listBottom + 12.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .semantics { contentDescription = "会话列表内容" },
            ) {
                if (showDesktopLogin) {
                    item(key = "desktop-login-required") {
                        DesktopHint("登录 MolaGPT 账号后查看电脑对话")
                    }
                }
                if (showDesktopError) {
                    item(key = "desktop-load-error") {
                        DesktopHint(desktopLoadError.orEmpty(), error = true)
                    }
                }
                // 本地没有会话时分页为空，电脑会话改由 desktopOnlyItems 单独显示。
                val desktopOnly = if (items.itemCount == 0 && !refreshing) desktopOnlyItems else emptyList()
                if (items.itemCount == 0 && desktopOnly.isEmpty() && !refreshing &&
                    !showDesktopLogin && !showDesktopError
                ) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 28.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = when {
                                    searchFailed && searching -> "搜索失败，请缩短关键词后重试"
                                    searchFailed -> "加载对话失败"
                                    searching -> "未找到相关对话"
                                    else -> "暂无对话"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else {
                    items(
                        items = desktopOnly,
                        key = { it.key },
                        contentType = { it.contentType },
                    ) { item ->
                        when (item) {
                            is SessionListItem.Header -> GroupLabel(item.label)
                            is SessionListItem.Desktop -> DesktopSessionRow(
                                item = item,
                                selected = currentDesktopKey == "${item.row.machineId}/${item.row.conversation.id}",
                                enabled = !selecting && !desktopPairingInProgress,
                                onClick = { onOpenDesktopConversation(item.row) },
                            )
                            is SessionListItem.Row -> Unit
                        }
                    }
                    items(
                        count = items.itemCount,
                        key = items.itemKey { it.key },
                        contentType = items.itemContentType { it.contentType },
                    ) { index ->
                        when (val item = items[index]) {
                            null -> SessionRowPlaceholder()
                            is SessionListItem.Header -> GroupLabel(item.label)
                            is SessionListItem.Desktop -> DesktopSessionRow(
                                item = item,
                                selected = currentDesktopKey == "${item.row.machineId}/${item.row.conversation.id}",
                                enabled = !selecting && !desktopPairingInProgress,
                                onClick = { onOpenDesktopConversation(item.row) },
                            )
                            is SessionListItem.Row -> {
                                val sessionId = item.conversation.sessionId
                                SessionRow(
                                    conversation = item.conversation,
                                    personaLabel = personaLabels.nameOf(item.conversation.personaId),
                                    selected = sessionId == currentSessionId,
                                    time = item.time,
                                    query = appliedQuery,
                                    snippet = item.snippet,
                                    selectionMode = selecting,
                                    checked = sessionId in selectedIds,
                                    onClick = {
                                        if (selecting) {
                                            selectedIds = if (sessionId in selectedIds) {
                                                selectedIds - sessionId
                                            } else {
                                                selectedIds + sessionId
                                            }
                                        } else if (item.conversation.isImageTask) {
                                            onOpenImageTask(sessionId)
                                        } else {
                                            onSelect(sessionId)
                                        }
                                    },
                                    onLongClick = {
                                        if (!selecting && !searching) {
                                            selecting = true
                                            selectedIds = setOf(sessionId)
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (confirmingBatch && selectedIds.isNotEmpty()) {
        val count = selectedIds.size
        AlertDialog(
            onDismissRequest = { confirmingBatch = false },
            title = { Text("删除已选对话") },
            text = {
                Text("确定删除已选的 $count 个对话？这些对话的消息将一并移除，此操作不可撤销。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val ids = selectedIds
                        confirmingBatch = false
                        exitSelection()
                        onDeleteMany(
                            ids,
                            items.nextSessionIdAfterDelete(
                                deletedSessionIds = ids,
                                currentSessionId = currentSessionId,
                            ),
                        )
                    },
                ) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmingBatch = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun SessionRowPlaceholder() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(RowMinHeight),
    )
}

@Composable
private fun DrawerHeader(
    /** 多选态由 [SelectionBar] 接管操作，标题栏只留品牌。 */
    showActions: Boolean,
    canSelect: Boolean,
    onSelect: () -> Unit,
    onNewChat: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 固定高度：多选态收起操作按钮时标题栏不跟着跳。
            .padding(start = 8.dp, top = 6.dp)
            .height(48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val context = LocalContext.current
        val appLogo = remember {
            runCatching {
                val drawable = context.packageManager.getApplicationIcon(context.packageName)
                val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: 108
                val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: 108
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                drawable.setBounds(0, 0, width, height)
                drawable.draw(Canvas(bitmap))
                bitmap.asImageBitmap()
            }.getOrNull()
        }

        if (appLogo != null) {
            Image(
                bitmap = appLogo,
                contentDescription = "MolaGPT",
                modifier = Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(9.dp)),
            )
        } else {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "M",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.ExtraBold,
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = "MolaGPT",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
        if (showActions) {
            IconButton(onClick = onSelect, enabled = canSelect) {
                Icon(
                    imageVector = Icons.Outlined.Checklist,
                    contentDescription = "多选",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (canSelect) 1f else 0.38f),
                    modifier = Modifier.size(22.dp),
                )
            }
            IconButton(onClick = onNewChat) {
                Icon(
                    imageVector = Icons.Outlined.AddComment,
                    contentDescription = "新建对话",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}

@Composable
private fun ConversationSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 4.dp)
            .height(40.dp)
            .clip(shape)
            .background(colors.surfaceContainer)
            .then(if (focused) Modifier.border(1.dp, colors.primary, shape) else Modifier)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.Search,
            contentDescription = null,
            tint = colors.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.onSurface),
            cursorBrush = SolidColor(colors.primary),
            modifier = Modifier
                .weight(1f)
                .onFocusChanged { focused = it.isFocused }
                .semantics { contentDescription = "搜索对话" },
            decorationBox = { innerTextField ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) {
                        Text(
                            text = "搜索对话",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    innerTextField()
                }
            },
        )
        if (query.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .clickable(onClick = { onQueryChange("") }),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "清除搜索",
                    tint = colors.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

/**
 * 来源筛选：等宽分段，一行放下全部选项，不横向滚动。
 *
 * 选中态是同一块滑块在分段间移动，而不是每段各自淡入淡出——后者在切换途中会同时亮两段。
 * 分段本身不带按压水波纹，滑块就是唯一的反馈；文字颜色随滑块走，字重不变，避免切换时宽度跳动。
 */
@Composable
private fun ConversationFilterRow(
    selected: ConversationFilter,
    onSelect: (ConversationFilter) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val filters = ConversationFilter.entries
    val selectedIndex = filters.indexOf(selected)
    val thumbShape = RoundedCornerShape(8.dp)
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 6.dp)
            .height(34.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(colors.surfaceContainer)
            .padding(3.dp),
    ) {
        val segmentWidth = maxWidth / filters.size
        val thumbOffset by animateDpAsState(
            targetValue = segmentWidth * selectedIndex,
            animationSpec = MolaMotion.spatialSettle(),
            label = "filter-thumb",
        )
        Box(
            modifier = Modifier
                .offset { IntOffset(thumbOffset.roundToPx(), 0) }
                .width(segmentWidth)
                .fillMaxHeight()
                .shadow(1.dp, thumbShape)
                .background(colors.surfaceBright, thumbShape),
        )
        Row(modifier = Modifier.fillMaxSize()) {
            filters.forEachIndexed { index, filter ->
                val active = index == selectedIndex
                // 文字颜色跟随滑块位置渐变：滑块盖住哪一段，哪一段才变成主色。
                val coverage = 1f - ((thumbOffset - segmentWidth * index) / segmentWidth).absoluteValue.coerceAtMost(1f)
                val textColor = lerp(colors.onSurfaceVariant, colors.primary, coverage)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .selectable(
                            selected = active,
                            interactionSource = null,
                            indication = null,
                            role = Role.Tab,
                            onClick = { onSelect(filter) },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = filter.label,
                        // 中英混排时按行框居中，而不是按各自字体的基线落位。
                        style = MaterialTheme.typography.labelMedium.copy(
                            lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
                        ),
                        color = textColor,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** 会话行左侧的来源图标；所有行都占这一列，标题才能对齐。 */
@Composable
private fun SourceIcon(icon: ImageVector, tint: Color) {
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = tint,
        modifier = Modifier.size(18.dp),
    )
    Spacer(Modifier.width(10.dp))
}

/** 时间前的次要信息（角色名、离线、电脑名），限宽截断，保住标题的可读长度。 */
@Composable
private fun RowDetail(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .padding(start = 6.dp)
            .widthIn(max = 88.dp),
    )
}

@Composable
private fun RowTime(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 8.dp),
    )
}

/**
 * 电脑 Work 会话行。版式与 [SessionRow] 一致：来源图标、单行标题、时间；
 * 进行中 / 待审批的状态放在时间前面。
 */
@Composable
private fun DesktopSessionRow(
    item: SessionListItem.Desktop,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val row = item.row
    val conversation = row.conversation
    val available = row.paired || row.online
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = RowMinHeight)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) colors.primary.copy(alpha = 0.12f) else Color.Transparent)
            .clickable(enabled = enabled && available && !row.pairing, onClick = onClick)
            .padding(start = 10.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SourceIcon(
            icon = Icons.Outlined.DesktopWindows,
            tint = if (row.online) colors.primary else colors.onSurfaceVariant.copy(alpha = 0.5f),
        )
        Text(
            text = conversation.title,
            style = MaterialTheme.typography.bodyMedium,
            color = when {
                selected -> colors.primary
                row.online -> colors.onSurface
                else -> colors.onSurfaceVariant
            },
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        when {
            conversation.pendingApprovals > 0 -> Box(
                modifier = Modifier
                    .padding(start = 8.dp)
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(colors.error)
                    .semantics { contentDescription = "等待审批" },
            )
            row.pairing || conversation.busy -> CircularProgressIndicator(
                strokeWidth = 1.5.dp,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .size(12.dp)
                    .semantics { contentDescription = if (row.pairing) "正在配对" else "正在生成" },
            )
        }
        item.detail?.let { RowDetail(it) }
        RowTime(item.time)
    }
}

@Composable
private fun DesktopHint(text: String, error: Boolean = false) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 12.dp, bottom = 4.dp),
    )
}

/** 多选态工具条：占据搜索框与筛选栏的位置，标题栏的新建入口同时收起，避免多选时误触。 */
@Composable
private fun SelectionBar(
    selectedCount: Int,
    totalCount: Int,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    val allSelected = selectedCount > 0 && selectedCount >= totalCount
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 4.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "已选 $selectedCount · 共 $totalCount",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = if (allSelected) onClearSelection else onSelectAll) {
            Text(if (allSelected) "取消全选" else "全选")
        }
        TextButton(onClick = onCancel) { Text("取消") }
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .clickable(enabled = selectedCount > 0, onClick = onDelete),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Delete,
                contentDescription = "删除已选",
                tint = if (selectedCount > 0) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                },
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun GroupLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 10.dp, top = 12.dp, bottom = 3.dp),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionRow(
    conversation: Conversation,
    /** 该会话使用的角色名；null 表示默认角色，不提示。 */
    personaLabel: String?,
    selected: Boolean,
    time: String,
    /** 已生效的搜索词，用于高亮；非搜索态为空串，此时渲染与无高亮完全一致。 */
    query: String,
    /** 正文命中片段；非空时标题下多渲染一行。 */
    snippet: String?,
    selectionMode: Boolean,
    checked: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(12.dp)
    val byok = conversation.providerKind == ProviderKind.BYOK
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = RowMinHeight)
            .clip(shape)
            .background(
                when {
                    selectionMode && checked -> accent.copy(alpha = 0.12f)
                    !selectionMode && selected -> accent.copy(alpha = 0.12f)
                    else -> Color.Transparent
                },
            )
            .then(
                if (selectionMode && checked) {
                    Modifier.border(1.dp, accent.copy(alpha = 0.45f), shape)
                } else {
                    Modifier
                },
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(start = 10.dp, end = if (selectionMode) 4.dp else 12.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SourceIcon(
            icon = when {
                conversation.isImageTask -> Icons.Outlined.Image
                byok -> Icons.Outlined.ChatBubbleOutline
                else -> MolaGlyph
            },
            tint = if (selected && !selectionMode) accent else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (conversation.pinned) {
                    Icon(
                        imageVector = Icons.Filled.Star,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(12.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    text = rememberHighlighted(conversation.title, query),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (selected) accent else MaterialTheme.colorScheme.onSurface,
                    fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (byok && personaLabel != null) RowDetail(personaLabel)
                RowTime(time)
            }
            if (snippet != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = rememberHighlighted(snippet, query),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (selectionMode) {
            // onCheckedChange = null：勾选交互统一由整行 clickable 处理，避免连点抵消。
            Box(
                modifier = Modifier.size(30.dp),
                contentAlignment = Alignment.Center,
            ) {
                Checkbox(checked = checked, onCheckedChange = null)
            }
        }
    }
}

private val RowMinHeight = 40.dp

private fun LazyPagingItems<SessionListItem>.nextSessionIdAfterDelete(
    deletedSessionIds: Set<String>,
    currentSessionId: String?,
): String? = nextSessionIdAfterDelete(
    // 画图任务不能作为「删除后落到的聊天」。
    loadedSessionIds = itemSnapshotList.items
        .filterIsInstance<SessionListItem.Row>()
        .filterNot { it.conversation.isImageTask }
        .map { it.conversation.sessionId },
    deletedSessionIds = deletedSessionIds,
    currentSessionId = currentSessionId,
)

/**
 * 删除后应落到哪个会话：当前会话没被删就留在原处；被删则取列表里第一个未被删的。
 * 全删光（或列表已加载部分全被删）返回 null，由调用方新建空会话兜底。
 */
internal fun nextSessionIdAfterDelete(
    loadedSessionIds: List<String>,
    deletedSessionIds: Set<String>,
    currentSessionId: String?,
): String? {
    if (currentSessionId == null || currentSessionId !in deletedSessionIds) return currentSessionId
    return loadedSessionIds.firstOrNull { it !in deletedSessionIds }
}
