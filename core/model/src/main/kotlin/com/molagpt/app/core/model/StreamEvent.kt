package com.molagpt.app.core.model

/**
 * 解析后的流式事件。**SSE 原始数据绝不直接进 Compose**——网络层把字节流解析成
 * 一连串 StreamEvent 对外暴露（见 :core:network 的 StreamParser）。
 * 单个事件通常只携带其中一种信息。
 */
sealed interface StreamEvent {
    /** 增量正文 / 增量思考（OpenAI 兼容 delta.content / reasoning_content）。 */
    data class Delta(val text: String? = null, val thinking: String? = null) : StreamEvent

    /** 联网搜索状态/搜索词。 */
    data class Pending(val label: String, val detail: String? = null, val routes: Boolean = false) : StreamEvent

    /** 引用来源。 */
    data class Sources(val refs: List<SourceReference>) : StreamEvent

    /** 工具调用（运行中/成功/失败）。 */
    data class Tool(
        val id: String,
        val name: String,
        val status: ToolStatus,
        val label: String? = null,
        val argsJson: String? = null,
        val resultPreview: String? = null,
        val provider: String? = null,
    ) : StreamEvent

    /** 图片产物。 */
    data class Image(val url: String, val prompt: String? = null) : StreamEvent

    /** 当前可见助手回复背后的协议级历史，供下一轮原样回放。 */
    data class WireHistory(
        val json: String,
        val metadataKey: String = ChatMessageMetadataKeys.OPENAI_WIRE_HISTORY,
    ) : StreamEvent

    /** 正常结束。 */
    data class Finish(val reason: String? = null, val usage: Usage? = null) : StreamEvent

    /** 流内错误。 */
    data class Failed(val message: String) : StreamEvent
}
