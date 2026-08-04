package com.molagpt.app.feature.session

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.molagpt.app.core.model.Conversation
import com.molagpt.app.core.model.ProviderKind
import androidx.paging.LoadState
import androidx.paging.PagingData
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemContentType
import androidx.paging.compose.itemKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

@Composable
fun SessionDrawer(
    sessions: Flow<PagingData<SessionListItem>>,
    currentSessionId: String?,
    onNewChat: () -> Unit,
    onSelect: (String) -> Unit,
    onDelete: (sessionId: String, nextSessionId: String?) -> Unit,
    onDeleteMany: (sessionIds: Set<String>, nextSessionId: String?) -> Unit,
    /** 「全选」向数据层取全量可见会话 id（不止 Paging 已加载的那部分）。 */
    onRequestAllIds: suspend () -> List<String>,
    modifier: Modifier = Modifier,
) {
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val items = sessions.collectAsLazyPagingItems()

    var selecting by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(emptySet<String>()) }
    // 进入多选时取一次全量可见 id：既是「共 N」的分母，也是「全选」的选中集。
    // items.itemCount 含分组标题，不能当会话总数用。
    var allIds by remember { mutableStateOf<List<String>?>(null) }
    var pendingDelete by remember { mutableStateOf<Conversation?>(null) }
    var confirmingBatch by remember { mutableStateOf(false) }

    fun exitSelection() {
        selecting = false
        selectedIds = emptySet()
    }

    LaunchedEffect(selecting) {
        allIds = if (selecting) onRequestAllIds() else null
    }

    // 全量还没到位时先按已加载的会话行数显示，同样要排除分组标题。
    val totalCount = allIds?.size
        ?: items.itemSnapshotList.items.count { it is SessionListItem.Row }

    // 多选态下返回键先退出多选；抽屉的关闭手势由调用方的 PredictiveBackHandler 处理。
    BackHandler(enabled = selecting) { exitSelection() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 8.dp),
    ) {
        DrawerHeader()
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
            NewChatButton(onClick = onNewChat)
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            val listState = rememberLazyListState()
            val refreshing = items.loadState.refresh is LoadState.Loading

            LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(1.dp),
                contentPadding = PaddingValues(top = 4.dp, bottom = navBottom + 12.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .semantics { contentDescription = "会话列表内容" },
            ) {
                if (items.itemCount == 0 && !refreshing) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 28.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "暂无对话",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else {
                    if (!selecting) {
                        item(key = "select-entry", contentType = "select-entry") {
                            ManageRow(onClick = { selecting = true })
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
                            is SessionListItem.Row -> {
                                val sessionId = item.conversation.sessionId
                                SessionRow(
                                    conversation = item.conversation,
                                    selected = sessionId == currentSessionId,
                                    time = item.time,
                                    selectionMode = selecting,
                                    checked = sessionId in selectedIds,
                                    onClick = {
                                        if (selecting) {
                                            selectedIds = if (sessionId in selectedIds) {
                                                selectedIds - sessionId
                                            } else {
                                                selectedIds + sessionId
                                            }
                                        } else {
                                            onSelect(sessionId)
                                        }
                                    },
                                    onLongClick = {
                                        if (!selecting) {
                                            selecting = true
                                            selectedIds = setOf(sessionId)
                                        }
                                    },
                                    onDelete = { pendingDelete = item.conversation },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    val deleteTarget = pendingDelete
    if (deleteTarget != null) {
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除对话") },
            text = { Text("确定删除「${deleteTarget.title}」吗？该对话的全部消息将一并移除，此操作不可撤销。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = null
                        onDelete(
                            deleteTarget.sessionId,
                            items.nextSessionIdAfterDelete(
                                deletedSessionIds = setOf(deleteTarget.sessionId),
                                currentSessionId = currentSessionId,
                            ),
                        )
                    },
                ) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } },
        )
    }

    if (confirmingBatch && selectedIds.isNotEmpty()) {
        val count = selectedIds.size
        AlertDialog(
            onDismissRequest = { confirmingBatch = false },
            title = { Text(if (count >= totalCount) "删除全部对话" else "删除已选对话") },
            text = {
                Text(
                    if (count >= totalCount) "确定删除全部 $count 个对话？这些对话的消息将一并移除，此操作不可撤销。"
                    else "确定删除已选的 $count 个对话？这些对话的消息将一并移除，此操作不可撤销。",
                )
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
            .height(44.dp),
    )
}

@Composable
private fun DrawerHeader() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, top = 14.dp, bottom = 6.dp),
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
        Text("MolaGPT", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun NewChatButton(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 4.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Add,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "新建对话",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Medium,
        )
    }
}

/** 多选的显式入口（长按任意会话行是等价的隐式入口）。 */
@Composable
private fun ManageRow(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "选择",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

/** 多选态工具条：占据「新建对话」的位置，避免多选时误触新建。 */
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
    selected: Boolean,
    time: String,
    selectionMode: Boolean,
    checked: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDelete: () -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
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
            .drawBehind {
                // 当前会话的左侧指示条；多选态让位于勾选描边，避免两种强调同时出现。
                if (selected && !selectionMode) {
                    val width = 3.dp.toPx()
                    drawRoundRect(
                        color = accent,
                        topLeft = Offset(0f, size.height * 0.2f),
                        size = Size(width, size.height * 0.6f),
                        cornerRadius = CornerRadius(width, width),
                    )
                }
            }
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
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
                    text = conversation.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (selected) accent else MaterialTheme.colorScheme.onSurface,
                    fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (conversation.providerKind == ProviderKind.BYOK) {
                    Spacer(Modifier.width(6.dp))
                    SourceBadge("BYOK")
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = time,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
        } else {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onDelete),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = "删除",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
private fun SourceBadge(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

private fun LazyPagingItems<SessionListItem>.nextSessionIdAfterDelete(
    deletedSessionIds: Set<String>,
    currentSessionId: String?,
): String? = nextSessionIdAfterDelete(
    loadedSessionIds = itemSnapshotList.items
        .filterIsInstance<SessionListItem.Row>()
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
