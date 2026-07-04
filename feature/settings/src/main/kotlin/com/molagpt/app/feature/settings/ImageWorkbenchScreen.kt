package com.molagpt.app.feature.settings

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.ImageSearch
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.molagpt.app.core.model.ByokImageFormat
import com.molagpt.app.core.model.ByokProvider
import com.molagpt.app.core.model.ByokPurpose
import com.molagpt.app.core.model.ProviderModel
import com.molagpt.app.core.network.ByokImageAttachment
import com.molagpt.app.core.network.ByokImageHit
import com.molagpt.app.core.network.ByokImageWorkbenchConfig
import com.molagpt.app.core.network.looksLikeByokImageReasoningModel
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val SizeOptions = listOf(
    "1024x1024" to "1024x1024 · 1:1",
    "1024x1536" to "1024x1536 · 2:3",
    "1536x1024" to "1536x1024 · 3:2",
    "2048x2048" to "2048x2048 · 1:1",
    "2048x1152" to "2048x1152 · 16:9",
    "1152x2048" to "1152x2048 · 9:16",
    "3840x2160" to "3840x2160 · 16:9",
    "2160x3840" to "2160x3840 · 9:16",
)

private val QualityOptions = listOf("auto", "high", "medium", "low")
private val FormatOptions = listOf("png", "jpeg", "webp")
private val BackgroundOptions = listOf("auto", "transparent", "opaque")
private val ModerationOptions = listOf("auto", "low")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageWorkbenchScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val providers by viewModel.byokProviderList.collectAsStateWithLifecycle()
    val imageProviders = providers.filter { it.enabled && it.purpose == ByokPurpose.IMAGE }
    var selectedProviderId by rememberSaveable { mutableStateOf("") }
    val selectedProvider = imageProviders.firstOrNull { it.id == selectedProviderId } ?: imageProviders.firstOrNull()
    val imageModels = selectedProvider?.models.orEmpty().filter { it.supportsImageGeneration }
    var selectedModelId by rememberSaveable { mutableStateOf("") }
    val selectedModel = imageModels.firstOrNull { it.id == selectedModelId } ?: imageModels.firstOrNull()

    var activeTab by rememberSaveable { mutableIntStateOf(0) }
    var prompt by rememberSaveable { mutableStateOf("") }
    var size by rememberSaveable { mutableStateOf("1024x1024") }
    var customSize by rememberSaveable { mutableStateOf("") }
    var useCustomSize by rememberSaveable { mutableStateOf(false) }
    var quality by rememberSaveable { mutableStateOf("auto") }
    var outputFormat by rememberSaveable { mutableStateOf("png") }
    var imageCountText by rememberSaveable { mutableStateOf("1") }
    var background by rememberSaveable { mutableStateOf("auto") }
    var moderation by rememberSaveable { mutableStateOf("auto") }
    var compression by rememberSaveable { mutableFloatStateOf(80f) }
    var clearOnSubmit by rememberSaveable { mutableStateOf(false) }
    var persistPrompt by rememberSaveable { mutableStateOf(true) }
    var timeoutText by rememberSaveable { mutableStateOf("600") }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showHistory by rememberSaveable { mutableStateOf(false) }
    var showAbout by rememberSaveable { mutableStateOf(false) }
    var showSizeWarning by rememberSaveable { mutableStateOf(false) }
    var showDebug by rememberSaveable { mutableStateOf(false) }
    var decodeInput by rememberSaveable { mutableStateOf("") }
    var decodeResult by remember { mutableStateOf<Base64Preview?>(null) }
    var decodeError by rememberSaveable { mutableStateOf<String?>(null) }
    var status by rememberSaveable { mutableStateOf("") }
    var statusError by rememberSaveable { mutableStateOf(false) }
    var sending by rememberSaveable { mutableStateOf(false) }
    var lastRaw by rememberSaveable { mutableStateOf("（尚未请求）") }
    var editingId by rememberSaveable { mutableStateOf<Long?>(null) }
    val attachments = remember { mutableStateListOf<ImageAttachmentDraft>() }
    val messages = remember { mutableStateListOf<WorkbenchMessage>() }
    val history = remember { mutableStateListOf<HistoryEntry>() }
    val prefs = remember { context.getSharedPreferences("matcha_image_workbench", Context.MODE_PRIVATE) }

    val currentSize by remember(useCustomSize, customSize, size) {
        derivedStateOf {
            normalizeSize(if (useCustomSize) customSize else size).ifBlank { "1024x1024" }
        }
    }
    val imageCount = imageCountText.toIntOrNull()?.coerceIn(1, 8) ?: 1
    val isChatImage = selectedProvider?.imageFormat == ByokImageFormat.OPENAI_CHAT_IMAGE
    val canReason = selectedModel?.id?.let { looksLikeByokImageReasoningModel(it) } == true
    var reasoning by rememberSaveable { mutableStateOf(false) }
    var reasoningEffort by rememberSaveable { mutableStateOf("medium") }
    val batchModeEnabled = attachments.size >= 2
    var batchMode by rememberSaveable { mutableStateOf(false) }
    if (!batchModeEnabled && batchMode) batchMode = false

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) {
            scope.launch {
                val added = withContext(Dispatchers.IO) { uris.mapNotNull { loadAttachment(context, it) } }
                attachments += added
                status = if (added.isEmpty()) "未读取到图片" else "已添加 ${added.size} 张参考图"
                statusError = added.isEmpty()
            }
        }
    }

    LaunchedEffect(Unit) {
        prompt = prefs.getString("last_prompt", "").orEmpty()
        clearOnSubmit = prefs.getBoolean("clear_on_submit", false)
        persistPrompt = prefs.getBoolean("persist_prompt", true)
        timeoutText = prefs.getInt("timeout", 600).toString()
        runCatching { history.addAll(loadHistory(context)) }
    }

    LaunchedEffect(imageProviders, selectedProviderId, selectedModelId) {
        if (selectedProvider == null) {
            selectedProviderId = ""
            selectedModelId = ""
            return@LaunchedEffect
        }
        if (selectedProviderId != selectedProvider.id) selectedProviderId = selectedProvider.id
        if (imageModels.none { it.id == selectedModelId }) selectedModelId = imageModels.firstOrNull()?.id.orEmpty()
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.systemBars.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
        topBar = {
            TopAppBar(
                title = { Text("抹茶画图") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { showHistory = true }) {
                        Icon(Icons.Filled.History, contentDescription = "历史记录")
                    }
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Filled.Settings, contentDescription = "设置")
                    }
                    IconButton(onClick = { showAbout = true }) {
                        Icon(Icons.Filled.Info, contentDescription = "关于")
                    }
                },
            )
        },
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .navigationBarsPadding(),
        ) {
            TabRow(selectedTabIndex = activeTab) {
                Tab(
                    selected = activeTab == 0,
                    onClick = { activeTab = 0 },
                    text = { Text("生成") },
                    icon = { Icon(Icons.Filled.AutoFixHigh, contentDescription = null) },
                )
                Tab(
                    selected = activeTab == 1,
                    onClick = { activeTab = 1 },
                    text = { Text("Base64") },
                    icon = { Icon(Icons.Filled.ImageSearch, contentDescription = null) },
                )
            }
            when (activeTab) {
                0 -> GeneratePanel(
                    providers = imageProviders,
                    selectedProvider = selectedProvider,
                    selectedProviderId = selectedProviderId,
                    onProviderSelected = { provider ->
                        selectedProviderId = provider.id
                        selectedModelId = provider.models.firstOrNull { it.supportsImageGeneration }?.id.orEmpty()
                    },
                    models = imageModels,
                    selectedModel = selectedModel,
                    selectedModelId = selectedModelId,
                    onModelSelected = { model ->
                        selectedModelId = model.id
                        if (maxEdge(currentSize) >= 1600 && model.id == "gpt-image-2") showSizeWarning = true
                    },
                    messages = messages,
                    attachments = attachments,
                    prompt = prompt,
                    onPromptChange = { prompt = it },
                    size = size,
                    onSizeChange = {
                        size = it
                        if (maxEdge(it) >= 1600 && selectedModelId == "gpt-image-2") showSizeWarning = true
                    },
                    customSize = customSize,
                    onCustomSizeChange = { customSize = it },
                    useCustomSize = useCustomSize,
                    onUseCustomSizeChange = { useCustomSize = it },
                    quality = quality,
                    onQualityChange = { quality = it },
                    outputFormat = outputFormat,
                    onOutputFormatChange = { outputFormat = it },
                    imageCountText = imageCountText,
                    onImageCountChange = { imageCountText = it.filter { ch -> ch.isDigit() }.take(2) },
                    background = background,
                    onBackgroundChange = { background = it },
                    moderation = moderation,
                    onModerationChange = { moderation = it },
                    compression = compression,
                    onCompressionChange = { compression = it },
                    batchMode = batchMode,
                    onBatchModeChange = { batchMode = it },
                    canReason = canReason && isChatImage,
                    reasoning = reasoning,
                    onReasoningChange = { reasoning = it },
                    reasoningEffort = reasoningEffort,
                    onReasoningEffortChange = { reasoningEffort = it },
                    sending = sending,
                    status = status,
                    statusError = statusError,
                    showDebug = showDebug,
                    lastRaw = lastRaw,
                    onToggleDebug = { showDebug = !showDebug },
                    onPickImages = { picker.launch("image/*") },
                    onClearAttachments = { attachments.clear() },
                    onEditAttachment = { editingId = it.id },
                    onRemoveAttachment = { attachments.remove(it) },
                    onClearMessages = { messages.clear() },
                    onSend = {
                        val provider = selectedProvider
                        val model = selectedModel
                        if (provider == null || model == null || prompt.isBlank()) {
                            status = "请填写 Prompt 并选择图像服务/模型"
                            statusError = true
                            return@GeneratePanel
                        }
                        scope.launch {
                            sending = true
                            status = "请求发送中..."
                            statusError = false
                            val snapshotPrompt = prompt.trim()
                            val userAttachments = attachments.toList()
                            messages += WorkbenchMessage.User(
                                prompt = snapshotPrompt,
                                attachments = userAttachments.map { it.previewBytes },
                                masked = userAttachments.map { it.hasMask },
                            )
                            val loadingId = System.currentTimeMillis()
                            messages += WorkbenchMessage.Loading(loadingId)
                            val config = ByokImageWorkbenchConfig(
                                size = currentSize,
                                n = imageCount,
                                quality = quality,
                                outputFormat = outputFormat,
                                background = background,
                                moderation = moderation,
                                outputCompression = compression.roundToInt(),
                                timeoutSeconds = timeoutText.toIntOrNull()?.coerceIn(10, 3600) ?: 600,
                                batchMode = batchMode,
                                reasoning = reasoning && canReason,
                                reasoningEffort = reasoningEffort,
                            )
                            val networkAttachments = userAttachments.map { it.toNetworkAttachment() }
                            val result = runCatching {
                                viewModel.runImageWorkbenchRequest(
                                    providerId = provider.id,
                                    modelId = model.id,
                                    prompt = snapshotPrompt,
                                    config = config,
                                    attachments = networkAttachments,
                                )
                            }
                            messages.removeAll { it is WorkbenchMessage.Loading && it.id == loadingId }
                            result.onSuccess { workbench ->
                                lastRaw = workbench.raw.ifBlank { "（空响应）" }
                                showDebug = workbench.hits.isEmpty()
                                status = workbench.status.ifBlank { "生成完成" } + if (workbench.usedFallback) "（已切换次选路径）" else ""
                                statusError = workbench.hits.isEmpty()
                                messages += WorkbenchMessage.Bot(
                                    hits = workbench.hits,
                                    raw = workbench.raw,
                                    note = status,
                                    requestCount = workbench.requestCount,
                                )
                                if (workbench.hits.isNotEmpty()) {
                                    val entry = HistoryEntry(
                                        id = System.currentTimeMillis(),
                                        prompt = snapshotPrompt,
                                        model = model.id,
                                        size = currentSize,
                                        hits = workbench.hits.map { it.url },
                                        timestamp = System.currentTimeMillis(),
                                    )
                                    history.add(0, entry)
                                    while (history.size > 20) history.removeAt(history.lastIndex)
                                    saveHistory(context, history)
                                }
                                if (persistPrompt) prefs.edit().putString("last_prompt", snapshotPrompt).apply()
                                if (clearOnSubmit) {
                                    prompt = ""
                                    attachments.clear()
                                }
                            }.onFailure { error ->
                                lastRaw = error.stackTraceToString().take(50_000)
                                showDebug = true
                                status = error.message ?: "请求失败"
                                statusError = true
                                messages += WorkbenchMessage.Error(status)
                            }
                            sending = false
                        }
                    },
                )
                1 -> Base64Panel(
                    input = decodeInput,
                    onInputChange = { decodeInput = it },
                    result = decodeResult,
                    error = decodeError,
                    onDecode = {
                        val parsed = runCatching { decodeBase64Preview(decodeInput) }
                        parsed.onSuccess {
                            decodeResult = it
                            decodeError = null
                        }.onFailure {
                            decodeResult = null
                            decodeError = it.message ?: "解析失败"
                        }
                    },
                    onClear = {
                        decodeInput = ""
                        decodeResult = null
                        decodeError = null
                    },
                    onSave = { preview ->
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) {
                                saveBytesToGallery(context, preview.bytes, preview.mimeType)
                            }
                            Toast.makeText(context, if (ok) "已保存到相册" else "保存失败", Toast.LENGTH_SHORT).show()
                        }
                    },
                )
            }
        }
    }

    val editing = attachments.firstOrNull { it.id == editingId }
    if (editing != null) {
        MaskEditorSheet(
            attachment = editing,
            onDismiss = { editingId = null },
            onClear = {
                editing.clearMask()
            },
        )
    }

    if (showSettings) {
        WorkbenchSettingsSheet(
            clearOnSubmit = clearOnSubmit,
            onClearOnSubmitChange = {
                clearOnSubmit = it
                prefs.edit().putBoolean("clear_on_submit", it).apply()
            },
            persistPrompt = persistPrompt,
            onPersistPromptChange = {
                persistPrompt = it
                prefs.edit().putBoolean("persist_prompt", it).apply()
            },
            timeoutText = timeoutText,
            onTimeoutTextChange = {
                timeoutText = it.filter { ch -> ch.isDigit() }.take(4)
                prefs.edit().putInt("timeout", timeoutText.toIntOrNull() ?: 600).apply()
            },
            onDismiss = { showSettings = false },
            onClearAll = {
                messages.clear()
                attachments.clear()
                history.clear()
                saveHistory(context, history)
                prefs.edit().clear().apply()
                prompt = ""
                status = "本地数据已清除"
                statusError = false
                showSettings = false
            },
        )
    }

    if (showHistory) {
        HistorySheet(
            history = history,
            onDismiss = { showHistory = false },
            onUse = { entry ->
                prompt = entry.prompt
                size = entry.size
                activeTab = 0
                showHistory = false
            },
            onDelete = { entry ->
                history.remove(entry)
                saveHistory(context, history)
            },
            onClear = {
                history.clear()
                saveHistory(context, history)
            },
        )
    }

    if (showAbout) {
        val uriHandler = LocalUriHandler.current
        AlertDialog(
            onDismissRequest = { showAbout = false },
            confirmButton = { TextButton(onClick = { showAbout = false }) { Text("关闭") } },
            icon = {
                Image(
                    painter = painterResource(R.drawable.matcha_image_icon),
                    contentDescription = "抹茶画图",
                    modifier = Modifier
                        .size(72.dp)
                        .clip(RoundedCornerShape(18.dp)),
                )
            },
            title = { Text("关于") },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("感谢MolaGPT和其他开源项目的支持")
                    OutlinedButton(
                        onClick = { uriHandler.openUri("https://github.com/DisaWdcba/SimpleAIPainting") },
                    ) {
                        Text("DisaWdcba/SimpleAIPainting")
                    }
                }
            },
        )
    }

    if (showSizeWarning) {
        AlertDialog(
            onDismissRequest = { showSizeWarning = false },
            icon = { Icon(Icons.Filled.Info, contentDescription = null) },
            title = { Text("尺寸需要 gpt-image-2-pro") },
            text = { Text("当前选择 gpt-image-2，但 $currentSize 通常仅 gpt-image-2-pro 稳定支持。继续生成可能被后端压回小尺寸。") },
            dismissButton = {
                TextButton(onClick = { showSizeWarning = false }) { Text("保持当前模型") }
            },
            confirmButton = {
                TextButton(onClick = {
                    val pro = imageModels.firstOrNull { it.id == "gpt-image-2-pro" }
                    if (pro != null) selectedModelId = pro.id
                    showSizeWarning = false
                }) { Text("切到 pro") }
            },
        )
    }
}

@Composable
private fun GeneratePanel(
    providers: List<ByokProvider>,
    selectedProvider: ByokProvider?,
    selectedProviderId: String,
    onProviderSelected: (ByokProvider) -> Unit,
    models: List<ProviderModel>,
    selectedModel: ProviderModel?,
    selectedModelId: String,
    onModelSelected: (ProviderModel) -> Unit,
    messages: List<WorkbenchMessage>,
    attachments: List<ImageAttachmentDraft>,
    prompt: String,
    onPromptChange: (String) -> Unit,
    size: String,
    onSizeChange: (String) -> Unit,
    customSize: String,
    onCustomSizeChange: (String) -> Unit,
    useCustomSize: Boolean,
    onUseCustomSizeChange: (Boolean) -> Unit,
    quality: String,
    onQualityChange: (String) -> Unit,
    outputFormat: String,
    onOutputFormatChange: (String) -> Unit,
    imageCountText: String,
    onImageCountChange: (String) -> Unit,
    background: String,
    onBackgroundChange: (String) -> Unit,
    moderation: String,
    onModerationChange: (String) -> Unit,
    compression: Float,
    onCompressionChange: (Float) -> Unit,
    batchMode: Boolean,
    onBatchModeChange: (Boolean) -> Unit,
    canReason: Boolean,
    reasoning: Boolean,
    onReasoningChange: (Boolean) -> Unit,
    reasoningEffort: String,
    onReasoningEffortChange: (String) -> Unit,
    sending: Boolean,
    status: String,
    statusError: Boolean,
    showDebug: Boolean,
    lastRaw: String,
    onToggleDebug: () -> Unit,
    onPickImages: () -> Unit,
    onClearAttachments: () -> Unit,
    onEditAttachment: (ImageAttachmentDraft) -> Unit,
    onRemoveAttachment: (ImageAttachmentDraft) -> Unit,
    onClearMessages: () -> Unit,
    onSend: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        WorkbenchHeader(
            providers = providers,
            selectedProvider = selectedProvider,
            selectedProviderId = selectedProviderId,
            onProviderSelected = onProviderSelected,
            models = models,
            selectedModel = selectedModel,
            selectedModelId = selectedModelId,
            onModelSelected = onModelSelected,
            onClearMessages = onClearMessages,
        )
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (messages.isEmpty()) {
                item { EmptyWorkbenchState() }
            } else {
                items(messages, key = { it.key }) { message ->
                    MessageBubble(message = message)
                }
            }
            if (showDebug) {
                item {
                    DebugCard(raw = lastRaw, onToggle = onToggleDebug)
                }
            }
        }
        ComposerCard(
            prompt = prompt,
            onPromptChange = onPromptChange,
            size = size,
            onSizeChange = onSizeChange,
            customSize = customSize,
            onCustomSizeChange = onCustomSizeChange,
            useCustomSize = useCustomSize,
            onUseCustomSizeChange = onUseCustomSizeChange,
            quality = quality,
            onQualityChange = onQualityChange,
            outputFormat = outputFormat,
            onOutputFormatChange = onOutputFormatChange,
            imageCountText = imageCountText,
            onImageCountChange = onImageCountChange,
            background = background,
            onBackgroundChange = onBackgroundChange,
            moderation = moderation,
            onModerationChange = onModerationChange,
            compression = compression,
            onCompressionChange = onCompressionChange,
            attachments = attachments,
            batchMode = batchMode,
            onBatchModeChange = onBatchModeChange,
            canReason = canReason,
            reasoning = reasoning,
            onReasoningChange = onReasoningChange,
            reasoningEffort = reasoningEffort,
            onReasoningEffortChange = onReasoningEffortChange,
            sending = sending,
            status = status,
            statusError = statusError,
            onPickImages = onPickImages,
            onClearAttachments = onClearAttachments,
            onEditAttachment = onEditAttachment,
            onRemoveAttachment = onRemoveAttachment,
            onSend = onSend,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorkbenchHeader(
    providers: List<ByokProvider>,
    selectedProvider: ByokProvider?,
    selectedProviderId: String,
    onProviderSelected: (ByokProvider) -> Unit,
    models: List<ProviderModel>,
    selectedModel: ProviderModel?,
    selectedModelId: String,
    onModelSelected: (ProviderModel) -> Unit,
    onClearMessages: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        shadowElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    modifier = Modifier
                        .size(9.dp)
                        .clip(CircleShape)
                        .background(ComposeColor(0xFF22C55E)),
                )
                Text("工作台", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = onClearMessages) {
                    Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text("清空记录", modifier = Modifier.padding(start = 4.dp))
                }
            }
            if (providers.isEmpty()) {
                Text(
                    "暂无已启用的图像 BYOK 服务，请先在 BYOK 服务中添加图像用途 provider。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    providers.forEach { provider ->
                        InputChip(
                            selected = selectedProviderId == provider.id,
                            onClick = { onProviderSelected(provider) },
                            label = { Text(provider.name, maxLines = 1) },
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    models.forEach { model ->
                        InputChip(
                            selected = selectedModelId == model.id,
                            onClick = { onModelSelected(model) },
                            label = { Text(model.displayName, maxLines = 1) },
                        )
                    }
                }
                if (selectedProvider != null && selectedModel != null) {
                    Text(
                        "${selectedProvider.name} / ${selectedModel.id} · ${if (selectedProvider.imageFormat == ByokImageFormat.OPENAI_CHAT_IMAGE) "对话补全出图" else "OpenAI 图像接口"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyWorkbenchState() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
    ) {
        Column(
            modifier = Modifier.padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.Filled.AutoFixHigh, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text("描述画面，添加参考图，开始生成。", style = MaterialTheme.typography.bodyMedium)
            Text(
                "记录、图片链接和原始响应会显示在这里。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ComposerCard(
    prompt: String,
    onPromptChange: (String) -> Unit,
    size: String,
    onSizeChange: (String) -> Unit,
    customSize: String,
    onCustomSizeChange: (String) -> Unit,
    useCustomSize: Boolean,
    onUseCustomSizeChange: (Boolean) -> Unit,
    quality: String,
    onQualityChange: (String) -> Unit,
    outputFormat: String,
    onOutputFormatChange: (String) -> Unit,
    imageCountText: String,
    onImageCountChange: (String) -> Unit,
    background: String,
    onBackgroundChange: (String) -> Unit,
    moderation: String,
    onModerationChange: (String) -> Unit,
    compression: Float,
    onCompressionChange: (Float) -> Unit,
    attachments: List<ImageAttachmentDraft>,
    batchMode: Boolean,
    onBatchModeChange: (Boolean) -> Unit,
    canReason: Boolean,
    reasoning: Boolean,
    onReasoningChange: (Boolean) -> Unit,
    reasoningEffort: String,
    onReasoningEffortChange: (String) -> Unit,
    sending: Boolean,
    status: String,
    statusError: Boolean,
    onPickImages: () -> Unit,
    onClearAttachments: () -> Unit,
    onEditAttachment: (ImageAttachmentDraft) -> Unit,
    onRemoveAttachment: (ImageAttachmentDraft) -> Unit,
    onSend: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 4.dp,
        shadowElevation = 8.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (attachments.isNotEmpty()) {
                AttachmentStrip(
                    attachments = attachments,
                    batchMode = batchMode,
                    onBatchModeChange = onBatchModeChange,
                    onClear = onClearAttachments,
                    onEdit = onEditAttachment,
                    onRemove = onRemoveAttachment,
                )
            }
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                color = MaterialTheme.colorScheme.surface,
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    OutlinedTextField(
                        value = prompt,
                        onValueChange = onPromptChange,
                        placeholder = { Text("描述你要生成的画面内容...") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        maxLines = 5,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                        verticalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        SelectMenu(
                            label = "尺寸",
                            value = if (useCustomSize) "自定义" else size,
                            options = SizeOptions,
                            onSelect = {
                                onUseCustomSizeChange(false)
                                onSizeChange(it)
                            },
                            extraOption = "自定义" to {
                                onUseCustomSizeChange(true)
                                if (customSize.isBlank()) onCustomSizeChange(size)
                            },
                        )
                        SelectMenu(
                            label = "质量",
                            value = quality,
                            options = QualityOptions.map { it to "质量 $it" },
                            onSelect = onQualityChange,
                        )
                        SelectMenu(
                            label = "格式",
                            value = outputFormat,
                            options = FormatOptions.map { it to it.uppercase() },
                            onSelect = onOutputFormatChange,
                        )
                        SelectMenu(
                            label = "背景",
                            value = background,
                            options = BackgroundOptions.map { it to it },
                            onSelect = onBackgroundChange,
                        )
                        SelectMenu(
                            label = "审核",
                            value = moderation,
                            options = ModerationOptions.map { it to it },
                            onSelect = onModerationChange,
                        )
                        OutlinedTextField(
                            value = imageCountText,
                            onValueChange = onImageCountChange,
                            label = { Text("N") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.size(width = 76.dp, height = 58.dp),
                        )
                    }
                    if (useCustomSize) {
                        OutlinedTextField(
                            value = customSize,
                            onValueChange = onCustomSizeChange,
                            label = { Text("自定义尺寸") },
                            placeholder = { Text("W×H，16 倍数，≤3840") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = {
                                onCustomSizeChange(normalizeSize(customSize))
                            }),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (outputFormat == "jpeg" || outputFormat == "webp") {
                        Column {
                            Text("压缩 ${compression.roundToInt()}", style = MaterialTheme.typography.labelSmall)
                            Slider(value = compression, onValueChange = onCompressionChange, valueRange = 0f..100f)
                        }
                    }
                    if (canReason) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Switch(checked = reasoning, onCheckedChange = onReasoningChange)
                            Text("推理强度", modifier = Modifier.weight(1f))
                            if (reasoning) {
                                SelectMenu(
                                    label = "effort",
                                    value = reasoningEffort,
                                    options = listOf("low", "medium", "high", "xhigh", "max").map { it to it },
                                    onSelect = onReasoningEffortChange,
                                )
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = status,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (statusError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = onPickImages) {
                            Icon(Icons.Filled.AddPhotoAlternate, contentDescription = "添加参考图")
                        }
                        FilledTonalIconButton(
                            onClick = onSend,
                            enabled = !sending && prompt.isNotBlank(),
                        ) {
                            if (sending) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Filled.Send, contentDescription = "发送")
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectMenu(
    label: String,
    value: String,
    options: List<Pair<String, String>>,
    onSelect: (String) -> Unit,
    extraOption: Pair<String, () -> Unit>? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    val text = options.firstOrNull { it.first == value }?.second ?: value
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = text,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor()
                .size(width = 160.dp, height = 58.dp),
            textStyle = MaterialTheme.typography.labelMedium,
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (key, title) ->
                DropdownMenuItem(text = { Text(title) }, onClick = {
                    onSelect(key)
                    expanded = false
                })
            }
            if (extraOption != null) {
                HorizontalDivider()
                DropdownMenuItem(text = { Text(extraOption.first) }, onClick = {
                    extraOption.second()
                    expanded = false
                })
            }
        }
    }
}

@Composable
private fun AttachmentStrip(
    attachments: List<ImageAttachmentDraft>,
    batchMode: Boolean,
    onBatchModeChange: (Boolean) -> Unit,
    onClear: () -> Unit,
    onEdit: (ImageAttachmentDraft) -> Unit,
    onRemove: (ImageAttachmentDraft) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
    ) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("参考图 ${attachments.size}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = onClear) { Text("清空") }
            }
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                attachments.forEachIndexed { index, attachment ->
                    Box(
                        modifier = Modifier
                            .size(86.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .clickable { onEdit(attachment) },
                    ) {
                        Image(
                            bitmap = attachment.previewBitmap.asImageBitmap(),
                            contentDescription = "参考图 ${index + 1}",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                        if (attachment.hasMask) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(5.dp)
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.error),
                            )
                        }
                        Row(
                            modifier = Modifier.align(Alignment.BottomEnd).padding(3.dp),
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            IconButton(onClick = { onEdit(attachment) }, modifier = Modifier.size(30.dp)) {
                                Icon(Icons.Filled.Edit, contentDescription = "编辑", tint = ComposeColor.White)
                            }
                            IconButton(onClick = { onRemove(attachment) }, modifier = Modifier.size(30.dp)) {
                                Icon(Icons.Filled.Close, contentDescription = "移除", tint = ComposeColor.White)
                            }
                        }
                    }
                }
            }
            if (attachments.size >= 2) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = ComposeColor(0xFFFFFBEB),
                    border = BorderStroke(1.dp, ComposeColor(0xFFFDE68A)),
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Checkbox(checked = batchMode, onCheckedChange = onBatchModeChange)
                        Text(
                            "批处理模式：${attachments.size} 张图 → ${attachments.size} 个独立请求，并发上限由 App 串行保护。",
                            style = MaterialTheme.typography.bodySmall,
                            color = ComposeColor(0xFF92400E),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(message: WorkbenchMessage) {
    when (message) {
        is WorkbenchMessage.User -> UserBubble(message)
        is WorkbenchMessage.Bot -> BotBubble(message)
        is WorkbenchMessage.Error -> ErrorBubble(message.text)
        is WorkbenchMessage.Loading -> LoadingBubble()
    }
}

@Composable
private fun UserBubble(message: WorkbenchMessage.User) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.86f),
            shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 6.dp),
            color = MaterialTheme.colorScheme.primary,
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (message.attachments.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                        message.attachments.forEachIndexed { index, bytes ->
                            val bitmap = remember(bytes) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
                            if (bitmap != null) {
                                Box(Modifier.size(58.dp).clip(RoundedCornerShape(8.dp))) {
                                    Image(bitmap = bitmap.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                    if (message.masked.getOrNull(index) == true) {
                                        Box(Modifier.align(Alignment.TopEnd).padding(4.dp).size(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.error))
                                    }
                                }
                            }
                        }
                    }
                }
                Text(message.prompt, color = MaterialTheme.colorScheme.onPrimary)
            }
        }
    }
}

@Composable
private fun BotBubble(message: WorkbenchMessage.Bot) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.96f),
            shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 6.dp, bottomEnd = 18.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (message.hits.isNotEmpty()) {
                    val columns = if (message.hits.size == 1) 1 else 2
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(columns),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = if (message.hits.size == 1) 430.dp else 520.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        userScrollEnabled = false,
                    ) {
                        items(message.hits) { hit ->
                            GeneratedImageCard(hit = hit)
                        }
                    }
                } else {
                    Text("响应中未找到图片，请查看调试面板。", color = MaterialTheme.colorScheme.error)
                }
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                ) {
                    Text(
                        "${message.note} · 请求 ${message.requestCount} 次",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(9.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun GeneratedImageCard(hit: ByokImageHit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
    ) {
        Column {
            Box(Modifier.fillMaxWidth().aspectRatio(1f).background(checkerBrush())) {
                AsyncImage(
                    model = decodeImageModel(hit.url),
                    contentDescription = hit.label.ifBlank { "生成图片" },
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
                if (hit.label.isNotBlank()) {
                    Surface(
                        modifier = Modifier.align(Alignment.TopStart).padding(6.dp),
                        shape = RoundedCornerShape(7.dp),
                        color = ComposeColor.Black.copy(alpha = 0.58f),
                    ) {
                        Text(hit.label, color = ComposeColor.White, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                    }
                }
            }
            Row(modifier = Modifier.padding(6.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                IconButton(onClick = {
                    clipboard.setText(AnnotatedString(hit.url))
                    Toast.makeText(context, "已复制链接", Toast.LENGTH_SHORT).show()
                }) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = "复制")
                }
                IconButton(onClick = {
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) { saveImageUrlToGallery(context, hit.url) }
                        Toast.makeText(context, if (ok) "已保存到相册" else "保存失败", Toast.LENGTH_SHORT).show()
                    }
                }) {
                    Icon(Icons.Filled.Download, contentDescription = "保存")
                }
            }
        }
    }
}

@Composable
private fun ErrorBubble(text: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.errorContainer,
    ) {
        Text(text, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.padding(12.dp))
    }
}

@Composable
private fun LoadingBubble() {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
            Row(modifier = Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Text("正在生成图像...")
            }
        }
    }
}

@Composable
private fun DebugCard(raw: String, onToggle: () -> Unit) {
    ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("开发调试：最后一次请求原始响应", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = onToggle) { Text("收起") }
            }
            Text(
                raw,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 260.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
                    .padding(10.dp),
            )
        }
    }
}

@Composable
private fun Base64Panel(
    input: String,
    onInputChange: (String) -> Unit,
    result: Base64Preview?,
    error: String?,
    onDecode: () -> Unit,
    onClear: () -> Unit,
    onSave: (Base64Preview) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            OutlinedTextField(
                value = input,
                onValueChange = onInputChange,
                label = { Text("粘贴 Base64 编码或 Data URL") },
                placeholder = { Text("data:image/png;base64,iVBORw0KGgo...") },
                minLines = 8,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onDecode) {
                    Icon(Icons.Filled.Visibility, contentDescription = null)
                    Text("解析并预览", modifier = Modifier.padding(start = 6.dp))
                }
                OutlinedButton(onClick = onClear) { Text("清空内容") }
            }
            if (error != null) {
                Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
            }
        }
        if (result != null) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Image(
                            bitmap = result.bitmap.asImageBitmap(),
                            contentDescription = "解析结果",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 180.dp, max = 500.dp)
                                .background(checkerBrush(), RoundedCornerShape(12.dp))
                                .clip(RoundedCornerShape(12.dp)),
                        )
                        Text("文件类型：${result.mimeType}")
                        Text("预估体积：${result.bytes.size / 1024} KB")
                        Text("Base64 字符总数：${result.base64Length}")
                        OutlinedButton(onClick = { onSave(result) }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Filled.Save, contentDescription = null)
                            Text("保存图片", modifier = Modifier.padding(start = 6.dp))
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MaskEditorSheet(
    attachment: ImageAttachmentDraft,
    onDismiss: () -> Unit,
    onClear: () -> Unit,
) {
    var tool by remember { mutableStateOf(MaskTool.Brush) }
    var brushSize by remember { mutableFloatStateOf(32f) }
    val bitmap = attachment.editBitmap
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val brushPreviewColor = if (tool == MaskTool.Brush) MaterialTheme.colorScheme.error else ComposeColor.White
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        contentWindowInsets = { WindowInsets(0) },
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .padding(bottom = 26.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("编辑参考图", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = onClear) {
                    Icon(Icons.Filled.RestartAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text("清除涂抹")
                }
                Button(onClick = onDismiss) { Text("完成") }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(420.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                    .onSizeChanged { viewport = it },
            ) {
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(tool, brushSize, viewport, attachment.maskVersion) {
                            detectDragGestures(
                                onDragStart = { pos -> attachment.drawMaskAt(pos, viewport, tool, brushSize) },
                                onDrag = { change, _ -> attachment.drawMaskAt(change.position, viewport, tool, brushSize) },
                            )
                        },
                ) {
                    val dst = fittedRect(bitmap.width, bitmap.height, size)
                    drawContext.canvas.nativeCanvas.drawBitmap(bitmap, null, dst, Paint(Paint.ANTI_ALIAS_FLAG))
                    drawContext.canvas.nativeCanvas.drawBitmap(attachment.overlayBitmap, null, dst, Paint(Paint.ANTI_ALIAS_FLAG))
                    drawCircle(
                        color = brushPreviewColor,
                        radius = brushSize / 2f,
                        center = Offset(dst.left + dst.width() - brushSize, dst.top + brushSize),
                        style = Stroke(2.dp.toPx()),
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                InputChip(
                    selected = tool == MaskTool.Brush,
                    onClick = { tool = MaskTool.Brush },
                    label = { Text("笔刷") },
                    leadingIcon = { Icon(Icons.Filled.Brush, contentDescription = null, modifier = Modifier.size(16.dp)) },
                )
                InputChip(
                    selected = tool == MaskTool.Eraser,
                    onClick = { tool = MaskTool.Eraser },
                    label = { Text("擦除") },
                    leadingIcon = { Icon(Icons.Filled.VisibilityOff, contentDescription = null, modifier = Modifier.size(16.dp)) },
                )
                Text("粗细 ${brushSize.roundToInt()}", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            }
            Slider(value = brushSize, onValueChange = { brushSize = it }, valueRange = 8f..100f)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorkbenchSettingsSheet(
    clearOnSubmit: Boolean,
    onClearOnSubmitChange: (Boolean) -> Unit,
    persistPrompt: Boolean,
    onPersistPromptChange: (Boolean) -> Unit,
    timeoutText: String,
    onTimeoutTextChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onClearAll: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        contentWindowInsets = { WindowInsets(0) },
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("设置", style = MaterialTheme.typography.titleLarge)
            ToggleRow("提交任务后清空输入框", "提交成功后清空 Prompt 和参考图。", clearOnSubmit, onClearOnSubmitChange)
            ToggleRow("重启后加载上次的 Prompt", "关闭后下次进入工作台 Prompt 为空。", persistPrompt, onPersistPromptChange)
            OutlinedTextField(
                value = timeoutText,
                onValueChange = onTimeoutTextChange,
                label = { Text("请求超时（秒）") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(onClick = onClearAll, modifier = Modifier.fillMaxWidth(), border = BorderStroke(1.dp, MaterialTheme.colorScheme.error)) {
                Icon(Icons.Filled.Delete, contentDescription = null)
                Text("清除所有本地数据", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(start = 6.dp))
            }
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("完成") }
        }
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistorySheet(
    history: List<HistoryEntry>,
    onDismiss: () -> Unit,
    onUse: (HistoryEntry) -> Unit,
    onDelete: (HistoryEntry) -> Unit,
    onClear: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        contentWindowInsets = { WindowInsets(0) },
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("历史记录 (${history.size}/20)", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = onClear, enabled = history.isNotEmpty()) { Text("清空全部") }
            }
            if (history.isEmpty()) {
                Text("暂无历史记录", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 24.dp))
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 520.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(history, key = { it.id }) { entry ->
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f),
                        ) {
                            Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(entry.prompt, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        "${entry.model} · ${entry.size} · ${entry.hits.size} 张 · ${formatTime(entry.timestamp)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                TextButton(onClick = { onUse(entry) }) { Text("使用") }
                                IconButton(onClick = { onDelete(entry) }) {
                                    Icon(Icons.Filled.Delete, contentDescription = "删除")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun checkerBrush(): Brush = Brush.linearGradient(
    listOf(
        ComposeColor(0xFFF8FAFC),
        ComposeColor(0xFFE2E8F0),
        ComposeColor(0xFFF8FAFC),
    ),
)

private fun decodeImageModel(url: String): Any {
    if (!url.startsWith("data:", ignoreCase = true)) return url
    val comma = url.indexOf(',')
    if (comma < 0 || !url.substring(0, comma).contains("base64", ignoreCase = true)) return url
    return runCatching {
        Base64.decode(url.substring(comma + 1), Base64.DEFAULT)
    }.getOrDefault(url)
}

private sealed class WorkbenchMessage(val key: String) {
    data class User(val prompt: String, val attachments: List<ByteArray>, val masked: List<Boolean>) : WorkbenchMessage("u-${System.nanoTime()}")
    data class Bot(val hits: List<ByokImageHit>, val raw: String, val note: String, val requestCount: Int) : WorkbenchMessage("b-${System.nanoTime()}")
    data class Error(val text: String) : WorkbenchMessage("e-${System.nanoTime()}")
    data class Loading(val id: Long) : WorkbenchMessage("l-$id")
}

@Stable
private class ImageAttachmentDraft(
    val id: Long,
    val fileName: String,
    val mimeType: String,
    val originalBytes: ByteArray,
    val previewBytes: ByteArray,
    val editBitmap: Bitmap,
) {
    val previewBitmap: Bitmap = BitmapFactory.decodeByteArray(previewBytes, 0, previewBytes.size)
    val maskBitmap: Bitmap = Bitmap.createBitmap(editBitmap.width, editBitmap.height, Bitmap.Config.ARGB_8888).apply {
        eraseColor(Color.TRANSPARENT)
    }
    var overlayBitmap: Bitmap by mutableStateOf(Bitmap.createBitmap(editBitmap.width, editBitmap.height, Bitmap.Config.ARGB_8888))
        private set
    var maskVersion by mutableIntStateOf(0)
        private set
    val hasMask: Boolean get() = maskVersion > 0 && bitmapHasAlpha(maskBitmap)

    fun drawMaskAt(position: Offset, viewport: IntSize, tool: MaskTool, brushSize: Float) {
        if (viewport.width <= 0 || viewport.height <= 0) return
        val dst = fittedRect(editBitmap.width, editBitmap.height, Size(viewport.width.toFloat(), viewport.height.toFloat()))
        if (!dst.contains(position.x, position.y)) return
        val x = ((position.x - dst.left) / dst.width() * editBitmap.width).coerceIn(0f, editBitmap.width.toFloat())
        val y = ((position.y - dst.top) / dst.height() * editBitmap.height).coerceIn(0f, editBitmap.height.toFloat())
        val scale = editBitmap.width / dst.width()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (tool == MaskTool.Brush) Color.WHITE else Color.TRANSPARENT
            style = Paint.Style.FILL
            if (tool == MaskTool.Eraser) xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        }
        AndroidCanvas(maskBitmap).drawCircle(x, y, brushSize * scale / 2f, paint)
        rebuildOverlay()
    }

    fun clearMask() {
        maskBitmap.eraseColor(Color.TRANSPARENT)
        rebuildOverlay(forceVersion = true)
    }

    fun toNetworkAttachment(): ByokImageAttachment = ByokImageAttachment(
        fileName = fileName,
        mimeType = mimeType.ifBlank { "image/png" },
        bytes = originalBytes,
        maskPngBytes = if (hasMask) buildAlphaMaskPng() else null,
        maskedOverlayBytes = if (hasMask) buildOverlayPng() else null,
    )

    private fun buildAlphaMaskPng(): ByteArray {
        val alpha = Bitmap.createBitmap(maskBitmap.width, maskBitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = AndroidCanvas(alpha)
        val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        canvas.drawRect(0f, 0f, alpha.width.toFloat(), alpha.height.toFloat(), white)
        val clear = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) }
        canvas.drawBitmap(maskBitmap, 0f, 0f, clear)
        return alpha.toPngBytes()
    }

    private fun buildOverlayPng(): ByteArray = overlayBitmap.toPngBytes()

    private fun rebuildOverlay(forceVersion: Boolean = false) {
        val next = Bitmap.createBitmap(editBitmap.width, editBitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = AndroidCanvas(next)
        canvas.drawBitmap(editBitmap, 0f, 0f, null)
        val red = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(120, 244, 63, 94)
            xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        }
        val colored = Bitmap.createBitmap(maskBitmap.width, maskBitmap.height, Bitmap.Config.ARGB_8888)
        val coloredCanvas = AndroidCanvas(colored)
        coloredCanvas.drawBitmap(maskBitmap, 0f, 0f, null)
        coloredCanvas.drawRect(0f, 0f, colored.width.toFloat(), colored.height.toFloat(), red)
        canvas.drawBitmap(colored, 0f, 0f, null)
        overlayBitmap = next
        if (forceVersion || bitmapHasAlpha(maskBitmap)) maskVersion++ else maskVersion = 0
    }
}

private enum class MaskTool { Brush, Eraser }

private data class Base64Preview(
    val bytes: ByteArray,
    val bitmap: Bitmap,
    val mimeType: String,
    val base64Length: Int,
)

private data class HistoryEntry(
    val id: Long,
    val prompt: String,
    val model: String,
    val size: String,
    val hits: List<String>,
    val timestamp: Long,
)

private fun loadAttachment(context: Context, uri: Uri): ImageAttachmentDraft? {
    val resolver = context.contentResolver
    val mime = resolver.getType(uri) ?: "image/png"
    val original = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
    val bitmap = BitmapFactory.decodeByteArray(original, 0, original.size) ?: return null
    val editBitmap = bitmap.scaleDown(maxEdge = 2048)
    val preview = editBitmap.scaleDown(maxEdge = 420).toJpegBytes(quality = 82)
    return ImageAttachmentDraft(
        id = System.nanoTime(),
        fileName = uri.lastPathSegment?.substringAfterLast('/').orEmpty().ifBlank { "image.png" },
        mimeType = mime,
        originalBytes = editBitmap.toPngBytes(),
        previewBytes = preview,
        editBitmap = editBitmap,
    )
}

private fun Bitmap.scaleDown(maxEdge: Int): Bitmap {
    val edge = maxOf(width, height)
    if (edge <= maxEdge) return this
    val scale = maxEdge.toFloat() / edge
    val matrix = Matrix().apply { postScale(scale, scale) }
    return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
}

private fun Bitmap.toPngBytes(): ByteArray = ByteArrayOutputStream().use { out ->
    compress(Bitmap.CompressFormat.PNG, 100, out)
    out.toByteArray()
}

private fun Bitmap.toJpegBytes(quality: Int): ByteArray = ByteArrayOutputStream().use { out ->
    compress(Bitmap.CompressFormat.JPEG, quality, out)
    out.toByteArray()
}

private fun bitmapHasAlpha(bitmap: Bitmap): Boolean {
    val pixels = IntArray(bitmap.width * bitmap.height)
    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    return pixels.any { Color.alpha(it) > 0 }
}

private fun fittedRect(bitmapWidth: Int, bitmapHeight: Int, viewport: Size): android.graphics.RectF {
    val scale = minOf(viewport.width / bitmapWidth, viewport.height / bitmapHeight)
    val width = bitmapWidth * scale
    val height = bitmapHeight * scale
    val left = (viewport.width - width) / 2f
    val top = (viewport.height - height) / 2f
    return android.graphics.RectF(left, top, left + width, top + height)
}

private fun android.graphics.RectF.contains(x: Float, y: Float): Boolean =
    x >= left && x <= right && y >= top && y <= bottom

private fun normalizeSize(raw: String): String {
    val match = Regex("""(\d+)\s*[xX×*]\s*(\d+)""").find(raw.trim()) ?: return raw.trim()
    val w = ((match.groupValues[1].toIntOrNull() ?: 1024) / 16f).roundToInt().coerceIn(1, 240) * 16
    val h = ((match.groupValues[2].toIntOrNull() ?: 1024) / 16f).roundToInt().coerceIn(1, 240) * 16
    return "${w}x$h"
}

private fun maxEdge(size: String): Int {
    val match = Regex("""(\d+)\s*[xX×*]\s*(\d+)""").find(size) ?: return 0
    return maxOf(match.groupValues[1].toIntOrNull() ?: 0, match.groupValues[2].toIntOrNull() ?: 0)
}

private fun decodeBase64Preview(input: String): Base64Preview {
    var text = input.trim()
    if (text.isBlank()) error("输入内容不能为空")
    val mime = if (text.startsWith("data:", ignoreCase = true)) {
        val comma = text.indexOf(',')
        val header = text.substringBefore(',')
        text = text.substring(comma + 1)
        header.substringAfter("data:", "image/png").substringBefore(";")
    } else {
        "image/png"
    }
    text = text.replace(Regex("""\s+"""), "")
    val bytes = Base64.decode(text, Base64.DEFAULT)
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("Base64 不是可识别的图片")
    val detected = detectMime(bytes) ?: mime
    return Base64Preview(bytes = bytes, bitmap = bitmap, mimeType = detected, base64Length = text.length)
}

private fun detectMime(bytes: ByteArray): String? = when {
    bytes.size >= 4 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() -> "image/png"
    bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "image/jpeg"
    bytes.size >= 3 && bytes[0] == 0x47.toByte() && bytes[1] == 0x49.toByte() && bytes[2] == 0x46.toByte() -> "image/gif"
    bytes.size >= 12 && bytes[0] == 0x52.toByte() && bytes[8] == 0x57.toByte() -> "image/webp"
    else -> null
}

private fun saveHistory(context: Context, entries: List<HistoryEntry>) {
    val raw = entries.joinToString("\n") { entry ->
        listOf(
            entry.id,
            entry.timestamp,
            entry.model.encodeField(),
            entry.size.encodeField(),
            entry.prompt.encodeField(),
            entry.hits.joinToString("|") { it.encodeField() },
        ).joinToString("\t")
    }
    context.getSharedPreferences("matcha_image_workbench", Context.MODE_PRIVATE)
        .edit()
        .putString("history", raw)
        .apply()
}

private fun loadHistory(context: Context): List<HistoryEntry> {
    val raw = context.getSharedPreferences("matcha_image_workbench", Context.MODE_PRIVATE).getString("history", "").orEmpty()
    if (raw.isBlank()) return emptyList()
    return raw.lineSequence().mapNotNull { line ->
        val parts = line.split('\t')
        if (parts.size < 6) return@mapNotNull null
        HistoryEntry(
            id = parts[0].toLongOrNull() ?: return@mapNotNull null,
            timestamp = parts[1].toLongOrNull() ?: 0L,
            model = parts[2].decodeField(),
            size = parts[3].decodeField(),
            prompt = parts[4].decodeField(),
            hits = parts[5].split('|').filter { it.isNotBlank() }.map { it.decodeField() },
        )
    }.toList()
}

private fun String.encodeField(): String = Base64.encodeToString(toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
private fun String.decodeField(): String = runCatching { String(Base64.decode(this, Base64.DEFAULT), Charsets.UTF_8) }.getOrDefault(this)

private fun formatTime(ts: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(ts))

private fun saveImageUrlToGallery(context: Context, url: String): Boolean {
    return if (url.startsWith("data:", ignoreCase = true)) {
        val comma = url.indexOf(',')
        if (comma < 0) false else {
            val mime = url.substringBefore(';').removePrefix("data:")
            val bytes = Base64.decode(url.substring(comma + 1), Base64.DEFAULT)
            saveBytesToGallery(context, bytes, mime)
        }
    } else {
        runCatching {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 60_000
                requestMethod = "GET"
            }
            connection.inputStream.use { input ->
                val bytes = input.readBytes()
                val mime = connection.contentType?.substringBefore(';') ?: detectMime(bytes) ?: "image/png"
                saveBytesToGallery(context, bytes, mime)
            }
        }.getOrDefault(false)
    }
}

private fun saveBytesToGallery(context: Context, bytes: ByteArray, mimeType: String): Boolean {
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return false
    val name = "Matcha_${System.currentTimeMillis()}." + when (mimeType) {
        "image/jpeg" -> "jpg"
        "image/webp" -> "webp"
        else -> "png"
    }
    val resolver: ContentResolver = context.contentResolver
    val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    } else {
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    }
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, name)
        put(MediaStore.Images.Media.MIME_TYPE, mimeType)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/MolaGPT")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
    }
    val uri = resolver.insert(collection, values) ?: return false
    return runCatching {
        resolver.openOutputStream(uri)?.use { out ->
            val format = when (mimeType) {
                "image/jpeg" -> Bitmap.CompressFormat.JPEG
                "image/webp" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSLESS else Bitmap.CompressFormat.WEBP
                else -> Bitmap.CompressFormat.PNG
            }
            bitmap.compress(format, 95, out)
        } ?: return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
        true
    }.getOrElse {
        runCatching { resolver.delete(uri, null, null) }
        false
    }
}
