package com.molagpt.app.core.network

import com.molagpt.app.core.model.ByokMemoryDigestEntry
import com.molagpt.app.core.model.ByokMemoryOp
import com.molagpt.app.core.model.ByokProfileKey
import com.molagpt.app.core.model.MemorySection

/**
 * 记忆整理：把一段对话交给便宜的小模型，换回结构化操作。
 *
 * 两步两次调用：先问「这段里有没有值得长期记住的东西」，为 true 才做提取。
 * 守门那次的提示词很短、输出只有一个标签、也不带已有记忆摘要，
 * 因此「没什么可记」的窗口从一次大调用降为一次小调用。
 *
 * **输出格式用 XML 标签而不是 JSON**：BYOK 用户常挂 7B 级别的便宜模型，
 * 它们的 JSON mode 要么不支持要么不稳；闭合标签在缺引号、多逗号、前后加废话的情况下仍能解析出来。
 */
class ByokMemoryAnalyzer(
    /** 四协议非流式文本补全。与会话标题共用同一条路径。 */
    private val completeText: suspend (
        providerId: String,
        modelId: String,
        prompt: String,
        maxTokens: Int,
    ) -> String?,
) {

    /**
     * 请求本身没成功：网络错、鉴权错、上游报错。**必须与「模型说没什么可记的」区分开**——
     * 后者推进水位线，前者不推进，留给下一次重试。压成同一个结果会让失败被记成成功。
     */
    class RequestFailed : Exception("memory analyzer request failed")

    /** 守门输出无法解析。与请求失败同等处理：不推进水位线。 */
    class Malformed : Exception("memory analyzer response was malformed")

    /** 待整理窗口里的一条消息。只有 [isUser] 为 true 的才能作为证据。 */
    data class WindowMessage(
        val messageId: String,
        val isUser: Boolean,
        val text: String,
    )

    data class Input(
        val providerId: String,
        val modelId: String,
        val window: List<WindowMessage>,
        val existing: List<ByokMemoryDigestEntry>,
        /** 用户已删除或忽略的内容。用于阻止同一事实换一种表述后被自动写回。 */
        val suppressed: List<String> = emptyList(),
    )

    /**
     * 第一步：这段窗口里有没有跨对话仍然成立的用户信息。
     *
     * 解析不出 true/false 就抛 [Malformed] 而不是当成 false——把"读不懂模型的回答"
     * 记成"没什么可记"，会让水位线越过一段根本没被看过的对话。
     */
    suspend fun gate(input: Input): Boolean {
        val raw = completeText(input.providerId, input.modelId, buildGatePrompt(input), GATE_OUTPUT_TOKENS)
            ?: throw RequestFailed()
        val match = GATE_REGEX.find(raw) ?: throw Malformed()
        return match.groupValues[1].equals("true", ignoreCase = true)
    }

    /** 第二步：提取具体操作。只在 [gate] 返回 true 时调用。 */
    suspend fun extract(input: Input): List<ByokMemoryOp> {
        val raw = completeText(input.providerId, input.modelId, buildExtractPrompt(input), MAX_OUTPUT_TOKENS)
            ?: throw RequestFailed()
        if (!MEMORY_OPS_REGEX.containsMatchIn(raw) && !OP_REGEX.containsMatchIn(raw)) {
            throw Malformed()
        }
        return parseOps(raw)
    }

    private fun buildGatePrompt(input: Input): String = buildString {
        append(GATE_INSTRUCTIONS)
        append("\n\n## 对话\n")
        append(renderWindow(input.window))
    }

    private fun buildExtractPrompt(input: Input): String = buildString {
        append(EXTRACT_INSTRUCTIONS)
        append("\n\n## 已有记忆\n")
        if (input.existing.isEmpty()) {
            append("（无）\n")
        } else {
            input.existing.forEach { append("- [${it.id}] ${it.section.wire}｜${it.text}\n") }
        }
        append("\n## 已删除或忽略\n")
        if (input.suppressed.isEmpty()) {
            append("（无）\n")
        } else {
            input.suppressed.forEach { append("- $it\n") }
        }
        append("\n## 对话\n")
        append(renderWindow(input.window))
    }

    /**
     * 不把 messageId 发给模型。证据归属由应用侧拿引文回查窗口得出，模型指定不了来源——
     * 给它 id 只会多一个可以被诱导伪造的字段。
     */
    private fun renderWindow(window: List<WindowMessage>): String =
        window.joinToString("\n") { message ->
            "${if (message.isUser) "用户" else "助手"}: ${message.text}"
        }

    /**
     * 宽容解析：只认 `<op ...>` 与其内部的 `<text>` / `<quote>`，
     * 标签外的前言、道歉、代码围栏一律忽略；`<memory_ops/>` 或没有任何 op 都返回空列表。
     */
    private fun parseOps(raw: String): List<ByokMemoryOp> =
        OP_REGEX.findAll(raw)
            .take(ByokMemoryOp.MAX_OPS)
            .mapNotNull { match ->
                val attributes = match.groupValues[1]
                val body = match.groupValues.getOrNull(2).orEmpty()
                val type = ByokMemoryOp.Type.fromWire(attributes.attribute("type")) ?: return@mapNotNull null
                ByokMemoryOp(
                    type = type,
                    targetId = attributes.attribute("target"),
                    text = body.tag("text") ?: attributes.attribute("text"),
                    section = MemorySection.entries.firstOrNull { it.wire == attributes.attribute("section") },
                    profileKey = ByokProfileKey.fromWire(attributes.attribute("profile_key")),
                    quote = body.tag("quote"),
                    confidence = attributes.attribute("confidence")?.toDoubleOrNull() ?: 0.0,
                )
            }
            .toList()

    private fun String.attribute(name: String): String? =
        Regex("""$name\s*=\s*"([^"]*)"""").find(this)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }

    private fun String.tag(name: String): String? =
        Regex("""<$name>([\s\S]*?)</$name>""").find(this)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }

    private companion object {
        /** 守门只输出一个标签，给多了反而诱导它写解释。 */
        const val GATE_OUTPUT_TOKENS = 32
        const val MAX_OUTPUT_TOKENS = 1600

        val GATE_REGEX = Regex("""<user_memory>\s*(true|false)""", RegexOption.IGNORE_CASE)

        /** 合法的空结果也必须带外层标签，避免把上游报错或模型闲聊误记成「没有新内容」。 */
        val MEMORY_OPS_REGEX = Regex("""<memory_ops\b[\s\S]*?(?:/>|</memory_ops>)""")

        /** 自闭合与带 body 两种写法都要收：弱模型经常把 reinforce 写成自闭合标签。 */
        val OP_REGEX = Regex("""<op\s+([^>]*?)(?:/>|>([\s\S]*?)</op>)""")

        val GATE_INSTRUCTIONS = """
            判断下面这段对话里，用户有没有明确说出在之后对话中仍可能有帮助的信息。

            包括：身份与背景、持续偏好与表达要求、正在进行的项目、近期仍相关的计划或处境、明确的禁止项。
            只要存在一条可能属于这些类别的用户陈述就输出 true；稳定性不确定也输出 true，下一步会把它列为待确认。

            只有确定整段都只是本轮临时参数、纯技术问答、助手推断或随口提到的话题时才输出 false。

            只输出一行，不要任何解释：
            <user_memory>true</user_memory>
            或
            <user_memory>false</user_memory>
        """.trimIndent()

        val EXTRACT_INSTRUCTIONS = """
            你是记忆整理器。从下面这段对话里提取在之后对话中仍可能有帮助的信息。

            只提取用户**明确说过**的内容，助手说的话只是语境，不能作为依据。
            可以提取：身份与背景、持续偏好与表达要求、正在进行的项目、近期仍相关的计划或处境、明确的禁止项。
            不要提取：只服务于本轮的临时参数、纯技术问答中的题目内容、你推断而用户没确认的结论、随口提到的话题。

            **禁止记录**：密钥、密码、令牌、身份证件、支付信息；
            以及种族、民族、宗教信仰、性取向、性生活、政治观点、犯罪记录、健康与病史。

            与「已有记忆」重复的不要重复新增：同一件事再次出现用 reinforce，用户改变了说法用 supersede，
            用户明确否认用 forget。证据不够硬的用 candidate。
            与「已删除或忽略」含义相同的内容不要重新添加，即使措辞不同。
            同一段对话里用户先说后改的，直接给改后的结论，用 supersede 指向旧记忆。

            text 用完整的第三人称陈述句描述用户，不要用「这个」「刚才」这类指回本轮的词。
            quote 必须逐字取自**用户**说过的话，一字不改。

            section 取以下之一：${MemorySection.entries.joinToString(" / ") { it.wire }}
            profile_key 只在这条正好是画像值时给出，取以下之一：${ByokProfileKey.entries.joinToString(" / ") { it.wire }}，
            此时 text 只写值本身（例如「阿罗」），不写整句。

            最多 ${ByokMemoryOp.MAX_OPS} 条。严格按下面的格式输出，不要输出任何其他文字：

            <memory_ops>
            <op type="add" section="进行中的项目" confidence="0.9">
              <text>用户正在开发 MolaGPT Mobile 的本地记忆。</text>
              <quote>我最近在做 MolaGPT Mobile 的本地记忆</quote>
            </op>
            <op type="reinforce" target="mem_xxxxxxxx">
              <quote>我最近在做 MolaGPT Mobile 的本地记忆</quote>
            </op>
            </memory_ops>

            没有值得记的就输出：
            <memory_ops/>
        """.trimIndent()
    }
}
