package com.molagpt.app.feature.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.molagpt.app.core.render.StreamingMarkdownView

/** 运营公告弹窗：正文按 Markdown 渲染；有链接时额外显示跳转按钮。 */
@Composable
fun OpsMessageDialog(
    message: OpsMessage,
    onDismiss: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    val maxBodyHeight = (LocalConfiguration.current.screenHeightDp * 0.45f).dp
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(message.title) },
        text = {
            StreamingMarkdownView(
                markdown = message.body,
                textScale = 0.92f,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxBodyHeight)
                    .verticalScroll(rememberScrollState()),
            )
        },
        confirmButton = {
            val url = message.url?.takeIf { it.isNotBlank() }
            if (url != null) {
                TextButton(
                    onClick = {
                        runCatching { uriHandler.openUri(url) }
                        onDismiss()
                    },
                ) { Text(message.urlLabel?.takeIf { it.isNotBlank() } ?: "查看详情") }
            } else {
                TextButton(onClick = onDismiss) { Text("知道了") }
            }
        },
        dismissButton = {
            if (!message.url.isNullOrBlank()) {
                TextButton(onClick = onDismiss) { Text("知道了") }
            }
        },
    )
}
