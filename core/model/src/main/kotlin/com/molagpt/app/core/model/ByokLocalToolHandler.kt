package com.molagpt.app.core.model

/**
 * BYOK 原生工具里那几个**只碰本机数据**的工具的执行回调。
 *
 * 存在的理由是依赖方向：工具定义与协议分派在 `core:network`，数据在 `core:storage`，
 * 而 storage 依赖 network。把执行做成回调（与 `mcpServersProvider` / `visionProviderResolver` 同一模式），
 * network 就只需要 `core:model` 里的这个接口。
 *
 * [sessionId] 由执行器用来定位本轮用户消息——**模型不能自己指定来源消息 id**，
 * 否则它可以把网页里读到的一句话标成用户说的，把注入内容变成长期记忆。
 */
interface ByokLocalToolHandler {

    /** 返回给模型看的文本结果。失败时返回以约定前缀开头的说明，由网络层归类为失败。 */
    suspend fun execute(name: String, argsJson: String, sessionId: String): String

    /** 未装配时的兜底：明确报不可用，而不是假装成功。 */
    object NoOp : ByokLocalToolHandler {
        override suspend fun execute(name: String, argsJson: String, sessionId: String): String =
            "$MEMORY_FAILURE_PREFIX local memory is not available"
    }

    companion object {
        const val SAVE_MEMORY = "save_memory"
        const val FORGET_MEMORY = "forget_memory"
        const val RECALL_CONVERSATIONS = "recall_conversations"

        val MEMORY_TOOLS = setOf(SAVE_MEMORY, FORGET_MEMORY)
        val RECALL_TOOLS = setOf(RECALL_CONVERSATIONS)
        val ALL = MEMORY_TOOLS + RECALL_TOOLS

        /** 失败前缀。网络层的结果分类与这里必须一致，否则失败会被渲染成成功的工具卡。 */
        const val MEMORY_FAILURE_PREFIX = "memory failed:"
        const val RECALL_FAILURE_PREFIX = "recall failed:"
    }
}
