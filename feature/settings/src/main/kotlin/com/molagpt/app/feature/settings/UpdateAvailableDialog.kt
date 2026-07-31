package com.molagpt.app.feature.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.molagpt.app.core.render.StreamingMarkdownView

/** 发现新版本：changelog 用 Markdown 渲染（GitHub Release body）。 */
@Composable
fun UpdateAvailableDialog(
    info: UpdateInfo,
    onDismiss: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    val maxBodyHeight = (LocalConfiguration.current.screenHeightDp * 0.45f).dp
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("发现新版本 ${info.version}") },
        text = {
            val notes = info.notes?.trim().orEmpty()
            if (notes.isBlank()) {
                Text(
                    text = "暂无更新说明",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                StreamingMarkdownView(
                    markdown = notes,
                    textScale = 0.92f,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = maxBodyHeight)
                        .verticalScroll(rememberScrollState()),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    runCatching { uriHandler.openUri(info.url) }
                    onDismiss()
                },
            ) { Text("前往下载") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("稍后") }
        },
    )
}
