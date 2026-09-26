package com.molagpt.app.feature.imagegen

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import coil3.compose.AsyncImage
import com.molagpt.app.core.render.ComposerSurface
import com.molagpt.app.core.render.MolaMotion
import com.molagpt.app.core.render.RoundIconButton
import com.molagpt.app.core.render.SegmentedControl
import com.molagpt.app.core.render.ToolChip
import com.molagpt.app.core.render.decodeImageModel
import com.molagpt.app.core.storage.ImageMode
import com.molagpt.app.core.storage.ImageRunKind

/**
 * 画图输入区。外壳、胶囊、圆按钮都是聊天输入区那一套。
 *
 * 从上到下：模式与参数胶囊 → 这次要用的图（参考图，或对话模式下的「上一张」）→ 输入框 →
 * 附件、「本次」说明、发送/停止。「本次」一行把这次到底是生成还是编辑、编辑哪张说清楚。
 */
@Composable
internal fun WorkbenchComposer(
    viewModel: ImageWorkbenchViewModel,
    selection: ModelSelection,
    mode: ImageMode,
    headUrl: String?,
    busy: Boolean,
    onPickImages: () -> Unit,
    onTakePhoto: () -> Unit,
    onOpenParams: () -> Unit,
    onCustomSize: () -> Unit,
    onEditMask: (RefDraft) -> Unit,
    modifier: Modifier = Modifier,
) {
    val editable = selection.editable
    val refs = if (editable) viewModel.refs else emptyList()
    val showHead = editable && refs.isEmpty() && mode == ImageMode.CHAT && headUrl != null && !viewModel.chainDetached
    val kind = planKind(editable, mode, refs.size, viewModel.perImage, showHead)
    val countOn = countApplies(editable, mode)
    val effectiveCount = if (kind == ImageRunKind.NEW && countOn) viewModel.count else 1
    val canSend = viewModel.prompt.isNotBlank() && selection.model != null
    val colorScheme = MaterialTheme.colorScheme

    ComposerSurface(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (editable) {
                SegmentedControl(
                    options = listOf(ImageMode.CHAT.key to "对话", ImageMode.GENERATE.key to "生成"),
                    selected = mode.key,
                    onSelect = { viewModel.setMode(ImageMode.of(it)) },
                    modifier = Modifier.width(112.dp),
                )
            }
            SizeChip(
                size = viewModel.size,
                onSelect = { viewModel.updateSize(it, selection) },
                onCustom = onCustomSize,
            )
            // 张数只对「生成新图」有意义；给了底图或参考图时收起来。
            if (countOn && kind == ImageRunKind.NEW) {
                CountChip(count = viewModel.count, onSelect = viewModel::updateCount)
            }
            ToolChip(
                label = "参数",
                checked = false,
                enabled = true,
                onClick = onOpenParams,
                leadingIcon = Icons.Filled.Tune,
                role = Role.Button,
            )
        }

        if (refs.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                refs.forEach { ref ->
                    RefThumb(ref = ref, onEdit = { onEditMask(ref) }, onRemove = { viewModel.removeRef(ref) })
                }
                if (refs.size >= 2) {
                    ToolChip(
                        label = "逐张编辑",
                        checked = viewModel.perImage,
                        enabled = true,
                        onClick = { viewModel.perImage = !viewModel.perImage },
                    )
                }
            }
        } else if (showHead) {
            HeadThumb(url = headUrl!!, onDetach = { viewModel.chainDetached = true })
        }

        BasicTextField(
            value = viewModel.prompt,
            onValueChange = { viewModel.prompt = it },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp, max = 126.dp)
                .padding(horizontal = 4.dp, vertical = 2.dp),
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = colorScheme.onSurface),
            cursorBrush = SolidColor(colorScheme.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
            maxLines = 6,
            decorationBox = { inner ->
                Box(modifier = Modifier.fillMaxWidth()) {
                    if (viewModel.prompt.isEmpty()) {
                        Text(
                            text = planPlaceholder(kind),
                            color = colorScheme.onSurfaceVariant.copy(alpha = 0.68f),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                    inner()
                }
            },
        )

        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (editable) {
                var menuOpen by remember { mutableStateOf(false) }
                Box {
                    RoundIconButton(
                        icon = Icons.Filled.Add,
                        contentDescription = "添加图片",
                        selected = menuOpen,
                        onClick = { menuOpen = true },
                    )
                    // 不可聚焦：否则弹出时抢走输入框焦点，键盘收起，整个输入区跟着掉下去。
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false },
                        properties = PopupProperties(focusable = false),
                    ) {
                        DropdownMenuItem(
                            text = { Text("拍照") },
                            leadingIcon = { Icon(Icons.Filled.PhotoCamera, null, tint = colorScheme.onSurfaceVariant) },
                            onClick = { menuOpen = false; onTakePhoto() },
                        )
                        DropdownMenuItem(
                            text = { Text("图片") },
                            leadingIcon = { Icon(Icons.Filled.Image, null, tint = colorScheme.onSurfaceVariant) },
                            onClick = { menuOpen = false; onPickImages() },
                        )
                    }
                }
                Spacer(Modifier.width(10.dp))
            }
            Text(
                text = buildAnnotatedString {
                    withStyle(SpanStyle(color = colorScheme.onSurfaceVariant)) { append("本次：") }
                    withStyle(SpanStyle(color = colorScheme.onSurface, fontWeight = FontWeight.SemiBold)) {
                        append(planLabel(kind, effectiveCount, refs.size))
                    }
                },
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(6.dp))
            Crossfade(targetState = busy, animationSpec = MolaMotion.standard(MolaMotion.Medium), label = "sendStop") { running ->
                if (running) {
                    RoundIconButton(
                        icon = Icons.Filled.Stop,
                        contentDescription = "停止生成",
                        selected = true,
                        containerColor = colorScheme.error,
                        contentColor = colorScheme.onError,
                        onClick = viewModel::stopCurrent,
                    )
                } else {
                    RoundIconButton(
                        icon = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "发送",
                        selected = canSend,
                        enabled = canSend,
                        containerColor = if (canSend) colorScheme.primary else colorScheme.surfaceVariant,
                        contentColor = if (canSend) colorScheme.onPrimary else colorScheme.onSurfaceVariant.copy(alpha = 0.54f),
                        onClick = { viewModel.send(selection) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SizeChip(size: String, onSelect: (String) -> Unit, onCustom: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        ToolChip(
            label = sizeChipLabel(size),
            checked = false,
            enabled = true,
            onClick = { open = true },
            trailingIcon = Icons.Filled.ArrowDropDown,
            role = Role.Button,
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, properties = PopupProperties(focusable = false)) {
            SizeOptions.forEach { (value, label) ->
                MenuOption(
                    title = label,
                    detail = value.replace("x", "×"),
                    selected = value == size,
                    onClick = { open = false; onSelect(value) },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(text = { Text("自定义尺寸…") }, onClick = { open = false; onCustom() })
        }
    }
}

@Composable
private fun CountChip(count: Int, onSelect: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        ToolChip(
            label = "$count 张",
            checked = false,
            enabled = true,
            onClick = { open = true },
            trailingIcon = Icons.Filled.ArrowDropDown,
            role = Role.Button,
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, properties = PopupProperties(focusable = false)) {
            CountOptions.forEach { n ->
                MenuOption(title = "$n 张", detail = null, selected = n == count, onClick = { open = false; onSelect(n) })
            }
        }
    }
}

@Composable
internal fun MenuOption(title: String, detail: String?, selected: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    color = if (selected) MaterialTheme.colorScheme.primary else Color.Unspecified,
                    fontWeight = if (selected) FontWeight.SemiBold else null,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (detail != null) {
                    Spacer(Modifier.width(12.dp))
                    Text(detail, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        trailingIcon = if (selected) {
            { Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp)) }
        } else null,
        onClick = onClick,
    )
}

/**
 * 缩略图外留一圈边，× 骑在右上角的外沿上。系统会把小按钮的触控区撑到 48dp，
 * 放在图里面的话，点图的上半部分都会被当成删除。
 */
@Composable
private fun ThumbFrame(
    size: androidx.compose.ui.unit.Dp,
    borderColor: Color,
    onClick: (() -> Unit)?,
    onRemove: () -> Unit,
    removeDescription: String,
    content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    Box(modifier = Modifier.size(size + 8.dp)) {
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .size(size)
                .clip(shape)
                .border(1.dp, borderColor, shape)
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
            content = content,
        )
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(20.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f))
                .clickable(onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Close, contentDescription = removeDescription, tint = MaterialTheme.colorScheme.surface, modifier = Modifier.size(13.dp))
        }
    }
}

@Composable
private fun RefThumb(ref: RefDraft, onEdit: () -> Unit, onRemove: () -> Unit) {
    val bitmap = remember(ref) { ref.previewBitmap.asImageBitmap() }
    ThumbFrame(
        size = 72.dp,
        borderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
        onClick = onEdit,
        onRemove = onRemove,
        removeDescription = "移除参考图",
    ) {
        Image(bitmap = bitmap, contentDescription = "编辑参考图的局部区域", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        Text(
            text = if (ref.hasMask) "已涂抹" else "涂抹",
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(4.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(if (ref.hasMask) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.55f))
                .padding(horizontal = 4.dp, vertical = 1.dp),
        )
    }
}

@Composable
private fun HeadThumb(url: String, onDetach: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        ThumbFrame(
            size = 52.dp,
            borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.32f),
            onClick = null,
            onRemove = onDetach,
            removeDescription = "改为生成新图",
        ) {
            AsyncImage(
                model = remember(url) { decodeImageModel(url) },
                contentDescription = "上一张",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = "续改上一张",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
