package com.molagpt.app.feature.imagegen

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.molagpt.app.core.render.ImeDismissBackHandler
import com.molagpt.app.core.render.MolaLogo
import com.molagpt.app.core.render.ToolChip
import com.molagpt.app.feature.file.CameraCapture
import com.molagpt.app.feature.file.ImagePreviewAction
import com.molagpt.app.feature.file.ImagePreviewHost
import kotlinx.coroutines.launch

private val StarterPrompts = listOf(
    "窗台上晒太阳的橘猫，午后逆光，胶片质感",
    "极简风格的香水产品图，浅色背景，柔和阴影",
    "水彩风格的山间小屋，清晨薄雾",
    "赛博朋克城市夜景海报，霓虹雨夜",
)

/**
 * 画图工作台。时间线按轮展示（用户气泡 + 结果），输入区在底部；历史在左侧抽屉，也同时出现在聊天主侧边栏里。
 *
 * 在途的生成由 [ImageTaskManager] 在应用作用域执行，这个页面只负责展示和发起——
 * 切任务、新建、离开页面都不会打断或串到别的任务上。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageWorkbenchScreen(
    viewModel: ImageWorkbenchViewModel,
    onBack: () -> Unit,
    onManageModels: (providerId: String?) -> Unit,
    onOpenGallery: () -> Unit,
    onOpenBase64: () -> Unit,
    /** 当前可见的任务，用于完成通知抑制。 */
    onVisibleTaskChange: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val providers by viewModel.providers.collectAsStateWithLifecycle()
    val task by viewModel.task.collectAsStateWithLifecycle()
    val running by viewModel.runningTaskIds.collectAsStateWithLifecycle()
    val summaries by viewModel.summaries.collectAsStateWithLifecycle()
    val galleryCount by viewModel.galleryCount.collectAsStateWithLifecycle()
    val imageProviders = providers.orEmpty()
    val selection = viewModel.selection(imageProviders, task)
    val mode = viewModel.mode(task)
    val currentTask = task
    val busy = currentTask != null && currentTask.taskId in running

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val snackbar = remember { SnackbarHostState() }
    var modelMenuOpen by remember { mutableStateOf(false) }
    var overflowOpen by remember { mutableStateOf(false) }
    var paramsOpen by rememberSaveable { mutableStateOf(false) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var aboutOpen by rememberSaveable { mutableStateOf(false) }
    var customSizeOpen by rememberSaveable { mutableStateOf(false) }
    var sizeWarning by rememberSaveable { mutableStateOf(false) }
    var confirmClearAll by rememberSaveable { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<Pair<String, String>?>(null) }
    var maskTarget by remember { mutableStateOf<RefDraft?>(null) }

    // 键盘弹着时返回先收键盘，不退页面。
    ImeDismissBackHandler()

    DisposableEffect(currentTask?.taskId) {
        onVisibleTaskChange(currentTask?.taskId)
        onDispose { onVisibleTaskChange(null) }
    }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is WorkbenchEvent.Message -> snackbar.showSnackbar(event.text)
                WorkbenchEvent.SizeNeedsPro -> sizeWarning = true
                is WorkbenchEvent.TaskFinished -> scope.launch {
                    val result = snackbar.showSnackbar(event.text, actionLabel = "查看", withDismissAction = true)
                    if (result == SnackbarResult.ActionPerformed) viewModel.openTask(event.taskId)
                }
            }
        }
    }

    val pickImages = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        viewModel.addImages(uris)
    }
    var cameraUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = cameraUri
        if (ok && uri != null) viewModel.addImages(listOf(uri))
        cameraUri = null
    }

    fun closeDrawer() {
        scope.launch { drawerState.close() }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = drawerState.isOpen,
        // 收起的抽屉停在屏幕左侧外；返回转场把整页右移时不裁剪就会被带进画面。
        modifier = modifier.clipToBounds(),
        drawerContent = {
            ModalDrawerSheet(
                drawerState = drawerState,
                drawerContainerColor = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth(0.86f),
            ) {
                WorkbenchDrawer(
                    tasks = summaries,
                    currentTaskId = currentTask?.taskId,
                    runningTaskIds = running,
                    galleryCount = galleryCount,
                    modelName = { providerId, modelId -> modelName(imageProviders, providerId, modelId) },
                    onNewTask = {
                        viewModel.newTask()
                        closeDrawer()
                    },
                    onOpenTask = {
                        viewModel.openTask(it)
                        closeDrawer()
                    },
                    onDeleteTask = { id, title -> pendingDelete = id to title },
                    onOpenGallery = {
                        closeDrawer()
                        onOpenGallery()
                    },
                )
            }
        },
    ) {
        ImagePreviewHost(
            extraAction = if (selection.editable) ImagePreviewAction("以此为底图") { viewModel.useAsBase(it) } else null,
        ) {
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                contentWindowInsets = WindowInsets.systemBars.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                snackbarHost = { SnackbarHost(snackbar) },
                topBar = {
                    TopAppBar(
                        navigationIcon = {
                            IconButton(onClick = onBack) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                            }
                        },
                        title = {
                            Column {
                                Text(
                                    text = currentTask?.title ?: "新画图任务",
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Box {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable(enabled = imageProviders.isNotEmpty()) { modelMenuOpen = true }
                                            .padding(end = 4.dp),
                                    ) {
                                        val model = selection.model
                                        if (model != null) {
                                            CapabilityBadge(editable = selection.editable)
                                            Spacer(Modifier.width(5.dp))
                                        }
                                        Text(
                                            text = model?.displayName ?: if (providers == null) "" else "未选择图像模型",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.primary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f, fill = false),
                                        )
                                        Icon(Icons.Filled.ArrowDropDown, contentDescription = "切换模型", tint = MaterialTheme.colorScheme.primary)
                                    }
                                    ModelMenu(
                                        expanded = modelMenuOpen,
                                        providers = imageProviders,
                                        selection = selection,
                                        onSelect = { provider, model ->
                                            modelMenuOpen = false
                                            viewModel.selectModel(provider, model)
                                        },
                                        onManage = {
                                            modelMenuOpen = false
                                            onManageModels(selection.provider?.id)
                                        },
                                        onDismiss = { modelMenuOpen = false },
                                    )
                                }
                            }
                        },
                        actions = {
                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                Box {
                                    Icon(Icons.Filled.History, contentDescription = "画图历史")
                                    // 别的任务还在后台生成时点一个小圆点。
                                    if (running.any { it != currentTask?.taskId }) {
                                        Box(
                                            Modifier
                                                .align(Alignment.TopEnd)
                                                .size(7.dp)
                                                .clip(CircleShape)
                                                .background(MaterialTheme.colorScheme.primary),
                                        )
                                    }
                                }
                            }
                            if (currentTask != null && currentTask.runs.isNotEmpty()) {
                                IconButton(onClick = viewModel::newTask) {
                                    Icon(Icons.Filled.Add, contentDescription = "新画图任务")
                                }
                            }
                            Box {
                                IconButton(onClick = { overflowOpen = true }) {
                                    Icon(Icons.Filled.MoreVert, contentDescription = "更多")
                                }
                                DropdownMenu(
                                    expanded = overflowOpen,
                                    onDismissRequest = { overflowOpen = false },
                                    properties = PopupProperties(focusable = false),
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("画廊") },
                                        leadingIcon = { Icon(Icons.Filled.PhotoLibrary, null) },
                                        onClick = { overflowOpen = false; onOpenGallery() },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Base64 工具") },
                                        leadingIcon = { Icon(Icons.Filled.Code, null) },
                                        onClick = { overflowOpen = false; onOpenBase64() },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("画图设置") },
                                        leadingIcon = { Icon(Icons.Filled.Settings, null) },
                                        onClick = { overflowOpen = false; settingsOpen = true },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("关于图像生成模块") },
                                        leadingIcon = { Icon(Icons.Filled.Info, null) },
                                        onClick = { overflowOpen = false; aboutOpen = true },
                                    )
                                }
                            }
                        },
                    )
                },
                bottomBar = {
                    if (imageProviders.isNotEmpty()) {
                        // ime 与导航栏取并集后一次性消费，键盘弹出时输入区跟着上移（与聊天页相同）。
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars)),
                        ) {
                            WorkbenchComposer(
                                viewModel = viewModel,
                                selection = selection,
                                mode = mode,
                                headUrl = currentTask?.chainHead?.let { viewModel.fileUrl(it.src) },
                                busy = busy,
                                onPickImages = {
                                    pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                },
                                onTakePhoto = {
                                    val uri = CameraCapture.newPhotoUri(context)
                                    if (uri == null) {
                                        Toast.makeText(context, "无法启动相机", Toast.LENGTH_SHORT).show()
                                    } else {
                                        cameraUri = uri
                                        takePhoto.launch(uri)
                                    }
                                },
                                onOpenParams = { paramsOpen = true },
                                onCustomSize = { customSizeOpen = true },
                                onEditMask = { maskTarget = it },
                            )
                        }
                    }
                },
            ) { inner ->
                Box(modifier = Modifier.fillMaxSize().padding(inner)) {
                    when {
                        providers == null -> Unit
                        imageProviders.isEmpty() -> NoProviderState(onAdd = { onManageModels(null) })
                        currentTask == null || currentTask.runs.isEmpty() -> EmptyState(
                            onPick = { viewModel.prompt = it },
                        )
                        else -> WorkbenchTimeline(
                            taskId = currentTask.taskId,
                            runs = currentTask.runs,
                            actions = TimelineActions(
                                urlOf = viewModel::fileUrl,
                                modelName = { providerId, modelId -> modelName(imageProviders, providerId, modelId) },
                                canUseAsBase = selection.editable,
                                onRegenerate = viewModel::regenerate,
                                onRetry = viewModel::retry,
                                onSwitchVersion = viewModel::switchVersion,
                                onUseAsBase = viewModel::useAsBase,
                            ),
                        )
                    }
                }
            }
        }
    }

    if (paramsOpen) {
        ParamsSheet(
            provider = selection.provider,
            model = selection.model,
            params = viewModel.params,
            onChange = viewModel::updateParams,
            onDismiss = { paramsOpen = false },
        )
    }
    if (settingsOpen) {
        WorkbenchSettingsSheet(
            timeoutSeconds = viewModel.params.timeoutSeconds,
            onTimeoutChange = { viewModel.updateParams(viewModel.params.copy(timeoutSeconds = it)) },
            onClearAll = {
                settingsOpen = false
                confirmClearAll = true
            },
            onDismiss = { settingsOpen = false },
        )
    }
    if (customSizeOpen) {
        CustomSizeDialog(
            initial = viewModel.size,
            onConfirm = {
                customSizeOpen = false
                viewModel.updateSize(it, selection)
            },
            onDismiss = { customSizeOpen = false },
        )
    }
    if (sizeWarning) {
        val pro = selection.provider?.models?.firstOrNull { it.id == "gpt-image-2-pro" && it.supportsImageGeneration }
        SizeWarningDialog(
            hasPro = pro != null,
            onSwitchPro = {
                sizeWarning = false
                val provider = selection.provider
                if (pro != null && provider != null) viewModel.selectModel(provider, pro)
            },
            onDismiss = { sizeWarning = false },
        )
    }
    if (aboutOpen) AboutDialog(onDismiss = { aboutOpen = false })
    if (confirmClearAll) {
        ConfirmDialog(
            title = "清除全部画图数据",
            text = "清除全部画图任务及图片？正在进行的生成将停止，此操作不可撤销。",
            confirmLabel = "清除",
            onConfirm = {
                confirmClearAll = false
                viewModel.deleteAll()
            },
            onDismiss = { confirmClearAll = false },
        )
    }
    pendingDelete?.let { (id, title) ->
        ConfirmDialog(
            title = "删除画图任务",
            text = "删除「$title」及其图片？此操作不可撤销。",
            confirmLabel = "删除",
            onConfirm = {
                pendingDelete = null
                viewModel.deleteTask(id)
            },
            onDismiss = { pendingDelete = null },
        )
    }
    maskTarget?.let { ref ->
        if (ref in viewModel.refs) {
            MaskEditorDialog(ref = ref, onDismiss = { maskTarget = null })
        } else {
            maskTarget = null
        }
    }
}

private fun modelName(providers: List<com.molagpt.app.core.model.ByokProvider>, providerId: String, modelId: String): String =
    providers.firstOrNull { it.id == providerId }?.models?.firstOrNull { it.id == modelId }?.displayName ?: modelId

@Composable
internal fun ImageWorkbenchBrandMark(size: Dp = 56.dp, cornerRadius: Dp = 16.dp) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MolaLogo(size = size, cornerRadius = cornerRadius)
        Text("×", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Image(
            painter = painterResource(R.drawable.matcha_image_icon),
            contentDescription = null,
            modifier = Modifier.size(size).clip(RoundedCornerShape(cornerRadius)),
        )
    }
}

@Composable
private fun EmptyState(onPick: (String) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        ImageWorkbenchBrandMark()
        Spacer(Modifier.height(14.dp))
        Text("要创作点什么？", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(20.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StarterPrompts.forEach { text ->
                ToolChip(label = text, checked = false, enabled = true, onClick = { onPick(text) }, role = Role.Button)
            }
        }
    }
}

@Composable
private fun NoProviderState(onAdd: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.matcha_image_icon),
            contentDescription = null,
            modifier = Modifier.size(56.dp).clip(RoundedCornerShape(16.dp)),
        )
        Spacer(Modifier.height(14.dp))
        Text("暂无图像服务", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(20.dp))
        Button(onClick = onAdd) { Text("添加图像服务") }
    }
}
