package com.molagpt.app.core.storage

import kotlinx.serialization.Serializable

/** 对话模式续改上一张；生成模式每次都出新图，可一次多张。 */
enum class ImageMode(val key: String) {
    CHAT("chat"),
    GENERATE("gen"),
    ;

    companion object {
        fun of(key: String?): ImageMode = entries.firstOrNull { it.key == key } ?: CHAT
    }
}

/** 这一轮拿什么当输入。决定请求走生成还是编辑，也决定界面上怎么称呼它。 */
enum class ImageRunKind(val key: String) {
    /** 纯文字生成新图。 */
    NEW("new"),

    /** 对话模式下拿上一轮的结果接着改。 */
    CHAIN("chain"),

    /** 用户给了一张底图。 */
    BASE("base"),

    /** 多张参考图合成一次请求。 */
    REFS("refs"),

    /** 多张图各编辑一次，每张出一张结果。 */
    PER_IMAGE("per_image"),
    ;

    val isEdit: Boolean get() = this != NEW

    companion object {
        fun of(key: String?): ImageRunKind = entries.firstOrNull { it.key == key } ?: NEW
    }
}

enum class ImageVersionStatus(val key: String) {
    RUNNING("running"),
    SUCCESS("success"),
    FAILED("failed"),
    CANCELED("canceled"),
    ;

    companion object {
        fun of(key: String?): ImageVersionStatus = entries.firstOrNull { it.key == key } ?: FAILED
    }
}

/**
 * 一张输入图。[src] 是相对工作台目录的路径（或旧数据里的完整 URL）。
 * 涂抹过的底图额外带两份文件：[mask] 给 images/edits，[overlay] 给只认图片的对话式接口。
 */
@Serializable
data class ImageRefRecord(
    val src: String,
    val mask: String? = null,
    val overlay: String? = null,
    /** 取自本任务之前的结果（续改、以此为底图），不是用户另外选的图。 */
    val fromOutput: Boolean = false,
)

@Serializable
data class ImageOutputRecord(
    val src: String,
    val width: Int = 0,
    val height: Int = 0,
)

/** 与接口直接对应的生成参数。随轮保存，「再次生成」原样复用。 */
@Serializable
data class ImageParams(
    val quality: String = "auto",
    val outputFormat: String = "png",
    val background: String = "auto",
    val moderation: String = "auto",
    val compression: Int = 80,
    val reasoning: Boolean = false,
    val reasoningEffort: String = "medium",
    val timeoutSeconds: Int = 600,
)

data class ImageVersion(
    val id: String,
    val runId: String,
    val status: ImageVersionStatus,
    val startedAt: Long,
    val endedAt: Long?,
    val outputs: List<ImageOutputRecord>,
    val error: String?,
    val raw: String?,
    val note: String?,
)

data class ImageRun(
    val id: String,
    val taskId: String,
    val prompt: String,
    val kind: ImageRunKind,
    val providerId: String,
    val modelId: String,
    val size: String,
    val count: Int,
    val params: ImageParams,
    val refs: List<ImageRefRecord>,
    val versions: List<ImageVersion>,
    val activeVersionId: String?,
    val createdAt: Long,
) {
    val activeIndex: Int
        get() = versions.indexOfFirst { it.id == activeVersionId }.takeIf { it >= 0 } ?: versions.lastIndex

    val activeVersion: ImageVersion? get() = versions.getOrNull(activeIndex)
}

data class ImageTask(
    val taskId: String,
    val title: String,
    val providerId: String,
    val modelId: String,
    val mode: ImageMode,
    val runs: List<ImageRun>,
) {
    /** 续改的起点：最近一轮当前版本里的第一张成功结果。 */
    val chainHead: ImageOutputRecord?
        get() = runs.asReversed().firstNotNullOfOrNull { run ->
            run.activeVersion?.takeIf { it.status == ImageVersionStatus.SUCCESS }?.outputs?.firstOrNull()
        }
}

data class ImageTaskSummary(
    val taskId: String,
    val title: String,
    val providerId: String,
    val modelId: String,
    val runCount: Int,
    val thumbnail: String?,
    val updatedAt: Long,
    val pinned: Boolean,
)

data class GalleryImage(
    val taskId: String,
    val runId: String,
    val versionId: String,
    val output: ImageOutputRecord,
    val finishedAt: Long,
)

/** 发送前在输入区整理好的一轮。 */
data class ImageRunDraft(
    val prompt: String,
    val kind: ImageRunKind,
    val providerId: String,
    val modelId: String,
    val size: String,
    val count: Int,
    val params: ImageParams,
    val refs: List<ImageRefRecord>,
)
