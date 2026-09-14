package com.molagpt.app.core.storage.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 角色表。仅 BYOK 模型使用；镜像桌面端 `personas` 表字段，便于未来云同步对齐。
 * 软删（[deletedAt] 非空），内置角色（[isBuiltin]）不可删除。
 */
@Entity(
    tableName = "personas",
    indices = [Index(value = ["deletedAt", "pinned", "sortOrder"])],
)
data class PersonaEntity(
    @PrimaryKey val id: String,
    val name: String,
    /** 图标 key（PersonaIcons.resolve）。 */
    val icon: String? = null,
    val systemPrompt: String = "",
    val defaultEnableNetwork: Boolean? = null,
    val defaultEnableWebFetch: Boolean? = null,
    val defaultThinking: Boolean? = null,
    val defaultReasoningEffort: String? = null,
    val sortOrder: Int = 0,
    val pinned: Boolean = false,
    val isBuiltin: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
    /** 软删墓碑（毫秒）；内置角色不会被打墓碑。 */
    val deletedAt: Long? = null,
    /**
     * 角色卡资料（[com.molagpt.app.core.model.PersonaProfile] 的 JSON）。null = 这就是个普通助手。
     *
     * 整块存而不是摊成三十个列：卡片规范还在变，字段多且大多只在提示词组装时一次性读完，
     * 摊开只会换来一串没人查询的列和无穷无尽的迁移。
     */
    val profileJson: String? = null,
    /** 卡片头像在托管目录里的相对路径；没有就用 [icon] 的矢量图标。 */
    val avatarPath: String? = null,
)
