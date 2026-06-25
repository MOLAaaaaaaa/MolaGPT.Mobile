package com.molagpt.app.core.storage

import com.molagpt.app.core.common.DispatcherProvider
import com.molagpt.app.core.model.Persona
import com.molagpt.app.core.storage.dao.PersonaDao
import com.molagpt.app.core.storage.entity.PersonaEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * 角色（Persona）本地仓库：CRUD + 内置角色幂等播种。仅 BYOK 链路消费。
 * 镜像桌面端 PersonaRepository / PersonaListViewModel 的职责，但落到 Room。
 */
class PersonaRepository(
    private val personaDao: PersonaDao,
    private val dispatchers: DispatcherProvider,
) {
    /** 活跃角色流（置顶优先、按 sortOrder/名称）。 */
    fun observeAll(): Flow<List<Persona>> = personaDao.observeActive().map { rows -> rows.map { it.toDomain() } }

    suspend fun list(): List<Persona> =
        withContext(dispatchers.io) { personaDao.listActive().map { it.toDomain() } }

    suspend fun get(id: String): Persona? =
        withContext(dispatchers.io) { personaDao.getById(id)?.toDomain() }

    /** 新建 / 编辑：写入并刷新 updatedAt。 */
    suspend fun save(persona: Persona): Persona = withContext(dispatchers.io) {
        val now = System.currentTimeMillis()
        val toSave = persona.copy(updatedAt = now, createdAt = if (persona.createdAt == 0L) now else persona.createdAt)
        personaDao.upsert(toSave.toEntity())
        toSave
    }

    /** 复制为一个可自由编辑的用户副本（内置角色也可复制）。 */
    suspend fun duplicate(source: Persona): Persona = withContext(dispatchers.io) {
        val now = System.currentTimeMillis()
        val maxOrder = personaDao.listActive().maxOfOrNull { it.sortOrder } ?: 0
        val copy = source.copy(
            id = newId(),
            name = source.name + " 副本",
            isBuiltin = false,
            pinned = false,
            sortOrder = maxOrder + 1,
            createdAt = now,
            updatedAt = now,
        )
        personaDao.upsert(copy.toEntity())
        copy
    }

    /** 软删（内置角色由 DAO 层 isBuiltin=0 守卫，不会被删）。 */
    suspend fun delete(id: String) = withContext(dispatchers.io) {
        personaDao.softDelete(id, System.currentTimeMillis())
    }

    /** 一个空白草稿（未落库），供「新建角色」编辑页初始化。名称留空，由编辑页占位符引导填写。 */
    fun blankDraft(existingCount: Int): Persona {
        val now = System.currentTimeMillis()
        return Persona(
            id = newId(),
            name = "",
            icon = "idea",
            systemPrompt = "",
            sortOrder = existingCount,
            createdAt = now,
            updatedAt = now,
        )
    }

    /**
     * 由某个角色生成一份「未落库」的可编辑副本草稿，供「复制为副本并编辑」用：
     * 编辑页点保存才写库，取消则不留痕（区别于 [duplicate] 的即时落库）。
     */
    fun draftCopy(source: Persona, existingCount: Int): Persona = source.copy(
        id = newId(),
        name = source.name + " 副本",
        isBuiltin = false,
        pinned = false,
        sortOrder = existingCount,
        createdAt = 0L,
        updatedAt = 0L,
    )

    /** 首次播种内置角色；幂等：表非空则跳过。App 启动时调用。 */
    suspend fun ensureSeeded() = withContext(dispatchers.io) {
        if (personaDao.count() == 0) {
            personaDao.insertAll(builtinSeeds(System.currentTimeMillis()))
        }
    }

    private fun newId(): String = "persona-" + UUID.randomUUID().toString().replace("-", "")

    companion object {
        // 内置角色文案对齐桌面端，图标用 PersonaIcons 的 key（非 emoji）。
        private fun builtinSeeds(now: Long): List<PersonaEntity> = listOf(
            PersonaEntity(
                id = Persona.BUILTIN_DEFAULT_ID,
                name = "通用助手",
                icon = "assistant",
                systemPrompt = "你是 MolaGPT 的默认助手。请用简洁、准确、友好的中文回答用户。\n\n" +
                    "当前背景：\n- 日期：{{date}}\n- 时间：{{time}}\n- 用户：{{username}}\n" +
                    "- 当前模型：{{model}}\n- 服务商：{{provider}}\n\n" +
                    "如果问题信息不足，先提出必要的澄清问题；如果可以直接解决，就给出清晰可执行的答案。",
                isBuiltin = true,
                pinned = true,
                sortOrder = 0,
                createdAt = now,
                updatedAt = now,
            ),
            PersonaEntity(
                id = "builtin-writer",
                name = "写作助手",
                icon = "write",
                systemPrompt = "你是一位资深的中文写作助手，擅长结构化表达、精准用词与节奏控制。\n\n" +
                    "请遵循以下原则：\n- 主动询问写作目的、读者与篇幅约束；\n" +
                    "- 修改建议给出具体替换文本，不止于评论；\n" +
                    "- 避免空话和套话，倾向用具体例子说明观点；\n- 输出时使用 Markdown，长文加分级标题。",
                isBuiltin = true,
                sortOrder = 1,
                createdAt = now,
                updatedAt = now,
            ),
            PersonaEntity(
                id = "builtin-coder",
                name = "代码助手",
                icon = "code",
                systemPrompt = "你是一位经验丰富的软件工程师，回答需具备工程师的严谨与坦诚。\n\n" +
                    "请遵循：\n- 先理解需求与现状，再给方案；如信息不足，先反问；\n" +
                    "- 给代码必给可运行的最小示例，并标注语言；\n" +
                    "- 指出潜在边界条件、错误处理与性能注意点；\n" +
                    "- 拒绝臆造 API；不确定的部分明确标注；\n- 中文回答，但代码、命令、API 名保留英文原文。",
                isBuiltin = true,
                sortOrder = 2,
                createdAt = now,
                updatedAt = now,
            ),
            PersonaEntity(
                id = "builtin-translator",
                name = "翻译助手",
                icon = "translate",
                systemPrompt = "你是一位专业译者。除非用户另行指定，默认按以下规则工作：\n" +
                    "- 中译英时追求自然流畅，避免直译腔；\n" +
                    "- 英译中时保留专业术语原文（首次出现给括注），整体行文符合中文表达习惯；\n" +
                    "- 保留原文格式：列表、代码块、链接、Markdown 结构原样保留；\n" +
                    "- 仅输出译文，不解释翻译过程；除非用户要求对比，否则不附原文。",
                isBuiltin = true,
                sortOrder = 3,
                createdAt = now,
                updatedAt = now,
            ),
            PersonaEntity(
                id = "builtin-scholar",
                name = "严谨学者",
                icon = "learn",
                systemPrompt = "你是一位治学严谨的研究者，回答应当：\n" +
                    "- 区分事实、推断与个人观点，必要时标注置信度；\n" +
                    "- 对不确定的内容明确说\"我不确定\"，不要编造引用；\n" +
                    "- 引用数据 / 研究时尽量给出年份与作者，但若来源不可考则诚实声明；\n" +
                    "- 鼓励用户基于一手资料独立验证；\n- 回答结构清晰，复杂论点先给结论再展开。",
                isBuiltin = true,
                sortOrder = 4,
                createdAt = now,
                updatedAt = now,
            ),
        )
    }
}

private fun PersonaEntity.toDomain(): Persona = Persona(
    id = id,
    name = name,
    icon = icon,
    systemPrompt = systemPrompt,
    defaultEnableNetwork = defaultEnableNetwork,
    defaultEnableWebFetch = defaultEnableWebFetch,
    defaultThinking = defaultThinking,
    defaultReasoningEffort = defaultReasoningEffort,
    sortOrder = sortOrder,
    pinned = pinned,
    isBuiltin = isBuiltin,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

private fun Persona.toEntity(): PersonaEntity = PersonaEntity(
    id = id,
    name = name,
    icon = icon,
    systemPrompt = systemPrompt,
    defaultEnableNetwork = defaultEnableNetwork,
    defaultEnableWebFetch = defaultEnableWebFetch,
    defaultThinking = defaultThinking,
    defaultReasoningEffort = defaultReasoningEffort,
    sortOrder = sortOrder,
    pinned = pinned,
    isBuiltin = isBuiltin,
    createdAt = createdAt,
    updatedAt = updatedAt,
    deletedAt = null,
)
