package com.molagpt.app.core.model

import kotlinx.serialization.Serializable
import java.security.MessageDigest
import kotlin.math.ceil
import kotlin.math.max

/** 一条被选中的世界书内容，以及它要插在哪儿。 */
data class LorebookHit(
    val bookName: String,
    val entry: LoreEntry,
    val content: String,
    val estimatedTokens: Int,
    val position: LorePosition? = null,
    val depth: Int? = null,
    val role: String? = null,
)

/** 每条条目「为什么进了 / 为什么没进」。手机上不做审计面板，但排查问题时是唯一的抓手。 */
data class LorebookDecision(
    val bookName: String,
    val entryName: String,
    val reason: String,
    val included: Boolean,
    val estimatedTokens: Int,
)

data class LorebookEvaluation(
    val hits: List<LorebookHit>,
    val decisions: List<LorebookDecision>,
    val states: Map<String, LoreActivationState>,
    val estimatedTokens: Int,
)

/** 条目的「还在生效」记号：在第几条消息时激活的，由哪次生成写下的。 */
@Serializable
data class LoreActivationState(val activatedAt: Int, val sourceMessageId: String)

data class LorebookOptions(
    val compatibility: RoleCompatibility = RoleCompatibility.CHARACTER_CARD_SPEC,
    /** SillyTavern 兼容模式下整轮的 token 预算；该模式下必填。 */
    val totalBudget: Int? = null,
    val states: Map<String, LoreActivationState> = emptyMap(),
    val sourceMessageId: String = "",
    /**
     * 本轮生成的标识。概率抽签与分组选择据此确定，**同一轮重复评估必须得到同样的结果**——
     * 否则「预览时命中、真发出去时没命中」这种问题永远查不清。
     */
    val generationId: String = "",
    /** 世界书来源的优先级（共享库多选时用），数值小的先进。 */
    val sourcePriorities: Map<String, Int> = emptyMap(),
    /** 写进激活记号的消息序号；不给就按 `messages.size + 1`。 */
    val activationMessageCount: Int? = null,
)

/**
 * 世界书匹配。移植自桌面端同名实现，**语义保留到全量**而不是砍成关键词匹配。
 *
 * 这么做的理由是保真而不是炫技：手机上导入的卡多半是别人在电脑上做好的，卡里本来就带着
 * `constant` / `probability` / `group` / `recursive`。运行时忽略这些字段，会让同一张卡在两端
 * 表现不一样——那比少个功能更糟。本文件是纯 Kotlin，没有 UI 成本。
 *
 * 手机上真正要收敛的是**编辑界面**（只开放六项可编辑，其余只读），不是运行时。
 *
 * 调用方负责把它放到 `Dispatchers.Default` 上。
 */
object LorebookMatcher {

    /** 单条关键词正则的执行上限。 */
    private const val KEYWORD_TIMEOUT_MS = 150L

    /** 粗略的 token 估算：ASCII 约 4 字符 1 token，其余按 1 字符 1 token。与桌面端同一口径。 */
    fun estimateTokens(text: String): Int =
        ceil(text.sumOf { if (it.code <= 127) 0.25 else 1.0 }).toInt()

    fun select(
        books: List<Lorebook>,
        messages: List<String>,
        interpolate: (String) -> String,
    ): List<LorebookHit> = evaluate(books, messages, interpolate).hits

    fun evaluate(
        books: List<Lorebook>,
        messages: List<String>,
        interpolate: (String) -> String,
        options: LorebookOptions = LorebookOptions(),
        countTokens: (String) -> Int = ::estimateTokens,
        /** 参与关键词扫描的文本，可与插入的文本不同（隐藏键在扫描时保留）。 */
        interpolateScan: ((String) -> String)? = null,
    ): LorebookEvaluation {
        val silly = options.compatibility == RoleCompatibility.SILLY_TAVERN
        // SillyTavern 按整轮算预算，不按每本书算，所以预算必须由调用方给出。
        // 在这里凭空编一个，只会让条目被静默丢掉。
        if (silly && (options.totalBudget == null || options.totalBudget < 0)) {
            throw IllegalArgumentException("SillyTavern 兼容模式需要世界书总预算。")
        }

        val hits = mutableListOf<LorebookHit>()
        val decisions = LinkedHashMap<String, LorebookDecision>()
        val states = LinkedHashMap(options.states)
        val candidates = books.distinctBy { it.id }
            .flatMap { book -> book.entries.map { Candidate(book, it) } }
        val processed = HashSet<String>()
        val regexCache = HashMap<Pair<String, Set<RegexOption>>, Regex>()
        val selectedGroups = HashSet<String>()
        val usedByBook = HashMap<String, String>()
        var usedText = ""
        var recursionText = ""
        var overflow = false
        var round = 0

        fun decide(c: Candidate, reason: String, included: Boolean = false, tokens: Int = 0) {
            decisions[c.key] = LorebookDecision(c.book.name, c.entry.displayName, reason, included, tokens)
        }

        fun sticky(c: Candidate): Boolean {
            val state = states[c.key] ?: return false
            return c.entry.sticky > 0 &&
                messages.size >= state.activatedAt &&
                messages.size - state.activatedAt <= c.entry.sticky
        }

        // 同一轮生成必须得到同样的概率与分组结果，所以抽签用生成标识做种子而不是随机数。
        fun draw(key: String): Double {
            if (options.generationId.isEmpty()) {
                throw IllegalStateException("世界书的概率与分组需要本轮生成标识。")
            }
            val bytes = MessageDigest.getInstance("SHA-256")
                .digest((options.generationId + ":" + key).toByteArray())
            val value = (bytes[0].toLong() and 0xFF) or
                ((bytes[1].toLong() and 0xFF) shl 8) or
                ((bytes[2].toLong() and 0xFF) shl 16) or
                ((bytes[3].toLong() and 0xFF) shl 24)
            return value / 4294967296.0
        }

        fun matches(rawKey: String, context: String, entry: LoreEntry): Boolean {
            val key = interpolate(rawKey).trim()
            if (key.isEmpty()) return false
            var pattern = key
            val flags = mutableSetOf<RegexOption>()
            // `/pattern/flags` 是 SillyTavern 里写正则键的方式。
            val slash = if (key.startsWith('/')) key.lastIndexOf('/') else -1
            if (slash > 0) {
                pattern = key.substring(1, slash)
                for (flag in key.substring(slash + 1)) {
                    when (flag) {
                        'i' -> flags += RegexOption.IGNORE_CASE
                        'm' -> flags += RegexOption.MULTILINE
                        's' -> flags += RegexOption.DOT_MATCHES_ALL
                        'g', 'u' -> Unit
                        else -> throw CharacterCardException("世界书「${entry.displayName}」的正则标志 $flag 暂不支持。")
                    }
                }
            } else {
                if (!entry.caseSensitive) flags += RegexOption.IGNORE_CASE
                if (!entry.useRegex) {
                    if (!entry.matchWholeWords) return context.contains(key, ignoreCase = !entry.caseSensitive)
                    pattern = "(?<![\\p{L}\\p{N}_])" + Regex.escape(key) + "(?![\\p{L}\\p{N}_])"
                }
            }
            val regex = regexCache.getOrPut(pattern to flags.toSet()) {
                runCatching { Regex(pattern, flags) }.getOrElse {
                    throw CharacterCardException("世界书「${entry.displayName}」的正则无效。")
                }
            }
            return try {
                RegexGuard.runBounded(KEYWORD_TIMEOUT_MS) { regex.containsMatchIn(context) }
            } catch (expired: RegexGuard.Expired) {
                throw CharacterCardException("世界书「${entry.displayName}」的正则匹配超时。")
            }
        }

        for (c in candidates) {
            if (!c.book.enabled || !c.entry.enabled) {
                decide(c, "未启用")
                processed += c.key
                continue
            }
            c.parse(interpolate(c.entry.content), interpolateScan?.invoke(c.entry.content))
            if (c.position == LorePosition.OUTLET && c.entry.outletName.isBlank()) {
                decide(c, "命名位置为空")
                processed += c.key
                continue
            }
            c.unsupported?.let {
                decide(c, it)
                processed += c.key
            }
        }

        while (true) {
            val active = mutableListOf<Candidate>()
            for (c in candidates) {
                if (c.key in processed) continue
                val e = c.entry
                if (c.forceOff && !c.forceOn) {
                    decide(c, "控制项已停用")
                    processed += c.key
                    continue
                }
                if (messages.size < e.delay) {
                    decide(c, "尚未到激活时间")
                    continue
                }
                if (round < e.delayUntilRecursion) {
                    decide(c, "等待递归扫描")
                    continue
                }
                if (round > 0 && (!c.book.recursiveScanning || e.excludeRecursion)) continue

                val isSticky = sticky(c)
                val state = states[c.key]
                if (!isSticky && state != null && e.cooldown > 0 &&
                    messages.size >= state.activatedAt &&
                    messages.size - state.activatedAt <= e.sticky + e.cooldown
                ) {
                    decide(c, "冷却中")
                    continue
                }

                var scope = c.scanScope ?: e.scanScope
                var depth = c.scanDepth ?: e.scanDepth
                if (scope == null) {
                    scope = if (depth == null) LoreScanScope.INHERIT
                    else if (depth == 0) LoreScanScope.ALL else LoreScanScope.RECENT
                }
                if (scope == LoreScanScope.INHERIT) {
                    scope = c.book.scanScope
                        ?: if (c.book.scanDepth == 0) LoreScanScope.ALL else LoreScanScope.RECENT
                    depth = c.book.scanDepth
                }
                val recent = when (scope) {
                    LoreScanScope.NONE -> ""
                    LoreScanScope.ALL -> messages.joinToString("\n")
                    else -> messages.takeLast(max(0, depth ?: c.book.scanDepth)).joinToString("\n")
                }
                val context = recent + if (round > 0) "\n$recursionText" else ""

                // CCV3 规定 use_regex=true 时应忽略 constant；SillyTavern 会直接激活。按兼容档走。
                var triggered = c.forceOn || isSticky || (e.constant && (silly || !e.useRegex))
                if (!triggered) {
                    triggered = e.keywords.any { matches(it, context, e) }
                    if (triggered && e.selective && e.secondaryKeywords.isNotEmpty()) {
                        val hitCount = e.secondaryKeywords.count { matches(it, context, e) }
                        triggered = when (e.selectiveLogic) {
                            LoreSelectiveLogic.AND_ALL -> hitCount == e.secondaryKeywords.size
                            LoreSelectiveLogic.NOT_ANY -> hitCount == 0
                            LoreSelectiveLogic.NOT_ALL -> hitCount < e.secondaryKeywords.size
                            LoreSelectiveLogic.AND_ANY -> hitCount > 0
                        }
                    }
                }
                if (!triggered) {
                    decide(c, "未命中")
                    continue
                }
                if (c.content.isBlank()) {
                    decide(c, "内容为空")
                    processed += c.key
                    continue
                }
                active += c
            }

            active.sortWith(
                compareByDescending<Candidate> { sticky(it) }
                    .thenBy { options.sourcePriorities[it.book.id] ?: 0 }
                    .thenByDescending { if (silly) it.entry.order else it.entry.selectionPriority },
            )

            // 之前已经采用过同组条目的，本轮直接出局。
            for (c in active.toList()) {
                if (c.groups.any { it in selectedGroups }) {
                    decide(c, "已采用同组条目")
                    processed += c.key
                    active -= c
                }
            }
            for (group in active.flatMap { it.groups }.distinct()) {
                val members = active.filter { group in it.groups }
                if (members.size <= 1) continue
                var winner = members.firstOrNull { sticky(it) }
                    ?: members.filter { it.entry.groupOverride }.maxByOrNull { it.entry.order }
                if (winner == null) {
                    val weight = members.sumOf { max(0, it.entry.groupWeight) }
                    if (weight == 0) {
                        members.forEach {
                            decide(it, "同组权重为零")
                            processed += it.key
                            active -= it
                        }
                        continue
                    }
                    var roll = draw("group:$group") * weight
                    for (member in members) {
                        roll -= max(0, member.entry.groupWeight)
                        if (roll < 0) {
                            winner = member
                            break
                        }
                    }
                }
                val chosen = winner ?: throw IllegalStateException("世界书组选择失败。")
                members.filter { it !== chosen }.forEach {
                    decide(it, "已采用同组条目")
                    processed += it.key
                    active -= it
                }
            }

            val added = mutableListOf<Candidate>()
            var candidateText = ""
            val baseTokens = countTokens(usedText)
            for (c in active) {
                processed += c.key
                val e = c.entry
                if (silly && overflow && !e.ignoreBudget) {
                    decide(c, "总预算已用完")
                    continue
                }
                val isSticky = sticky(c)
                if (!isSticky && e.useProbability &&
                    (e.probability <= 0 || (e.probability < 100 && draw(c.key) * 100 >= e.probability))
                ) {
                    decide(c, "本轮未触发")
                    continue
                }
                val entryText = c.content + "\n"
                val tokens = countTokens(entryText)
                if (silly) {
                    candidateText += entryText
                    if (!e.ignoreBudget && baseTokens + countTokens(candidateText) >= options.totalBudget!!) {
                        overflow = true
                        decide(c, "超出总预算", tokens = tokens)
                        continue
                    }
                } else {
                    val bookText = usedByBook.getOrDefault(c.book.id, "") + entryText
                    val overBook = countTokens(bookText) > c.book.tokenBudget
                    val overTotal = options.totalBudget?.let { countTokens(usedText + entryText) > it } == true
                    if (!e.ignoreBudget && (overBook || overTotal)) {
                        decide(c, "超出预算", tokens = tokens)
                        continue
                    }
                    usedByBook[c.book.id] = bookText
                }
                usedText += entryText
                hits += LorebookHit(c.book.name, e, c.content, tokens, c.position, c.depth, c.role)
                decide(
                    c,
                    when {
                        isSticky -> "持续生效"
                        c.forceOn || e.constant -> "常驻"
                        round > 0 -> "递归命中"
                        else -> "关键词命中"
                    },
                    included = true,
                    tokens = tokens,
                )
                added += c
                selectedGroups += c.groups
                if (options.sourceMessageId.isNotEmpty() && !isSticky && (e.sticky > 0 || e.cooldown > 0)) {
                    states[c.key] = LoreActivationState(
                        options.activationMessageCount ?: (messages.size + 1),
                        options.sourceMessageId,
                    )
                }
            }

            if (silly && overflow) break
            val recursive = added.filterNot { it.entry.preventRecursion }
            if (recursive.isNotEmpty() &&
                candidates.any { it.key !in processed && it.book.recursiveScanning }
            ) {
                recursionText += "\n" + recursive.joinToString("\n") { it.scanContent }
                round++
                continue
            }
            val nextDelay = candidates
                .filter { it.key !in processed && it.book.recursiveScanning && it.entry.delayUntilRecursion > round }
                .minOfOrNull { it.entry.delayUntilRecursion } ?: -1
            if (nextDelay < 0) break
            round = nextDelay
        }

        val valid = candidates.map { it.key }.toHashSet()
        states.keys.retainAll(valid)
        for (c in candidates) {
            if (c.key !in decisions) decide(c, if (overflow) "总预算已用完" else "未命中")
        }
        return LorebookEvaluation(
            hits = hits.sortedBy { it.entry.order },
            decisions = decisions.values.toList(),
            states = states,
            estimatedTokens = countTokens(usedText),
        )
    }

    /**
     * 一条候选条目在本轮的工作状态，包含内容开头那几行 `@@` 控制项解析出来的覆盖值。
     */
    private class Candidate(val book: Lorebook, val entry: LoreEntry) {
        val key: String get() = book.id + ":" + entry.id
        var content: String = ""
            private set
        var scanContent: String = ""
            private set
        var position: LorePosition = entry.placement
            private set
        var depth: Int = entry.depth
            private set
        var role: String = entry.role
            private set
        var scanScope: LoreScanScope? = null
            private set
        var scanDepth: Int? = null
            private set
        var forceOn: Boolean = false
            private set
        var forceOff: Boolean = false
            private set
        var unsupported: String? = null
            private set
        val groups: List<String> = entry.group.split(',').map(String::trim).filter(String::isNotEmpty)

        fun parse(text: String, scanText: String?) {
            val lines = text.replace("\r\n", "\n").split('\n')
            var offset = 0
            while (offset < lines.size && lines[offset].startsWith("@@")) {
                val parts = lines[offset].split(' ', limit = 2).map(String::trim)
                val value = parts.getOrElse(1) { "" }
                when (parts[0]) {
                    "@@activate" -> forceOn = true
                    "@@dont_activate" -> forceOff = true
                    "@@depth" -> {
                        depth = max(0, number(value))
                        position = LorePosition.AT_DEPTH
                    }
                    "@@role" -> {
                        if (value !in setOf("system", "user", "assistant")) {
                            throw CharacterCardException("世界书「${entry.displayName}」的消息身份无效。")
                        }
                        role = value
                    }
                    "@@scan_depth" -> {
                        scanDepth = max(0, number(value))
                        scanScope = if (scanDepth == 0) LoreScanScope.NONE else LoreScanScope.RECENT
                    }
                    "@@position" -> when (value) {
                        "before_desc" -> position = LorePosition.BEFORE_CHARACTER
                        "after_desc" -> position = LorePosition.AFTER_CHARACTER
                        else -> unsupported = "暂未支持的位置：$value"
                    }
                    else -> unsupported = "暂未支持的控制项：${parts[0]}"
                }
                offset++
            }
            content = if (offset == 0) text else lines.drop(offset).joinToString("\n").trim('\r', '\n')
            scanContent = when {
                scanText == null -> content
                offset == 0 -> scanText
                else -> scanText.replace("\r\n", "\n").split('\n').drop(offset).joinToString("\n")
            }
        }

        private fun number(value: String): Int = value.toIntOrNull()
            ?: throw CharacterCardException("世界书「${entry.displayName}」的控制项数值无效。")
    }
}
