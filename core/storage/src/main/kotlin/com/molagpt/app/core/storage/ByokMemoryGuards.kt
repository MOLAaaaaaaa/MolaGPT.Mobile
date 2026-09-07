package com.molagpt.app.core.storage

/**
 * 记忆写入的内容校验。工具写入与自动整理共用同一份实现——
 * 两条路径各带一套规则，只会让「哪条路能写进什么」变成没人说得清的事。
 */
object ByokMemoryGuards {

    /**
     * 明显的凭据与证件。不追求完备——真正的防线是「必须来自用户本轮原话」，
     * 这里挡的是用户自己粘了一把密钥、模型顺手要记下来的情况。
     *
     * 用户在记忆页打开「允许包含隐私信息」后这一层不再生效，由用户自己承担。
     */
    fun looksSensitive(text: String): Boolean = SENSITIVE_PATTERNS.any { it.containsMatchIn(text) }

    /**
     * 受保护特征：种族、宗教、性取向、政治观点、性生活、犯罪记录、健康状况。
     *
     * **处置方式与凭据不同——命中只降级为候选，不拒绝。** 凭据永远不该进记忆，
     * 而这一类可能正是用户自己要求记住的（「我吃素，别给我推荐肉菜」背后可能就是宗教原因），
     * 直接拒等于功能失效。降级成候选既不静默写入，也不静默丢弃。
     *
     * 这一层注定不完备：这类事实没有凭据那样的语法特征，写不出可靠的正则。
     * 真正起作用的是提示词里那段禁止说明，这里只是兜住最直白的表述。
     */
    fun looksProtected(text: String): Boolean = PROTECTED_PATTERNS.any { it.containsMatchIn(text) }

    /**
     * 用户是否真的在否认或撤回。删记忆不可逆且会连带写压制记录，
     * 只凭模型说「这条该删了」不足以动手——必须有用户自己说过的否认话语。
     */
    fun looksLikeDenial(text: String): Boolean = DENIAL_REGEX.containsMatchIn(text)

    private val SENSITIVE_PATTERNS = listOf(
        Regex("""\bsk-[A-Za-z0-9_-]{16,}"""),
        Regex("""\b(?:ghp|gho|github_pat)_[A-Za-z0-9_]{16,}"""),
        Regex("""\bBearer\s+[A-Za-z0-9._-]{20,}""", RegexOption.IGNORE_CASE),
        Regex("""\b(?:api[_-]?key|access[_-]?token|secret|password|口令|密码)\s*[:：=]\s*\S{6,}""", RegexOption.IGNORE_CASE),
        // 身份证 / 银行卡
        Regex("""\b\d{17}[\dXx]\b"""),
        Regex("""\b\d{16,19}\b"""),
    )

    private val PROTECTED_PATTERNS = listOf(
        // 宗教信仰
        Regex("""基督教|天主教|东正教|新教|伊斯兰|穆斯林|回教|佛教|道教|印度教|犹太教|锡克教|无神论|信教|信仰|礼拜|斋戒|受洗"""),
        Regex("""\b(?:christian|catholic|muslim|islam|buddhis|hindu|jewish|judaism|atheis|agnostic)""", RegexOption.IGNORE_CASE),
        // 种族与民族
        Regex("""种族|族裔|少数民族|汉族|回族|维吾尔|藏族|蒙古族|犹太人|黑人|白人"""),
        Regex("""\b(?:ethnicity|racial|race is|black|white|asian|hispanic|latino)\b""", RegexOption.IGNORE_CASE),
        // 性取向与性生活
        Regex("""性取向|同性恋|异性恋|双性恋|无性恋|跨性别|变性|出柜|性生活|性癖"""),
        Regex("""\b(?:gay|lesbian|bisexual|asexual|transgender|queer|lgbt|sexual orientation|sex life)\b""", RegexOption.IGNORE_CASE),
        // 政治观点
        Regex("""政治立场|政治观点|党员|入党|左派|右派|保守派|自由派|支持.{0,4}党|反对.{0,4}党"""),
        Regex("""\b(?:political views?|conservative|liberal|leftist|right.?wing|votes? for)\b""", RegexOption.IGNORE_CASE),
        // 犯罪记录
        Regex("""犯罪记录|前科|坐牢|服刑|被捕|判刑|案底|缓刑"""),
        Regex("""\b(?:criminal record|convicted|arrested|imprisoned|felony|on parole)\b""", RegexOption.IGNORE_CASE),
        // 健康与病史
        Regex("""确诊|病史|慢性病|抑郁症|焦虑症|双相|精神分裂|艾滋|HIV|癌症|糖尿病|残疾|服用.{0,6}药"""),
        Regex("""\b(?:diagnosed|mental illness|depression|bipolar|schizophren|hiv|cancer|diabetes|disabilit)""", RegexOption.IGNORE_CASE),
    )

    private val DENIAL_REGEX = Regex(
        """不对|不是|错了|不再|已经不|别记|忘掉|忘记|删掉|删除|去掉|别再|取消|改成|不用了|""" +
            """no longer|not anymore|forget|remove that""",
        RegexOption.IGNORE_CASE,
    )
}
