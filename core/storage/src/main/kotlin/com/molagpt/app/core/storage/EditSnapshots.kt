package com.molagpt.app.core.storage

import com.molagpt.app.core.storage.entity.MessageEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 用户消息的「编辑分支」快照。
 *
 * 结构与 Web 的 `meta.editSnapshots` 完全同构，本地按云端原始 JSON 串存放（见
 * [SyncMapper.META_EDIT_SNAPSHOTS]），同步时纯透传，双向不丢：
 *
 * ```json
 * { "currentIndex": -1,
 *   "snapshots": [ { "timestamp": 0, "label": "版本 1", "conversationHistory": [ …消息… ] } ],
 *   "currentVersion": [ …消息… ] }
 * ```
 *
 * `currentIndex == -1` 表示当前停在「最新编辑版本」，此时最新时间线是会话本身，
 * 只有切走时才把它存进 `currentVersion`——与 Web 的 `navigateEditSnapshot` 一致。
 */
object EditSnapshots {
    const val KEY = SyncMapper.META_EDIT_SNAPSHOTS

    /** 最新版索引：不落在 `snapshots` 数组里，用 -1 表示。 */
    const val LIVE_INDEX = -1

    private const val F_CURRENT_INDEX = "currentIndex"
    private const val F_SNAPSHOTS = "snapshots"
    private const val F_CURRENT_VERSION = "currentVersion"
    private const val F_HISTORY = "conversationHistory"
    private const val F_TIMESTAMP = "timestamp"
    private const val F_LABEL = "label"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 供 UI 显示 `< 当前/总数 >`；无分支时返回 null。 */
    data class View(val position: Int, val total: Int)

    /** 一次切换的结果：要恢复的时间线 + 写回用户消息的新元数据串。 */
    data class Navigated(val timeline: JsonArray, val metadata: String)

    private fun decode(raw: String?): JsonObject? {
        if (raw.isNullOrBlank()) return null
        return runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
    }

    private fun snapshotsOf(obj: JsonObject?): List<JsonObject> =
        (obj?.get(F_SNAPSHOTS) as? JsonArray)?.filterIsInstance<JsonObject>().orEmpty()

    private fun currentIndexOf(obj: JsonObject?): Int =
        (obj?.get(F_CURRENT_INDEX)?.jsonPrimitive?.intOrNull ?: LIVE_INDEX)

    /**
     * 版本条展示信息。总数 = 历史快照数 + 1（最新版）；位置按时间顺序，最新版排在最后。
     * 少于 2 个版本时不显示，返回 null。
     */
    fun view(raw: String?): View? {
        val obj = decode(raw) ?: return null
        val snapshots = snapshotsOf(obj)
        if (snapshots.isEmpty()) return null
        val total = snapshots.size + 1
        val index = currentIndexOf(obj)
        val position = if (index == LIVE_INDEX) total else index + 1
        return View(position = position.coerceIn(1, total), total = total)
    }

    /**
     * 编辑前调用：把当前整条时间线追加为一个历史快照，并把指针置回最新版。
     * [timeline] 应为当前会话的全部非 system 消息（对齐 Web 的快照内容）。
     */
    fun append(raw: String?, timeline: JsonArray, timestamp: Long): String {
        val obj = decode(raw)
        val existing = snapshotsOf(obj)
        val snapshot = buildJsonObject {
            put(F_TIMESTAMP, timestamp)
            put(F_LABEL, "版本 ${existing.size + 1}")
            put(F_HISTORY, timeline)
        }
        return buildJsonObject {
            put(F_CURRENT_INDEX, LIVE_INDEX)
            put(F_SNAPSHOTS, buildJsonArray { existing.forEach { add(it) }; add(snapshot) })
            // 指针回到最新版，旧的 currentVersion 已无意义（最新时间线即会话本身）。
        }.toString()
    }

    /**
     * 在版本间切换。[liveTimeline] 是会话当前的全部非 system 消息——仅当从最新版切走时
     * 需要把它存进 `currentVersion`，否则切回来就没了。越界或无分支返回 null。
     *
     * [persistBack]：离开**历史分支**时是否把 [liveTimeline] 写回该快照。
     * - MolaGPT 传 false，保持 Web 的「快照冻结」语义（历史分支上不允许改动，见 Web 的
     *   `startRegenerate` 只认最新一条回答）；
     * - BYOK 传 true，让历史分支上的重生成/切版本改动能留存，实现分支独立编辑。
     *
     * 注意按「时间顺序位置」而非 `currentIndex` 直接加减：最新版的索引是 -1，但它在时间上
     * 排在所有历史快照**之后**，直接对索引加减会把方向弄反。
     */
    fun navigate(raw: String?, delta: Int, liveTimeline: JsonArray, persistBack: Boolean = false): Navigated? {
        val obj = decode(raw) ?: return null
        val snapshots = snapshotsOf(obj)
        if (snapshots.isEmpty()) return null
        val total = snapshots.size + 1
        val current = currentIndexOf(obj)
        val currentPos = if (current == LIVE_INDEX) total else current + 1
        val nextPos = currentPos + delta
        if (nextPos < 1 || nextPos > total) return null
        val next = if (nextPos == total) LIVE_INDEX else nextPos - 1
        if (next == current) return null

        val storedCurrentVersion = obj[F_CURRENT_VERSION] as? JsonArray
        // 离开最新版时才刷新 currentVersion；已在历史版本上时会话内容不是最新版，不能覆盖。
        val currentVersion = if (current == LIVE_INDEX) liveTimeline else storedCurrentVersion

        // BYOK：离开历史分支时把（可能被重生成/切版本改过的）当前时间线写回该快照。
        val effectiveSnapshots =
            if (persistBack && current != LIVE_INDEX && current in snapshots.indices) {
                snapshots.toMutableList().apply {
                    val old = this[current]
                    this[current] = buildJsonObject {
                        old.forEach { (k, v) -> if (k != F_HISTORY) put(k, v) }
                        put(F_HISTORY, liveTimeline)
                    }
                }
            } else {
                snapshots
            }

        val timeline = if (next == LIVE_INDEX) {
            currentVersion ?: return null
        } else {
            effectiveSnapshots[next][F_HISTORY] as? JsonArray ?: return null
        }

        val metadata = buildJsonObject {
            put(F_CURRENT_INDEX, next)
            put(F_SNAPSHOTS, buildJsonArray { effectiveSnapshots.forEach { add(it) } })
            currentVersion?.let { put(F_CURRENT_VERSION, it) }
        }.toString()
        return Navigated(timeline = timeline, metadata = metadata)
    }

    /** 会话消息 → 快照用的时间线（去掉 system；内层不再嵌套快照，避免指数膨胀）。 */
    fun timelineOf(messages: List<MessageEntity>): JsonArray = buildJsonArray {
        messages.asSequence()
            .filterNot { it.role.equals(com.molagpt.app.core.model.Role.SYSTEM.name, ignoreCase = true) }
            .forEach { add(SyncMapper.messageToJson(it, includeSnapshots = false)) }
    }

    /** 快照时间线 → 可落库的消息实体（id 由时间戳+序号确定性生成）。 */
    fun messagesOf(sessionId: String, timeline: JsonArray): List<MessageEntity> =
        timeline.filterIsInstance<JsonObject>()
            .mapIndexed { i, obj -> SyncMapper.jsonToMessage(sessionId, obj, i) }
}
