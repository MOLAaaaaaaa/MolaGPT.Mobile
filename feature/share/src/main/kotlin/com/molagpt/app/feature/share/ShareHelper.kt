package com.molagpt.app.feature.share

import android.content.Context
import android.content.Intent
import com.molagpt.app.core.model.ChatMessage
import com.molagpt.app.core.model.Role

/** 分享：把消息/会话拼成纯文本走系统分享面板（首版精简，云端分享链接属后续）。 */
object ShareHelper {

    fun shareText(context: Context, text: String, title: String = "分享") {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(intent, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun shareConversation(context: Context, title: String, messages: List<ChatMessage>) {
        val body = buildString {
            appendLine("# $title").appendLine()
            messages.forEach { m ->
                val who = when (m.role) {
                    Role.USER -> "我"
                    Role.ASSISTANT -> "MolaGPT"
                    else -> m.role.name
                }
                appendLine("**$who：**")
                appendLine(m.rawText ?: "")
                appendLine()
            }
        }
        shareText(context, body, "分享对话")
    }
}
