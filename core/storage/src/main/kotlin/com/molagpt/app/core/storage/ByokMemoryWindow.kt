package com.molagpt.app.core.storage

import com.molagpt.app.core.model.ChatMessage
import com.molagpt.app.core.model.MessageStatus
import com.molagpt.app.core.model.Role

/**
 * 把一段待整理的消息裁成交给模型的窗口。纯函数，可单测。
 *
 * 这一层不决定「要不要花钱」——那由整理器的轮数/时间阈值和守门模型负责。
 * 它只回答「这一段里哪些内容值得发出去」：剥掉代码与链接，丢掉剥完什么都不剩的消息。
 * 剥而不是整条跳过，是因为偏好经常混在技术内容里（贴一大段报错，末尾一句「以后都用 Kotlin」）。
 */
object ByokMemoryWindow {

    /** 首次整理的窗口上限。老会话第一次开记忆时，不把整段历史一次性发出去。 */
    const val FIRST_WINDOW_CAP = 20

    /** 常规窗口上限。攒了很久没整理时，一次也不该无限膨胀。 */
    const val WINDOW_CAP = 40

    /**
     * 剥掉代码与链接后仍需保留的最少字符，低于此认为这条没有可提取内容。
     *
     * 取 4 而不是英文项目常见的 8：中文一个字就是一个词，「我住在上海」只有 5 个字符，
     * 却正是最该被记住的那类事实。8 会把这类整句直接丢掉。
     */
    const val MIN_CHARS = 4

    /** 单条消息进入提示词的字符上限。没有整条丢弃的规则，只截断。 */
    const val MAX_CHARS_PER_MESSAGE = 1200

    /**
     * 一条 `STREAMING` / `PENDING` 的消息多久之后当它已经废弃。
     *
     * 进程被杀时库里会留下永远流不完的行（没有启动时的修复逻辑），只按状态判断的话，
     * 那一条会把整个会话的整理永久卡住——这正是水位线最怕的一类饿死。
     */
    const val IN_FLIGHT_GRACE_MILLIS = 5 * 60 * 1000L

    data class Entry(
        val messageId: String,
        val role: Role,
        val createdAt: Long,
        /** 剥掉代码与链接、并截断后的正文。 */
        val text: String,
    )

    data class Window(
        val entries: List<Entry>,
        /** 本窗口最后一条消息的 createdAt。整理成功后水位线推到这里。 */
        val endAt: Long,
        /** 助手回合数，用于与「攒够 N 轮」阈值比较。 */
        val assistantTurns: Int,
    ) {
        val isEmpty: Boolean get() = entries.isEmpty()
        val userEntries: List<Entry> get() = entries.filter { it.role == Role.USER }
    }

    /**
     * [messages] 需按 createdAt 升序。[watermarkAt] 为 0 表示这个会话从没整理过，
     * 此时窗口按 [FIRST_WINDOW_CAP] 截取尾部。
     */
    fun build(
        messages: List<ChatMessage>,
        watermarkAt: Long,
        resetAt: Long,
        nowMillis: Long = System.currentTimeMillis(),
        /**
         * 首窗是否截断。false 对应「完整」整理范围：首窗也按 [WINDOW_CAP] 走，
         * 靠反复整理逐段读完，而不是把上百条消息塞进一次请求。
         */
        capFirstWindow: Boolean = true,
    ): Window {
        val fresh = messages.filter { message ->
            message.createdAt > watermarkAt &&
                (message.role == Role.USER || message.role == Role.ASSISTANT)
        }
        // 窗口必须停在还没写完的那一轮之前。水位线一旦越过一条正在流的回答，
        // 它写完时已经落在水位线之下，这一轮就再也不会被整理，而且没有任何报错。
        val settled = fresh.takeWhile { !it.isInFlightAt(nowMillis) }
        // 被截断说明后面那一轮还在进行中，此时结尾的用户消息属于这一轮，留给下个窗口。
        val pending = if (settled.size == fresh.size) settled else settled.dropLastWhile { it.role == Role.USER }
        if (pending.isEmpty()) return Window(emptyList(), watermarkAt, 0)

        // 水位线要按「看过的范围」推进，而不是按「留下来的内容」。
        // 否则剥完为空、或早于清空时间点的消息会永远留在待整理队列里：
        // 每次扫描都把它们数进来，还会一直占掉本轮能处理的会话名额。
        val endAt = pending.last().createdAt
        // 停止和报错的回合不算回合：它们没产生回答，凑不出「聊了一段」这件事。
        val assistantTurns = pending.count {
            it.role == Role.ASSISTANT && it.status == MessageStatus.COMPLETE
        }

        // 截断的方向决定了被丢掉的是哪一段，这里有两种完全不同的意图：
        //   · 省 token 的首窗：**故意**只读尾部，老会话前面的历史直接放弃，水位线一次推到底；
        //   · 其余情况：从水位线往后取一批，水位线只推到这一批的末尾，剩下的下次接着读。
        // 第二种如果也取尾部，中间那一段会被跳过并永远读不到——攒了很久才整理的会话尤其明显。
        val capped: List<ChatMessage>
        val windowEndAt: Long
        when {
            capFirstWindow && watermarkAt <= 0L -> {
                capped = pending.takeLast(FIRST_WINDOW_CAP)
                windowEndAt = endAt
            }
            pending.size > WINDOW_CAP -> {
                capped = pending.take(WINDOW_CAP)
                windowEndAt = capped.last().createdAt
            }
            else -> {
                capped = pending
                windowEndAt = endAt
            }
        }

        val entries = capped.mapNotNull { message ->
            if (message.createdAt < resetAt) return@mapNotNull null
            // 半截的回答只是噪声：它永远不能当证据，却要占预算，
            // 还可能让模型把一句没说完的话当成结论。用户那一侧照收——话是他说的。
            if (message.role == Role.ASSISTANT && message.status != MessageStatus.COMPLETE) {
                return@mapNotNull null
            }
            val stripped = strip(message.rawText.orEmpty())
            if (stripped.length < MIN_CHARS) return@mapNotNull null
            Entry(
                messageId = message.messageId,
                role = message.role,
                createdAt = message.createdAt,
                text = stripped.take(MAX_CHARS_PER_MESSAGE),
            )
        }
        return Window(entries, windowEndAt, assistantTurns)
    }

    /** 代码围栏、行内代码与 URL 都不含关于用户的稳定事实，只会占 token 并成为注入载体。 */
    fun strip(text: String): String = text
        .replace(FENCE_REGEX, " ")
        .replace(INLINE_CODE_REGEX, " ")
        .replace(URL_REGEX, " ")
        .replace(WHITESPACE_REGEX, " ")
        .trim()

    private fun ChatMessage.isInFlightAt(nowMillis: Long): Boolean =
        isStreaming && nowMillis - updatedAt < IN_FLIGHT_GRACE_MILLIS

    private val FENCE_REGEX = Regex("""```[\s\S]*?(?:```|$)""")
    private val INLINE_CODE_REGEX = Regex("""`[^`\n]{1,200}`""")
    private val URL_REGEX = Regex("""https?://\S+""")
    private val WHITESPACE_REGEX = Regex("""\s+""")
}
