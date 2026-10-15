package com.molagpt.app.core.storage

import com.molagpt.app.core.model.SkillInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.CharacterCodingException
import java.util.UUID
import java.util.zip.ZipInputStream

/** 技能包只保存和读取文件，不提供任何脚本执行入口。 */
class SkillRepository(private val root: File) {
    private val mutex = Mutex()
    private val _skills = MutableStateFlow(discover())
    val skills = _skills.asStateFlow()

    suspend fun importFile(input: InputStream): SkillInfo = withContext(Dispatchers.IO) {
        input.buffered().use { stream ->
            stream.mark(4)
            val isZip = stream.read() == 0x50 && stream.read() == 0x4b &&
                stream.read() == 0x03 && stream.read() == 0x04
            stream.reset()
            if (isZip) {
                importZip(stream)
            } else {
                val bytes = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var count = stream.read(buffer)
                while (count != -1) {
                    require(bytes.size() + count <= MAX_TEXT_BYTES) { "技能说明超过 1 MB。" }
                    bytes.write(buffer, 0, count)
                    count = stream.read(buffer)
                }
                importMarkdown(decodeText(bytes.toByteArray()))
            }
        }
    }

    suspend fun importMarkdown(content: String): SkillInfo = withContext(Dispatchers.IO) {
        mutex.withLock {
            require(content.toByteArray(Charsets.UTF_8).size <= MAX_TEXT_BYTES) { "技能说明过大，请将资料拆分为附属文件。" }
            val staging = stagingDirectory()
            try {
                File(staging, "SKILL.md").writeText(content, Charsets.UTF_8)
                install(staging)
            } finally {
                staging.deleteRecursively()
            }
        }
    }

    suspend fun importZip(input: InputStream): SkillInfo = withContext(Dispatchers.IO) {
        mutex.withLock {
            val staging = stagingDirectory()
            try {
                ZipInputStream(input).use { zip ->
                    var entry = zip.nextEntry
                    var entries = 0
                    var totalBytes = 0L
                    val buffer = ByteArray(8192)
                    while (entry != null) {
                        require(++entries <= MAX_ZIP_ENTRIES) { "技能包文件过多。" }
                        val target = inside(staging, entry.name)
                        if (entry.isDirectory) {
                            check(target.isDirectory || target.mkdirs()) { "无法创建技能目录。" }
                        } else {
                            check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs()) { "无法创建技能目录。" }
                            target.outputStream().use { output ->
                                var count = zip.read(buffer)
                                while (count != -1) {
                                    totalBytes += count
                                    require(totalBytes <= MAX_ZIP_BYTES) { "技能包解压后超过 16 MB。" }
                                    output.write(buffer, 0, count)
                                    count = zip.read(buffer)
                                }
                            }
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
                val candidates = if (File(staging, "SKILL.md").isFile) {
                    listOf(staging)
                } else {
                    staging.listFiles().orEmpty().filter { it.isDirectory && File(it, "SKILL.md").isFile }
                }
                require(candidates.size == 1) { "请选择只包含一个技能的 ZIP，SKILL.md 应在根目录或一级子目录。" }
                install(candidates.single())
            } finally {
                staging.deleteRecursively()
            }
        }
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            require(_skills.value.any { it.id == id }) { "技能不存在。" }
            check(inside(root, id).deleteRecursively()) { "无法删除技能。" }
            _skills.value = discover()
        }
    }

    suspend fun contents(id: String): String = withContext(Dispatchers.IO) {
        mutex.withLock {
            require(_skills.value.any { it.id == id }) { "技能不存在。" }
            readText(inside(inside(root, id), "SKILL.md"))
        }
    }

    suspend fun readForModel(
        name: String,
        path: String,
        offset: Int,
        allowedSkills: List<SkillInfo>,
    ): String = withContext(Dispatchers.IO) {
        mutex.withLock {
            val skill = allowedSkills.firstOrNull { it.name == name }
                ?: throw IllegalArgumentException("该技能未在当前会话启用。")
            require(_skills.value.any { it.id == skill.id }) { "技能不存在。" }
            val text = readText(inside(inside(root, skill.id), path))
            require(offset in 0..text.length) { "offset 超出文件范围。" }
            var end = minOf(offset + PAGE_CHARS, text.length)
            if (end < text.length && end > offset && text[end - 1].isHighSurrogate()) end--
            buildString {
                append('[').append(skill.name).append('/').append(path)
                append("; characters ").append(offset).append("..").append(end).append('/').append(text.length)
                if (end < text.length) append("; next_offset=").append(end)
                append("]\n\n").append(text.substring(offset, end))
            }
        }
    }

    private fun stagingDirectory(): File {
        check(root.isDirectory || root.mkdirs()) { "无法创建技能目录。" }
        return File(root, ".import-${UUID.randomUUID()}").apply { check(mkdir()) { "无法导入技能。" } }
    }

    private fun install(directory: File): SkillInfo {
        val parsed = SkillManifest.parse(readText(File(directory, "SKILL.md")))
        require(_skills.value.none { it.name.equals(parsed.name, ignoreCase = true) }) { "已有同名技能，请先删除后再导入。" }
        val id = UUID.randomUUID().toString()
        check(directory.renameTo(File(root, id))) { "无法保存技能。" }
        val skill = SkillInfo(id, parsed.name, parsed.description, parsed.compatibility)
        _skills.value = (_skills.value + skill).sortedBy { it.name.lowercase() }
        return skill
    }

    private fun discover(): List<SkillInfo> = root.listFiles().orEmpty()
        .filter { it.isDirectory && !it.name.startsWith('.') && File(it, "SKILL.md").isFile }
        .map { directory ->
            val parsed = SkillManifest.parse(readText(File(directory, "SKILL.md")))
            SkillInfo(directory.name, parsed.name, parsed.description, parsed.compatibility)
        }.sortedBy { it.name.lowercase() }

    private fun readText(file: File): String {
        require(file.isFile) { "技能文件不存在。" }
        require(file.length() <= MAX_TEXT_BYTES) { "文件过大，请拆分后读取。" }
        return decodeText(file.readBytes())
    }

    private fun decodeText(bytes: ByteArray): String {
        require(bytes.none { it == 0.toByte() }) { "只能读取文本文件。" }
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
        } catch (error: CharacterCodingException) {
            throw IllegalArgumentException("文件不是 UTF-8 文本。", error)
        }
    }

    private fun inside(directory: File, path: String): File {
        require(path.isNotBlank() && !File(path).isAbsolute && '\\' !in path) { "请使用技能内的相对路径。" }
        val target = File(directory, path).canonicalFile
        require(target.path.startsWith(directory.canonicalPath + File.separator)) { "路径超出技能目录。" }
        return target
    }

    private companion object {
        const val MAX_TEXT_BYTES = 1024 * 1024
        const val MAX_ZIP_BYTES = 16 * 1024 * 1024
        const val MAX_ZIP_ENTRIES = 256
        const val PAGE_CHARS = 16_000
    }
}

/** 只提取技能使用的顶层标量；支持多行描述，不改写原始说明。 */
internal data class SkillManifest(val name: String, val description: String, val compatibility: String?) {
    companion object {
        fun parse(content: String): SkillManifest {
            val lines = content.removePrefix("\uFEFF").trimStart().lines()
            require(lines.firstOrNull()?.trim() == "---") { "SKILL.md 缺少 YAML 信息头。" }
            val end = (1 until lines.size).firstOrNull { lines[it].trim() == "---" }
                ?: throw IllegalArgumentException("SKILL.md 的 YAML 信息头未结束。")
            val fields = mutableMapOf<String, String>()
            var i = 1
            while (i < end) {
                val line = lines[i++]
                if (line.firstOrNull()?.isWhitespace() == true) continue
                val colon = line.indexOf(':')
                if (colon <= 0) continue
                val key = line.substring(0, colon).trim()
                if (key !in setOf("name", "description", "compatibility")) continue
                val value = line.substring(colon + 1).trim()
                fields[key] = if (value.matches(Regex("[|>][-+1-9]*"))) {
                    val block = mutableListOf<String>()
                    while (i < end && (lines[i].isBlank() || lines[i].first().isWhitespace())) {
                        block += lines[i++].trim()
                    }
                    block.joinToString(if (value.startsWith('>')) " " else "\n").trim()
                } else if (value.length >= 2 && value.first() == '\'' && value.last() == '\'') {
                    value.substring(1, value.length - 1).replace("''", "'")
                } else if (value.length >= 2 && value.first() == '"' && value.last() == '"') {
                    kotlinx.serialization.json.Json.decodeFromString<String>(value)
                } else value
            }
            val name = fields["name"].orEmpty().trim()
            val description = fields["description"].orEmpty().trim()
            require(name.isNotBlank() && '\n' !in name && '\r' !in name) { "请填写有效的 name。" }
            require(description.isNotBlank()) { "请填写 description，说明用途与使用时机。" }
            return SkillManifest(name, description, fields["compatibility"]?.takeIf { it.isNotBlank() })
        }
    }
}
