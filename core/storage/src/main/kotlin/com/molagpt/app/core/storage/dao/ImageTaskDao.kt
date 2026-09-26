package com.molagpt.app.core.storage.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.molagpt.app.core.storage.entity.ImageRunEntity
import com.molagpt.app.core.storage.entity.ImageTaskEntity
import com.molagpt.app.core.storage.entity.ImageVersionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ImageTaskDao {
    @Query("SELECT * FROM image_tasks WHERE taskId = :taskId")
    suspend fun getTask(taskId: String): ImageTaskEntity?

    @Query("SELECT * FROM image_tasks WHERE taskId = :taskId")
    fun observeTask(taskId: String): Flow<ImageTaskEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTask(entity: ImageTaskEntity)

    @Query("UPDATE image_tasks SET providerId = :providerId, modelId = :modelId WHERE taskId = :taskId")
    suspend fun updateModel(taskId: String, providerId: String, modelId: String)

    @Query("UPDATE image_tasks SET mode = :mode WHERE taskId = :taskId")
    suspend fun updateMode(taskId: String, mode: String)

    @Query("SELECT * FROM image_runs WHERE taskId = :taskId ORDER BY createdAt")
    fun observeRuns(taskId: String): Flow<List<ImageRunEntity>>

    @Query("SELECT * FROM image_runs WHERE id = :runId")
    suspend fun getRun(runId: String): ImageRunEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRun(entity: ImageRunEntity)

    @Query("UPDATE image_runs SET activeVersionId = :versionId WHERE id = :runId")
    suspend fun setActiveVersion(runId: String, versionId: String)

    @Query("SELECT * FROM image_versions WHERE taskId = :taskId ORDER BY createdAt")
    fun observeVersions(taskId: String): Flow<List<ImageVersionEntity>>

    @Query("SELECT * FROM image_versions WHERE id = :versionId")
    suspend fun getVersion(versionId: String): ImageVersionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertVersion(entity: ImageVersionEntity)

    /**
     * 进程被杀时还在跑的版本。请求随进程一起没了，重开后不能再显示「生成中」，
     * 标成失败，界面上给出重试。
     */
    @Query(
        "UPDATE image_versions SET status = 'failed', endedAt = :now, error = :reason " +
            "WHERE status = 'running' AND id NOT IN (:liveIds)",
    )
    suspend fun failOrphanRunning(now: Long, reason: String, liveIds: List<String>)

    /** 抽屉列表：任务 + 最近一张成功的图做缩略图。可见性、排序与主侧边栏同一套规则。 */
    @Query(
        """
        SELECT c.sessionId AS taskId,
               c.title AS title,
               c.updatedAt AS updatedAt,
               c.pinned AS pinned,
               t.providerId AS providerId,
               t.modelId AS modelId,
               (SELECT COUNT(*) FROM image_runs AS r WHERE r.taskId = c.sessionId) AS runCount,
               (
                   SELECT v.outputsJson FROM image_versions AS v
                   WHERE v.taskId = c.sessionId AND v.status = 'success'
                   ORDER BY v.endedAt DESC
                   LIMIT 1
               ) AS latestOutputsJson
        FROM conversations AS c
        INNER JOIN image_tasks AS t ON t.taskId = c.sessionId
        WHERE c.deletedAt IS NULL
          AND c.visibleInList = 1
        ORDER BY c.pinned DESC, c.updatedAt DESC
        """,
    )
    fun observeTaskRows(): Flow<List<ImageTaskRow>>

    /** 画廊：所有仍存在的任务里成功版本的产出，新的在前。 */
    @Query(
        """
        SELECT v.id AS versionId, v.runId AS runId, v.taskId AS taskId, v.outputsJson AS outputsJson,
               COALESCE(v.endedAt, v.createdAt) AS finishedAt
        FROM image_versions AS v
        INNER JOIN conversations AS c ON c.sessionId = v.taskId
        WHERE v.status = 'success' AND c.deletedAt IS NULL
        ORDER BY finishedAt DESC
        """,
    )
    fun observeGalleryRows(): Flow<List<ImageGalleryRow>>

    @Query("SELECT refsJson FROM image_runs")
    suspend fun allRefsJson(): List<String>

    @Query("SELECT outputsJson FROM image_versions")
    suspend fun allOutputsJson(): List<String>

    @Query("SELECT refsJson FROM image_runs WHERE taskId = :taskId")
    suspend fun refsJsonOf(taskId: String): List<String>

    @Query("SELECT outputsJson FROM image_versions WHERE taskId = :taskId")
    suspend fun outputsJsonOf(taskId: String): List<String>

    /** 会话行已经不在了的任务（从主侧边栏删掉的）。 */
    @Query("SELECT taskId FROM image_tasks WHERE taskId NOT IN (SELECT sessionId FROM conversations WHERE deletedAt IS NULL)")
    suspend fun orphanTaskIds(): List<String>

    @Query("SELECT taskId FROM image_tasks")
    suspend fun allTaskIds(): List<String>

    @Query("DELETE FROM image_versions WHERE taskId = :taskId")
    suspend fun deleteVersionsOf(taskId: String)

    @Query("DELETE FROM image_runs WHERE taskId = :taskId")
    suspend fun deleteRunsOf(taskId: String)

    @Query("DELETE FROM image_tasks WHERE taskId = :taskId")
    suspend fun deleteTask(taskId: String)

    // —— 会话表上那一行 ——

    @Query("UPDATE conversations SET updatedAt = :now, visibleInList = 1, model = :modelId WHERE sessionId = :taskId")
    suspend fun touchConversation(taskId: String, modelId: String, now: Long)

    @Query("UPDATE conversations SET title = :title WHERE sessionId = :taskId")
    suspend fun setConversationTitle(taskId: String, title: String)

    @Query("DELETE FROM conversations WHERE sessionId = :taskId")
    suspend fun deleteConversation(taskId: String)
}

data class ImageTaskRow(
    val taskId: String,
    val title: String,
    val updatedAt: Long,
    val pinned: Boolean,
    val providerId: String,
    val modelId: String,
    val runCount: Int,
    val latestOutputsJson: String?,
)

data class ImageGalleryRow(
    val versionId: String,
    val runId: String,
    val taskId: String,
    val outputsJson: String,
    val finishedAt: Long,
)
