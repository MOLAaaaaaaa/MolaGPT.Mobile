package com.molagpt.app.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.molagpt.app.core.model.ByokMcpServer
import com.molagpt.app.core.model.WebSearchProvider
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * BYOK 自定义工具独立页：从设置页「对话工具」入口卡进入。
 * - 联网搜索服务（当前服务商 + key + 结果数；改动后才出现保存按钮）
 * - MCP 服务器（空状态 + 底部添加按钮 → 底部弹层含测试连接；行内开关 + 点击进详情）
 * - 外挂视觉配置（目标模型选择；无可用模型时提示 + 模型管理入口）
 * - 图像生成配置（目标模型 / 尺寸 / 风格 + 工作台入口）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ByokToolsScreen(
    viewModel: SettingsViewModel,
    onOpenImageWorkbench: () -> Unit,
    onOpenMcpDetail: (String) -> Unit,
    onOpenByokProviders: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val s by viewModel.settings.collectAsStateWithLifecycle()
    val visionOptions by viewModel.visionModelOptions.collectAsStateWithLifecycle()
    val imageGenOptions by viewModel.imageGenModelOptions.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    fun showSnack(msg: String) = scope.launch { snackbar.showSnackbar(msg) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.systemBars.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
        topBar = {
            TopAppBar(
                title = { Text("BYOK 自定义工具") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
            ) {
                Text(
                    "以下工具仅对自定义 API 模型生效，由 App 本机执行，与 MolaGPT 账户工具相互独立。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.86f),
                    modifier = Modifier.padding(14.dp),
                )
            }

            SearchServiceCard(
                provider = s.webSearchProvider,
                maxResults = s.webSearchMaxResults,
                loadKey = viewModel::webSearchApiKey,
                onSave = viewModel::setWebSearch,
            )

            McpServersCard(
                servers = s.byokMcpServers,
                onSave = viewModel::saveMcpServer,
                onDelete = viewModel::deleteMcpServer,
                onSetEnabled = viewModel::setMcpServerEnabled,
                onOpenDetail = onOpenMcpDetail,
                onTest = viewModel::testMcpConnection,
            )

            VisionProxyCard(
                enabled = s.visionProxyEnabled,
                modelKey = s.visionProxyModelKey,
                options = visionOptions,
                onChange = viewModel::setVisionProxy,
                onOpenByokProviders = onOpenByokProviders,
                onNoModels = { showSnack("当前无可用视觉模型，请先在自定义 API 模型中添加并启用视觉模型") },
            )

            ImageGenCard(
                enabled = s.imageGenEnabled,
                modelKey = s.imageGenModelKey,
                size = s.imageGenSize,
                style = s.imageGenStyle,
                aspectRatio = s.imageGenAspectRatio,
                reasoning = s.imageGenReasoning,
                reasoningEffort = s.imageGenReasoningEffort,
                options = imageGenOptions,
                onChange = viewModel::setImageGenConfig,
                onOpenImageWorkbench = onOpenImageWorkbench,
                onOpenByokProviders = onOpenByokProviders,
                onNoModels = { showSnack("当前无可用图像模型，请先在自定义 API 模型中添加并启用图像生成模型") },
            )

            Box(Modifier.padding(bottom = 16.dp))
        }
    }
}

// ── 联网搜索服务（当前服务商下拉 + 脏态才显示保存） ──

@Composable
private fun SearchServiceCard(
    provider: String,
    maxResults: Int,
    loadKey: (String) -> String,
    onSave: (provider: String, apiKey: String, maxResults: Int) -> Unit,
) {
    // 以已保存值 为 key：设置变化后状态同步复位，脏态消失。
    var searchProvider by rememberSaveable(provider) { mutableStateOf(provider) }
    var searchKey by rememberSaveable(searchProvider, provider) { mutableStateOf(loadKey(searchProvider)) }
    var searchMax by rememberSaveable(maxResults) { mutableStateOf(maxResults.toString()) }
    var expanded by remember { mutableStateOf(false) }

    val savedKey = loadKey(provider)
    val isDirty = searchProvider != provider ||
        searchKey != savedKey ||
        searchMax.toIntOrNull() != maxResults
    val selected = WebSearchProvider.fromId(searchProvider)

    SectionTitle("联网搜索服务")
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.30f),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // 当前服务商：点击展开 DropdownMenu（popup，不挤压下方字段）。
            Text("当前服务商", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Box {
                OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(selected.displayName, modifier = Modifier.weight(1f))
                    Text("▾", style = MaterialTheme.typography.labelSmall)
                }
                androidx.compose.material3.DropdownMenu(
                    expanded = expanded, onDismissRequest = { expanded = false },
                ) {
                    WebSearchProvider.entries.forEach { p ->
                        DropdownMenuItem(
                            text = { Text(p.displayName) },
                            onClick = {
                                searchProvider = p.id
                                searchKey = loadKey(p.id)
                                expanded = false
                            },
                        )
                    }
                }
            }
            if (selected.needsKey) {
                OutlinedTextField(value = searchKey, onValueChange = { searchKey = it },
                    label = { Text("${selected.displayName} API Key") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    visualTransformation = PasswordVisualTransformation())
            }
            OutlinedTextField(value = searchMax, onValueChange = { v -> searchMax = v.filter { it.isDigit() }.take(2) },
                label = { Text("结果数 (1-10)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            if (isDirty) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = {
                        onSave(searchProvider, searchKey, searchMax.toIntOrNull()?.coerceIn(1, 10) ?: 6)
                    }) { Text("保存搜索") }
                }
            }
        }
    }
}

// ── MCP 服务器（空状态无多余按钮；添加弹层含测试连接 + 头名） ──

@Composable
private fun McpServersCard(
    servers: List<ByokMcpServer>,
    onSave: (ByokMcpServer) -> Unit,
    onDelete: (String) -> Unit,
    onSetEnabled: (String, Boolean) -> Unit,
    onOpenDetail: (String) -> Unit,
    onTest: suspend (ByokMcpServer) -> String,
) {
    var showSheet by remember { mutableStateOf(false) }

    SectionTitle("MCP 服务器")
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.30f),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (servers.isEmpty()) {
                Text("暂无 MCP 服务器", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 6.dp))
            } else {
                servers.forEach { server ->
                    Row(modifier = Modifier.fillMaxWidth().clickable { onOpenDetail(server.id) }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(server.name, style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(server.endpoint, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        ToggleSwitch(checked = server.enabled) { onSetEnabled(server.id, it) }
                        TextButton(onClick = { onDelete(server.id) }) { Text("删除") }
                        ForwardChevron()
                    }
                }
            }
            TextButton(onClick = { showSheet = true }, modifier = Modifier.align(Alignment.End)) { Text("添加 MCP 服务器") }
        }
    }

    if (showSheet) McpAddSheet(
        onDismiss = { showSheet = false },
        onTest = { server -> onTest(server) },
        onSave = { onSave(it); showSheet = false },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun McpAddSheet(
    onDismiss: () -> Unit,
    onTest: suspend (ByokMcpServer) -> String,
    onSave: (ByokMcpServer) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var endpoint by rememberSaveable { mutableStateOf("") }
    var headerName by rememberSaveable { mutableStateOf("Authorization") }
    var token by rememberSaveable { mutableStateOf("") }
    var enabled by rememberSaveable { mutableStateOf(true) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        // 自带 inset 置 0，底部留白只由内容的 navigationBarsPadding 处理一次（否则全屏展开时底部多一条白条把内容顶出屏幕）。
        contentWindowInsets = { WindowInsets(0) },
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp).verticalScroll(rememberScrollState()).navigationBarsPadding().imePadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("添加 MCP 服务器", style = MaterialTheme.typography.titleLarge)
            Text(
                "接入 Model Context Protocol 服务器，为对话扩展外部工具。仅支持 HTTP (Streamable HTTP) 类型。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("服务器名称") },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                supportingText = { Text("仅用于本地显示") })
            OutlinedTextField(value = endpoint, onValueChange = { endpoint = it }, label = { Text("服务器地址") },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                placeholder = { Text("https://mcp.example/api") })
            OutlinedTextField(value = headerName, onValueChange = { headerName = it }, label = { Text("请求头名") },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                supportingText = { Text("默认 Authorization；令牌以 Bearer <值> 写入此头") })
            OutlinedTextField(value = token, onValueChange = { token = it }, label = { Text("访问令牌") },
                modifier = Modifier.fillMaxWidth(), singleLine = true, visualTransformation = PasswordVisualTransformation())
            ToggleRow("启用此服务器", enabled, onChange = { enabled = it })
            // 测试连接
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        if (endpoint.isBlank()) { testResult = "请填写服务器地址"; return@OutlinedButton }
                        testing = true; testResult = null
                        scope.launch {
                            val result = onTest(ByokMcpServer(
                                id = "test", name = name.ifBlank { "MCP 服务器" },
                                endpoint = endpoint.trim(), headerName = headerName.trim().ifBlank { "Authorization" },
                                token = token.trim().takeIf { it.isNotBlank() }, enabled = enabled))
                            testResult = result; testing = false
                        }
                    },
                    enabled = !testing,
                    modifier = Modifier.weight(1f),
                ) { Text(if (testing) "测试中…" else "测试连接") }
                TextButton(
                    onClick = {
                        if (endpoint.isNotBlank()) onSave(ByokMcpServer(
                            id = UUID.randomUUID().toString().replace("-", "").take(10),
                            name = name.ifBlank { "MCP 服务器" }, endpoint = endpoint.trim(),
                            headerName = headerName.trim().ifBlank { "Authorization" },
                            token = token.trim().takeIf { it.isNotBlank() }, enabled = enabled))
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("添加") }
            }
            testResult?.let {
                Text(it, style = MaterialTheme.typography.bodySmall,
                    color = if (it.startsWith("连接成功")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            }
        }
    }
}

// ── 外挂视觉配置（无可用模型时提示 + 模型管理入口） ──

@Composable
private fun VisionProxyCard(
    enabled: Boolean,
    modelKey: String?,
    options: List<SettingsViewModel.ModelOption>,
    onChange: (enabled: Boolean, modelKey: String?) -> Unit,
    onOpenByokProviders: () -> Unit,
    onNoModels: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val hasModels = options.isNotEmpty()
    val selectedLabel = options.firstOrNull { it.key == modelKey }?.label ?: "选择模型"
    SectionTitle("视觉理解")
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.30f),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
            ToggleRow("启用视觉理解", enabled, subtitle = "当前模型不支持视觉时，代理到此模型",
                onChange = { onChange(it, modelKey) })
            if (enabled) {
                Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = {
                        if (hasModels) expanded = true else onNoModels("当前无可用视觉模型，请先在自定义 API 模型中添加并启用视觉模型")
                    }, modifier = Modifier.weight(1f)) {
                        Text(selectedLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    TextButton(onClick = onOpenByokProviders) { Text("模型管理") }
                }
                androidx.compose.material3.DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    options.forEach { opt ->
                        DropdownMenuItem(text = { Text(opt.label) },
                            onClick = { onChange(true, opt.key); expanded = false })
                    }
                }
            }
        }
    }
}

// ── 图像生成配置（同视觉：无模型时提示 + 模型管理入口；尺寸/宽高比/推理走 image_config） ──

@Composable
private fun ImageGenCard(
    enabled: Boolean,
    modelKey: String?,
    size: String,
    style: String?,
    aspectRatio: String,
    reasoning: Boolean,
    reasoningEffort: String,
    options: List<SettingsViewModel.ModelOption>,
    onChange: (enabled: Boolean, modelKey: String?, size: String, style: String?, aspectRatio: String, reasoning: Boolean, reasoningEffort: String) -> Unit,
    onOpenImageWorkbench: () -> Unit,
    onOpenByokProviders: () -> Unit,
    onNoModels: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var editedSize by rememberSaveable(size) { mutableStateOf(size) }
    var editedStyle by rememberSaveable(style) { mutableStateOf(style ?: "") }
    var editedAspect by rememberSaveable(aspectRatio) { mutableStateOf(aspectRatio) }
    var editedReasoning by rememberSaveable(reasoning) { mutableStateOf(reasoning) }
    var editedEffort by rememberSaveable(reasoningEffort) { mutableStateOf(reasoningEffort) }
    val hasModels = options.isNotEmpty()
    val selectedLabel = options.firstOrNull { it.key == modelKey }?.label ?: "选择模型"
    // 所选模型是否支持出图推理（GPT-5 Image / Gemini 3 Image 系列）。
    val selectedModelId = modelKey?.substringAfterLast("::").orEmpty()
    val supportsReasoning = selectedModelId.isNotBlank() &&
        com.molagpt.app.core.network.looksLikeByokImageReasoningModel(selectedModelId)

    fun save() = onChange(
        enabled, modelKey,
        editedSize, editedStyle.takeIf { it.isNotBlank() },
        editedAspect, editedReasoning && supportsReasoning, editedEffort,
    )

    SectionTitle("图像生成")
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.30f),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ToggleRow("启用图像生成", enabled, subtitle = "模型可调用此图像服务",
                onChange = { onChange(it, modelKey, editedSize, editedStyle.takeIf { s -> s.isNotBlank() }, editedAspect, editedReasoning && supportsReasoning, editedEffort) })
            if (enabled) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = {
                        if (hasModels) expanded = true else onNoModels()
                    }, modifier = Modifier.weight(1f)) {
                        Text(selectedLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    TextButton(onClick = onOpenByokProviders) { Text("模型管理") }
                }
                androidx.compose.material3.DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    options.forEach { opt ->
                        DropdownMenuItem(text = { Text(opt.label) },
                            onClick = {
                                onChange(true, opt.key, editedSize, editedStyle.takeIf { s -> s.isNotBlank() }, editedAspect, editedReasoning && supportsReasoning, editedEffort)
                                expanded = false
                            })
                    }
                }
                // 尺寸档位（image_config.image_size）。
                Text("尺寸", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    com.molagpt.app.core.model.ImageGenerationConfig.IMAGE_SIZES.forEach { sz ->
                        ImagePill(sz, selected = editedSize == sz, onClick = { editedSize = sz })
                    }
                }
                // 宽高比（image_config.aspect_ratio）。
                Text("宽高比", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    com.molagpt.app.core.model.ImageGenerationConfig.ASPECT_RATIOS.forEach { ar ->
                        ImagePill(ar, selected = editedAspect == ar, onClick = { editedAspect = ar })
                    }
                }
                // 推理强度：仅所选模型支持时显示。
                if (supportsReasoning) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ToggleSwitch(checked = editedReasoning) { editedReasoning = it }
                        Text("推理强度", style = MaterialTheme.typography.bodyMedium)
                    }
                    if (editedReasoning) {
                        Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            com.molagpt.app.core.model.ImageGenerationConfig.REASONING_EFFORTS.forEach { e ->
                                ImagePill(e, selected = editedEffort == e, onClick = { editedEffort = e })
                            }
                        }
                    }
                }
                OutlinedTextField(value = editedStyle, onValueChange = { editedStyle = it },
                    label = { Text("风格（可选）") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = { save() }, modifier = Modifier.weight(1f)) { Text("保存") }
                    TextButton(onClick = onOpenImageWorkbench, modifier = Modifier.weight(1f)) { Text("图像工作台") }
                }
            }
        }
    }
}

/** 图像参数小药丸（选中态用主色填充）。 */
@Composable
private fun ImagePill(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}

/** 迷你开关（白/暗白圆点 + 主色 track，同 ToggleRow 配色）。 */
@Composable
private fun ToggleSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    androidx.compose.material3.Switch(
        checked = checked, onCheckedChange = onChange,
        colors = androidx.compose.material3.SwitchDefaults.colors(
            checkedThumbColor = Color.White,
            uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
            checkedTrackColor = MaterialTheme.colorScheme.primary,
            uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
            uncheckedBorderColor = MaterialTheme.colorScheme.outline,
        ),
    )
}