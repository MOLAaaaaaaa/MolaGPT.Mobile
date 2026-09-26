package com.molagpt.app.feature.imagegen

import android.graphics.Paint
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.PopupProperties
import com.molagpt.app.core.model.ByokImageFormat
import com.molagpt.app.core.model.ByokProvider
import com.molagpt.app.core.model.ProviderModel
import com.molagpt.app.core.network.looksLikeByokImageReasoningModel
import com.molagpt.app.core.render.ImeDismissBackHandler
import com.molagpt.app.core.render.ToolChip
import com.molagpt.app.core.storage.ImageParams
import kotlin.math.roundToInt

/** 模型能力小标：能不能接着改图。 */
@Composable
internal fun CapabilityBadge(editable: Boolean, modifier: Modifier = Modifier) {
    val color = if (editable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Text(
        text = if (editable) "可编辑" else "仅生成",
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = color,
        maxLines = 1,
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 4.dp, vertical = 1.dp),
    )
}

/** 顶栏模型菜单：按服务分组，底部固定「管理图像服务」。 */
@Composable
internal fun ModelMenu(
    expanded: Boolean,
    providers: List<ByokProvider>,
    selection: ModelSelection,
    onSelect: (ByokProvider, ProviderModel) -> Unit,
    onManage: () -> Unit,
    onDismiss: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss, properties = PopupProperties(focusable = false)) {
        providers.forEachIndexed { index, provider ->
            if (index > 0) HorizontalDivider()
            DropdownMenuItem(
                text = { Text(provider.name, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) },
                onClick = {},
                enabled = false,
            )
            val models = provider.models.filter { it.supportsImageGeneration }
            if (models.isEmpty()) {
                DropdownMenuItem(
                    text = { Text("暂无图像模型", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                    onClick = {},
                    enabled = false,
                )
            }
            models.forEach { model ->
                val selected = provider.id == selection.provider?.id && model.id == selection.model?.id
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                model.displayName,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            Spacer(Modifier.width(8.dp))
                            CapabilityBadge(editable = model.supportsImageEdit)
                            if (selected) {
                                Spacer(Modifier.width(10.dp))
                                Icon(Icons.Filled.Check, "当前模型", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                            }
                        }
                    },
                    onClick = { onSelect(provider, model) },
                )
            }
        }
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text("管理图像服务") },
            leadingIcon = { Icon(Icons.Filled.Settings, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
            onClick = onManage,
        )
    }
}

/**
 * 生成参数。只列当前接口认的那几项：images 接口有质量、格式、背景、审核；
 * 对话式接口和 Gemini 由模型自己决定，只剩推理开关（支持时）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ParamsSheet(
    provider: ByokProvider?,
    model: ProviderModel?,
    params: ImageParams,
    onChange: (ImageParams) -> Unit,
    onDismiss: () -> Unit,
) {
    val imagesApi = provider?.imageFormat == ByokImageFormat.OPENAI_IMAGES &&
        provider.type != com.molagpt.app.core.model.ByokProviderType.GEMINI
    val canReason = model != null && looksLikeByokImageReasoningModel(model.id) &&
        provider?.imageFormat == ByokImageFormat.OPENAI_CHAT_IMAGE
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        contentWindowInsets = { WindowInsets(0) },
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
                .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars)),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("生成参数", style = MaterialTheme.typography.titleMedium)
            if (imagesApi) {
                ChoiceRow("质量", QualityOptions, params.quality) { onChange(params.copy(quality = it)) }
                ChoiceRow("格式", FormatOptions, params.outputFormat) { onChange(params.copy(outputFormat = it)) }
                if (params.outputFormat == "jpeg" || params.outputFormat == "webp") {
                    Column {
                        Text("压缩质量 ${params.compression}", style = MaterialTheme.typography.labelLarge)
                        Slider(
                            value = params.compression.toFloat(),
                            onValueChange = { onChange(params.copy(compression = it.roundToInt())) },
                            valueRange = 0f..100f,
                        )
                    }
                }
                ChoiceRow("背景", BackgroundOptions, params.background) { onChange(params.copy(background = it)) }
                ChoiceRow("内容审核", ModerationOptions, params.moderation) { onChange(params.copy(moderation = it)) }
            } else {
                Text(
                    "质量与格式由模型决定",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (canReason) {
                ToggleRow("图像推理", "生成耗时可能增加", params.reasoning) { onChange(params.copy(reasoning = it)) }
                if (params.reasoning) {
                    ChoiceRow("推理强度", EffortOptions, params.reasoningEffort) { onChange(params.copy(reasoningEffort = it)) }
                }
            }
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("完成") }
        }
    }
}

@Composable
private fun ChoiceRow(label: String, options: List<Pair<String, String>>, value: String, onSelect: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (key, title) ->
                ToolChip(
                    label = title,
                    checked = key == value,
                    enabled = true,
                    onClick = { onSelect(key) },
                    role = Role.RadioButton,
                )
            }
        }
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WorkbenchSettingsSheet(
    timeoutSeconds: Int,
    onTimeoutChange: (Int) -> Unit,
    onClearAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(timeoutSeconds.toString()) }
    ModalBottomSheet(
        onDismissRequest = {
            text.toIntOrNull()?.let { onTimeoutChange(it.coerceIn(10, 3600)) }
            onDismiss()
        },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        contentWindowInsets = { WindowInsets(0) },
    ) {
        // 键盘弹着时返回先收键盘，不关弹层。
        ImeDismissBackHandler()
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
                .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars)),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("画图设置", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = text,
                onValueChange = { v -> text = v.filter(Char::isDigit).take(4) },
                label = { Text("请求超时（秒）") },
                supportingText = { Text("大尺寸和推理任务可能耗时较长") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(
                onClick = onClearAll,
                modifier = Modifier.fillMaxWidth(),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.6f)),
            ) {
                Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("清除全部画图数据", color = MaterialTheme.colorScheme.error)
            }
            Button(
                onClick = {
                    text.toIntOrNull()?.let { onTimeoutChange(it.coerceIn(10, 3600)) }
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("完成") }
        }
    }
}

@Composable
internal fun CustomSizeDialog(initial: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    val (w0, h0) = parseSize(initial) ?: (1024 to 1024)
    var w by remember { mutableStateOf(w0.toString()) }
    var h by remember { mutableStateOf(h0.toString()) }
    val normalized = normalizeSize("${w}x$h")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("自定义尺寸") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = w,
                        onValueChange = { w = it.filter(Char::isDigit).take(4) },
                        label = { Text("宽") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    Text("×")
                    OutlinedTextField(
                        value = h,
                        onValueChange = { h = it.filter(Char::isDigit).take(4) },
                        label = { Text("高") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                }
                Text(
                    text = normalized?.let { "实际尺寸：${it.replace("x", "×")}" } ?: "输入宽和高",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { normalized?.let(onConfirm) }, enabled = normalized != null) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
internal fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(confirmLabel, color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
internal fun SizeWarningDialog(hasPro: Boolean, onSwitchPro: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Info, contentDescription = null) },
        title = { Text("大尺寸生成提醒") },
        text = { Text("gpt-image-2 生成大尺寸图片时可能降低分辨率。") },
        confirmButton = {
            if (hasPro) TextButton(onClick = onSwitchPro) { Text("切换至 Pro") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("继续") } },
    )
}

@Composable
internal fun AboutDialog(onDismiss: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
        icon = { ImageWorkbenchBrandMark() },
        title = { Text("关于图像生成模块") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("图片生成、连续编辑与局部重绘。")
                Text(
                    "移植自开源项目，感谢原作者。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = { uriHandler.openUri("https://github.com/DisaWdcba/SimpleAIPainting") }) {
                    Text("DisaWdcba/SimpleAIPainting")
                }
            }
        },
    )
}

/** 涂抹要修改的区域。红色 = 允许改，其余保持原样。 */
@Composable
internal fun MaskEditorDialog(ref: RefDraft, onDismiss: () -> Unit) {
    var tool by remember { mutableStateOf(MaskTool.Brush) }
    var brushSize by remember { mutableFloatStateOf(36f) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val tint = remember { maskTintPaint() }
    val plain = remember { Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG) }
    val previewColor = if (tool == MaskTool.Brush) MaterialTheme.colorScheme.error else Color.White
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(WindowInsets.systemBars.asPaddingValues())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "关闭") }
                    Text("局部重绘", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = ref::clearMask, enabled = ref.hasMask) { Text("清除") }
                    Button(onClick = onDismiss) { Text("完成") }
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                        .onSizeChanged { viewport = it },
                ) {
                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(tool, brushSize, viewport) {
                                detectDragGestures(
                                    onDragStart = { ref.drawMaskAt(it, viewport, tool, brushSize) },
                                    onDrag = { change, _ -> ref.drawMaskAt(change.position, viewport, tool, brushSize) },
                                    onDragEnd = ref::onStrokeEnd,
                                    onDragCancel = ref::onStrokeEnd,
                                )
                            },
                    ) {
                        @Suppress("UNUSED_VARIABLE")
                        val version = ref.maskVersion
                        val dst = fittedRect(ref.editBitmap.width, ref.editBitmap.height, size)
                        drawContext.canvas.nativeCanvas.drawBitmap(ref.editBitmap, null, dst, plain)
                        drawContext.canvas.nativeCanvas.drawBitmap(ref.maskBitmap, null, dst, tint)
                        drawCircle(
                            color = previewColor,
                            radius = brushSize / 2f,
                            center = Offset(dst.right - brushSize, dst.top + brushSize),
                            style = Stroke(2.dp.toPx()),
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    ToolChip(
                        label = "画笔",
                        checked = tool == MaskTool.Brush,
                        enabled = true,
                        onClick = { tool = MaskTool.Brush },
                        leadingIcon = Icons.Filled.Brush,
                        role = Role.RadioButton,
                    )
                    ToolChip(
                        label = "橡皮",
                        checked = tool == MaskTool.Eraser,
                        enabled = true,
                        onClick = { tool = MaskTool.Eraser },
                        leadingIcon = Icons.Filled.VisibilityOff,
                        role = Role.RadioButton,
                    )
                    Text("粗细 ${brushSize.roundToInt()}", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                }
                Slider(value = brushSize, onValueChange = { brushSize = it }, valueRange = 8f..120f)
                Text(
                    "红色区域将被重绘",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
        }
    }
}
