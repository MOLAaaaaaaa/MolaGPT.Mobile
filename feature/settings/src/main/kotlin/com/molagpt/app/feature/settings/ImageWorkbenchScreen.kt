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
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.ImageSearch
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
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
import androidx.compose.material3.DropdownMenu
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.molagpt.app.core.model.ByokImageFormat
import com.molagpt.app.core.model.ByokProvider
import com.molagpt.app.core.model.ByokPurpose
import com.molagpt.app.core.model.ProviderModel
import com.molagpt.app.core.network.ByokImageAttachment
import com.molagpt.app.core.network.ByokImageHit
import com.molagpt.app.core.network.ByokImageWorkbenchConfig
import com.molagpt.app.core.network.ByokImageWorkbenchResult
import com.molagpt.app.core.network.looksLikeByokImageReasoningModel
import com.molagpt.app.core.render.decodeImageModel
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ImageWorkbenchScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    onManageModels: (providerId: String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val providers by viewModel.byokProviderList.collectAsStateWithLifecycle()
    val imageProviders = providers.filter { it.enabled && it.purpose == ByokPurpose.IMAGE }
    val prefs = remember { context.getSharedPreferences(WORKBENCH_PREFS, Context.MODE_PRIVATE) }

    val sessions = remember { mutableStateListOf<WorkbenchSessionUi>() }
    var currentSessionId by rememberSaveable { mutableStateOf("") }
    var sessionsLoaded by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (sessionsLoaded) return@LaunchedEffect
        val snapshots = withContext(Dispatchers.IO) { loadSessions(context) }
        if (snapshots.isNotEmpty()) {
            snapshots.sortedByDescending { it.updatedAt }.forEach { sessions.add(WorkbenchSessionUi.from(it)) }
        } else {
            sessions.add(
                WorkbenchSessionUi.new(
                    providerId = imageProviders.firstOrNull()?.id.orEmpty(),
                    modelId = imageProviders.firstOrNull()?.models?.firstOrNull { it.supportsImageGeneration }?.id.orEmpty(),
                ),
            )
        }
        if (currentSessionId.isBlank() || sessions.none { it.id == currentSessionId }) {
            currentSessionId = sessions.first().id
        }
        sessionsLoaded = true
    }
    val currentSession = sessions.firstOrNull { it.id == currentSessionId }

    val selectedProvider = imageProviders.firstOrNull { it.id == currentSession?.providerId } ?: imageProviders.firstOrNull()
    val imageModels = selectedProvider?.models.orEmpty().filter { it.supportsImageGeneration }
    val selectedModel = imageModels.firstOrNull { it.id == currentSession?.modelId } ?: imageModels.firstOrNull()
    val editable = selectedModel?.supportsImageEdit == true

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
    var autoContinue by rememberSaveable { mutableStateOf(true) }
    var timeoutText by rememberSaveable { mutableStateOf("600") }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showSessions by rememberSaveable { mutableStateOf(false) }
    var showAbout by rememberSaveable { mutableStateOf(false) }
    var showModelPicker by rememberSaveable { mutableStateOf(false) }
    var showTopMenu by remember { mutableStateOf(false) }
    var showSizeWarning by rememberSaveable { mutableStateOf(false) }
    var showDebug by rememberSaveable { mutableStateOf(false) }
    var decodeInput by remember { mutableStateOf("") }
    var decodeResult by remember { mutableStateOf<Base64Preview?>(null) }
    var decodeError by rememberSaveable { mutableStateOf<String?>(null) }
    var status by rememberSaveable { mutableStateOf("") }
    var statusError by rememberSaveable { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var generationJob by remember { mutableStateOf<Job?>(null) }
    var lastRaw by remember { mutableStateOf("（尚未请求）") }
    var editingId by rememberSaveable { mutableStateOf<Long?>(null) }
    var ctx by remember { mutableStateOf<RecordHit?>(null) }
    var galleryIndex by rememberSaveable { mutableStateOf(-1) }
    val attachments = remember { mutableStateListOf<ImageAttachmentDraft>() }

    val currentSize by remember(useCustomSize, customSize, size) {
        derivedStateOf { normalizeSize(if (useCustomSize) customSize else size).ifBlank { "1024x1024" } }
    }
    val imageCount = imageCountText.toIntOrNull()?.coerceIn(1, 8) ?: 1
    val isChatImageFormat = selectedProvider?.imageFormat == ByokImageFormat.OPENAI_CHAT_IMAGE
    val canReason = selectedModel?.id?.let { looksLikeByokImageReasoningModel(it) } == true
    var reasoning by rememberSaveable { mutableStateOf(false) }
    var reasoningEffort by rememberSaveable { mutableStateOf("medium") }
    val batchModeEnabled = editable && attachments.size >= 2
    var batchMode by rememberSaveable { mutableStateOf(false) }
    if (!batchModeEnabled && batchMode) batchMode = false

    fun persistNow() {
        val snapshot = sessions.map { it.toSnapshot() }
        scope.launch(Dispatchers.IO) { saveSessions(context, snapshot) }
    }

    fun resetComposer() {
        prompt = ""
        attachments.clear()
        ctx = null
        showDebug = false
        status = ""
        statusError = false
        galleryIndex = -1
    }

    fun newSession() {
        val s = WorkbenchSessionUi.new(
            providerId = selectedProvider?.id.orEmpty(),
            modelId = selectedModel?.id.orEmpty(),
        )
        sessions.add(0, s)
        currentSessionId = s.id
        resetComposer()
        persistNow()
    }

    fun switchSession(id: String) {
        if (id == currentSessionId) return
        currentSessionId = id
        resetComposer()
        val target = sessions.firstOrNull { it.id == id } ?: return
        val targetModel = imageProviders.firstOrNull { it.id == target.providerId }
            ?.models?.firstOrNull { it.id == target.modelId }
        val lastHit = target.records.lastOrNull { !it.loading }?.hits?.firstOrNull()
        if (lastHit != null && autoContinue && targetModel?.supportsImageEdit == true) {
            ctx = lastHit
        }
    }

    fun deleteSession(id: String) {
        val target = sessions.firstOrNull { it.id == id } ?: return
        val snapshotToWipe = target.toSnapshot()
        scope.launch(Dispatchers.IO) { deleteSessionFiles(context, snapshotToWipe) }
        sessions.remove(target)
        if (sessions.isEmpty()) {
            sessions.add(
                WorkbenchSessionUi.new(
                    providerId = imageProviders.firstOrNull()?.id.orEmpty(),
                    modelId = imageProviders.firstOrNull()?.models?.firstOrNull { it.supportsImageGeneration }?.id.orEmpty(),
                ),
            )
        }
        if (id == currentSessionId) {
            currentSessionId = sessions.first().id
            resetComposer()
        }
        persistNow()
    }

    fun clearAllSessions() {
        val snapshotsToWipe = sessions.map { it.toSnapshot() }
        scope.launch(Dispatchers.IO) {
            snapshotsToWipe.forEach { deleteSessionFiles(context, it) }
            saveSessions(context, emptyList())
        }
        sessions.clear()
        val s = WorkbenchSessionUi.new(
            providerId = imageProviders.firstOrNull()?.id.orEmpty(),
            modelId = imageProviders.firstOrNull()?.models?.firstOrNull { it.supportsImageGeneration }?.id.orEmpty(),
        )
        sessions.add(s)
        currentSessionId = s.id
        resetComposer()
    }

    LaunchedEffect(Unit) {
        clearOnSubmit = prefs.getBoolean("clear_on_submit", false)
        autoContinue = prefs.getBoolean("auto_continue", true)
        timeoutText = prefs.getInt("timeout", 600).toString()
    }

    LaunchedEffect(imageProviders, currentSessionId, sessionsLoaded) {
        val session = currentSession ?: return@LaunchedEffect
        if (!sessionsLoaded) return@LaunchedEffect
        val provider = imageProviders.firstOrNull { it.id == session.providerId } ?: imageProviders.firstOrNull()
        if (provider == null) {
            session.providerId = ""
            session.modelId = ""
            return@LaunchedEffect
        }
        if (session.providerId != provider.id) session.providerId = provider.id
        val models = provider.models.filter { it.supportsImageGeneration }
        if (models.none { it.id == session.modelId }) session.modelId = models.firstOrNull()?.id.orEmpty()
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) {
            scope.launch {
                val added = withContext(Dispatchers.IO) { uris.mapNotNull { loadAttachment(context, it) } }
                attachments += added
                if (added.isNotEmpty()) ctx = null
                status = if (added.isEmpty()) "未读取到图片" else "已添加 ${added.size} 张参考图"
                statusError = added.isEmpty()
            }
        }
    }

    suspend fun runGeneration(
        session: WorkbenchSessionUi,
        recordPrompt: String,
        networkAttachments: List<ByokImageAttachment>,
        pendingRefs: List<RecordRef>,
        requestCount: Int,
    ) {
        val provider = selectedProvider
        val model = selectedModel
        if (provider == null || model == null) return
        sending = true
        status = "任务执行中"
        statusError = false
        val placeholder = WorkbenchRecord(
            id = System.nanoTime(),
            prompt = recordPrompt,
            refs = pendingRefs,
            hits = emptyList(),
            model = model.id,
            size = currentSize,
            requestCount = requestCount,
            loading = true,
        )
        session.records.add(placeholder)
        galleryIndex = -1
        val config = ByokImageWorkbenchConfig(
            size = currentSize,
            n = requestCount,
            quality = quality,
            outputFormat = outputFormat,
            background = background,
            moderation = moderation,
            outputCompression = compression.roundToInt(),
            timeoutSeconds = timeoutText.toIntOrNull()?.coerceIn(10, 3600) ?: 600,
            batchMode = batchMode,
            reasoning = reasoning && canReason && isChatImageFormat,
            reasoningEffort = reasoningEffort,
        )
        val result: Result<ByokImageWorkbenchResult> = try {
            Result.success(
                viewModel.runImageWorkbenchRequest(
                    providerId = provider.id,
                    modelId = model.id,
                    prompt = recordPrompt,
                    config = config,
                    attachments = networkAttachments,
                ),
            )
        } catch (ce: CancellationException) {
            session.records.remove(placeholder)
            withContext(Dispatchers.IO + NonCancellable) {
                pendingRefs.forEach { deleteFileIfLocal(context, it.path) }
            }
            status = "已停止本次生成"
            statusError = false
            sending = false
            generationJob = null
            throw ce
        } catch (e: Exception) {
            Result.failure(e)
        }
        val idx = session.records.indexOfFirst { it.id == placeholder.id }
        result.onSuccess { workbench ->
            lastRaw = workbench.raw.ifBlank { "（空响应）" }
            showDebug = workbench.hits.isEmpty()
            status = workbench.status.ifBlank { "生成完成" } + if (workbench.usedFallback) "（已切换次选路径）" else ""
            statusError = workbench.hits.isEmpty()
            val persistedHits = withContext(Dispatchers.IO) { workbench.hits.map { persistHit(context, it) } }
            val finalRecord = placeholder.copy(
                hits = persistedHits,
                requestCount = workbench.requestCount,
                loading = false,
            )
            if (idx >= 0) session.records[idx] = finalRecord
            session.updatedAt = System.currentTimeMillis()
            persistNow()
            if (editable && persistedHits.isNotEmpty() && autoContinue) {
                ctx = persistedHits.first()
            }
            attachments.clear()
            if (clearOnSubmit) prompt = ""
            galleryIndex = -1
        }.onFailure { error ->
            if (idx >= 0) session.records.removeAt(idx)
            withContext(Dispatchers.IO) { pendingRefs.forEach { deleteFileIfLocal(context, it.path) } }
            lastRaw = error.stackTraceToString().take(20_000)
            showDebug = true
            status = error.message ?: "请求失败"
            statusError = true
        }
        sending = false
        generationJob = null
    }

    fun send() {
        val session = currentSession ?: return
        val provider = selectedProvider
        val model = selectedModel
        if (provider == null || model == null || prompt.isBlank()) {
            status = "请填写 Prompt 并选择图像服务/模型"
            statusError = true
            return
        }
        val snapshotPrompt = prompt.trim()
        val userAttachments = if (editable) attachments.toList() else emptyList()
        val ctxSnapshot = ctx
        val usingCtx = editable && userAttachments.isEmpty() && ctxSnapshot != null
        if (session.records.none { !it.loading }) {
            session.title = snapshotPrompt.take(18).let { if (snapshotPrompt.length > 18) "$it…" else it }
        }
        generationJob = scope.launch {
            val pendingRefs = withContext(Dispatchers.IO) {
                when {
                    usingCtx -> listOf(RecordRef(path = ctxSnapshot.path, hasMask = false, isContext = true))
                    userAttachments.isNotEmpty() -> userAttachments.map {
                        RecordRef(path = persistBytes(context, it.previewBytes, "ref"), hasMask = it.hasMask, isContext = false)
                    }
                    else -> emptyList()
                }
            }
            val networkAttachments = when {
                usingCtx -> listOfNotNull(loadPathAsAttachment(context, ctxSnapshot.path))
                else -> userAttachments.map { it.toNetworkAttachment() }
            }
            runGeneration(session, snapshotPrompt, networkAttachments, pendingRefs, imageCount)
        }
    }

    fun regenerate(record: WorkbenchRecord) {
        val session = currentSession ?: return
        if (sending) return
        generationJob = scope.launch {
            val networkAttachments = withContext(Dispatchers.IO) {
                record.refs.mapNotNull { loadPathAsAttachment(context, it.path) }
            }
            runGeneration(session, record.prompt, networkAttachments, record.refs, record.requestCount)
        }
    }

    fun cancelGeneration() {
        generationJob?.cancel()
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.systemBars.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
        topBar = {
            TopAppBar(
                title = {
                    if (activeTab == 1) {
                        Text("Base64 工具")
                    } else {
                        Box {
                            Column(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable(enabled = imageProviders.isNotEmpty()) { showModelPicker = true }
                                    .padding(end = 4.dp),
                            ) {
                                Text(
                                    currentSession?.title ?: "新画图对话",
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = when {
                                            selectedProvider == null -> "尚未配置图像服务"
                                            selectedModel == null -> "${selectedProvider.name} · 请选择模型"
                                            else -> "${selectedProvider.name} · ${selectedModel.displayName}"
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (selectedProvider == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false).basicMarquee(),
                                    )
                                    if (selectedModel != null) {
                                        CapabilityTag(editable = editable, compact = true)
                                    }
                                    Icon(
                                        Icons.Filled.ArrowDropDown,
                                        contentDescription = "切换模型",
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                            ModelPickerMenu(
                                expanded = showModelPicker,
                                onDismiss = { showModelPicker = false },
                                providers = imageProviders,
                                selectedProviderId = currentSession?.providerId.orEmpty(),
                                selectedModelId = currentSession?.modelId.orEmpty(),
                                onModelSelected = { provider, model ->
                                    showModelPicker = false
                                    currentSession?.let { s ->
                                        if (s.providerId != provider.id) {
                                            s.providerId = provider.id
                                            attachments.clear()
                                            ctx = null
                                        }
                                        s.modelId = model.id
                                    }
                                    if (!model.supportsImageEdit) {
                                        attachments.clear()
                                        ctx = null
                                    }
                                    if (maxEdge(currentSize) >= 1600 && model.id == "gpt-image-2") showSizeWarning = true
                                },
                                onManageModels = {
                                    showModelPicker = false
                                    onManageModels(currentSession?.providerId?.takeIf { it.isNotBlank() })
                                },
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { if (activeTab == 1) activeTab = 0 else onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (activeTab == 0) {
                        IconButton(onClick = { showSessions = true }) {
                            Icon(Icons.Filled.History, contentDescription = "历史对话")
                        }
                        Box {
                            IconButton(onClick = { showTopMenu = true }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = "更多")
                            }
                            DropdownMenu(expanded = showTopMenu, onDismissRequest = { showTopMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text("新画图对话") },
                                    leadingIcon = { Icon(Icons.Filled.AutoFixHigh, contentDescription = null) },
                                    onClick = {
                                        showTopMenu = false
                                        newSession()
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Base64 工具") },
                                    leadingIcon = { Icon(Icons.Filled.ImageSearch, contentDescription = null) },
                                    onClick = {
                                        showTopMenu = false
                                        activeTab = 1
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("画图设置") },
                                    leadingIcon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                                    onClick = {
                                        showTopMenu = false
                                        showSettings = true
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("关于抹茶画图") },
                                    leadingIcon = { Icon(Icons.Filled.Info, contentDescription = null) },
                                    onClick = {
                                        showTopMenu = false
                                        showAbout = true
                                    },
                                )
                            }
                        }
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
            when {
                activeTab == 1 -> Base64Panel(
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
                imageProviders.isEmpty() -> NoProviderState(onManageModels = { onManageModels(null) })
                else -> GeneratePanel(
                    editable = editable,
                    records = currentSession?.records.orEmpty(),
                    galleryIndex = galleryIndex,
                    onGalleryIndexChange = { galleryIndex = it },
                    attachments = attachments,
                    ctx = ctx,
                    prompt = prompt,
                    onPromptChange = { prompt = it },
                    size = size,
                    onSizeChange = {
                        size = it
                        if (maxEdge(it) >= 1600 && currentSession?.modelId == "gpt-image-2") showSizeWarning = true
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
                    canReason = canReason && isChatImageFormat,
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
                    onClearContext = { ctx = null },
                    onPickAsContext = { record, index ->
                        if (editable) {
                            ctx = record.hits.getOrNull(index)
                            attachments.clear()
                        }
                    },
                    onRegenerate = ::regenerate,
                    onCancel = ::cancelGeneration,
                    onSend = ::send,
                )
            }
        }
    }

    val editing = attachments.firstOrNull { it.id == editingId }
    if (editing != null) {
        MaskEditorSheet(
            attachment = editing,
            onDismiss = { editingId = null },
            onClear = { editing.clearMask() },
        )
    }

    if (showSettings) {
        WorkbenchSettingsSheet(
            clearOnSubmit = clearOnSubmit,
            onClearOnSubmitChange = {
                clearOnSubmit = it
                prefs.edit().putBoolean("clear_on_submit", it).apply()
            },
            autoContinue = autoContinue,
            onAutoContinueChange = {
                autoContinue = it
                prefs.edit().putBoolean("auto_continue", it).apply()
            },
            timeoutText = timeoutText,
            onTimeoutTextChange = {
                timeoutText = it.filter { ch -> ch.isDigit() }.take(4)
                prefs.edit().putInt("timeout", timeoutText.toIntOrNull() ?: 600).apply()
            },
            onDismiss = { showSettings = false },
            onClearAll = {
                clearAllSessions()
                showSettings = false
            },
        )
    }

    if (showSessions) {
        SessionSheet(
            sessions = sessions,
            currentId = currentSessionId,
            imageProviders = imageProviders,
            onNew = {
                newSession()
                showSessions = false
            },
            onSwitch = {
                switchSession(it)
                showSessions = false
            },
            onDelete = ::deleteSession,
            onClearAll = ::clearAllSessions,
            onDismiss = { showSessions = false },
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
                    modifier = Modifier.size(72.dp).clip(RoundedCornerShape(18.dp)),
                )
            },
            title = { Text("抹茶画图") },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("原生图像工作台，支持生成、多轮修改与蒙版局部重绘。")
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
                    if (pro != null) currentSession?.modelId = pro.id
                    showSizeWarning = false
                }) { Text("切到 pro") }
            },
        )
    }
}

@Composable
private fun NoProviderState(onManageModels: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Filled.AutoFixHigh, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
        Text(
            "暂无已启用的图像 BYOK 服务",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            "添加一个图像用途的服务后即可开始生成",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
        Button(onClick = onManageModels, modifier = Modifier.padding(top = 20.dp)) {
            Icon(Icons.Filled.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
            Text("去管理图像服务", modifier = Modifier.padding(start = 6.dp))
        }
    }
}

@Composable
private fun CapabilityTag(editable: Boolean, compact: Boolean = false) {
    Surface(
        shape = RoundedCornerShape(5.dp),
        color = if (editable) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.14f),
        modifier = Modifier.padding(start = 6.dp),
    ) {
        Text(
            if (editable) "编辑和生成" else "仅生成",
            color = if (editable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
        )
    }
}

@Composable
private fun GeneratePanel(
    editable: Boolean,
    records: List<WorkbenchRecord>,
    galleryIndex: Int,
    onGalleryIndexChange: (Int) -> Unit,
    attachments: List<ImageAttachmentDraft>,
    ctx: RecordHit?,
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
    onClearContext: () -> Unit,
    onPickAsContext: (WorkbenchRecord, Int) -> Unit,
    onRegenerate: (WorkbenchRecord) -> Unit,
    onCancel: () -> Unit,
    onSend: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f)) {
            if (editable) {
                ChatRecordsView(
                    records = records,
                    showDebug = showDebug,
                    lastRaw = lastRaw,
                    onToggleDebug = onToggleDebug,
                    onPickAsContext = onPickAsContext,
                    onRegenerate = onRegenerate,
                )
            } else {
                GalleryView(
                    records = records,
                    selectedIndex = galleryIndex,
                    onSelectIndex = onGalleryIndexChange,
                    sending = sending,
                    onRegenerate = onRegenerate,
                    showDebug = showDebug,
                    lastRaw = lastRaw,
                    onToggleDebug = onToggleDebug,
                )
            }
        }
        ComposerCard(
            editable = editable,
            ctx = ctx,
            onClearContext = onClearContext,
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
            onCancel = onCancel,
            onSend = onSend,
        )
    }
}

@Composable
private fun ChatRecordsView(
    records: List<WorkbenchRecord>,
    showDebug: Boolean,
    lastRaw: String,
    onToggleDebug: () -> Unit,
    onPickAsContext: (WorkbenchRecord, Int) -> Unit,
    onRegenerate: (WorkbenchRecord) -> Unit,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(records.size, showDebug) {
        val lastIndex = records.lastIndex + if (showDebug) 1 else 0
        if (lastIndex >= 0) listState.animateScrollToItem(lastIndex)
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (records.isEmpty()) {
            item { EmptyWorkbenchState(editable = true) }
        } else {
            items(records, key = { it.id }) { record ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    UserBubble(record)
                    if (record.loading) {
                        LoadingBubble()
                    } else {
                        BotBubble(record = record, onPickAsContext = onPickAsContext, onRegenerate = onRegenerate)
                    }
                }
            }
        }
        if (showDebug) {
            item { DebugCard(raw = lastRaw, onToggle = onToggleDebug) }
        }
    }
}

@Composable
private fun EmptyWorkbenchState(editable: Boolean) {
    Column(
        modifier = Modifier.fillMaxWidth().heightIn(min = 280.dp).padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
            Icon(
                Icons.Filled.AutoFixHigh,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(14.dp).size(26.dp),
            )
        }
        Text(
            if (editable) "描述画面，或添加参考图开始" else "描述画面，开始生成",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = 18.dp),
        )
        Text(
            if (editable) "生成后继续输入可编辑生成的图像" else "模型不支持编辑，每次请求均为独立",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun UserBubble(record: WorkbenchRecord) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.86f),
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 4.dp),
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)),
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (record.refs.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                        record.refs.forEach { ref ->
                            Box(Modifier.size(58.dp).clip(RoundedCornerShape(8.dp))) {
                                AsyncImage(
                                    model = decodeImageModel(ref.path),
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                )
                                if (ref.hasMask) {
                                    Box(Modifier.align(Alignment.TopEnd).padding(4.dp).size(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.error))
                                }
                                if (ref.isContext) {
                                    Surface(
                                        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                                        color = ComposeColor.Black.copy(alpha = 0.55f),
                                    ) {
                                        Text(
                                            "上一张",
                                            color = ComposeColor.White,
                                            style = MaterialTheme.typography.labelSmall,
                                            textAlign = TextAlign.Center,
                                            modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                Text(record.prompt, color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

@Composable
private fun BotBubble(
    record: WorkbenchRecord,
    onPickAsContext: (WorkbenchRecord, Int) -> Unit,
    onRegenerate: (WorkbenchRecord) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (record.hits.isNotEmpty()) {
            val columns = if (record.hits.size == 1) 1 else 2
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                modifier = Modifier.fillMaxWidth().heightIn(max = if (record.hits.size == 1) 460.dp else 540.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                userScrollEnabled = false,
            ) {
                itemsIndexed(record.hits) { index, hit ->
                    GeneratedImageCard(
                        path = hit.path,
                        label = hit.label,
                        onClick = { onPickAsContext(record, index) },
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { onPickAsContext(record, 0) }) {
                    Icon(Icons.Filled.AutoFixHigh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text("继续修改", modifier = Modifier.padding(start = 4.dp))
                }
                TextButton(onClick = { onRegenerate(record) }) {
                    Icon(Icons.Filled.RestartAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text("再次生成", modifier = Modifier.padding(start = 4.dp))
                }
            }
        } else {
            Text("响应中未找到图片，请查看原始响应。", color = MaterialTheme.colorScheme.error)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
            Text(
                "${record.model} · ${record.size.replace("x", "×")} · 请求 ${record.requestCount} 次",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun GeneratedImageCard(path: String, label: String, onClick: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var imageLoadFailed by remember(path) { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
    ) {
        Column {
            Box(
                Modifier.fillMaxWidth().aspectRatio(1f).background(checkerBrush()).clickable(onClick = onClick),
            ) {
                AsyncImage(
                    model = decodeImageModel(path),
                    contentDescription = label.ifBlank { "生成图片" },
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                    onSuccess = { imageLoadFailed = false },
                    onError = { imageLoadFailed = true },
                )
                if (imageLoadFailed) {
                    Column(
                        modifier = Modifier.align(Alignment.Center).padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(Icons.Filled.ImageSearch, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        Text("图片加载失败", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (label.isNotBlank()) {
                    Surface(
                        modifier = Modifier.align(Alignment.TopStart).padding(6.dp),
                        shape = RoundedCornerShape(7.dp),
                        color = ComposeColor.Black.copy(alpha = 0.58f),
                    ) {
                        Text(label, color = ComposeColor.White, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                    }
                }
            }
            Row(modifier = Modifier.padding(6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(path))
                    Toast.makeText(context, "已复制链接", Toast.LENGTH_SHORT).show()
                }) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text("复制", modifier = Modifier.padding(start = 4.dp))
                }
                TextButton(onClick = {
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) { saveImageUrlToGallery(context, path) }
                        Toast.makeText(context, if (ok) "已保存到相册" else "保存失败", Toast.LENGTH_SHORT).show()
                    }
                }) {
                    Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text("保存", modifier = Modifier.padding(start = 4.dp))
                }
            }
        }
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

/** 工作台画廊布局：大预览 + 生成记录胶片条，刻意与对话流区分——避免让人误以为该模型可继续编辑。 */
@Composable
private fun GalleryView(
    records: List<WorkbenchRecord>,
    selectedIndex: Int,
    onSelectIndex: (Int) -> Unit,
    sending: Boolean,
    onRegenerate: (WorkbenchRecord) -> Unit,
    showDebug: Boolean,
    lastRaw: String,
    onToggleDebug: () -> Unit,
) {
    val effectiveIndex = if (selectedIndex in records.indices) selectedIndex else records.lastIndex
    val selected = records.getOrNull(effectiveIndex)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Surface(
            modifier = Modifier.fillMaxWidth().weight(1f),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Box(Modifier.fillMaxSize().background(checkerBrush()), contentAlignment = Alignment.Center) {
                when {
                    selected == null && !sending -> Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(30.dp),
                    ) {
                        Icon(Icons.Filled.AutoFixHigh, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(30.dp))
                        Text("输入 Prompt，点击「生成」", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 12.dp))
                        Text(
                            "该模型为单次生成，不支持参考图与改图\n每条 Prompt 独立出图，结果按记录排列",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    selected != null && selected.loading -> Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        CircularProgressIndicator()
                        Text("正在生成…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    selected != null && selected.hits.size == 1 -> AsyncImage(
                        model = decodeImageModel(selected.hits.first().path),
                        contentDescription = "生成图片",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                    selected != null && selected.hits.size > 1 -> LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(selected.hits) { hit ->
                            AsyncImage(
                                model = decodeImageModel(hit.path),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(10.dp)),
                            )
                        }
                    }
                    selected != null -> Text("响应中未找到图片", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(20.dp))
                }
            }
        }
        if (selected != null && !selected.loading) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${selected.model} · ${selected.size.replace("x", "×")} · ${selected.hits.size} 张",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { onRegenerate(selected) }, enabled = !sending) {
                    Icon(Icons.Filled.RestartAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text("再次生成", modifier = Modifier.padding(start = 4.dp))
                }
                TextButton(onClick = {
                    val url = selected.hits.firstOrNull()?.path ?: return@TextButton
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) { saveImageUrlToGallery(context, url) }
                        Toast.makeText(context, if (ok) "已保存到相册" else "保存失败", Toast.LENGTH_SHORT).show()
                    }
                }) {
                    Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text("保存", modifier = Modifier.padding(start = 4.dp))
                }
            }
        }
        if (records.isNotEmpty()) {
            Text("生成记录", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                records.forEachIndexed { index, record ->
                    val isSelected = index == effectiveIndex
                    Box(
                        modifier = Modifier
                            .size(62.dp)
                            .clip(RoundedCornerShape(11.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .then(
                                if (isSelected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(11.dp))
                                else Modifier,
                            )
                            .clickable { onSelectIndex(index) },
                    ) {
                        if (record.loading) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            }
                        } else {
                            AsyncImage(
                                model = decodeImageModel(record.hits.firstOrNull()?.path.orEmpty()),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                            if (record.hits.size > 1) {
                                Surface(
                                    modifier = Modifier.align(Alignment.BottomEnd).padding(3.dp),
                                    shape = RoundedCornerShape(5.dp),
                                    color = ComposeColor.Black.copy(alpha = 0.55f),
                                ) {
                                    Text("×${record.hits.size}", color = ComposeColor.White, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 4.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
        if (showDebug) {
            DebugCard(raw = lastRaw, onToggle = onToggleDebug)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ComposerCard(
    editable: Boolean,
    ctx: RecordHit?,
    onClearContext: () -> Unit,
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
    onCancel: () -> Unit,
    onSend: () -> Unit,
) {
    var showAdvanced by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .imePadding(),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
            tonalElevation = 2.dp,
            shadowElevation = 8.dp,
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (editable && ctx != null && attachments.isEmpty()) {
                    ContextStrip(ctx = ctx, onClear = onClearContext)
                }
                if (editable && attachments.isNotEmpty()) {
                    AttachmentStrip(
                        attachments = attachments,
                        batchMode = batchMode,
                        onBatchModeChange = onBatchModeChange,
                        onClear = onClearAttachments,
                        onEdit = onEditAttachment,
                        onRemove = onRemoveAttachment,
                    )
                }
                InputChip(
                    selected = showAdvanced,
                    onClick = { showAdvanced = !showAdvanced },
                    label = {
                        val sizeLabel = (if (useCustomSize) customSize else size).ifBlank { "自定义" }.replace("x", "×")
                        Text("$sizeLabel · $quality · ${outputFormat.uppercase()} · $imageCountText 张")
                    },
                    leadingIcon = { Icon(Icons.Filled.Settings, contentDescription = null, modifier = Modifier.size(16.dp)) },
                )
                BasicTextField(
                    value = prompt,
                    onValueChange = onPromptChange,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp, max = 132.dp).padding(horizontal = 4.dp, vertical = 6.dp),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    minLines = 1,
                    maxLines = 4,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                    decorationBox = { innerTextField ->
                        Box {
                            if (prompt.isBlank()) {
                                Text(
                                    if (editable) "描述想生成或修改的图像…" else "描述图像，即刻生成",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            innerTextField()
                        }
                    },
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (editable) {
                        FilledTonalIconButton(onClick = onPickImages, modifier = Modifier.size(40.dp)) {
                            Icon(Icons.Filled.AddPhotoAlternate, contentDescription = "添加参考图", modifier = Modifier.size(20.dp))
                        }
                    }
                    Text(
                        when {
                            !editable -> "每次生成相互独立"
                            attachments.isEmpty() -> "支持参考图上传与多轮修改"
                            else -> "编辑 ${attachments.size} 张参考图"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (status.isNotBlank()) {
                        Text(
                            text = "· $status",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (statusError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        Box(Modifier.weight(1f))
                    }
                    if (editable) {
                        FilledTonalIconButton(
                            onClick = { if (sending) onCancel() else onSend() },
                            enabled = sending || prompt.isNotBlank(),
                            modifier = Modifier.size(40.dp),
                        ) {
                            if (sending) Icon(Icons.Filled.Close, contentDescription = "停止生成", modifier = Modifier.size(20.dp))
                            else Icon(Icons.Filled.Send, contentDescription = "发送", modifier = Modifier.size(20.dp))
                        }
                    } else {
                        Button(
                            onClick = { if (sending) onCancel() else onSend() },
                            enabled = sending || prompt.isNotBlank(),
                            shape = RoundedCornerShape(50),
                        ) {
                            if (sending) {
                                Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                                Text("停止", modifier = Modifier.padding(start = 6.dp))
                            } else {
                                Icon(Icons.Filled.AutoFixHigh, contentDescription = null, modifier = Modifier.size(16.dp))
                                Text("生成", modifier = Modifier.padding(start = 6.dp))
                            }
                        }
                    }
                }
            }
        }
    }
    if (showAdvanced) {
        WorkbenchParametersSheet(
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
            canReason = canReason,
            reasoning = reasoning,
            onReasoningChange = onReasoningChange,
            reasoningEffort = reasoningEffort,
            onReasoningEffortChange = onReasoningEffortChange,
            onDismiss = { showAdvanced = false },
        )
    }
}

@Composable
private fun ContextStrip(ctx: RecordHit, onClear: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.07f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
    ) {
        Row(modifier = Modifier.padding(9.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            AsyncImage(
                model = decodeImageModel(ctx.path),
                contentDescription = "上一张",
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(38.dp).clip(RoundedCornerShape(8.dp)),
            )
            Column(Modifier.weight(1f)) {
                Text("基于上一张结果修改", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                Text("继续输入即可迭代这张图", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = onClear, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Filled.Close, contentDescription = "脱离上下文", modifier = Modifier.size(16.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorkbenchParametersSheet(
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
    canReason: Boolean,
    reasoning: Boolean,
    onReasoningChange: (Boolean) -> Unit,
    reasoningEffort: String,
    onReasoningEffortChange: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        contentWindowInsets = { WindowInsets(0) },
    ) {
        Column(
            // verticalScroll 在前+ime 让位：焦点框（如自定义尺寸，位置偏下）在键盘弹出时可滚动到键盘上方（对齐 ModelEditSheet）。
            modifier = Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp).windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars)),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("生成参数", style = MaterialTheme.typography.titleLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                SelectMenu(
                    label = "尺寸",
                    value = if (useCustomSize) "自定义" else size,
                    options = SizeOptions,
                    onSelect = {
                        onUseCustomSizeChange(false)
                        onSizeChange(it)
                    },
                    extraOption = "自定义尺寸" to {
                        onUseCustomSizeChange(true)
                        if (customSize.isBlank()) onCustomSizeChange(size)
                    },
                )
                SelectMenu("质量", quality, QualityOptions.map { it to it }, onQualityChange)
                SelectMenu("格式", outputFormat, FormatOptions.map { it to it.uppercase() }, onOutputFormatChange)
                SelectMenu("数量", imageCountText, (1..8).map { it.toString() to it.toString() }, onImageCountChange)
                SelectMenu("背景", background, BackgroundOptions.map { it to it }, onBackgroundChange)
                SelectMenu("审核", moderation, ModerationOptions.map { it to it }, onModerationChange)
                if (canReason && reasoning) {
                    SelectMenu("推理强度", reasoningEffort, listOf("low", "medium", "high", "xhigh", "max").map { it to it }, onReasoningEffortChange)
                }
            }
            if (useCustomSize) {
                OutlinedTextField(
                    value = customSize,
                    onValueChange = onCustomSizeChange,
                    label = { Text("自定义尺寸") },
                    placeholder = { Text("1920×1080（16 的倍数，≤3840）") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onCustomSizeChange(normalizeSize(customSize)) }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (canReason) {
                ToggleRow("启用图像推理", "适用于 GPT-5 / Gemini 3 Image。", reasoning, onReasoningChange)
            }
            if (outputFormat == "jpeg" || outputFormat == "webp") {
                Text("压缩率 ${compression.roundToInt()}", style = MaterialTheme.typography.labelMedium)
                Slider(value = compression, onValueChange = onCompressionChange, valueRange = 0f..100f)
            }
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("完成") }
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
        InputChip(
            selected = expanded,
            onClick = { expanded = true },
            label = {
                Text(if (label == "尺寸") text.replace("x", "×").substringBefore(" · ") else "$label $text", maxLines = 1)
            },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor(),
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
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            attachments.forEachIndexed { index, attachment ->
                Box(
                    modifier = Modifier.size(72.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant).clickable { onEdit(attachment) },
                ) {
                    Image(
                        bitmap = attachment.previewBitmap.asImageBitmap(),
                        contentDescription = "参考图 ${index + 1}",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                    Surface(
                        modifier = Modifier.align(Alignment.BottomStart).padding(4.dp),
                        shape = RoundedCornerShape(6.dp),
                        color = ComposeColor.Black.copy(alpha = 0.58f),
                    ) {
                        Text(
                            if (attachment.hasMask) "已涂抹" else "涂抹",
                            color = ComposeColor.White,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                        )
                    }
                    IconButton(onClick = { onRemove(attachment) }, modifier = Modifier.align(Alignment.TopEnd).size(28.dp)) {
                        Icon(Icons.Filled.Close, contentDescription = "移除", tint = ComposeColor.White, modifier = Modifier.size(17.dp))
                    }
                }
            }
            TextButton(onClick = onClear) { Text("清空") }
        }
        if (attachments.size >= 2) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = batchMode, onCheckedChange = onBatchModeChange)
                Text(
                    "按 ${attachments.size} 张参考图分别生成",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 模型选择器：对齐 MolaGPT 聊天顶栏的下拉菜单样式——
 * 按服务（provider）分组，组标题不可点，组内列图像模型（带「可编辑 / 仅生成」标签），
 * 菜单底部固定一条“管理图像服务与模型”快速入口。
 */
@Composable
private fun ModelPickerMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    providers: List<ByokProvider>,
    selectedProviderId: String,
    selectedModelId: String,
    onModelSelected: (ByokProvider, ProviderModel) -> Unit,
    onManageModels: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        if (providers.isEmpty()) {
            DropdownMenuItem(
                text = { Text("暂无图像服务 · 点此添加") },
                leadingIcon = { Icon(Icons.Filled.Settings, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                onClick = onManageModels,
            )
            return@DropdownMenu
        }
        providers.forEachIndexed { index, provider ->
            if (index > 0) HorizontalDivider()
            // 分组标题（服务名，不可点）。
            DropdownMenuItem(
                text = {
                    Text(
                        provider.name,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                },
                onClick = {},
                enabled = false,
            )
            val models = provider.models.filter { it.supportsImageGeneration }
            if (models.isEmpty()) {
                DropdownMenuItem(
                    text = {
                        Text(
                            "该服务暂无图像模型",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    onClick = {},
                    enabled = false,
                )
            }
            models.forEach { model ->
                val isSelected = provider.id == selectedProviderId && model.id == selectedModelId
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                model.displayName,
                                color = if (isSelected) MaterialTheme.colorScheme.primary else ComposeColor.Unspecified,
                                fontWeight = if (isSelected) FontWeight.SemiBold else null,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            CapabilityTag(editable = model.supportsImageEdit)
                        }
                    },
                    leadingIcon = if (isSelected) {
                        { Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp)) }
                    } else null,
                    onClick = { onModelSelected(provider, model) },
                )
            }
        }
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text("管理图像服务与模型") },
            leadingIcon = { Icon(Icons.Filled.Settings, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
            onClick = onManageModels,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionSheet(
    sessions: List<WorkbenchSessionUi>,
    currentId: String,
    imageProviders: List<ByokProvider>,
    onNew: () -> Unit,
    onSwitch: (String) -> Unit,
    onDelete: (String) -> Unit,
    onClearAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        contentWindowInsets = { WindowInsets(0) },
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp).windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars)),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("历史对话", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = onClearAll, enabled = sessions.isNotEmpty()) { Text("清空全部") }
            }
            OutlinedButton(onClick = onNew, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.AutoFixHigh, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("新画图对话", modifier = Modifier.padding(start = 6.dp))
            }
            if (sessions.isEmpty()) {
                Text("暂无历史对话", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 24.dp))
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(sessions, key = { it.id }) { session ->
                        val model = imageProviders.firstOrNull { it.id == session.providerId }?.models?.firstOrNull { it.id == session.modelId }
                        val lastCompleted = session.records.lastOrNull { !it.loading }
                        Surface(
                            modifier = Modifier.fillMaxWidth().clickable { onSwitch(session.id) },
                            shape = RoundedCornerShape(12.dp),
                            color = if (session.id == currentId) MaterialTheme.colorScheme.primary.copy(alpha = 0.09f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f),
                        ) {
                            Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                val thumbPath = lastCompleted?.hits?.firstOrNull()?.path
                                if (thumbPath != null) {
                                    AsyncImage(
                                        model = decodeImageModel(thumbPath),
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.size(50.dp).clip(RoundedCornerShape(11.dp)),
                                    )
                                } else {
                                    Box(
                                        Modifier.size(50.dp).clip(RoundedCornerShape(11.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(Icons.Filled.AutoFixHigh, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                                    }
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(session.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 3.dp)) {
                                        CapabilityTag(editable = model?.supportsImageEdit ?: true, compact = true)
                                        Text(
                                            " ${session.modelId} · ${session.records.count { !it.loading }} 次 · ${formatTime(session.updatedAt)}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                                IconButton(onClick = { onDelete(session.id) }) {
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
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier.fillMaxSize().padding(WindowInsets.systemBars.asPaddingValues()).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "关闭") }
                    Text("涂抹编辑区域", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = onClear) { Text("清除") }
                    Button(onClick = onDismiss) { Text("完成") }
                }
                Box(
                    modifier = Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)).onSizeChanged { viewport = it },
                ) {
                    Canvas(
                        modifier = Modifier.fillMaxSize().pointerInput(tool, brushSize, viewport, attachment.maskVersion) {
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
                Text(
                    "红色区域 = 允许 AI 修改的范围 · 其余部分保持原样",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorkbenchSettingsSheet(
    clearOnSubmit: Boolean,
    onClearOnSubmitChange: (Boolean) -> Unit,
    autoContinue: Boolean,
    onAutoContinueChange: (Boolean) -> Unit,
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
            // verticalScroll 在前+ime 让位：焦点框（请求超时，位置偏下）在键盘弹出时可滚动到键盘上方（对齐 ModelEditSheet）。
            modifier = Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp).windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars)),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("画图设置", style = MaterialTheme.typography.titleLarge)
            ToggleRow("提交后清空输入", "生成成功后清空 Prompt。", clearOnSubmit, onClearOnSubmitChange)
            ToggleRow("自动延续上一张", "可编辑模型下，生成后默认基于最新结果继续修改。", autoContinue, onAutoContinueChange)
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
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)) {
        Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange)
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
                            modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp, max = 500.dp).background(checkerBrush(), RoundedCornerShape(12.dp)).clip(RoundedCornerShape(12.dp)),
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

// ===================== 数据模型 & 持久化 =====================

private const val WORKBENCH_PREFS = "matcha_image_workbench"
private const val WORKBENCH_IMAGE_DIR = "image_workbench"
private const val WORKBENCH_SESSIONS_KEY = "sessions_v1"
private val workbenchJson = Json { ignoreUnknownKeys = true }

@Serializable
private data class RecordRef(val path: String, val hasMask: Boolean = false, val isContext: Boolean = false)

@Serializable
private data class RecordHit(val path: String, val label: String = "")

@Serializable
private data class WorkbenchRecord(
    val id: Long,
    val prompt: String,
    val refs: List<RecordRef> = emptyList(),
    val hits: List<RecordHit> = emptyList(),
    val model: String,
    val size: String,
    val requestCount: Int = 1,
    @Transient val loading: Boolean = false,
)

@Serializable
private data class SessionSnapshot(
    val id: String,
    val title: String,
    val providerId: String,
    val modelId: String,
    val updatedAt: Long,
    val records: List<WorkbenchRecord> = emptyList(),
)

@Serializable
private data class SessionsFile(val sessions: List<SessionSnapshot> = emptyList())

@Stable
private class WorkbenchSessionUi(
    val id: String,
    title: String,
    providerId: String,
    modelId: String,
    records: List<WorkbenchRecord> = emptyList(),
    updatedAt: Long = System.currentTimeMillis(),
) {
    var title by mutableStateOf(title)
    var providerId by mutableStateOf(providerId)
    var modelId by mutableStateOf(modelId)
    var updatedAt by mutableStateOf(updatedAt)
    val records = mutableStateListOf<WorkbenchRecord>().apply { addAll(records) }

    fun toSnapshot() = SessionSnapshot(id, title, providerId, modelId, updatedAt, records.toList())

    companion object {
        fun from(s: SessionSnapshot) = WorkbenchSessionUi(s.id, s.title, s.providerId, s.modelId, s.records, s.updatedAt)
        fun new(providerId: String, modelId: String) = WorkbenchSessionUi(
            id = UUID.randomUUID().toString(),
            title = "新画图对话",
            providerId = providerId,
            modelId = modelId,
        )
    }
}

private fun loadSessions(context: Context): List<SessionSnapshot> {
    val raw = context.getSharedPreferences(WORKBENCH_PREFS, Context.MODE_PRIVATE).getString(WORKBENCH_SESSIONS_KEY, null)
        ?: return emptyList()
    return runCatching { workbenchJson.decodeFromString<SessionsFile>(raw).sessions }.getOrDefault(emptyList())
}

private fun saveSessions(context: Context, sessions: List<SessionSnapshot>) {
    val raw = workbenchJson.encodeToString(SessionsFile(sessions))
    context.getSharedPreferences(WORKBENCH_PREFS, Context.MODE_PRIVATE).edit().putString(WORKBENCH_SESSIONS_KEY, raw).apply()
}

private fun imageDir(context: Context): File = File(context.filesDir, WORKBENCH_IMAGE_DIR).apply { mkdirs() }

private fun persistBytes(context: Context, bytes: ByteArray, prefix: String): String {
    val file = File(imageDir(context), "${prefix}_${System.nanoTime()}.png")
    file.writeBytes(bytes)
    return Uri.fromFile(file).toString()
}

/** 把工作台产出的图片 hit（可能是几 MB 的 base64 data URL）落地为文件，避免写入 SharedPreferences。 */
private fun persistHit(context: Context, hit: ByokImageHit): RecordHit {
    if (!hit.isData) return RecordHit(path = hit.url, label = hit.label)
    val bytes = runCatching {
        val comma = hit.url.indexOf(',')
        Base64.decode(hit.url.substring(comma + 1), Base64.DEFAULT)
    }.getOrNull()
    val path = bytes?.let { runCatching { persistBytes(context, it, "result") }.getOrNull() } ?: hit.url
    return RecordHit(path = path, label = hit.label)
}

private fun deleteFileIfLocal(context: Context, path: String) {
    if (!path.startsWith("file://")) return
    runCatching { Uri.parse(path).path?.let { File(it).delete() } }
}

private fun deleteSessionFiles(context: Context, snapshot: SessionSnapshot) {
    snapshot.records.forEach { r ->
        r.hits.forEach { deleteFileIfLocal(context, it.path) }
        r.refs.forEach { deleteFileIfLocal(context, it.path) }
    }
}

/** 读取一个已持久化的引用（file:// 或 http(s)://）为可再次提交给网络层的附件。 */
private suspend fun loadPathAsAttachment(context: Context, path: String): ByokImageAttachment? = withContext(Dispatchers.IO) {
    runCatching {
        val bytes = when {
            path.startsWith("file://") -> Uri.parse(path).path?.let { File(it).readBytes() } ?: return@runCatching null
            path.startsWith("http://") || path.startsWith("https://") -> {
                (URL(path).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 30_000
                    requestMethod = "GET"
                }.inputStream.use { it.readBytes() }
            }
            else -> return@runCatching null
        }
        ByokImageAttachment(fileName = "context.png", mimeType = "image/png", bytes = bytes)
    }.getOrNull()
}

// ===================== 通用工具函数 =====================

@Composable
private fun checkerBrush(): Brush {
    val surface = MaterialTheme.colorScheme.surface
    val variant = MaterialTheme.colorScheme.surfaceVariant
    return Brush.linearGradient(listOf(surface, variant, surface))
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

private fun formatTime(ts: Long): String = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(ts))

private fun saveImageUrlToGallery(context: Context, url: String): Boolean {
    return if (url.startsWith("data:", ignoreCase = true)) {
        val comma = url.indexOf(',')
        if (comma < 0) false else {
            val mime = url.substringBefore(';').removePrefix("data:")
            val bytes = Base64.decode(url.substring(comma + 1), Base64.DEFAULT)
            saveBytesToGallery(context, bytes, mime)
        }
    } else if (url.startsWith("file://")) {
        runCatching {
            val bytes = Uri.parse(url).path?.let { File(it).readBytes() } ?: return@runCatching false
            saveBytesToGallery(context, bytes, detectMime(bytes) ?: "image/png")
        }.getOrDefault(false)
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
