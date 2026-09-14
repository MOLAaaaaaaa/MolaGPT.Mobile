package com.molagpt.app.feature.settings

import android.content.Context
import android.net.Uri
import com.molagpt.app.core.model.CharacterCardException
import com.molagpt.app.core.model.CharacterCardReader
import com.molagpt.app.core.model.Persona
import com.molagpt.app.core.storage.PersonaAvatarStore
import com.molagpt.app.core.storage.PersonaRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 导入结果：成功时给出角色，以及卡里「保留了但当期不运行」的那些东西。 */
sealed interface CardImportResult {
    data class Success(val persona: Persona, val notes: List<String>) : CardImportResult
    data class Failure(val message: String) : CardImportResult
}

/** 一张卡最大允许多少字节。角色卡都是几百 KB 量级，再大基本是选错了文件。 */
private const val MAX_CARD_BYTES = 16 * 1024 * 1024

/**
 * 从用户选中的文件导入一张角色卡（PNG / charX / JSON，V1-V3）。
 *
 * 读文件与解析都在 IO 线程；失败一律转成一句能读的话，不把异常栈甩给用户。
 */
suspend fun importCharacterCard(
    context: Context,
    uri: Uri,
    repository: PersonaRepository,
    avatars: PersonaAvatarStore,
): CardImportResult = withContext(Dispatchers.IO) {
    val bytes = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            // 先读一个字节确认能打开，再整体读进来；超限直接拒绝，别把超大文件拖进内存。
            val buffered = input.buffered()
            val data = buffered.readBytes()
            if (data.size > MAX_CARD_BYTES) null else data
        }
    }.getOrNull()
        ?: return@withContext CardImportResult.Failure("无法读取所选文件，或文件过大。")

    val card = try {
        CharacterCardReader.read(bytes)
    } catch (e: CharacterCardException) {
        return@withContext CardImportResult.Failure(e.message ?: "这个文件不是角色卡。")
    } catch (e: Exception) {
        return@withContext CardImportResult.Failure("角色卡解析失败。")
    }

    val existing = repository.list()
    val persona = repository.blankDraft(existing.size).copy(
        name = uniqueName(card.name, existing.map { it.name }.toSet()),
        systemPrompt = card.systemPrompt,
        profile = card.profile,
        avatarPath = card.avatar?.let { avatars.save(it) },
        icon = "idea",
    )
    CardImportResult.Success(repository.save(persona), card.profile.importNotes)
}

/** 同名的卡导两次很常见，给后来的加个序号，而不是让列表里出现两个一模一样的名字。 */
private fun uniqueName(name: String, taken: Set<String>): String {
    if (name !in taken) return name
    var index = 2
    while ("$name $index" in taken) index++
    return "$name $index"
}
