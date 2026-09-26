package com.molagpt.app.feature.imagegen

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.molagpt.app.core.render.ImeDismissBackHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private class Base64Preview(val bytes: ByteArray, val bitmap: Bitmap, val mimeType: String, val base64Length: Int)

/** 把接口返回的 Base64 或 data URL 还原成图片，排查「有响应但没出图」时用。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Base64ToolScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var input by rememberSaveable { mutableStateOf("") }
    var result by remember { mutableStateOf<Base64Preview?>(null) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }

    ImeDismissBackHandler()

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Base64 工具") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
                },
            )
        },
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                label = { Text("Base64 或 Data URL") },
                placeholder = { Text("data:image/png;base64,iVBORw0KGgo…") },
                minLines = 6,
                maxLines = 12,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = {
                    val text = input
                    scope.launch {
                        val parsed = withContext(Dispatchers.Default) { runCatching { decode(text) } }
                        result = parsed.getOrNull()
                        error = parsed.exceptionOrNull()?.let { it.message ?: "解析失败" }
                    }
                }, enabled = input.isNotBlank()) { Text("解析") }
                OutlinedButton(onClick = {
                    input = ""
                    result = null
                    error = null
                }) { Text("清空") }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            result?.let { preview ->
                Image(
                    bitmap = remember(preview) { preview.bitmap.asImageBitmap() },
                    contentDescription = "解析结果",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 160.dp, max = 480.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                )
                Text(
                    "${preview.mimeType} · ${preview.bitmap.width}×${preview.bitmap.height} · ${preview.bytes.size / 1024} KB · ${preview.base64Length} 字符",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = {
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) { ImageActions.writeToGallery(context, preview.bytes) }
                        Toast.makeText(context, if (ok) "已保存到相册" else "保存失败", Toast.LENGTH_SHORT).show()
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text("保存到相册") }
            }
        }
    }
}

private fun decode(input: String): Base64Preview {
    var text = input.trim()
    if (text.isEmpty()) error("内容为空")
    if (text.startsWith("data:", ignoreCase = true)) text = text.substringAfter(',')
    text = text.replace(Regex("""\s+"""), "")
    val bytes = runCatching { Base64.decode(text, Base64.DEFAULT) }.getOrElse { error("Base64 格式无效") }
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("图片格式无法识别")
    return Base64Preview(bytes, bitmap, mimeOf(bytes), text.length)
}
