package com.molagpt.app.feature.imagegen

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.molagpt.app.core.model.ByokProvider
import com.molagpt.app.core.model.ByokPurpose
import com.molagpt.app.core.model.ProviderModel
import com.molagpt.app.core.storage.ByokProviderRepository
import com.molagpt.app.core.storage.GalleryImage
import com.molagpt.app.core.storage.ImageMode
import com.molagpt.app.core.storage.ImageParams
import com.molagpt.app.core.storage.ImageRunKind
import com.molagpt.app.core.storage.ImageTask
import com.molagpt.app.core.storage.ImageTaskRepository
import com.molagpt.app.core.storage.ImageTaskSummary
import com.molagpt.app.core.storage.ImageVersionStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 当前生效的服务与模型。任务里记的那个不可用时回退到第一个可用的，但不改写任务。 */
internal data class ModelSelection(val provider: ByokProvider?, val model: ProviderModel?) {
    val editable: Boolean get() = model?.supportsImageEdit == true
}

internal sealed interface WorkbenchEvent {
    data class Message(val text: String) : WorkbenchEvent
    data class TaskFinished(val taskId: String, val text: String) : WorkbenchEvent
    data object SizeNeedsPro : WorkbenchEvent
}

class ImageWorkbenchViewModel(
    private val manager: ImageTaskManager,
    private val repository: ImageTaskRepository,
    providerRepository: ByokProviderRepository,
    private val appContext: Context,
    /** 通知、主侧边栏、画廊请求打开的任务；进页面时消费。 */
    private val pendingOpen: MutableStateFlow<String?>,
) : ViewModel() {

    private val prefs = WorkbenchPrefs(appContext)

    /** null = 还没从库里读到；空表 = 确实没有图像服务。 */
    internal val providers: StateFlow<List<ByokProvider>?> = providerRepository.providers
        .map { list -> list.filter { it.enabled && it.purpose == ByokPurpose.IMAGE } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val currentTaskId = MutableStateFlow<String?>(null)

    @OptIn(ExperimentalCoroutinesApi::class)
    internal val task: StateFlow<ImageTask?> = currentTaskId
        .flatMapLatest { id -> if (id == null) flowOf(null) else repository.observeTask(id) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    internal val summaries: StateFlow<List<ImageTaskSummary>> = repository.observeSummaries()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    internal val galleryCount: StateFlow<Int> = repository.observeGallery()
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    internal val runningTaskIds: StateFlow<Set<String>> = manager.runningTaskIds

    private val _events = MutableSharedFlow<WorkbenchEvent>(extraBufferCapacity = 8)
    internal val events: SharedFlow<WorkbenchEvent> = _events.asSharedFlow()

    // —— 输入区 ——
    var prompt by mutableStateOf("")
    internal val refs = mutableStateListOf<RefDraft>()
    internal var perImage by mutableStateOf(false)

    /** 用户点掉了「上一张」，这一次改为生成新图。发送后复位。 */
    internal var chainDetached by mutableStateOf(false)
    internal var size by mutableStateOf(prefs.size)
        private set
    internal var count by mutableStateOf(prefs.count)
        private set
    internal var params by mutableStateOf(prefs.params)
        private set

    /** 没有任务（空白页）时的选择；有任务时以任务上记的为准。 */
    private var draftProviderId by mutableStateOf(prefs.providerId)
    private var draftModelId by mutableStateOf(prefs.modelId)
    private var draftMode by mutableStateOf(ImageMode.CHAT)

    /** 刚发出的首个版本 → 原文。失败或取消时，输入框还空着就把原文放回去。 */
    private val restoreOnFailure = HashMap<String, String>()

    init {
        viewModelScope.launch {
            val requested = pendingOpen.value
            if (requested != null) {
                pendingOpen.value = null
                openTask(requested)
            } else {
                prefs.lastTaskId?.let { last -> if (repository.exists(last)) currentTaskId.value = last }
            }
        }
        viewModelScope.launch {
            pendingOpen.collect { id ->
                if (id != null) {
                    pendingOpen.value = null
                    openTask(id)
                }
            }
        }
        viewModelScope.launch {
            manager.completions.collect { c ->
                val original = restoreOnFailure.remove(c.versionId)
                val visible = c.taskId == currentTaskId.value
                if (original != null && visible && c.status != ImageVersionStatus.SUCCESS && prompt.isBlank()) {
                    prompt = original
                }
                if (!visible && c.status == ImageVersionStatus.SUCCESS) {
                    val name = c.title.ifBlank { "画图任务" }
                    _events.tryEmit(WorkbenchEvent.TaskFinished(c.taskId, "「$name」已生成 ${c.imageCount} 张"))
                }
            }
        }
    }

    internal val currentId: String? get() = currentTaskId.value

    internal fun selection(providers: List<ByokProvider>, task: ImageTask?): ModelSelection {
        val wantProvider = task?.providerId ?: draftProviderId
        val wantModel = task?.modelId ?: draftModelId
        val provider = providers.firstOrNull { it.id == wantProvider } ?: providers.firstOrNull()
        val models = provider?.models.orEmpty().filter { it.supportsImageGeneration }
        val model = models.firstOrNull { it.id == wantModel && provider?.id == wantProvider } ?: models.firstOrNull()
        return ModelSelection(provider, model)
    }

    internal fun mode(task: ImageTask?): ImageMode = task?.mode ?: draftMode

    fun openTask(taskId: String) {
        if (currentTaskId.value == taskId) return
        currentTaskId.value = taskId
        prefs.lastTaskId = taskId
        resetComposerInputs()
    }

    fun newTask() {
        currentTaskId.value = null
        prefs.lastTaskId = null
        draftMode = ImageMode.CHAT
        resetComposerInputs()
    }

    private fun resetComposerInputs() {
        refs.clear()
        perImage = false
        chainDetached = false
    }

    internal fun selectModel(provider: ByokProvider, model: ProviderModel) {
        draftProviderId = provider.id
        draftModelId = model.id
        prefs.providerId = provider.id
        prefs.modelId = model.id
        currentTaskId.value?.let { id -> viewModelScope.launch { repository.updateModel(id, provider.id, model.id) } }
        if (!model.supportsImageEdit) resetComposerInputs()
        if (model.id == "gpt-image-2" && maxEdge(size) >= 1600) _events.tryEmit(WorkbenchEvent.SizeNeedsPro)
    }

    internal fun setMode(mode: ImageMode) {
        draftMode = mode
        currentTaskId.value?.let { id -> viewModelScope.launch { repository.updateMode(id, mode) } }
    }

    internal fun updateSize(value: String, selection: ModelSelection) {
        size = value
        prefs.size = value
        if (selection.model?.id == "gpt-image-2" && maxEdge(value) >= 1600) _events.tryEmit(WorkbenchEvent.SizeNeedsPro)
    }

    internal fun updateCount(value: Int) {
        count = value
        prefs.count = value
    }

    internal fun updateParams(value: ImageParams) {
        params = value
        prefs.params = value
    }

    fun addImages(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val added = withContext(Dispatchers.IO) { uris.mapNotNull { RefDraft.fromUri(appContext, it) } }
            refs += added
            if (added.size < uris.size) _events.tryEmit(WorkbenchEvent.Message("有 ${uris.size - added.size} 张图片读取失败"))
        }
    }

    /** 「以此为底图」：拿一张结果替换掉输入区现有的参考图。 */
    internal fun useAsBase(url: String) {
        viewModelScope.launch {
            val file = repository.files.fileOf(url)
            val draft = withContext(Dispatchers.IO) {
                file?.takeIf { it.isFile }?.let { RefDraft.fromFile(it, repository.files.relativize(url)) }
            }
            if (draft == null) {
                _events.tryEmit(WorkbenchEvent.Message("图片读取失败"))
                return@launch
            }
            refs.clear()
            refs += draft
            perImage = false
        }
    }

    internal fun removeRef(draft: RefDraft) {
        refs.remove(draft)
        if (refs.size < 2) perImage = false
    }

    /**
     * 发送。先把输入区清掉（发送即清空，失败再放回），然后在应用作用域里建任务、落库、发请求——
     * 这之后离开页面也不影响它跑完。
     */
    internal fun send(selection: ModelSelection): Boolean {
        val text = prompt.trim()
        if (text.isEmpty()) return false
        val provider = selection.provider
        val model = selection.model
        if (provider == null || model == null) {
            _events.tryEmit(WorkbenchEvent.Message("请选择图像模型"))
            return false
        }
        val task = task.value
        val mode = mode(task)
        val editable = selection.editable
        val head = task?.chainHead?.takeIf { !chainDetached }
        val drafts = if (editable) refs.toList() else emptyList()
        val kind = planKind(editable, mode, drafts.size, perImage, head != null)
        val runCount = if (kind == ImageRunKind.NEW && countApplies(editable, mode)) count else 1
        val taskId = currentTaskId.value
        val submitSize = size
        val submitParams = params

        prompt = ""
        chainDetached = false
        if (mode == ImageMode.CHAT) {
            refs.clear()
            perImage = false
        }

        viewModelScope.launch {
            val pending = withContext(Dispatchers.Default) {
                when (kind) {
                    ImageRunKind.NEW -> emptyList()
                    ImageRunKind.CHAIN -> listOf(ImageTaskManager.PendingRef.Existing(head!!.src))
                    else -> drafts.map { it.toPending() }
                }
            }
            val submitted = runCatching {
                manager.submit(
                    ImageTaskManager.Submission(
                        taskId = taskId,
                        providerId = provider.id,
                        modelId = model.id,
                        mode = mode,
                        prompt = text,
                        kind = kind,
                        size = submitSize,
                        count = runCount,
                        params = submitParams,
                        refs = pending,
                    ),
                )
            }.getOrElse { error ->
                if (prompt.isBlank()) prompt = text
                _events.tryEmit(WorkbenchEvent.Message(error.message ?: "发送失败"))
                return@launch
            }
            restoreOnFailure[submitted.versionId] = text
            // 等待期间用户可能已经切走；只有还停在原处（或空白页）时才跟过去。
            if (currentTaskId.value == taskId) {
                currentTaskId.value = submitted.taskId
                prefs.lastTaskId = submitted.taskId
            }
        }
        return true
    }

    internal fun regenerate(runId: String) = manager.regenerate(runId)

    internal fun retry(versionId: String) = manager.retry(versionId)

    internal fun stopCurrent() {
        currentTaskId.value?.let(manager::cancelTask)
    }

    internal fun switchVersion(runId: String, versionId: String) {
        viewModelScope.launch { repository.setActiveVersion(runId, versionId) }
    }

    internal fun deleteTask(taskId: String) {
        if (taskId == currentTaskId.value) newTask()
        viewModelScope.launch {
            manager.deleteTask(taskId)
            _events.tryEmit(WorkbenchEvent.Message("画图任务已删除"))
        }
    }

    internal fun deleteAll() {
        newTask()
        viewModelScope.launch {
            manager.deleteAll()
            _events.tryEmit(WorkbenchEvent.Message("已清除全部画图数据"))
        }
    }

    internal fun fileUrl(src: String): String = repository.files.url(src)
}
