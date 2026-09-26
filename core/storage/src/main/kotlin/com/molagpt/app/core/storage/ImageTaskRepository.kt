package com.molagpt.app.core.storage

import androidx.room.withTransaction
import com.molagpt.app.core.common.DispatcherProvider
import com.molagpt.app.core.model.ProviderIds
import com.molagpt.app.core.model.ProviderKind
import com.molagpt.app.core.storage.dao.ImageTaskRow
import com.molagpt.app.core.storage.entity.ConversationEntity
import com.molagpt.app.core.storage.entity.ImageRunEntity
import com.molagpt.app.core.storage.entity.ImageTaskEntity
import com.molagpt.app.core.storage.entity.ImageVersionEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * 画图任务的读写。任务在会话表里占一行（侧边栏、搜索、置顶、重命名都复用那一套），
 * 其余状态在 image_* 三张表。所有写入都按 id 定位，和界面当前开着哪个任务无关。
 */
class ImageTaskRepository(
    private val database: MolaDatabase,
    val files: ImageFileStore,
    private val dispatchers: DispatcherProvider,
) {
    private val dao = database.imageTaskDao()
    private val conversationDao = database.conversationDao()

    fun observeTask(taskId: String): Flow<ImageTask?> = combine(
        conversationDao.observeById(taskId),
        dao.observeTask(taskId),
        dao.observeRuns(taskId),
        dao.observeVersions(taskId),
    ) { conversation, task, runs, versions ->
        if (conversation == null || conversation.deletedAt != null || task == null) return@combine null
        val byRun = versions.groupBy { it.runId }
        ImageTask(
            taskId = taskId,
            title = conversation.title,
            providerId = task.providerId,
            modelId = task.modelId,
            mode = ImageMode.of(task.mode),
            runs = runs.map { it.toDomain(byRun[it.id].orEmpty()) },
        )
    }.distinctUntilChanged().flowOn(dispatchers.io)

    fun observeSummaries(): Flow<List<ImageTaskSummary>> = dao.observeTaskRows()
        .map { rows -> rows.map { it.toSummary() } }
        .flowOn(dispatchers.io)

    fun observeGallery(): Flow<List<GalleryImage>> = dao.observeGalleryRows()
        .map { rows ->
            rows.flatMap { row ->
                decodeOutputs(row.outputsJson).map { output ->
                    GalleryImage(row.taskId, row.runId, row.versionId, output, row.finishedAt)
                }
            }
        }
        .flowOn(dispatchers.io)

    suspend fun title(taskId: String): String? = withContext(dispatchers.io) {
        conversationDao.getById(taskId)?.title
    }

    suspend fun exists(taskId: String): Boolean = withContext(dispatchers.io) {
        dao.getTask(taskId) != null && conversationDao.getById(taskId)?.deletedAt == null
    }

    /** 建任务。会话行先不进侧边栏，第一轮落库时才显示——和空白聊天一样。 */
    suspend fun createTask(providerId: String, modelId: String, mode: ImageMode): String =
        withContext(dispatchers.io) {
            val taskId = UUID.randomUUID().toString()
            val now = System.currentTimeMillis()
            database.withTransaction {
                conversationDao.upsert(imageConversation(taskId, DEFAULT_TITLE, modelId, now, now, visible = false))
                dao.upsertTask(ImageTaskEntity(taskId, providerId, modelId, mode.key, now))
            }
            taskId
        }

    suspend fun updateModel(taskId: String, providerId: String, modelId: String) = withContext(dispatchers.io) {
        dao.updateModel(taskId, providerId, modelId)
    }

    suspend fun updateMode(taskId: String, mode: ImageMode) = withContext(dispatchers.io) {
        dao.updateMode(taskId, mode.key)
    }

    /** 先落一轮和它的第一个版本（状态 running），返回 (runId, versionId)。 */
    suspend fun insertRun(taskId: String, draft: ImageRunDraft): Pair<String, String> = withContext(dispatchers.io) {
        val now = System.currentTimeMillis()
        val runId = UUID.randomUUID().toString()
        val versionId = UUID.randomUUID().toString()
        database.withTransaction {
            val isFirst = dao.refsJsonOf(taskId).isEmpty()
            dao.insertRun(
                ImageRunEntity(
                    id = runId,
                    taskId = taskId,
                    prompt = draft.prompt,
                    kind = draft.kind.key,
                    providerId = draft.providerId,
                    modelId = draft.modelId,
                    size = draft.size,
                    count = draft.count,
                    paramsJson = json.encodeToString(ImageParams.serializer(), draft.params),
                    refsJson = json.encodeToString(refListSerializer, draft.refs),
                    activeVersionId = versionId,
                    createdAt = now,
                ),
            )
            dao.upsertVersion(runningVersion(versionId, runId, taskId, now))
            if (isFirst) dao.setConversationTitle(taskId, titleFrom(draft.prompt))
            dao.touchConversation(taskId, draft.modelId, now)
        }
        runId to versionId
    }

    /** 「再次生成」：同一轮追加一个版本并切过去。 */
    suspend fun addVersion(runId: String): String? = withContext(dispatchers.io) {
        val run = dao.getRun(runId) ?: return@withContext null
        val now = System.currentTimeMillis()
        val versionId = UUID.randomUUID().toString()
        database.withTransaction {
            dao.upsertVersion(runningVersion(versionId, runId, run.taskId, now))
            dao.setActiveVersion(runId, versionId)
            dao.touchConversation(run.taskId, run.modelId, now)
        }
        versionId
    }

    /** 「重试」：失败或取消的版本原地重跑，不多出一个版本。 */
    suspend fun restartVersion(versionId: String): Boolean = withContext(dispatchers.io) {
        val version = dao.getVersion(versionId) ?: return@withContext false
        if (version.status == ImageVersionStatus.RUNNING.key) return@withContext false
        val now = System.currentTimeMillis()
        dao.upsertVersion(
            version.copy(
                status = ImageVersionStatus.RUNNING.key,
                startedAt = now,
                endedAt = null,
                outputsJson = "[]",
                error = null,
                raw = null,
                note = null,
            ),
        )
        dao.getRun(version.runId)?.let { dao.touchConversation(it.taskId, it.modelId, now) }
        true
    }

    suspend fun finishVersion(
        versionId: String,
        status: ImageVersionStatus,
        outputs: List<ImageOutputRecord> = emptyList(),
        error: String? = null,
        raw: String? = null,
        note: String? = null,
    ) = withContext(dispatchers.io) {
        val version = dao.getVersion(versionId) ?: return@withContext
        dao.upsertVersion(
            version.copy(
                status = status.key,
                endedAt = System.currentTimeMillis(),
                outputsJson = json.encodeToString(outputListSerializer, outputs),
                error = error,
                raw = raw?.take(RAW_LIMIT),
                note = note,
            ),
        )
    }

    suspend fun setActiveVersion(runId: String, versionId: String) = withContext(dispatchers.io) {
        dao.setActiveVersion(runId, versionId)
    }

    /** 重跑一个版本要用的全部输入。 */
    suspend fun runOf(versionId: String): ImageRun? = withContext(dispatchers.io) {
        val version = dao.getVersion(versionId) ?: return@withContext null
        dao.getRun(version.runId)?.toDomain(listOf(version))
    }

    suspend fun versionTaskId(versionId: String): String? = withContext(dispatchers.io) {
        dao.getVersion(versionId)?.taskId
    }

    suspend fun deleteTask(taskId: String) = withContext(dispatchers.io) {
        database.withTransaction {
            dao.deleteVersionsOf(taskId)
            dao.deleteRunsOf(taskId)
            dao.deleteTask(taskId)
            dao.deleteConversation(taskId)
        }
        files.deleteTaskDir(taskId)
    }

    suspend fun deleteAll(): List<String> = withContext(dispatchers.io) {
        val ids = dao.allTaskIds()
        database.withTransaction {
            ids.forEach { id ->
                dao.deleteVersionsOf(id)
                dao.deleteRunsOf(id)
                dao.deleteTask(id)
                dao.deleteConversation(id)
            }
        }
        files.deleteAll()
        ids
    }

    /** 从主侧边栏删掉的任务只删了会话行，这里把其余记录和文件一并收掉。返回被收掉的任务 id。 */
    suspend fun purgeOrphans(): List<String> = withContext(dispatchers.io) {
        val orphans = dao.orphanTaskIds()
        orphans.forEach { id ->
            database.withTransaction {
                dao.deleteVersionsOf(id)
                dao.deleteRunsOf(id)
                dao.deleteTask(id)
            }
            files.deleteTaskDir(id)
        }
        orphans
    }

    suspend fun failInterrupted(liveVersionIds: Collection<String>) = withContext(dispatchers.io) {
        dao.failOrphanRunning(System.currentTimeMillis(), INTERRUPTED, liveVersionIds.toList())
    }

    suspend fun sweepFiles() = withContext(dispatchers.io) {
        val referenced = HashSet<String>()
        dao.allRefsJson().forEach { raw ->
            decodeRefs(raw).forEach { ref ->
                referenced += ref.src
                ref.mask?.let(referenced::add)
                ref.overlay?.let(referenced::add)
            }
        }
        dao.allOutputsJson().forEach { raw -> decodeOutputs(raw).forEach { referenced += it.src } }
        files.sweep(referenced)
    }

    /**
     * 旧版把所有会话存成 SharedPreferences 里的一段 JSON。只搬有内容的会话；已经存在的 id 跳过，
     * 所以重复调用是安全的。返回搬过来的任务数。
     */
    suspend fun importLegacy(raw: String): Int = withContext(dispatchers.io) {
        val sessions = runCatching { json.decodeFromString(LegacyFile.serializer(), raw).sessions }
            .getOrDefault(emptyList())
        var imported = 0
        sessions.filter { it.records.isNotEmpty() }.forEach { session ->
            if (dao.getTask(session.id) != null || conversationDao.getById(session.id) != null) return@forEach
            val base = session.updatedAt - session.records.size
            database.withTransaction {
                conversationDao.upsert(
                    imageConversation(
                        taskId = session.id,
                        title = session.title.ifBlank { DEFAULT_TITLE },
                        modelId = session.modelId,
                        createdAt = base,
                        updatedAt = session.updatedAt,
                        visible = true,
                    ),
                )
                dao.upsertTask(ImageTaskEntity(session.id, session.providerId, session.modelId, ImageMode.CHAT.key, base))
                session.records.forEachIndexed { index, record ->
                    val at = base + index
                    val runId = UUID.randomUUID().toString()
                    val versionId = UUID.randomUUID().toString()
                    val refs = record.refs.map { ImageRefRecord(src = files.relativize(it.path), fromOutput = it.isContext) }
                    val kind = when {
                        refs.isEmpty() -> ImageRunKind.NEW
                        refs.any { it.fromOutput } -> ImageRunKind.CHAIN
                        refs.size == 1 -> ImageRunKind.BASE
                        else -> ImageRunKind.REFS
                    }
                    val outputs = record.hits.map { ImageOutputRecord(src = files.relativize(it.path)) }
                    dao.insertRun(
                        ImageRunEntity(
                            id = runId,
                            taskId = session.id,
                            prompt = record.prompt,
                            kind = kind.key,
                            providerId = session.providerId,
                            modelId = record.model,
                            size = record.size,
                            count = record.requestCount,
                            paramsJson = json.encodeToString(ImageParams.serializer(), ImageParams()),
                            refsJson = json.encodeToString(refListSerializer, refs),
                            activeVersionId = versionId,
                            createdAt = at,
                        ),
                    )
                    dao.upsertVersion(
                        ImageVersionEntity(
                            id = versionId,
                            runId = runId,
                            taskId = session.id,
                            status = if (outputs.isEmpty()) ImageVersionStatus.FAILED.key else ImageVersionStatus.SUCCESS.key,
                            startedAt = at,
                            endedAt = at,
                            outputsJson = json.encodeToString(outputListSerializer, outputs),
                            error = if (outputs.isEmpty()) "未识别到图片" else null,
                            raw = null,
                            note = null,
                            createdAt = at,
                        ),
                    )
                }
            }
            imported++
        }
        imported
    }

    private fun imageConversation(
        taskId: String,
        title: String,
        modelId: String,
        createdAt: Long,
        updatedAt: Long,
        visible: Boolean,
    ) = ConversationEntity(
        sessionId = taskId,
        title = title,
        model = modelId,
        providerId = ProviderIds.IMAGE_WORKBENCH,
        // 会话表的来源只有两种；画图任务本地存储、不进云同步，归在 BYOK 这一侧。
        providerKind = ProviderKind.BYOK.name,
        createdAt = createdAt,
        updatedAt = updatedAt,
        visibleInList = visible,
    )

    private fun runningVersion(id: String, runId: String, taskId: String, now: Long) = ImageVersionEntity(
        id = id,
        runId = runId,
        taskId = taskId,
        status = ImageVersionStatus.RUNNING.key,
        startedAt = now,
        endedAt = null,
        outputsJson = "[]",
        error = null,
        raw = null,
        note = null,
        createdAt = now,
    )

    private fun ImageRunEntity.toDomain(versions: List<ImageVersionEntity>) = ImageRun(
        id = id,
        taskId = taskId,
        prompt = prompt,
        kind = ImageRunKind.of(kind),
        providerId = providerId,
        modelId = modelId,
        size = size,
        count = count,
        params = runCatching { json.decodeFromString(ImageParams.serializer(), paramsJson) }.getOrDefault(ImageParams()),
        refs = decodeRefs(refsJson),
        versions = versions.map { it.toDomain() },
        activeVersionId = activeVersionId,
        createdAt = createdAt,
    )

    private fun ImageVersionEntity.toDomain() = ImageVersion(
        id = id,
        runId = runId,
        status = ImageVersionStatus.of(status),
        startedAt = startedAt,
        endedAt = endedAt,
        outputs = decodeOutputs(outputsJson),
        error = error,
        raw = raw,
        note = note,
    )

    private fun ImageTaskRow.toSummary() = ImageTaskSummary(
        taskId = taskId,
        title = title,
        providerId = providerId,
        modelId = modelId,
        runCount = runCount,
        thumbnail = latestOutputsJson?.let(::decodeOutputs)?.firstOrNull()?.let { files.url(it.src) },
        updatedAt = updatedAt,
        pinned = pinned,
    )

    private fun decodeRefs(raw: String): List<ImageRefRecord> =
        runCatching { json.decodeFromString(refListSerializer, raw) }.getOrDefault(emptyList())

    private fun decodeOutputs(raw: String): List<ImageOutputRecord> =
        runCatching { json.decodeFromString(outputListSerializer, raw) }.getOrDefault(emptyList())

    companion object {
        const val DEFAULT_TITLE = "新画图任务"
        const val INTERRUPTED = "应用被关闭，生成已中断"
        private const val RAW_LIMIT = 16_000
        private const val TITLE_LIMIT = 24

        private val json = Json { ignoreUnknownKeys = true }
        private val refListSerializer = ListSerializer(ImageRefRecord.serializer())
        private val outputListSerializer = ListSerializer(ImageOutputRecord.serializer())

        fun titleFrom(prompt: String): String {
            val line = prompt.trim().lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
            if (line.isEmpty()) return DEFAULT_TITLE
            return if (line.length > TITLE_LIMIT) line.take(TITLE_LIMIT) + "…" else line
        }
    }
}

@Serializable
private data class LegacyRef(val path: String, val hasMask: Boolean = false, val isContext: Boolean = false)

@Serializable
private data class LegacyHit(val path: String, val label: String = "")

@Serializable
private data class LegacyRecord(
    val id: Long,
    val prompt: String,
    val refs: List<LegacyRef> = emptyList(),
    val hits: List<LegacyHit> = emptyList(),
    val model: String,
    val size: String,
    val requestCount: Int = 1,
)

@Serializable
private data class LegacySession(
    val id: String,
    val title: String,
    val providerId: String,
    val modelId: String,
    val updatedAt: Long,
    val records: List<LegacyRecord> = emptyList(),
)

@Serializable
private data class LegacyFile(val sessions: List<LegacySession> = emptyList())
