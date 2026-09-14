package com.molagpt.app.core.model

/** 示例对话里的一句。 */
data class RolePromptMessage(val role: String, val text: String)

/**
 * 一条按深度插入的补充。[depth] 是「距对话末尾几条」，0 表示贴在最后一条消息之前。
 * [order] 决定同一深度上的先后。
 */
data class RolePromptInsertion(
    val source: String,
    val role: String,
    val depth: Int,
    val order: Int,
    val text: String,
)

/**
 * 系统提示之外、还需要塞进消息列表的部分。
 *
 * 桌面端要把它序列化成 JSON 交给 Pi sidecar 去插；移动端的 `ChatRequest.messages` 就是最终的
 * wire 列表，直接插即可，少一层。
 */
data class RolePromptPlan(
    val examples: List<List<RolePromptMessage>> = emptyList(),
    val insertions: List<RolePromptInsertion> = emptyList(),
) {
    val isEmpty: Boolean get() = examples.isEmpty() && insertions.isEmpty()
}

data class RolePromptResult(
    val systemPrompt: String,
    val plan: RolePromptPlan,
    val lore: LorebookEvaluation,
)

/**
 * 把角色卡 + 世界书组装成这一轮要发出去的提示词。移植自桌面端 `RolePromptBuilder`。
 *
 * 世界书条目按各自的位置分流：角色资料前后、示例对话前后、按深度插入、作者注释前后、命名出口。
 * 手机上不做剧情摘要与故事记忆（那是「编排」，属于坐在电脑前干的事），其余语义保持一致。
 */
object RolePromptBuilder {

    private val EXAMPLE_SEPARATOR = Regex("""(?:^|\r?\n)\s*<START>\s*(?:\r?\n|$)""", RegexOption.IGNORE_CASE)

    fun build(
        personaName: String,
        profile: PersonaProfile,
        /** 角色自己的系统提示词（[Persona.systemPrompt]）。 */
        personaSystemPrompt: String,
        /** 会话级提示词覆盖；null 表示没有。 */
        conversationPrompt: String? = null,
        promptMode: String? = null,
        /** 供世界书扫描的历史消息文本，按时间正序。 */
        history: List<String> = emptyList(),
        /** 参与本轮的世界书：卡内的 + 引用的共享库。 */
        books: List<Lorebook> = emptyList(),
        vars: PromptVariables,
        /** 本轮生成标识：世界书的概率与分组抽签据此确定。 */
        generationId: String,
        states: Map<String, LoreActivationState> = emptyMap(),
        sourceMessageId: String = "",
        sourcePriorities: Map<String, Int> = emptyMap(),
    ): RolePromptResult {
        val character = profile.nickname.ifBlank { personaName }
        val fields = mapOf(
            "description" to profile.description,
            "personality" to profile.personality,
            "scenario" to profile.scenario,
            "persona" to profile.userDescription,
            "charprompt" to personaSystemPrompt,
            "charjailbreak" to profile.postHistoryInstructions,
        )
        var context = vars.copy(
            characterName = character,
            username = vars.username?.takeIf { it.isNotBlank() } ?: profile.userName.takeIf { it.isNotBlank() },
            roleFields = fields,
        )
        fun expand(text: String) = SystemPromptComposer.interpolate(text, context)

        val lore = LorebookMatcher.evaluate(
            books = books,
            messages = history,
            interpolate = ::expand,
            options = LorebookOptions(
                compatibility = profile.compatibility,
                totalBudget = if (profile.compatibility == RoleCompatibility.SILLY_TAVERN) profile.loreBudget else null,
                states = states,
                sourceMessageId = sourceMessageId,
                generationId = generationId,
                sourcePriorities = sourcePriorities,
                activationMessageCount = history.size + 1,
            ),
        )
        fun hitsAt(position: LorePosition) =
            lore.hits.filter { (it.position ?: it.entry.placement) == position }

        // 命名出口要先备好，后面展开正文时才能用上 {{出口名}}。
        val outlets = hitsAt(LorePosition.OUTLET)
            .filter { it.entry.outletName.isNotEmpty() }
            .groupBy { it.entry.outletName }
            .mapValues { (_, group) -> group.joinToString("\n") { it.content } }
        context = context.copy(outlets = outlets)

        val main = SystemPromptComposer.combine(
            personaPrompt = expand(personaSystemPrompt),
            conversationPrompt = SystemPromptComposer.interpolate(
                conversationPrompt,
                context.copy(original = expand(personaSystemPrompt)),
            ).takeIf { it.isNotBlank() },
            mode = promptMode,
        ).orEmpty()

        val sections = mutableListOf(main)
        sections += hitsAt(LorePosition.BEFORE_CHARACTER).map { it.content }
        sections += "角色：\n$character"
        for ((label, value) in listOf(
            "角色资料" to profile.description,
            "性格与表达" to profile.personality,
            "场景" to profile.scenario,
            "用户身份" to profile.userDescription,
        )) {
            if (value.isNotBlank()) sections += "$label：\n${expand(value)}"
        }
        sections += hitsAt(LorePosition.AFTER_CHARACTER).map { it.content }

        val examples = mutableListOf<List<RolePromptMessage>>()
        examples += hitsAt(LorePosition.BEFORE_EXAMPLES).flatMap { parseExamples(it.content, character, context.username, personaName) }
        examples += parseExamples(profile.exampleDialogue, character, context.username, personaName, ::expand)
        examples += hitsAt(LorePosition.AFTER_EXAMPLES).flatMap { parseExamples(it.content, character, context.username, personaName) }

        val insertions = mutableListOf<RolePromptInsertion>()
        fun insert(source: String, role: String, depth: Int, order: Int, text: String) {
            if (text.isBlank()) return
            // 贴在末尾的补充只能是系统或用户身份：把它写成 assistant 等于替模型开了个头。
            val safeRole = if (depth == 0 && role == "assistant") "system" else role
            insertions += RolePromptInsertion(source, safeRole, maxOf(0, depth), order, text)
        }
        for (hit in hitsAt(LorePosition.AT_DEPTH)) {
            insert(
                source = hit.bookName + " · " + hit.entry.displayName,
                role = hit.role ?: hit.entry.role,
                depth = hit.depth ?: hit.entry.depth,
                order = hit.entry.order,
                text = hit.content,
            )
        }
        insert("角色补充", profile.characterNoteRole, profile.characterNoteDepth, 0, expand(profile.characterNote))
        val note = (hitsAt(LorePosition.BEFORE_NOTE) + hitsAt(LorePosition.AFTER_NOTE))
            .map { it.content }
            .filter { it.isNotBlank() }
        insert("对话补充", "system", 4, 1, note.joinToString("\n\n"))
        insert(
            "后置指令",
            "system",
            0,
            Int.MAX_VALUE,
            SystemPromptComposer.interpolate(profile.postHistoryInstructions, context.copy(original = "")),
        )

        return RolePromptResult(
            systemPrompt = sections.filter { it.isNotBlank() }.joinToString("\n\n"),
            plan = RolePromptPlan(examples, insertions.sortedWith(compareBy({ -it.depth }, { it.order }))),
            lore = lore,
        )
    }

    /**
     * 把 `mes_example` 拆成若干组对话。`<START>` 分组，`名字:` 分说话人。
     *
     * 认得出的说话人有：角色名、角色库里的名字、用户称呼，以及 `{{char}}` / `{{user}}` 这两个
     * 字面写法——卡片作者两种都在用。
     */
    private fun parseExamples(
        text: String,
        characterName: String,
        userName: String?,
        libraryName: String,
        expand: ((String) -> String)? = null,
    ): List<List<RolePromptMessage>> {
        if (text.isBlank()) return emptyList()
        val speakers = HashMap<String, String>()
        fun register(name: String, role: String) {
            if (name.isNotBlank()) speakers[name.lowercase()] = role
        }
        register("user", "user")
        register("assistant", "assistant")
        register("char", "assistant")
        register(characterName, "assistant")
        register(libraryName, "assistant")
        register(userName?.takeIf { it.isNotBlank() } ?: "用户", "user")
        register("{{user}}", "user")
        register("{{char}}", "assistant")

        val output = mutableListOf<List<RolePromptMessage>>()
        for (block in EXAMPLE_SEPARATOR.split(text)) {
            if (block.isBlank()) continue
            val messages = mutableListOf<RolePromptMessage>()
            val content = StringBuilder()
            var role: String? = null
            fun flush() {
                if (content.isNotEmpty()) {
                    val value = content.toString().trimEnd()
                    messages += RolePromptMessage(role ?: "system", expand?.invoke(value) ?: value)
                }
                content.setLength(0)
            }
            for (line in block.replace("\r\n", "\n").split('\n')) {
                val colon = line.indexOfFirst { it == ':' || it == '：' }
                val speaker = if (colon > 0) speakers[line.substring(0, colon).trim().lowercase()] else null
                if (speaker != null) {
                    flush()
                    role = speaker
                    content.appendLine(line.substring(colon + 1).trimStart())
                } else {
                    content.appendLine(line)
                }
            }
            flush()
            if (messages.isNotEmpty()) output += messages
        }
        return output
    }
}
