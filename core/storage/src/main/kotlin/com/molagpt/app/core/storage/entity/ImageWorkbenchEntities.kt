package com.molagpt.app.core.storage.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 画图任务的工作台状态。标题、排序、置顶、软删都在会话表里同 id 的那一行上，
 * 这里只放会话表装不下的：当前选的图像服务 / 模型和对话模式。
 */
@Entity(tableName = "image_tasks")
data class ImageTaskEntity(
    @PrimaryKey val taskId: String,
    val providerId: String,
    val modelId: String,
    /** `chat` 续改上一张 / `gen` 每次生成新图。 */
    val mode: String,
    val createdAt: Long,
)

/**
 * 一轮：用户的一次提交。发送时先落库再发请求，结果按 id 回写，
 * 不依赖界面上当时打开的是哪个任务。
 *
 * 模型、尺寸、参数、参考图都记在轮上，「再次生成」照原样重跑，不受当前输入区设置影响。
 */
@Entity(tableName = "image_runs", indices = [Index("taskId")])
data class ImageRunEntity(
    @PrimaryKey val id: String,
    val taskId: String,
    val prompt: String,
    /** new / chain / base / refs / per_image，见 ImageRunKind。 */
    val kind: String,
    val providerId: String,
    val modelId: String,
    val size: String,
    val count: Int,
    val paramsJson: String,
    val refsJson: String,
    /** 当前展示的版本；null 取最新一版。 */
    val activeVersionId: String?,
    val createdAt: Long,
)

/**
 * 一轮下的一个版本。「再次生成」追加版本，「重试」原地重跑失败的那个版本。
 * 失败和取消也留一条，界面上照样是一张卡。
 */
@Entity(tableName = "image_versions", indices = [Index("runId"), Index("taskId")])
data class ImageVersionEntity(
    @PrimaryKey val id: String,
    val runId: String,
    val taskId: String,
    /** running / success / failed / canceled。 */
    val status: String,
    val startedAt: Long,
    val endedAt: Long?,
    val outputsJson: String,
    val error: String?,
    /** 仅失败或未识别到图片时保留的响应摘要，供排查。 */
    val raw: String?,
    /** 附加说明，如走了次选路径。 */
    val note: String?,
    val createdAt: Long,
)
