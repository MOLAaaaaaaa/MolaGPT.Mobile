package com.molagpt.app.core.network

import com.molagpt.app.core.model.DeltaCommand
import com.molagpt.app.core.model.MessageFragment
import com.molagpt.app.core.model.StreamEvent

/**
 * 把 [StreamEvent] 翻译成对「当前消息」的局部增量命令 [DeltaCommand]。
 * 本身无状态（splitter 状态在 StreamParser，fragment 合并状态在 Repository），
 * 保持职责清晰：事件→命令的纯映射。
 */
class ChatStreamController {
    fun toCommands(event: StreamEvent): List<DeltaCommand> = when (event) {
        is StreamEvent.Delta -> buildList {
            // thinking 先于 text：推理模型语义是「先思考后回答」，同一帧内思考增量应排在正文增量之前，
            // 否则会把正文片段插到思考块前面、破坏交错顺序（参见 ChatRepository.applyCommand 的末尾续写规则）。
            event.thinking?.let { add(DeltaCommand.AppendThinking(it)) }
            event.text?.let { add(DeltaCommand.AppendText(it)) }
        }
        is StreamEvent.Sources -> listOf(DeltaCommand.SetSources(event.refs))
        is StreamEvent.Pending -> listOf(DeltaCommand.SetPending(event.label, event.detail))
        is StreamEvent.Image -> listOf(DeltaCommand.AddImage(event.url, event.prompt))
        is StreamEvent.WireHistory -> listOf(
            DeltaCommand.SetMetadata(event.metadataKey, event.json),
        )
        is StreamEvent.Tool -> listOf(
            DeltaCommand.UpsertTool(
                MessageFragment.ToolCall(
                    id = "tool_${event.id}",
                    name = event.name,
                    status = event.status,
                    label = event.label,
                    argsJson = event.argsJson,
                    resultPreview = event.resultPreview,
                    provider = event.provider,
                ),
            ),
        )
        is StreamEvent.Finish -> listOf(DeltaCommand.Complete(event.usage, event.reason))
        is StreamEvent.Failed -> listOf(DeltaCommand.Fail(event.message))
    }
}
