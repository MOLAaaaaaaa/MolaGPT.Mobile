package com.molagpt.app.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * 角色卡（SillyTavern / Character Card V1-V3）的完整资料，整块随 `PersonaEntity.profileJson` 落库。
 *
 * **字段按卡片规范收全，即使当期运行时还用不上**：手机上导入的卡多半是别人在电脑上做好的，
 * 解析时丢字段会让同一张卡在两端表现不一致，也会逼出一次没必要的数据迁移。
 *
 * 工具开关、默认模型这类**应用偏好**不放这里，它们属于 [Persona]；这里只放卡片自己的内容。
 */
@Serializable
data class PersonaProfile(
    /** null 表示没显式选过，由 [defaultMode] 按字段推断。 */
    val mode: ConversationMode? = null,
    val compatibility: RoleCompatibility = RoleCompatibility.CHARACTER_CARD_SPEC,
    val cardSpec: String = "chara_card_v3",
    /** 角色自称。留空则沿用 [Persona.name]。 */
    val nickname: String = "",
    val creator: String = "",
    val creatorNotes: String = "",
    val characterVersion: String = "",
    val tags: List<String> = emptyList(),
    val summary: String = "",
    val description: String = "",
    val personality: String = "",
    val scenario: String = "",
    /** 用户在这段关系里的称呼与身份（卡片里的 `{{user}}` / persona）。 */
    val userName: String = "",
    val userDescription: String = "",
    val greeting: String = "",
    val alternateGreetings: List<String> = emptyList(),
    val exampleDialogue: String = "",
    /** 历史之后追加的指令（`post_history_instructions`，俗称 jailbreak 槽）。 */
    val postHistoryInstructions: String = "",
    /** 按深度插入的角色补充（`extensions.depth_prompt`）。 */
    val characterNote: String = "",
    val characterNoteDepth: Int = 4,
    val characterNoteRole: String = "system",
    /** 随卡携带的世界书。 */
    val lorebooks: List<Lorebook> = emptyList(),
    val embeddedLorebookId: String? = null,
    /** 引用的共享世界书 id（独立于卡片，跨角色复用）。 */
    val sharedLorebookIds: List<String> = emptyList(),
    /** SillyTavern 兼容模式下整轮世界书的 token 预算。 */
    val loreBudget: Int = 2048,
    /**
     * 卡片的 `extensions` 原样保留（局部正则、脚本、RisuAI 等第三方扩展都在里面）。
     *
     * 本期一个都不运行，但必须存下来：导入提示里写着「已保留」，那就得真的保留住，
     * 否则这张卡在手机上转一圈就残了。
     */
    val extensions: JsonObject? = null,
    /**
     * 卡片的 `assets` 描述原样保留。
     *
     * 只是**描述**——头像之外的资源文件本身没有导入，charX 包里的其它文件不会落到本机。
     */
    val assets: JsonArray? = null,
    /** 导入时保留但当期不运行的东西，原样说给用户听（脚本扩展、局部正则等）。 */
    val importNotes: List<String> = emptyList(),
) {
    /** 没显式选过模式时，卡里只要填了角色扮演相关的字段就按「氛围」走。 */
    @Transient
    val defaultMode: ConversationMode = mode ?: if (
        description.isNotBlank() || personality.isNotBlank() || scenario.isNotBlank() ||
        userName.isNotBlank() || userDescription.isNotBlank() || greeting.isNotBlank() ||
        exampleDialogue.isNotBlank() || postHistoryInstructions.isNotBlank() ||
        alternateGreetings.isNotEmpty() || lorebooks.isNotEmpty()
    ) {
        ConversationMode.ATMOSPHERE
    } else {
        ConversationMode.CHAT
    }

    /**
     * 这张卡是不是「空的」——没有任何会进提示词的内容，等同于一段普通系统提示。
     *
     * 单独列字段而不是复用 [defaultMode]：那个判的是「像不像角色扮演」，这个判的是「有没有东西可用」。
     * 两者并不等价——只引用了共享世界书、或只写了角色补充的角色，形态上仍是助手，但必须参与组装。
     */
    val isEmpty: Boolean get() = description.isBlank() && personality.isBlank() && scenario.isBlank() &&
        nickname.isBlank() && userName.isBlank() && userDescription.isBlank() &&
        greeting.isBlank() && alternateGreetings.isEmpty() && exampleDialogue.isBlank() &&
        postHistoryInstructions.isBlank() && characterNote.isBlank() &&
        lorebooks.isEmpty() && sharedLorebookIds.isEmpty()
}

/** 对话形态：普通助手 / 角色扮演。 */
@Serializable
enum class ConversationMode {
    @SerialName("chat") CHAT,
    @SerialName("atmosphere") ATMOSPHERE,
}

/**
 * 世界书语义的取舍口径。
 * - [CHARACTER_CARD_SPEC]：按 V3 规范，预算按每本书算。
 * - [SILLY_TAVERN]：按 SillyTavern 的实际行为，预算按整轮算。导入的卡默认落这一档。
 */
@Serializable
enum class RoleCompatibility {
    @SerialName("spec") CHARACTER_CARD_SPEC,
    @SerialName("sillytavern") SILLY_TAVERN,
}

@Serializable
enum class LoreScanScope {
    @SerialName("inherit") INHERIT,
    @SerialName("recent") RECENT,
    @SerialName("all") ALL,
    @SerialName("none") NONE,
}

/** 次要关键词的判定方式（SillyTavern 的 selectiveLogic，取值顺序即卡片里的数值）。 */
@Serializable
enum class LoreSelectiveLogic {
    @SerialName("and_any") AND_ANY,
    @SerialName("not_all") NOT_ALL,
    @SerialName("not_any") NOT_ANY,
    @SerialName("and_all") AND_ALL,
}

/** 条目插入位置（取值顺序即卡片里的 `extensions.position` 数值）。 */
@Serializable
enum class LorePosition {
    @SerialName("before_char") BEFORE_CHARACTER,
    @SerialName("after_char") AFTER_CHARACTER,
    @SerialName("before_note") BEFORE_NOTE,
    @SerialName("after_note") AFTER_NOTE,
    @SerialName("at_depth") AT_DEPTH,
    @SerialName("before_examples") BEFORE_EXAMPLES,
    @SerialName("after_examples") AFTER_EXAMPLES,
    /** 命名出口：内容不直接插入，而是填进提示词里的同名占位。 */
    @SerialName("outlet") OUTLET,
}

@Serializable
data class Lorebook(
    val id: String,
    val name: String = "世界书",
    val enabled: Boolean = true,
    val scanDepth: Int = 4,
    val scanScope: LoreScanScope? = null,
    val tokenBudget: Int = 2048,
    val recursiveScanning: Boolean = false,
    val entries: List<LoreEntry> = emptyList(),
    /** 除 entries 外的原始卡片字段，原样留着，导出时不丢。 */
    val cardData: JsonObject? = null,
)

/**
 * 一条世界书条目。
 *
 * [cardData] 是它在卡片里的原始 JSON。手机上只开放六项可编辑（名称/关键词/内容/启用/常驻/
 * 位置与深度），其余字段既不展示成表单也不丢弃——保存时原样写回，卡在两端来回搬也不会变味。
 */
@Serializable
data class LoreEntry(
    val id: String,
    val name: String = "",
    val content: String = "",
    val keywords: List<String> = emptyList(),
    val secondaryKeywords: List<String> = emptyList(),
    val enabled: Boolean = true,
    /** 常驻：不看关键词，每轮都进上下文。 */
    val constant: Boolean = false,
    val caseSensitive: Boolean = false,
    /** 命中主关键词后还要按 [selectiveLogic] 校验次要关键词。 */
    val selective: Boolean = false,
    val selectiveLogic: LoreSelectiveLogic = LoreSelectiveLogic.AND_ANY,
    val useRegex: Boolean = false,
    val matchWholeWords: Boolean = false,
    val priority: Int = 0,
    val insertionOrder: Int? = null,
    val budgetPriority: Int? = null,
    val scanDepth: Int? = null,
    val scanScope: LoreScanScope? = null,
    val beforeCharacter: Boolean = false,
    val position: LorePosition? = null,
    val depth: Int = 4,
    val role: String = "system",
    /** 本条内容不参与下一轮递归扫描。 */
    val excludeRecursion: Boolean = false,
    /** 本条命中后不再触发递归。 */
    val preventRecursion: Boolean = false,
    val delayUntilRecursion: Int = 0,
    val probability: Int = 100,
    val useProbability: Boolean = true,
    /** 逗号分隔的分组名；同组每轮只采用一条。 */
    val group: String = "",
    val outletName: String = "",
    val groupOverride: Boolean = false,
    val groupWeight: Int = 100,
    /** 命中后持续生效的轮数。 */
    val sticky: Int = 0,
    /** 生效结束后的冷却轮数。 */
    val cooldown: Int = 0,
    /** 对话满多少条之后这条才开始参与。 */
    val delay: Int = 0,
    val ignoreBudget: Boolean = false,
    val cardData: JsonObject? = null,
) {
    val displayName: String get() = name.ifBlank { keywords.firstOrNull() ?: "未命名条目" }

    /** 插入排序用的次序。卡片没给 insertionOrder 时退回到 priority 的相反数（数值越大越靠前）。 */
    val order: Int get() = insertionOrder ?: -priority

    /** 预算不够时谁先被裁掉。 */
    val selectionPriority: Int get() = budgetPriority ?: insertionOrder ?: priority

    val placement: LorePosition
        get() = position ?: if (beforeCharacter) LorePosition.BEFORE_CHARACTER else LorePosition.AFTER_CHARACTER
}

/**
 * 关键词列表 ↔ 可编辑文本。
 *
 * **一行一个关键词**，而不是用逗号顿号分隔：关键词本身就可能含逗号——`/a{1,3}/` 这种
 * 正则量词是最常见的例子，按标点切会把一条规则劈成两条，用户只是改个名字都会踩到。
 * 换行不会出现在卡片的关键词里，所以它是这里唯一分得清「内容」和「分隔符」的字符。
 */
/**
 * 自建世界书与条目的 id。
 *
 * 卡片自带的条目沿用卡里的 id；用户新建的走这里，加前缀是为了出问题时一眼看出来源。
 */
object LoreIds {
    fun book(): String = "book-" + random()
    fun entry(): String = "lore-" + random()

    private fun random(): String = java.util.UUID.randomUUID().toString().replace("-", "")
}

object LoreKeywords {
    fun toText(keywords: List<String>): String = keywords.joinToString("\n")

    fun fromText(text: String): List<String> =
        text.split('\n').map(String::trim).filter(String::isNotEmpty)

    /** 关键词里真混进了换行（畸形卡片）时，编辑器应当只读展示，别去动它。 */
    fun isEditable(keywords: List<String>): Boolean = keywords.none { it.contains('\n') }

    /**
     * 保存时决定关键词列表：文本一个字都没动过（或这条本就不可编辑）就把原列表原样带回，
     * 不重新解析一遍。改个条目名就把关键词重排一次，是没必要冒的险。
     */
    fun resolve(original: List<String>, editedText: String, editable: Boolean): List<String> =
        if (!editable || editedText == toText(original)) original else fromText(editedText)
}
