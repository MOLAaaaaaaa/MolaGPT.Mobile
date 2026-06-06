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

enum class UploadStatus { PENDING, UPLOADING, UPLOADED, FAILED }

/** 用户发送时携带的附件（图片/文档）。content builder 据此构造多模态 content。 */
@Serializable
data class Attachment(
    val id: String,
    val name: String,
    val mimeType: String,
    val localUri: String? = null,
    val remoteUrl: String? = null,
    val sandboxPath: String? = null,
    val label: String? = null,
    val thumbnailUrl: String? = null,
    val sizeBytes: Long? = null,
)
