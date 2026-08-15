package com.molagpt.app.core.model

import kotlinx.serialization.Serializable

/** 服务器侧文件信息（上传结果 / 生成产物）。 */
@Serializable
data class FileInfo(
    val id: String,
    val name: String,
    val mimeType: String? = null,
    val sizeBytes: Long? = null,
    val url: String? = null,
    val localPath: String? = null,
    val sandboxPath: String? = null,
    val uploadStatus: UploadStatus = UploadStatus.PENDING,
)

enum class UploadStatus {
    PENDING,
    UPLOADING,
    UPLOADED,
    FAILED,

    /** 托管副本读不到了（用户清了 App 数据 / 老会话只存过 content:// URI）。 */
    MISSING,
}

/** 用户发送时携带的附件（图片/文档）。content builder 据此构造多模态 content。 */
@Serializable
data class Attachment(
    val id: String,
    val name: String,
    val mimeType: String,
    /**
     * 托管副本在 filesDir 下的**相对**路径（如 `attachments/<uuid>.png`）。
     * BYOK 附件的唯一真相源——存相对路径而非绝对路径，App 数据目录整体迁移后仍能解析。
     */
    val localPath: String? = null,
    val remoteUrl: String? = null,
    val sandboxPath: String? = null,
    val label: String? = null,
    val thumbnailUrl: String? = null,
    val sizeBytes: Long? = null,
    /**
     * 托管副本丢失/不可读。请求里跳过该附件，但**保留条目**——用户在气泡上能看到
     * 「附件不可用」，模型也会收到一条说明，而不是附件凭空消失。
     */
    val unavailable: Boolean = false,
)
