package com.molagpt.app.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.zip.InflaterInputStream
import java.util.zip.ZipInputStream

/** 导入失败的原因直接说给用户听，不包装成技术栈信息。 */
class CharacterCardException(message: String) : Exception(message)

/** 一张读出来的角色卡：名称 + 系统提示 + 卡片资料 + 头像原图（可能没有）。 */
data class ImportedCharacter(
    val name: String,
    val systemPrompt: String,
    val profile: PersonaProfile,
    val avatar: ByteArray?,
) {
    override fun equals(other: Any?): Boolean =
        other is ImportedCharacter && name == other.name && systemPrompt == other.systemPrompt &&
            profile == other.profile && avatar.contentEquals(other.avatar)

    override fun hashCode(): Int =
        (((name.hashCode() * 31) + systemPrompt.hashCode()) * 31 + profile.hashCode()) * 31 +
            (avatar?.contentHashCode() ?: 0)
}

/**
 * 角色卡读取：PNG（`tEXt` / `iTXt`）、charX（zip）、裸 JSON，覆盖 V1 / V2 / V3。
 *
 * 放在纯 Kotlin/JVM 的 `:core:model` 里，plain JUnit 就能测。为此刻意不碰两样东西：
 * - 不引 `metadata-extractor`：它只认 `[chara:` 那种老写法，V3 的 `ccv3` 与 iTXt 压缩块读不出来；
 * - 不用 `java.util.Base64`（API 26）也不用 `android.util.Base64`（会把本模块拖成 Android 模块），
 *   改用文件末尾那个自带的解码器。本项目 minSdk 是 23。
 */
object CharacterCardReader {

    private val PNG_SIGNATURE = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)
    private val ZIP_SIGNATURE = byteArrayOf(80, 75, 3, 4)
    private val SUPPORTED_SPECS = setOf("chara_card_v2", "chara_card_v3")
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun read(bytes: ByteArray): ImportedCharacter {
        val root = readDocument(bytes)
        val spec = root.text("spec")
        if (spec.isNotEmpty() && spec !in SUPPORTED_SPECS) {
            throw CharacterCardException("请选择 V1、V2 或 V3 角色卡。")
        }
        val data = if (spec.isEmpty()) root else root["data"] as? JsonObject
            ?: throw CharacterCardException("角色卡缺少资料。")
        val name = data.text("name")
        if (name.isBlank()) throw CharacterCardException("角色卡缺少名称。")
        // V1 没有 spec 字段，只能靠特征字段判断这到底是不是一张卡。
        if (spec.isEmpty() && !data.containsKey("description") && !data.containsKey("first_mes")) {
            throw CharacterCardException("这个文件不是角色卡。")
        }

        val extensions = data["extensions"] as? JsonObject
        val lorebooks = mutableListOf<Lorebook>()
        (data["character_book"] as? JsonObject)?.let { lorebooks += readBook(it) }

        var profile = PersonaProfile(
            mode = ConversationMode.ATMOSPHERE,
            compatibility = RoleCompatibility.SILLY_TAVERN,
            cardSpec = spec.ifEmpty { "chara_card_v3" },
            nickname = data.text("nickname"),
            creator = data.text("creator"),
            creatorNotes = data.text("creator_notes"),
            characterVersion = data.text("character_version"),
            tags = data["tags"].strings(),
            description = data.text("description"),
            personality = data.text("personality"),
            scenario = data.text("scenario"),
            greeting = data.text("first_mes"),
            alternateGreetings = data["alternate_greetings"].strings(),
            exampleDialogue = data.text("mes_example"),
            postHistoryInstructions = data.text("post_history_instructions"),
            lorebooks = lorebooks,
            embeddedLorebookId = lorebooks.firstOrNull()?.id,
        )
        (extensions?.get("depth_prompt") as? JsonObject)?.let { note ->
            profile = profile.copy(
                characterNote = note.text("prompt"),
                characterNoteDepth = note.int("depth", 4),
                characterNoteRole = roleOf(note.int("role", 0)),
            )
        }
        // 扩展与资源描述整块留着：导入提示说了「已保留」，就得真的能在存档里找回来。
        profile = profile.copy(
            extensions = extensions,
            assets = data["assets"] as? JsonArray,
            importNotes = importNotes(extensions, data),
        )

        return ImportedCharacter(
            name = name,
            systemPrompt = data.text("system_prompt"),
            profile = profile,
            avatar = readAvatar(bytes, data),
        )
    }

    /** 单独导入一本世界书（SillyTavern 导出的 `.json`，或 V3 的 `lorebook_v3`）。 */
    fun readLorebook(bytes: ByteArray): Lorebook {
        var root = parseJson(bytes.decodeUtf8())
        if (root.text("spec") == "lorebook_v3") {
            root = root["data"] as? JsonObject ?: throw CharacterCardException("世界书缺少资料。")
        }
        if (root["entries"] !is JsonArray && root["entries"] !is JsonObject) {
            throw CharacterCardException("世界书缺少条目。")
        }
        return readBook(root)
    }

    /** 取出卡片的 JSON 文档本身，不做语义解析。导入前的「这是什么文件」判断也走它。 */
    fun readDocument(bytes: ByteArray): JsonObject = when {
        bytes.startsWith(PNG_SIGNATURE) -> parseJson(readPngMetadata(bytes))
        bytes.startsWith(ZIP_SIGNATURE) ->
            parseJson(readZipEntry(bytes, "card.json")?.decodeUtf8() ?: throw CharacterCardException("角色包缺少 card.json。"))
        else -> parseJson(bytes.decodeUtf8())
    }

    // —— 卡片资料 ——

    private fun importNotes(extensions: JsonObject?, data: JsonObject): List<String> {
        val notes = mutableListOf<String>()
        val helper = extensions?.get("tavern_helper") as? JsonObject
        val hasHelperScripts = (helper?.get("scripts") as? JsonArray)?.isNotEmpty() == true ||
            (helper?.get("variables")?.let { it is JsonObject && it.isNotEmpty() || it is JsonArray && it.isNotEmpty() } == true)
        val hasLegacyHelper = (extensions?.get("TavernHelper_scripts") as? JsonArray)?.isNotEmpty() == true ||
            extensions?.get("TavernHelper_characterScriptVariables")
                ?.let { it is JsonObject && it.isNotEmpty() || it is JsonArray && it.isNotEmpty() } == true
        if (hasHelperScripts || hasLegacyHelper) notes += "脚本扩展已保留，暂不运行"
        if ((extensions?.get("regex_scripts") as? JsonArray)?.isNotEmpty() == true) notes += "局部正则已保留，暂不运行"
        if (extensions?.get("risuai") is JsonObject) notes += "RisuAI 扩展已保留"
        val known = setOf(
            "depth_prompt", "tavern_helper", "TavernHelper_scripts",
            "TavernHelper_characterScriptVariables", "regex_scripts", "risuai",
        )
        val others = extensions?.keys?.count { it !in known } ?: 0
        if (others > 0) notes += "另有 $others 项卡片扩展已保留"
        // 只留清单，不留文件——头像之外的资源没有落到本机，说法要和实际一致。
        if ((data["assets"] as? JsonArray)?.isNotEmpty() == true) notes += "附带资源清单已保留，文件未导入"
        return notes
    }

    private fun readAvatar(bytes: ByteArray, data: JsonObject): ByteArray? {
        if (bytes.startsWith(PNG_SIGNATURE)) return bytes
        if (!bytes.startsWith(ZIP_SIGNATURE)) return null
        val assets = (data["assets"] as? JsonArray)?.filterIsInstance<JsonObject>().orEmpty()
        val icon = assets.firstOrNull { it.text("type") == "icon" && it.text("name") == "main" }
            ?: assets.firstOrNull { it.text("type") == "icon" }
            ?: return null
        val uri = icon.text("uri")
        if (!uri.startsWith("embeded://")) return null
        return readZipEntry(bytes, uri.removePrefix("embeded://"))
            ?: throw CharacterCardException("角色卡的头像资源不存在。")
    }

    // —— 世界书 ——

    private fun readBook(book: JsonObject): Lorebook {
        val depth = book.int("scan_depth", 4)
        val budget = book.int("token_budget", 2048)
        if (depth < 0 || budget < 0) throw CharacterCardException("世界书的扫描范围和预算不能为负数。")
        // SillyTavern 原生导出把 entries 写成对象（键是序号），V2/V3 规范写成数组。
        val native = book["entries"] is JsonObject
        val rawEntries: List<JsonElement> = when (val entries = book["entries"]) {
            is JsonObject -> entries.values.toList()
            is JsonArray -> entries.toList()
            else -> emptyList()
        }
        return Lorebook(
            id = newId(),
            name = book.text("name").ifBlank { "世界书" },
            scanDepth = depth,
            scanScope = when (depth) {
                0 -> LoreScanScope.NONE
                Int.MAX_VALUE -> LoreScanScope.ALL
                else -> LoreScanScope.RECENT
            },
            tokenBudget = budget,
            recursiveScanning = book.flag("recursive_scanning"),
            entries = rawEntries.map { node ->
                readEntry(node as? JsonObject ?: throw CharacterCardException("世界书条目为空。"), native)
            },
            cardData = JsonObject(book.filterKeys { it != "entries" }),
        )
    }

    private fun readEntry(entry: JsonObject, native: Boolean): LoreEntry {
        val ext = if (native) entry else entry["extensions"] as? JsonObject ?: JsonObject(emptyMap())
        val entryDepth = ext.intOrNull(if (native) "scanDepth" else "scan_depth")
        val position = ext.intOrNull("position")
            ?: if (entry.text("position") == "before_char") 0 else 1
        val delayKey = if (native) "delayUntilRecursion" else "delay_until_recursion"
        // 这个字段历史上既出现过布尔也出现过轮数，两种都得认。
        val delayUntilRecursion = (ext[delayKey] as? JsonPrimitive)?.booleanOrNull
            ?.let { if (it) 1 else 0 }
            ?: ext.intOrNull(delayKey) ?: 0

        return LoreEntry(
            id = newId(),
            name = entry.text("name").ifBlank { entry.text("comment") },
            content = entry.text("content"),
            keywords = entry[if (native) "key" else "keys"].strings(),
            secondaryKeywords = entry[if (native) "keysecondary" else "secondary_keys"].strings(),
            // 原生导出用「禁用」，规范用「启用」，两边是反的。
            enabled = if (native) !entry.flag("disable") else entry.flag("enabled", true),
            constant = entry.flag("constant"),
            caseSensitive = ext.flag(
                if (native) "caseSensitive" else "case_sensitive",
                entry.flag("case_sensitive"),
            ),
            selective = entry.flag("selective"),
            selectiveLogic = LoreSelectiveLogic.entries
                .getOrElse(ext.int("selectiveLogic", 0)) { LoreSelectiveLogic.AND_ANY },
            useRegex = entry.flag("use_regex"),
            matchWholeWords = ext.flag(if (native) "matchWholeWords" else "match_whole_words"),
            insertionOrder = entry.int(if (native) "order" else "insertion_order", 100),
            budgetPriority = entry.intOrNull("priority"),
            scanDepth = entryDepth,
            scanScope = when (entryDepth) {
                null -> LoreScanScope.INHERIT
                0 -> LoreScanScope.NONE
                Int.MAX_VALUE -> LoreScanScope.ALL
                else -> LoreScanScope.RECENT
            },
            position = LorePosition.entries.getOrNull(position),
            beforeCharacter = position == 0,
            depth = ext.int("depth", 4),
            role = roleOf(ext.int("role", 0)),
            excludeRecursion = ext.flag(if (native) "excludeRecursion" else "exclude_recursion"),
            preventRecursion = ext.flag(if (native) "preventRecursion" else "prevent_recursion"),
            delayUntilRecursion = delayUntilRecursion,
            probability = ext.int("probability", 100),
            useProbability = ext.flag("useProbability", true),
            group = ext.text("group"),
            outletName = ext.text(if (native) "outletName" else "outlet_name"),
            groupOverride = ext.flag(if (native) "groupOverride" else "group_override"),
            groupWeight = ext.int(if (native) "groupWeight" else "group_weight", 100),
            sticky = ext.int("sticky", 0),
            cooldown = ext.int("cooldown", 0),
            delay = ext.int("delay", 0),
            ignoreBudget = ext.flag(if (native) "ignoreBudget" else "ignore_budget"),
            cardData = entry,
        )
    }

    // —— 容器 ——

    private fun readPngMetadata(bytes: ByteArray): String {
        var legacy: PngChunk? = null
        var v3: PngChunk? = null
        for (chunk in readChunks(bytes)) {
            if (chunk.type != "tEXt" && chunk.type != "iTXt") continue
            val start = chunk.offset + 8
            val keyEnd = bytes.indexOfZero(start, start + chunk.length)
            if (keyEnd < 0) continue
            when (String(bytes, start, keyEnd - start, Charsets.US_ASCII).lowercase()) {
                "ccv3" -> if (v3 == null) v3 = chunk
                "chara" -> if (legacy == null) legacy = chunk
            }
        }
        val selected = v3 ?: legacy ?: throw CharacterCardException("这张图片没有角色卡资料。")
        val start = selected.offset + 8
        val end = start + selected.length
        var from = bytes.indexOfZero(start, end) + 1

        if (selected.type == "iTXt") {
            if (end - from < 2) throw CharacterCardException("PNG 角色资料不完整。")
            val compressed = bytes[from].toInt() == 1
            from += 2 // 压缩标志 + 压缩方法
            // 语言标签与翻译后的关键词，各自以 0 结尾。
            repeat(2) {
                val zero = bytes.indexOfZero(from, end)
                if (zero < 0) throw CharacterCardException("PNG 角色资料不完整。")
                from = zero + 1
            }
            if (compressed) {
                val inflated = InflaterInputStream(ByteArrayInputStream(bytes, from, end - from))
                    .use { it.readBytes() }
                return Base64Rfc4648.decode(inflated.decodeUtf8()).decodeUtf8()
            }
        }
        return Base64Rfc4648.decode(String(bytes, from, end - from, Charsets.UTF_8)).decodeUtf8()
    }

    private data class PngChunk(val offset: Int, val length: Int, val type: String)

    private fun readChunks(bytes: ByteArray): List<PngChunk> {
        val chunks = mutableListOf<PngChunk>()
        var offset = 8
        while (offset + 12 <= bytes.size) {
            val length = bytes.readUInt32(offset)
            if (length > bytes.size - offset - 12L) throw CharacterCardException("PNG 文件不完整。")
            val type = String(bytes, offset + 4, 4, Charsets.US_ASCII)
            chunks += PngChunk(offset, length.toInt(), type)
            offset += length.toInt() + 12
            if (type == "IEND") break
        }
        return chunks
    }

    private fun readZipEntry(bytes: ByteArray, name: String): ByteArray? =
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: return null
                if (entry.name == name) return zip.readBytes()
                zip.closeEntry()
            }
            @Suppress("UNREACHABLE_CODE")
            null
        }

    // —— 小工具 ——

    private fun parseJson(text: String): JsonObject {
        val body = text.trimStart('﻿')
        if (body.isBlank()) throw CharacterCardException("角色卡内容为空。")
        return runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
            ?: throw CharacterCardException("角色卡内容不是有效的 JSON。")
    }

    private fun newId(): String = UUID.randomUUID().toString().replace("-", "")

    private fun roleOf(value: Int): String = when (value) {
        1 -> "user"
        2 -> "assistant"
        else -> "system"
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    private fun ByteArray.decodeUtf8(): String = toString(Charsets.UTF_8)

    private fun ByteArray.readUInt32(at: Int): Long =
        ((this[at].toLong() and 0xFF) shl 24) or
            ((this[at + 1].toLong() and 0xFF) shl 16) or
            ((this[at + 2].toLong() and 0xFF) shl 8) or
            (this[at + 3].toLong() and 0xFF)

    private fun ByteArray.indexOfZero(from: Int, until: Int): Int {
        for (i in from until minOf(until, size)) if (this[i].toInt() == 0) return i
        return -1
    }

    /** 卡片在野外什么类型都可能写成字符串，取值一律宽松，读不到就用默认值。 */
    private fun JsonObject.text(key: String): String =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: ""

    private fun JsonObject.intOrNull(key: String): Int? = (this[key] as? JsonPrimitive)?.let {
        it.intOrNull ?: it.content.toIntOrNull()
    }

    private fun JsonObject.int(key: String, otherwise: Int): Int = intOrNull(key) ?: otherwise

    private fun JsonObject.flag(key: String, otherwise: Boolean = false): Boolean =
        (this[key] as? JsonPrimitive)?.let { it.booleanOrNull ?: it.content.toBooleanStrictOrNull() } ?: otherwise

    private fun JsonElement?.strings(): List<String> = (this as? JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.content?.takeIf(String::isNotBlank) }
        .orEmpty()
}

/**
 * RFC 4648 解码器。`java.util.Base64` 要 API 26，`android.util.Base64` 会让 `:core:model`
 * 变成 Android 模块、连带毁掉纯 JVM 单测——所以这二十来行自己写。
 * 顺带认 URL-safe 字母表，并跳过 PNG 文本块里常见的换行。
 */
private object Base64Rfc4648 {
    private val TABLE = IntArray(128) { -1 }.also { table ->
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
            .forEachIndexed { index, ch -> table[ch.code] = index }
        table['-'.code] = 62
        table['_'.code] = 63
    }

    fun decode(text: String): ByteArray {
        val out = ByteArrayOutputStream(text.length * 3 / 4 + 3)
        var buffer = 0
        var bits = 0
        for (ch in text) {
            if (ch == '=') break
            val value = if (ch.code < 128) TABLE[ch.code] else -1
            if (value < 0) {
                if (ch.isWhitespace()) continue
                throw CharacterCardException("角色卡的资料块不是有效的 Base64。")
            }
            buffer = (buffer shl 6) or value
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((buffer shr bits) and 0xFF)
            }
        }
        return out.toByteArray()
    }
}
