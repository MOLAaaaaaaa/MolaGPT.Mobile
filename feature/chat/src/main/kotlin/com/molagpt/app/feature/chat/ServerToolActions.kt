package com.molagpt.app.feature.chat

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.mutableStateMapOf
import com.molagpt.app.core.model.MessageFragment

internal class ServerToolActions(
    val retry: suspend (String) -> MessageFragment.ToolCall,
    val download: (ticket: String, filename: String) -> Unit,
) {
    val results = mutableStateMapOf<String, MessageFragment.ToolCall>()
}

internal val LocalServerToolActions = staticCompositionLocalOf<ServerToolActions?> { null }
