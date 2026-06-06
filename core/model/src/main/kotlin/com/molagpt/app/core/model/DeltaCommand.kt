package com.molagpt.app.core.model

/**
 * 对「当前正在生成的消息」施加的**局部增量命令**。ChatStreamController 把 [StreamEvent]
 * 翻译成 DeltaCommand，Repository 据此只更新该消息的对应 fragment，**绝不整段重建**。
 */
sealed interface DeltaCommand {
    data class AppendText(val chunk: String) : DeltaCommand
    data class AppendThinking(val chunk: String) : DeltaCommand
    data class UpsertTool(val tool: MessageFragment.ToolCall) : DeltaCommand
    data class SetSources(val refs: List<SourceReference>) : DeltaCommand
    data class SetPending(val label: String, val detail: String?) : DeltaCommand
    data class AddImage(val url: String, val prompt: String?) : DeltaCommand
    data class Complete(val usage: Usage? = null, val finishReason: String? = null) : DeltaCommand
    data class Fail(val message: String) : DeltaCommand
}
