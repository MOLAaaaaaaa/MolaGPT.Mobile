package com.molagpt.app.feature.imagegen

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Base64
import com.molagpt.app.core.common.DispatcherProvider
import com.molagpt.app.core.common.Logger
import com.molagpt.app.core.model.ByokImageFormat
import com.molagpt.app.core.model.ByokProvider
import com.molagpt.app.core.model.ByokPurpose
import com.molagpt.app.core.network.ByokImageApi
import com.molagpt.app.core.network.ByokImageAttachment
import com.molagpt.app.core.network.ByokImageHit
import com.molagpt.app.core.network.ByokImageWorkbenchConfig
import com.molagpt.app.core.network.ImageCallGate
import com.molagpt.app.core.network.MolaApiException
import com.molagpt.app.core.network.looksLikeByokImageReasoningModel
import com.molagpt.app.core.storage.ByokProviderRepository
import com.molagpt.app.core.storage.ImageMode
import com.molagpt.app.core.storage.ImageOutputRecord
import com.molagpt.app.core.storage.ImageRefRecord
import com.molagpt.app.core.storage.ImageRun
import com.molagpt.app.core.storage.ImageRunDraft
import com.molagpt.app.core.storage.ImageRunKind
import com.molagpt.app.core.storage.ImageTaskRepository
import com.molagpt.app.core.storage.ImageVersionStatus
import java.io.InterruptedIOException
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 画图任务的执行者，应用级单例（跟聊天的后台流管理同一个思路）。
 *
 * 任务不属于哪个页面：先落库一条 running 版本，再在应用作用域里发请求，结果按版本 id 写回。
 * 页面关掉、切到别的任务、新建任务都不影响在途的生成；删除任务才会取消它。
 */
class ImageTaskManager(
    appContext: Context,
    private val repository: ImageTaskRepository,
    private val providers: ByokProviderRepository,
    private val api: ByokImageApi,
    private val scope: CoroutineScope,
    private val dispatchers: DispatcherProvider,
) {
    /** 一个版本跑完（成功、失败或取消）。 */
    data class Completion(
        val taskId: String,
        val runId: String,
        val versionId: String,
        val status: ImageVersionStatus,
        val imageCount: Int,
        val title: String,
        val modelId: String,
    )

    /** 一次提交要用到的参考图：已有文件，或刚选的图（还没写盘）。 */
    sealed interface PendingRef {
        data class Existing(val src: String) : PendingRef
        /** 涂抹蒙版必须和底图同尺寸，所以涂抹过的结果图也按缩放后的这份另存，不引用原文件。 */
        class Fresh(
            val png: ByteArray,
            val maskPng: ByteArray?,
            val overlayPng: ByteArray?,
        ) : PendingRef
    }

    data class Submission(
        val taskId: String?,
        val providerId: String,
        val modelId: String,
        val mode: ImageMode,
        val prompt: String,
        val kind: ImageRunKind,
        val size: String,
        val count: Int,
        val params: com.molagpt.app.core.storage.ImageParams,
        val refs: List<PendingRef>,
    )

    data class Submitted(val taskId: String, val runId: String, val versionId: String)

    private val files = repository.files
    private val jobs = ConcurrentHashMap<String, Job>()

    /** 在跑的版本 → 所属任务。 */
    private val running = MutableStateFlow<Map<String, String>>(emptyMap())

    val runningTaskIds: StateFlow<Set<String>> = running
        .map { it.values.toSet() }
        .stateIn(scope, SharingStarted.Eagerly, emptySet())

    val activeCount: StateFlow<Int> = running
        .map { it.size }
        .stateIn(scope, SharingStarted.Eagerly, 0)

    val runningVersionIds: StateFlow<Map<String, String>> = running.asStateFlow()

    private val _completions = MutableSharedFlow<Completion>(extraBufferCapacity = 16)
    val completions: SharedFlow<Completion> = _completions.asSharedFlow()

    /**
     * 启动对账：旧版数据搬家、上次被杀时停在 running 的版本标失败、清掉主侧边栏删掉的任务残留。
     * 提交前都要先等它，否则刚落库的 running 会被当成残留标掉。
     */
    private val startup = scope.async {
        runCatching { importLegacy(appContext) }
            .onFailure { Logger.w(TAG, "legacy import failed: ${it.message}", it) }
        runCatching { repository.failInterrupted(emptyList()) }
        runCatching { repository.purgeOrphans() }
        runCatching { repository.sweepFiles() }
    }

    suspend fun submit(submission: Submission): Submitted = scope.async {
        startup.await()
        val taskId = submission.taskId?.takeIf { repository.exists(it) }
            ?: repository.createTask(submission.providerId, submission.modelId, submission.mode)
        val refs = withContext(dispatchers.io) { submission.refs.map { persistRef(taskId, it) } }
        val (runId, versionId) = repository.insertRun(
            taskId,
            ImageRunDraft(
                prompt = submission.prompt,
                kind = submission.kind,
                providerId = submission.providerId,
                modelId = submission.modelId,
                size = submission.size,
                count = submission.count,
                params = submission.params,
                refs = refs,
            ),
        )
        launch(versionId, taskId)
        Submitted(taskId, runId, versionId)
    }.await()

    /** 再次生成：同一轮追加一个版本，参数全用这一轮当时的。 */
    fun regenerate(runId: String) {
        scope.launch {
            startup.await()
            val versionId = repository.addVersion(runId) ?: return@launch
            val taskId = repository.versionTaskId(versionId) ?: return@launch
            launch(versionId, taskId)
        }
    }

    /** 重试失败或取消的版本。 */
    fun retry(versionId: String) {
        scope.launch {
            startup.await()
            if (jobs.containsKey(versionId)) return@launch
            if (!repository.restartVersion(versionId)) return@launch
            val taskId = repository.versionTaskId(versionId) ?: return@launch
            launch(versionId, taskId)
        }
    }

    fun cancel(versionId: String) {
        jobs[versionId]?.cancel()
    }

    fun cancelTask(taskId: String) {
        running.value.filterValues { it == taskId }.keys.forEach { cancel(it) }
    }

    fun isRunning(taskId: String): Boolean = taskId in runningTaskIds.value

    suspend fun deleteTask(taskId: String) {
        stopAndJoin(running.value.filterValues { it == taskId }.keys)
        repository.deleteTask(taskId)
    }

    suspend fun deleteAll() {
        stopAndJoin(running.value.keys)
        repository.deleteAll()
    }

    /** 主侧边栏删了画图任务之后调用：取消它们还在跑的生成，收掉记录和文件。 */
    fun onConversationsDeleted() {
        scope.launch {
            startup.await()
            val stillThere = running.value.values.toSet().filter { repository.exists(it) }.toSet()
            stopAndJoin(running.value.filterValues { it !in stillThere }.keys)
            repository.purgeOrphans()
            repository.sweepFiles()
        }
    }

    private suspend fun stopAndJoin(versionIds: Collection<String>) {
        val targets = versionIds.mapNotNull { jobs[it] }
        targets.forEach { it.cancel() }
        targets.joinAll()
    }

    private fun launch(versionId: String, taskId: String) {
        val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) { execute(versionId) }
        jobs[versionId] = job
        running.update { it + (versionId to taskId) }
        job.invokeOnCompletion {
            jobs.remove(versionId, job)
            running.update { it - versionId }
        }
        job.start()
    }

    private suspend fun execute(versionId: String) {
        val run = repository.runOf(versionId) ?: return
        val gate = ImageCallGate()
        try {
            val provider = resolveProvider(run)
            val attachments = withContext(dispatchers.io) { run.refs.mapNotNull(::loadAttachment) }
            if (run.kind.isEdit && attachments.isEmpty()) throw MolaApiException(400, "参考图已不可用")
            val canReason = looksLikeByokImageReasoningModel(run.modelId) &&
                provider.imageFormat == ByokImageFormat.OPENAI_CHAT_IMAGE
            val config = ByokImageWorkbenchConfig(
                size = run.size,
                n = run.count.coerceIn(1, MAX_COUNT),
                quality = run.params.quality,
                outputFormat = run.params.outputFormat,
                background = run.params.background,
                moderation = run.params.moderation,
                outputCompression = run.params.compression,
                timeoutSeconds = run.params.timeoutSeconds.coerceIn(10, 3600),
                batchMode = run.kind == ImageRunKind.PER_IMAGE,
                reasoning = run.params.reasoning && canReason,
                reasoningEffort = run.params.reasoningEffort,
            )
            val result = blocking(gate) {
                api.runWorkbench(provider, run.modelId, run.prompt, config, attachments, gate)
            }
            val outputs = blocking(gate) { result.hits.mapNotNull { persistHit(run.taskId, it, gate) } }
            // 结果已经落盘，收尾的写库不能再被取消打断，否则这一版会永远停在「生成中」。
            withContext(NonCancellable) {
                if (outputs.isEmpty()) {
                    repository.finishVersion(versionId, ImageVersionStatus.FAILED, error = "未识别到图片", raw = result.raw)
                } else {
                    repository.finishVersion(
                        versionId,
                        ImageVersionStatus.SUCCESS,
                        outputs = outputs,
                        note = if (result.usedFallback) "已使用备用接口" else null,
                    )
                }
                emit(run, versionId, if (outputs.isEmpty()) ImageVersionStatus.FAILED else ImageVersionStatus.SUCCESS, outputs.size)
            }
        } catch (e: Throwable) {
            // 停止时掐断连接，阻塞线程抛的是 IOException("Canceled")，作用域会把它而不是
            // CancellationException 抛上来，所以按「是否被取消」判断，而不是按异常类型。
            val canceled = e is CancellationException || gate.cancelled || !currentCoroutineContext().isActive
            withContext(NonCancellable) {
                if (canceled) {
                    repository.finishVersion(versionId, ImageVersionStatus.CANCELED)
                    emit(run, versionId, ImageVersionStatus.CANCELED, 0)
                } else {
                    Logger.w(TAG, "image run failed: ${e.message}", e)
                    repository.finishVersion(
                        versionId,
                        ImageVersionStatus.FAILED,
                        error = friendlyError(e),
                        raw = e.message?.takeIf { it.isNotBlank() },
                    )
                    emit(run, versionId, ImageVersionStatus.FAILED, 0)
                }
            }
            if (e is CancellationException) throw e
        }
    }

    private suspend fun emit(run: ImageRun, versionId: String, status: ImageVersionStatus, count: Int) {
        val title = runCatching { repository.title(run.taskId) }.getOrNull().orEmpty()
        _completions.tryEmit(Completion(run.taskId, run.id, versionId, status, count, title, run.modelId))
    }

    private suspend fun resolveProvider(run: ImageRun): ByokProvider {
        val provider = providers.get(run.providerId) ?: throw MolaApiException(400, "图像服务已被删除")
        if (!provider.enabled) throw MolaApiException(400, "图像服务已停用")
        if (provider.purpose != ByokPurpose.IMAGE) throw MolaApiException(400, "请选择图像用途的服务")
        if (provider.models.none { it.id == run.modelId && it.supportsImageGeneration }) {
            throw MolaApiException(400, "模型 ${run.modelId} 已不在该服务中")
        }
        return provider
    }

    /**
     * 阻塞调用放到 IO 线程上跑；协程被取消时掐断连接，让阻塞线程立刻退出，
     * 「停止」不用再等服务端把整张图返回。
     */
    private suspend fun <T> blocking(gate: ImageCallGate, block: () -> T): T = coroutineScope {
        val work = async(dispatchers.io) { block() }
        try {
            work.await()
        } catch (e: CancellationException) {
            gate.cancel()
            throw e
        }
    }

    private fun persistRef(taskId: String, ref: PendingRef): ImageRefRecord = when (ref) {
        is PendingRef.Existing -> ImageRefRecord(src = files.relativize(ref.src), fromOutput = true)
        is PendingRef.Fresh -> ImageRefRecord(
            src = files.write(taskId, ref.png, "png"),
            mask = ref.maskPng?.let { files.write(taskId, it, "png") },
            overlay = ref.overlayPng?.let { files.write(taskId, it, "png") },
        )
    }

    private fun loadAttachment(ref: ImageRefRecord): ByokImageAttachment? {
        val bytes = files.read(ref.src) ?: ref.src.takeIf { it.startsWith("http") }?.let { api.download(it) } ?: return null
        return ByokImageAttachment(
            fileName = "image.${extensionOf(bytes)}",
            mimeType = mimeOf(bytes),
            bytes = bytes,
            maskPngBytes = ref.mask?.let(files::read),
            maskedOverlayBytes = ref.overlay?.let(files::read),
        )
    }

    private fun persistHit(taskId: String, hit: ByokImageHit, gate: ImageCallGate): ImageOutputRecord? {
        val bytes = if (hit.isData) {
            runCatching { Base64.decode(hit.url.substringAfter(','), Base64.DEFAULT) }.getOrNull()
        } else {
            api.download(hit.url, gate)
        }
        if (bytes == null || bytes.isEmpty()) {
            // 下不下来的远程图至少把链接留下，界面照常尝试加载。
            return if (hit.isData) null else ImageOutputRecord(src = hit.url)
        }
        val src = files.write(taskId, bytes, extensionOf(bytes))
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        return ImageOutputRecord(src = src, width = bounds.outWidth.coerceAtLeast(0), height = bounds.outHeight.coerceAtLeast(0))
    }

    private suspend fun importLegacy(context: Context) {
        val prefs = context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(LEGACY_KEY, null) ?: return
        repository.importLegacy(raw)
        prefs.edit().remove(LEGACY_KEY).apply()
    }

    private fun friendlyError(e: Throwable): String = when (e) {
        is MolaApiException -> e.message?.lineSequence()?.firstOrNull { it.isNotBlank() } ?: "请求失败"
        is UnknownHostException -> "网络不可用，请检查连接"
        is InterruptedIOException -> "请求超时"
        else -> e.message?.lineSequence()?.firstOrNull { it.isNotBlank() } ?: "请求失败"
    }

    companion object {
        private const val TAG = "ImageTaskManager"
        const val MAX_COUNT = 4
        private const val LEGACY_PREFS = "matcha_image_workbench"
        private const val LEGACY_KEY = "sessions_v1"
    }
}

internal fun mimeOf(bytes: ByteArray): String = when (extensionOf(bytes)) {
    "jpg" -> "image/jpeg"
    "webp" -> "image/webp"
    "gif" -> "image/gif"
    else -> "image/png"
}

internal fun extensionOf(bytes: ByteArray): String = when {
    bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "jpg"
    bytes.size >= 12 && bytes[0] == 0x52.toByte() && bytes[8] == 0x57.toByte() -> "webp"
    bytes.size >= 3 && bytes[0] == 0x47.toByte() && bytes[1] == 0x49.toByte() && bytes[2] == 0x46.toByte() -> "gif"
    else -> "png"
}
