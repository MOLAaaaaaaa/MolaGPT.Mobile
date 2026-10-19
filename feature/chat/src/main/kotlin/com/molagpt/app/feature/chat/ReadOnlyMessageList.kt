package com.molagpt.app.feature.chat

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.molagpt.app.core.model.ChatMessage

/**
 * 聊天页的消息列表，只读：给别的页面显示一段不在本机生成的对话（如电脑上的对话）。
 * 渲染与聊天页完全相同，只是不提供编辑和重新生成。
 */
@Composable
fun ReadOnlyMessageList(
    messages: List<ChatMessage>,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(12.dp),
    bottomOverlayPadding: Dp = 0.dp,
    header: (@Composable () -> Unit)? = null,
    listState: LazyListState = rememberLazyListState(),
) {
    MessageList(
        messages = messages,
        modifier = modifier,
        contentPadding = contentPadding,
        bottomOverlayPadding = bottomOverlayPadding,
        canEdit = false,
        canEditAssistant = false,
        canRegenerate = false,
        header = header,
        listState = listState,
    )
}

/**
 * 顶栏玻璃的显现程度，传给 MolaTopBar 的 overlap：列表由程序滚到底时没有嵌套滚动事件，
 * 只能按列表位置算。内容滚入顶栏下方 [distancePx] 内渐显，与聊天页一致。
 */
fun LazyListState.topBarOverlap(distancePx: Float): Float =
    if (firstVisibleItemIndex > 0) 1f else (firstVisibleItemScrollOffset / distancePx).coerceIn(0f, 1f)
