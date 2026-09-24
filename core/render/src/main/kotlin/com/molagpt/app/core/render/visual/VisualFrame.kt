package com.molagpt.app.core.render.visual

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.OpenInFull
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

class VisualMenuItem(val label: String, val onClick: () -> Unit)

/**
 * 内嵌组件的外框：标题、右上角的全屏与更多菜单，内容放在下面。
 *
 * 右上角的按钮浮在标题行之上、不在录制层里，「保存图片」「分享图片」拿到的是标题加内容，
 * 不带这几个按钮。录制层自带底色：透明底的 PNG 发到聊天软件里常被显示成黑底。
 */
@Composable
fun VisualFrame(
    title: String?,
    modifier: Modifier = Modifier,
    meta: String? = null,
    imageName: String = "图表",
    onFullscreen: (() -> Unit)? = null,
    menu: List<VisualMenuItem> = emptyList(),
    capture: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val palette = rememberVisualPalette()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val layer = rememberGraphicsLayer()
    val shape = RoundedCornerShape(12.dp)
    var menuOpen by remember { mutableStateOf(false) }

    val items = buildList {
        addAll(menu)
        if (capture) {
            add(VisualMenuItem("保存图片") {
                scope.launch {
                    val bitmap = layer.toImageBitmap().asAndroidBitmap()
                    val ok = VisualExport.savePng(context, bitmap)
                    Toast.makeText(context, if (ok) "已保存到相册" else "保存失败", Toast.LENGTH_SHORT).show()
                }
            })
            add(VisualMenuItem("分享图片") {
                scope.launch {
                    val bitmap = layer.toImageBitmap().asAndroidBitmap()
                    runCatching { VisualExport.sharePng(context, bitmap, VisualExport.safeName(title, imageName)) }
                        .onFailure { Toast.makeText(context, "分享失败", Toast.LENGTH_SHORT).show() }
                }
            })
        }
    }
    val tools = (if (onFullscreen != null) 1 else 0) + (if (items.isNotEmpty()) 1 else 0)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, palette.border, shape),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .drawWithContent {
                    layer.record { this@drawWithContent.drawContent() }
                    drawLayer(layer)
                }
                .background(palette.surface),
        ) {
            if (!title.isNullOrBlank() || !meta.isNullOrBlank() || tools > 0) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 14.dp, top = 11.dp, end = 8.dp + 40.dp * tools, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = title?.takeIf { it.isNotBlank() }.orEmpty(),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = palette.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (!meta.isNullOrBlank()) {
                        Text(
                            text = meta,
                            style = MaterialTheme.typography.labelSmall,
                            color = palette.muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }
            content()
        }

        if (tools > 0) {
            Row(modifier = Modifier.align(Alignment.TopEnd).padding(top = 2.dp, end = 2.dp)) {
                if (onFullscreen != null) {
                    IconButton(onClick = onFullscreen, modifier = Modifier.size(40.dp)) {
                        Icon(
                            Icons.Outlined.OpenInFull,
                            contentDescription = "全屏查看",
                            tint = palette.muted,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                if (items.isNotEmpty()) {
                    Box {
                        IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(40.dp)) {
                            Icon(
                                Icons.Filled.MoreVert,
                                contentDescription = "更多",
                                tint = palette.muted,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            items.forEach { item ->
                                DropdownMenuItem(
                                    text = { Text(item.label) },
                                    onClick = {
                                        menuOpen = false
                                        item.onClick()
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 全屏组件的顶栏：关闭、标题和一行操作提示。系统栏的边距由宿主统一留。 */
@Composable
internal fun FullscreenBar(
    title: String,
    hint: String?,
    onClose: () -> Unit,
    actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
) {
    val palette = rememberVisualPalette()
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) {
            Icon(Icons.Filled.Close, contentDescription = "关闭", tint = palette.text)
        }
        Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = palette.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!hint.isNullOrBlank()) {
                Text(
                    text = hint,
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        actions()
    }
}
